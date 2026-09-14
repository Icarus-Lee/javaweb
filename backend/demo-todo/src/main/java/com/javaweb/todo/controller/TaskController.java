package com.javaweb.todo.controller;

import com.javaweb.todo.model.Task;
import com.javaweb.todo.repo.TaskRepo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskRepo repo;

    // 构造器注入（Spring IoC 的直观体验）：框架把 TaskRepo 递进来。
    public TaskController(TaskRepo repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Task> list() {
        return repo.findAll();
    }

    @PostMapping
    public Task create(@Valid @RequestBody Task task) {
        task.id = null;               // 新建：让数据库自增
        return repo.save(task);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Task> toggle(@PathVariable Long id) {
        return repo.findById(id)
                   .map(t -> {
                       t.done = !t.done;        // 状态翻转（勾选完成）
                       return ResponseEntity.ok(repo.save(t));
                   })
                   .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
