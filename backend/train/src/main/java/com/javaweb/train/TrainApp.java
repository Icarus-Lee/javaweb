package com.javaweb.train;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling          // 让 @Scheduled 定时关单生效
public class TrainApp {
    public static void main(String[] args) {
        SpringApplication.run(TrainApp.class, args);
    }
}
