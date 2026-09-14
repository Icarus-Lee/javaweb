package com.javaweb.takeaway.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventProducer {
    public static final String TOPIC = "takeout-order-events";
    private final KafkaTemplate<String, String> kafka;

    public OrderEventProducer(KafkaTemplate<String, String> kafka) { this.kafka = kafka; }

    public void orderCreated(String orderNo, Long dishId, int qty, String phase) {
        String payload = "{\"orderNo\":\"" + orderNo + "\",\"dishId\":" + dishId +
                ",\"qty\":" + qty + ",\"phase\":\"" + phase + "\"}";
        kafka.send(TOPIC, orderNo, payload);
    }
}
