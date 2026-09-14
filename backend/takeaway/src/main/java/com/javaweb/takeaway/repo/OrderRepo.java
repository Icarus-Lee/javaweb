package com.javaweb.takeaway.repo;

import com.javaweb.takeaway.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrderRepo extends JpaRepository<Order, Long> {
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Order> findByStatus(String status);
    java.util.Optional<Order> findByOrderNo(String orderNo);
}
