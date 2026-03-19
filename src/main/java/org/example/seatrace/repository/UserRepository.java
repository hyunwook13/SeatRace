package org.example.seatrace.repository;

import java.util.Optional;
import org.example.seatrace.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByEmail(String email);
}
