package org.example.seatrace.user;

import java.util.Optional;
import org.example.seatrace.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);
}
