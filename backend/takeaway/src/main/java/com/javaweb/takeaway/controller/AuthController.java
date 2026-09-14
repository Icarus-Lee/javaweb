package com.javaweb.takeaway.controller;

import com.javaweb.takeaway.model.User;
import com.javaweb.takeaway.repo.UserRepo;
import com.javaweb.takeaway.security.JwtUtil;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepo users;
    private final JwtUtil jwt;

    public AuthController(UserRepo users, JwtUtil jwt) { this.users = users; this.jwt = jwt; }

    public record RegisterReq(@NotBlank String username, @NotBlank String password,
                              String role) {}        // role: USER（默认）/ RIDER
    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq req) {
        String username = req.username().trim();
        if (!username.matches("[A-Za-z0-9_]{3,32}"))
            throw new IllegalArgumentException("用户名需为 3-32 位字母数字下划线");
        if (users.findByUsername(username).isPresent())
            throw new IllegalStateException("用户名已存在");
        String role = "RIDER".equalsIgnoreCase(req.role()) ? "RIDER" : "USER";
        User u = new User();
        u.username = username;
        u.passwordHash = sha256("takeaway-" + req.password());
        u.role = role;
        users.save(u);
        return Map.of("id", u.id, "role", role);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        User u = users.findByUsername(req.username().trim())
                .orElseThrow(() -> new IllegalStateException("用户不存在"));
        if (!u.passwordHash.equals(sha256("takeaway-" + req.password())))
            throw new IllegalStateException("密码错误");
        return Map.of("token", jwt.issue(u.id, u.username, u.role), "role", u.role);
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
