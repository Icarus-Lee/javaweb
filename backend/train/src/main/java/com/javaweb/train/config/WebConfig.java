package com.javaweb.train.config;

import com.javaweb.train.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 注册"检票口"：明确覆盖范围与豁免清单（查询余票公开，下单需登录）。
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;

    public WebConfig(AuthInterceptor auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register", "/api/auth/login",
                        "/api/trips", "/api/trips/**", "/api/health");
    }
}
