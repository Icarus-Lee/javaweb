package com.javaweb.takeaway.messaging;

import com.javaweb.takeaway.model.Order;
import com.javaweb.takeaway.repo.OrderRepo;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 派单员：只有已支付（PAID）的事件才会被派单；CREATED 事件直接忽略（防止"白派单"）。
@Component
public class DispatchConsumer {
    private final OrderRepo orders;

    public DispatchConsumer(OrderRepo orders) { this.orders = orders; }

    @KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "dispatch")
    @Transactional
    public void onOrder(String payload) {
        // 简化 JSON 解析：找 orderNo 与 phase（教学场景允许这种"抠字段"方式）
        String orderNo = extract(payload, "orderNo");
        String phase = extract(payload, "phase");

        Order o = orders.findByOrderNo(orderNo).orElse(null);
        if (o == null || !"PAID".equalsIgnoreCase(phase) || !"PAID".equals(o.status)) return;
        if (o.status.equals("PAID")) {
            o.status = "DISPATCHED";
            o.riderId = 9000L;          // 模拟事由：本机演示"调度中心"一次性指派给骑手 9000
            o.dispatchedAt = Instant.now();
            orders.save(o);
            System.out.println("[dispatch] order=" + orderNo + " -> 派给骑手 9000");
        }
    }

    private String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\":\"");
        if (i < 0) return null;
        int start = i + field.length() + 4;
        int end = json.indexOf('"', start);
        return end > 0 ? json.substring(start, end) : null;
    }
}
