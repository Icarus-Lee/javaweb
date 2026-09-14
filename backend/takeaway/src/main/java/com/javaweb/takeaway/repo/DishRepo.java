package com.javaweb.takeaway.repo;

import com.javaweb.takeaway.model.Dish;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DishRepo extends JpaRepository<Dish, Long> {
    List<Dish> findByShopId(Long shopId);
}
