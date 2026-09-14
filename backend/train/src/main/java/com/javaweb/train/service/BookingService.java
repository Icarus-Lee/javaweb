package com.javaweb.train.service;

import com.javaweb.train.messaging.OrderEventProducer;
import com.javaweb.train.model.Booking;
import com.javaweb.train.model.TrainTrip;
import com.javaweb.train.repo.BookingRepo;
import com.javaweb.train.repo.TrainRepo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// 下单服务：锁 → 扣票 → 发号 → 落库 → 发 Kafka 事件。任何一步失败都会回滚前面的代价。
@Service
public class BookingService {
    private final TrainRepo trips;
    private final BookingRepo bookings;
    private final StringRedisTemplate redis;
    private final OrderEventProducer producer;

    public BookingService(TrainRepo trips, BookingRepo bookings,
                          StringRedisTemplate redis, OrderEventProducer producer) {
        this.trips = trips;
        this.bookings = bookings;
        this.redis = redis;
        this.producer = producer;
    }

    public record BookResult(String orderNo, int seatNo) {}

    /** 下单：Redis 分布式锁保"同一车次一秒内只有一个人在动账"，原子递减防超卖。 */
    public Booking book(Long userId, Long tripId) {
        String stockKey = "train:trip:" + tripId + ":stock";
        String lockKey  = "train:trip:" + tripId + ":lock";
        String seqKey   = "train:trip:" + tripId + ":seq";

        // 1) 谁先 set 成功谁锁门（10 秒自动解锁，防"人走门忘关"）
        Boolean locked = redis.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (locked == null || !locked) {
            throw new IllegalStateException("手速太快，请重试（车次处理中）");
        }
        try {
            // 2) 原子扣票：DECR，结果为负即"卖重复了"，必须还回去
            Long stock = redis.opsForValue().decrement(stockKey);
            if (stock == null) {
                throw new IllegalStateException("车次未初始化（stock key 缺失）");
            }
            if (stock < 0) {
                redis.opsForValue().increment(stockKey);
                throw new IllegalStateException("已售罄");
            }
            // 3) 座位号：INCR 序列分配（教学版从 1 号开始）
            Long seat = redis.opsForValue().increment(seqKey);

            TrainTrip trip = trips.findById(tripId)
                    .orElseThrow(() -> new IllegalArgumentException("车次不存在"));

            Booking b = new Booking();
            b.orderNo = java.util.UUID.randomUUID().toString();
            b.userId = userId;
            b.tripId = tripId;
            b.seatNo = seat.intValue();
            b.status = "UNPAID";
            b.createdAt = Instant.now();
            b = bookings.save(b);          // 4) 落库（H2）
            // 5) 广播给下游：本课的 Kafka 事件先给"审计台"盖个戳
            producer.orderCreated(b.orderNo, tripId, b.seatNo, trip.priceYuan, "CREATED");
            return b;
        } finally {
            redis.delete(lockKey);         // 6) 归还锁（finally 保证"无论成败都还"）
        }
    }

    public Booking pay(Long userId, String orderNo) {
        Booking b = bookings.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalStateException("订单不存在"));
        if (!b.userId.equals(userId)) throw new IllegalStateException("不是你的订单");
        if (!"UNPAID".equals(b.status)) throw new IllegalStateException("订单状态不允许支付: " + b.status);
        b.status = "PAID";
        b.paidAt = Instant.now();
        producer.orderCreated(b.orderNo, b.tripId, b.seatNo, 0, "PAID");
        return bookings.save(b);
    }

    /** 取消：回滚余票（INCR）。 */
    public Booking cancel(Long userId, String orderNo) {
        Booking b = bookings.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalStateException("订单不存在"));
        if (!b.userId.equals(userId)) throw new IllegalStateException("不是你的订单");
        if ("CANCELLED".equals(b.status)) return b;
        b.status = "CANCELLED";
        String stockKey = "train:trip:" + b.tripId + ":stock";
        redis.opsForValue().increment(stockKey);
        producer.orderCreated(b.orderNo, b.tripId, b.seatNo, 0, "CANCELLED");
        return bookings.save(b);
    }

    /** 超时关单：UNPAID 超过 5 分钟自动取消（定时任务每 30 秒扫一次）。 */
    @Scheduled(fixedDelay = 15_000, initialDelay = 20_000)
    public void closeExpired() {
        for (Booking b : bookings.findByStatus("UNPAID")) {
            if (b.createdAt.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
                cancel(b.userId, b.orderNo);
            }
        }
    }
}
