package com.javaweb.takeaway.repo;

import com.javaweb.takeaway.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepo extends JpaRepository<User, Long> {
    java.util.Optional<User> findByUsername(String username);
}
