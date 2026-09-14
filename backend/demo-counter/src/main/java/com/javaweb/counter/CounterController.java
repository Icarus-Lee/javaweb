package com.javaweb.counter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

// demo-counter：用 Redis 做的"限量秒杀"雏形（教程 R03/R04 引子）。
@RestController
@RequestMapping("/api")
public class CounterController {
    public static final int STOCK = 10;
    private final StringRedisTemplate redis;

    public CounterController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 计数器：每次访问 +1，返回最新值。
    @GetMapping("/counter")
    public Map<String, Object> counter() {
        Long n = redis.opsForValue().increment("demo:counter");
        return Map.of("n", n == null ? 0 : n);
    }

    // 秒杀雏形：只有 10 张票，用 Redis 原子递减，抢超自动拒绝。
    @PostMapping("/seckill")
    public Map<String, Object> seckill() {
        Long left = redis.opsForValue().decrement("demo:seckill:stock");
        if (left == null) {
            redis.opsForValue().set("demo:seckill:stock", String.valueOf(STOCK));  // 首次初始化
            left = redis.opsForValue().decrement("demo:seckill:stock");
        }
        if (left < 0) {
            redis.opsForValue().increment("demo:seckill:stock");   // 还回去
            return Map.of("ok", false, "reason", "已售罄");
        }
        return Map.of("ok", true, "left", left);
    }

    @GetMapping("/seckill/status")
    public Map<String, Object> status() {
        String s = redis.opsForValue().get("demo:seckill:stock");
        long left = s == null ? STOCK : Long.parseLong(s);
        return Map.of("stock", left);
    }
}
