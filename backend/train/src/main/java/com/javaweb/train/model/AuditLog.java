package com.javaweb.train.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity(name = "audit_logs")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(length = 40)
    public String topic;
    public String content;
    public Instant createdAt;
}
