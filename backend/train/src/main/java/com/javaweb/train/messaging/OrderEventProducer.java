package com.javaweb.train.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 生产者：把"订单已发生"的事实写给 Kafka（fire-and-record，教学版不追求Exactly-Once）。
@Component
public class OrderEventProducer {
    public static final String TOPIC = "train-order-events";
    private final org.springframework.kafka.core.KafkaTemplate<String, String> kafka;

    public OrderEventProducer(org.springframework.kafka.core.KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void orderCreated(String orderNo, Long tripId, int seatNo, int price, String phase) {
        String payload = "{\"orderNo\":\"" + orderNo + "\",\"tripId\":" + tripId +
                ",\"seatNo\":" + seatNo + ",\"price\":" + price + ",\"phase\":\"" + phase + "\"}";
        kafka.send(TOPIC, orderNo, payload);   // key=orderNo：同一订单进同一分区（顺序保证）
    }
}
