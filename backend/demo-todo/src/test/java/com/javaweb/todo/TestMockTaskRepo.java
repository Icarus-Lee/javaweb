package com.javaweb.todo;

import com.javaweb.todo.model.Task;
import com.javaweb.todo.repo.TaskRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// S14 教程的 Mockito 最小示例：不起 Spring、连数据库都换成假件——毫秒级单元测试。
@ExtendWith(MockitoExtension.class)
class TestMockTaskRepo {

    @Mock
    TaskRepo repo;       // 假的 Repo：save/findById 全听号令

    @Test
    void save走通且verify留痕() {
        Task in = new Task("Mockito 假件");
        Task out = new Task("Mockito 假件");
        when(repo.save(any(Task.class))).thenReturn(out);      // 桩：save 调用返回假实体

        Task got = repo.save(in);

        assertEquals(out, got);
        verify(repo).save(in);                                 // 断言：save(in) 确实被调过一次
    }

    @Test
    void findById查无此人返回空Optional() {
        when(repo.findById(99L)).thenReturn(Optional.empty());

        assertEquals(true, repo.findById(99L).isEmpty());
        verify(repo).findById(99L);
    }
}
