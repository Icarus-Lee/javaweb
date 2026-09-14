package com.javaweb.takeaway.service;

import com.javaweb.takeaway.messaging.OrderEventProducer;
import com.javaweb.takeaway.model.Dish;
import com.javaweb.takeaway.model.Order;
import com.javaweb.takeaway.repo.DishRepo;
import com.javaweb.takeaway.repo.OrderRepo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

// 订单核心：点击下单 → 状态机流转，Kafka 只在关键节点广播事实。
@Service
public class OrderService {
    // 状态机：非法跳跃一律拒绝（保证流程不能被乱序事件破坏）。
    private static final Set<String> LEGAL_NEXT = Set.of(
            "CREATED-PAID", "PAID-DISPATCHED", "DISPATCHED-DELIVERED", "CREATED-CANCELED", "PAID-CANCELED");

    private final OrderRepo orders;
    private final DishRepo dishes;
    private final StringRedisTemplate redis;
    private final OrderEventProducer producer;

    public OrderService(OrderRepo orders, DishRepo dishes,
                        StringRedisTemplate redis, OrderEventProducer producer) {
        this.orders = orders;
        this.dishes = dishes;
        this.redis = redis;
        this.producer = producer;
    }

    public record Item(Long shopId, Long dishId, Integer quantity) {}
    public record BookReq(Long shopId, Long dishId, Integer quantity) {}

    public String transition(Order o, String to) {
        if (!LEGAL_NEXT.contains(o.status + "-" + to))
            throw new IllegalStateException("非法状态流转: " + o.status + " -> " + to);
        o.status = to;
        return to;
    }

    @Transactional
    public Order create(Long userId, BookReq req) {
        int qty = req.quantity() == null ? 1 : req.quantity();
        if (qty <= 0) throw new IllegalArgumentException("至少点 1 份");
        Dish dish = dishes.findById(req.dishId())
                .orElseThrow(() -> new IllegalArgumentException("菜品不存在"));

        Order o = new Order();
        o.orderNo = java.util.UUID.randomUUID().toString();
        o.userId = userId;
        o.shopId = dish.shopId;
        o.dishId = dish.id;
        o.quantity = qty;
        o.totalYuan = dish.priceYuan * qty;
        o.status = "CREATED";
        o.createdAt = Instant.now();
        o = orders.save(o);
        producer.orderCreated(o.orderNo, o.dishId, o.quantity, "CREATED");
        return o;
    }

    public Order pay(Long userId, String orderNo) {
        Order o = orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalStateException("订单不存在"));
        if (!o.userId.equals(userId)) throw new IllegalStateException("不是你的订单");
        transition(o, "PAID");
        o.paidAt = Instant.now();
        // 支付成功即扣库存（Redis 乐观扣减；失败则回滚到 CREATED）。
        Long left = redis.opsForValue().decrement("takeaway:dish:" + o.dishId + ":stock");
        if (left != null && left < 0) {
            redis.opsForValue().increment("takeaway:dish:" + o.dishId + ":stock");
            throw new IllegalStateException("库存不足，请稍后再试");
        }
        producer.orderCreated(o.orderNo, o.dishId, o.quantity, "PAID");
        return orders.save(o);
    }

    public java.util.List<Order> mine(Long userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Order find(String orderNo) {
        return orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new IllegalStateException("订单不存在"));
    }
}
