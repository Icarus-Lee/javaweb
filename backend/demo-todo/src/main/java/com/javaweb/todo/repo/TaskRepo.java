package com.javaweb.todo.repo;

import com.javaweb.todo.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;

// TODO ----注释：第一条 JPA 数据访问接口：一行继承，增删改查全自动。
public interface TaskRepo extends JpaRepository<Task, Long> {
}
