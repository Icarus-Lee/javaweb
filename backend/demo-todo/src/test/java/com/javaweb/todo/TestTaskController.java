package com.javaweb.todo;

import com.javaweb.todo.model.Task;
import com.javaweb.todo.repo.TaskRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// S14 教程的 MockMvc 最小示例：不起真实端口，在内存里模拟一次完整请求管线。
@SpringBootTest
@AutoConfigureMockMvc     // 把 MockMvc 造好并塞给测试类（含拦截器、校验、序列化）
class TestTaskController {

    @Autowired MockMvc mvc;

    @Test
    void 建一条再读回来() throws Exception {
        // POST /api/tasks → 200，且 JSON 里回显标题
        mvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"title\":\"MockMvc 写入的任务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("MockMvc 写入的任务"));

        // GET /api/tasks → 200，数组里必须能找到刚才那条
        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'MockMvc 写入的任务')]").exists());
    }

    @Test
    void 空标题应有校验异常() throws Exception {
        // @NotBlank 校验桩在标准测试配置下默认生效：400/业务码均可，这里抓"非 200"）
        mvc.perform(post("/api/tasks")
                        .contentType("application/json")
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list有内容() throws Exception {
        Task t = repo().save(new Task("预备数据"));
        mvc.perform(get("/api/tasks").accept("application/json"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("预备数据")));
    }

    @Autowired TaskRepo taskRepo;
    TaskRepo repo() { return taskRepo; }
}
