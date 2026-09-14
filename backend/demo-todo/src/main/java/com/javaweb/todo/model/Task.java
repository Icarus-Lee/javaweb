package com.javaweb.todo.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotBlank;

@Entity
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotBlank(message = "标题不能为空")
    public String title;

    public boolean done = false;

    protected Task() {}               // JPA 需要无参构造（protected 即可）

    public Task(String title) {
        this.title = title;
    }
}
