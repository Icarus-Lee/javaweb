package com.javaweb.train.repo;

import com.javaweb.train.model.TrainTrip;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TrainRepo extends JpaRepository<TrainTrip, Long> {
    List<TrainTrip> findByFromCityAndToCityOrderById(String from, String to);
    List<TrainTrip> findByOrderById();
}
