package com.javaweb.takeaway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TakeawayApp {
    public static void main(String[] args) {
        SpringApplication.run(TakeawayApp.class, args);
    }
}
