package com.javaweb.takeaway.model;

import jakarta.persistence.*;

@Entity(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(unique = true, nullable = false, length = 64)
    public String username;

    @Column(nullable = false, length = 128)
    public String passwordHash;

    /** USER=顾客 / RIDER=骑手（双角色一个模型，教学简化） */
    public String role;
}
