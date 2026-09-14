package com.javaweb.train.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity(name = "bookings")
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 36)
    public String orderNo;        // 订单号（对外的"流水号"）

    public Long userId;
    public Long tripId;

    public int seatNo;            // 座位号（Redis 序列分配）

    /** UNPAID=未支付 / PAID=已支付 / CANCELLED=已取消（含超时关单） */
    public String status;

    public Instant createdAt;
    public Instant paidAt;
}
