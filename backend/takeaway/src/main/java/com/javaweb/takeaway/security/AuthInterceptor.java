package com.javaweb.takeaway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwt;

    public AuthInterceptor(JwtUtil jwt) { this.jwt = jwt; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String path = req.getRequestURI();
        // 骑手接单接口在 Controller 内再验 role；此处只校"登录了"。
        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) { resp.setStatus(401); return false; }
        io.jsonwebtoken.Claims c = jwt.verify(auth.substring(7));
        if (c == null) { resp.setStatus(401); return false; }
        req.setAttribute("uid", ((Number) c.get("uid")).longValue());
        req.setAttribute("role", String.valueOf(c.get("role")));
        return true;
    }
}
