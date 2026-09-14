package com.javaweb.train.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

// 拦截器：像"检票口"——不是谁都能进（写问题的关键位置）。
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwt;
    public AuthInterceptor(JwtUtil jwt) { this.jwt = jwt; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String auth = req.getHeader("Authorization");      // 约定：Bearer <token>
        if (auth == null || !auth.startsWith("Bearer ")) {
            resp.setStatus(401);
            return false;
        }
        Long uid = jwt.verify(auth.substring(7));
        if (uid == null) { resp.setStatus(401); return false; }
        req.setAttribute("uid", uid);
        return true;
    }
}
