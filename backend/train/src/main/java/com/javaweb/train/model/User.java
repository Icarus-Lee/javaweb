package com.javaweb.train.model;

import jakarta.persistence.*;

@Entity(name = "users")      // "user" 在很多库是保留字，起名 users 更安全
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(unique = true, nullable = false, length = 64)
    public String username;

    @Column(nullable = false, length = 128)
    public String passwordHash;   // sha256(salt + password)——教学版简写，先认识"不存明文"

    public String nickname;
}
