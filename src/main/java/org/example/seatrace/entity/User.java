package org.example.seatrace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User extends BaseEntity{

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100, unique = true)
  private String email;

  @Column(nullable = false, length = 100)
  private String passwordHash;

  @Column(nullable = false, length = 20)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private UserRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserStatus status;

  @Builder
  public User(
      String email,
      String passwordHash,
      String name,
      UserRole role,
      UserStatus status
  ) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.name = name;
    this.role = (role == null) ? UserRole.USER : role;
    this.status = (status == null) ? UserStatus.ACTIVE : status;
  }

  public void changeName(String name) {
    this.name = name;
  }

  public void changeRole(UserRole role) {
    this.role = role;
  }

  public void deactivate() {
    this.status = UserStatus.INACTIVE;
  }

  public void delete() {
    this.status = UserStatus.DELETED;
  }

  public enum UserStatus {
    ACTIVE,
    INACTIVE,
    DELETED
  }
}
