package com.javaweb.train.config;

import com.javaweb.train.model.TrainTrip;
import com.javaweb.train.repo.TrainRepo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 开机自检种子数据：把车次灌进 H2，同时把"余票"放进 Redis（教学：两种存储各司其职）。
@Component
public class SeedRunner implements CommandLineRunner {
    private final TrainRepo trains;
    private final StringRedisTemplate redis;

    public SeedRunner(TrainRepo tripsRepo, StringRedisTemplate redis) {
        this.trains = tripsRepo;
        this.redis = redis;
    }

    @Override
    public void run(String... args) {
        if (trains.count() > 0) {
            return;   // 已有种子，不重复灌
        }
        record TripSpec(String no, String from, String to, String depart, String arrive, int seats, int price) {}
        var trips = java.util.List.of(
                new TripSpec("G1024", "上海虹桥", "苏州", "08:00", "08:35", 3, 42),
                new TripSpec("G1025", "上海虹桥", "南京", "09:15", "11:00", 2, 129),
                new TripSpec("D3102", "北京南", "淄博", "10:40", "14:20", 1, 88),
                new TripSpec("K1971", "杭州", "福州", "13:00", "19:30", 5, 156),
                new TripSpec("G77",   "广州南", "长沙南", "16:00", "18:05", 4, 314)
        );
        for (TripSpec t : trips) {
            TrainTrip trip = new TrainTrip();
            trip.trainNo = t.no();
            trip.fromCity = t.from();
            trip.toCity = t.to();
            trip.departTime = t.depart();
            trip.arriveTime = t.arrive();
            trip.priceYuan = t.price();
            trip.totalSeats = t.seats();
            trains.save(trip);
            redis.opsForValue().set("train:trip:" + trip.id + ":stock", String.valueOf(t.seats()));
            redis.opsForValue().set("train:trip:" + trip.id + ":seq", "0");
        }
    }
}
