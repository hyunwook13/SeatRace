package org.example.seatrace.service;

import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.SignupRequest;
import org.example.seatrace.dto.UserDto;
import org.example.seatrace.entity.User;
import org.example.seatrace.entity.UserRole;
import org.example.seatrace.entity.User.UserStatus;
import org.example.seatrace.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional
  public User signup(SignupRequest request) {
    userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
    });

    User user = User.builder()
        .email(request.getEmail())
        .name(request.getName())
        .passwordHash(passwordEncoder.encode(request.getPassword()))
        .role(UserRole.USER)
        .status(UserStatus.ACTIVE)
        .build();

    return userRepository.save(user);
  }
}
