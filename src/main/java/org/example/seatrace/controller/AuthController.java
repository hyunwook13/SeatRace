package org.example.seatrace.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.dto.SignupRequest;
import org.example.seatrace.dto.SignupResponse;
import org.example.seatrace.entity.User;
import org.example.seatrace.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @Operation(summary = "회원가입")
  @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SignupResponse> signup(@RequestBody @Valid SignupRequest request) {
    User user = authService.signup(request);

    SignupResponse response = SignupResponse.from(user);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(response);
  }
}
