package com.javaweb.train.repo;

import com.javaweb.train.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BookingRepo extends JpaRepository<Booking, Long> {
    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Booking> findByStatus(String status);   // UNPAID / PAID / CANCELLED
    Optional<Booking> findByOrderNo(String orderNo);
}
