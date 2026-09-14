package com.javaweb.takeaway.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 36)
    public String orderNo;

    public Long userId;          // 顾客
    public Long shopId;
    public Long dishId;
    public int quantity;
    public int totalYuan;

    /** CREATED(已下单) -> PAID(已支付) -> DISPATCHED(已派单) -> DELIVERED(已送达)；CANCELED/REFUNDED 为异常分支 */
    public String status;
    public Long riderId;

    public Instant createdAt;
    public Instant paidAt;
    public Instant dispatchedAt;
    public Instant deliveredAt;
}
