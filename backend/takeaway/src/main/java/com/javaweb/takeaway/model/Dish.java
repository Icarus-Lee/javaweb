package com.javaweb.takeaway.model;

import jakarta.persistence.*;

@Entity(name = "dishes")
public class Dish {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Long shopId;         // 哪家店
    @Column(length = 64, nullable = false)
    public String name;
    public int priceYuan;
    public int stock;           // 今日限售（教学：外卖菜也可能卖完）
}
