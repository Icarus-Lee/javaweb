package com.javaweb.train.controller;

import com.javaweb.train.model.TrainTrip;
import com.javaweb.train.repo.TrainRepo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 车次与余票查询（免登录）。
@RestController
@RequestMapping("/api/trips")
public class TripController {
    private final TrainRepo repo;
    private final StringRedisTemplate redis;

    public TripController(TrainRepo repo, StringRedisTemplate redis) {
        this.repo = repo;
        this.redis = redis;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        List<TrainTrip> trips = (from != null && to != null && !from.isBlank() && !to.isBlank())
                ? repo.findByFromCityAndToCityOrderById(from, to)
                : repo.findByOrderById();
        return trips.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id);
            m.put("trainNo", t.trainNo);
            m.put("from", t.fromCity);
            m.put("to", t.toCity);
            m.put("depart", t.departTime);
            m.put("arrive", t.arriveTime);
            m.put("price", t.priceYuan);
            m.put("total", t.totalSeats);
            String s = redis.opsForValue().get("train:trip:" + t.id + ":stock");
            m.put("stock", s == null ? 0 : Integer.parseInt(s));   // 余票实时在 Redis
            return m;
        }).toList();
    }
}
