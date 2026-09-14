package com.javaweb.train.controller;

import com.javaweb.train.model.User;
import com.javaweb.train.repo.UserRepo;
import com.javaweb.train.security.JwtUtil;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

// 用户口令哈希：sha256(盐:密码)。生产请换 BCrypt——教学先建立"不存明文"的意识。
@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepo users;
    private final com.javaweb.train.security.JwtUtil jwt;

    public AuthController(UserRepo users, com.javaweb.train.security.JwtUtil jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    public record RegisterReq(@NotBlank String username, @NotBlank String password) {}
    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq req) {
        String username = req.username().trim();
        if (!username.matches("[A-Za-z0-9_]{3,32}"))
            throw new IllegalArgumentException("用户名需为 3-32 位字母/数字/下划线");
        if (users.findByUsername(username).isPresent())
            throw new IllegalStateException("用户名已存在");
        User u = new User();
        u.username = username;
        u.passwordHash = sha256("javaweb-" + req.password());
        u.nickname = username;
        users.save(u);
        return Map.of("id", u.id, "username", u.username);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        User u = users.findByUsername(req.username())
                .orElseThrow(() -> new IllegalStateException("用户不存在"));
        if (!u.passwordHash.equals(sha256("javaweb-" + req.password())))
            throw new IllegalStateException("密码错误");
        String token = jwt.issue(u.id, u.username);
        return Map.of("token", token, "username", u.username);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
