package com.javaweb.train.model;

import jakarta.persistence.*;

@Entity(name = "train_trips")
public class TrainTrip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 8)
    public String trainNo;        // 车次号，如 G1024

    public String fromCity;
    public String toCity;

    @Column(nullable = false, length = 5)
    public String departTime;     // HH:mm（教学简化：不建时刻表外键）

    @Column(nullable = false, length = 5)
    public String arriveTime;

    public int totalSeats;        // 总座位（Redis 里的余票以此为初始值）
    public int priceYuan;         // 票价（元，教学用整数）
}
