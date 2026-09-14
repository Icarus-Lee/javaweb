package com.javaweb.takeaway.config;

import com.javaweb.takeaway.model.Dish;
import com.javaweb.takeaway.model.User;
import com.javaweb.takeaway.repo.DishRepo;
import com.javaweb.takeaway.repo.UserRepo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeedRunner implements CommandLineRunner {
    private final UserRepo users;
    private final DishRepo dishes;
    private final StringRedisTemplate redis;

    public SeedRunner(UserRepo users, DishRepo dishes, StringRedisTemplate redis) {
        this.users = users;
        this.dishes = dishes;
        this.redis = redis;
    }

    @Override
    public void run(String... args) throws Exception {
        if (users.findByUsername("alice").isPresent() && dishes.count() > 0) return;
        User alice = new User();
        alice.username = "alice"; alice.passwordHash = sha256("takeaway-123456"); alice.role = "USER";
        users.save(alice);
        User rider = new User();
        rider.username = "rider9"; rider.passwordHash = sha256("takeaway-123456"); rider.role = "RIDER";
        users.save(rider);

        Object[][] md = {
                {1L, "重辣鸡腿堡", 22, 5},
                {1L, "芝士鸡排饭", 26, 8},
                {2L, "招牌奶茶", 12, 30},
                {2L, "杨枝甘露", 15, 20},
                {3L, "十八街麻花礼盒", 33, 100},
        };
        for (Object[] d : md) {
            Dish dish = new Dish();        // id 让数据库自增生成（IDENTITY 策略下更稳）
            dish.shopId = Long.parseLong("" + d[0]);
            dish.name = "" + d[1];
            dish.priceYuan = Integer.parseInt("" + d[2]);
            dish.stock = Integer.parseInt("" + d[3]);
            dishes.save(dish);
            redis.opsForValue().set("takeaway:dish:" + dish.id + ":stock", "" + dish.stock);
        }
    }

    private static String sha256(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(d);
    }
}
