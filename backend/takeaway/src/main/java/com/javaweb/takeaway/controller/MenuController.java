package com.javaweb.takeaway.controller;

import com.javaweb.takeaway.model.Dish;
import com.javaweb.takeaway.repo.DishRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

// 菜单接口：-cache-aside 写法（第一次查库→写 Redis→之后 60 秒内全走缓存）。
// 同时演示"防穿透"：不存在的 shopId 用 30 秒的“空哨兵”挡在 Redis 里。
@RestController
@RequestMapping("/api/menus")
public class MenuController {
    private final DishRepo dishes;
    private final StringRedisTemplate redis;
    private final ObjectMapper om = new ObjectMapper();

    public MenuController(DishRepo dishes, StringRedisTemplate redis) {
        this.dishes = dishes;
        this.redis = redis;
    }

    @GetMapping("/{shopId}")
    public Object menu(@PathVariable Long shopId) {
        String key = "takeaway:menu:" + shopId;
        String cached = redis.opsForValue().get(key);
        if ("\u0000".equals(cached)) return List.of();          // 空哨兵：已确认"没有这家店"
        if (cached != null) {
            try { return om.readValue(cached, List.class); } catch (Exception ignored) {}
        }
        List<Dish> menu = dishes.findByShopId(shopId);
        String json;
        try { json = om.writeValueAsString(menu); }
        catch (Exception e) { throw new RuntimeException(e); }
        if (menu.isEmpty()) {
            redis.opsForValue().set(key, "\u0000", Duration.ofSeconds(30));  // 空 TTL 短一些
        } else {
            redis.opsForValue().set(key, json, Duration.ofSeconds(60));
        }
        return menu;
    }

    @GetMapping
    public Object all() {
        return dishes.findAll();
    }
}
