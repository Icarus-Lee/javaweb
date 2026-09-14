package com.javaweb.train.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;

// JWT 工具：签发与校验（0.12 新 API：builder → signWith → compact）。
@Component
public class JwtUtil {
    private final SecretKey key;
    private final Duration ttlMin;

    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.ttl-minutes:60}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.ttlMin = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(Long userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMin.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 uid；不合法/过期都返回 null（教学：不区分两类失败）。 */
    public Long verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                           .parseSignedClaims(token).getPayload();
            return ((Number) c.get("uid")).longValue();
        } catch (Exception e) {
            return null;
        }
    }
}
