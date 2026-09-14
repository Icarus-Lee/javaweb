package com.javaweb.train.messaging;

import com.javaweb.train.model.AuditLog;
import com.javaweb.train.repo.AuditLogRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

// 审计台：消费订单事件并盖戳入库 —— 展示"生产者/消费者解耦"效果。
// 消费者不在请求线程里：下单接口毫秒级返回，审计慢慢来。
@Component
public class AuditConsumer {
    private final AuditLogRepo repo;

    public AuditConsumer(AuditLogRepo repo) { this.repo = repo; }

    @KafkaListener(topics = OrderEventProducer.TOPIC, groupId = "audit")
    public void onOrder(String payload) {
        AuditLog log = new AuditLog();
        log.topic = OrderEventProducer.TOPIC;
        log.content = payload;
        log.createdAt = Instant.now();
        repo.save(log);
        System.out.println("[audit] " + payload);
    }
}
