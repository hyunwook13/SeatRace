package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "reservation.lock")
public class ReservationLockProperties {

  /**
   * Redis( Redisson ) 분산락 사용 여부.
   * - 초고부하에서 DB 락/커넥션 고갈을 피하기 위해 좌석 선점/예약 상태 변경에 적용한다.
   */
  private boolean enabled = true;

  /**
   * 락 획득 대기 시간(ms). 0이면 즉시 실패.
   */
  private long waitMs = 50;

  /**
   * 락 점유 최대 시간(ms). (lease time)
   * - 프로세스 장애 시 락이 영구히 남는 것을 방지한다.
   */
  private long leaseMs = 3_000;

  /**
   * 락 획득 실패 시 요청을 실패 처리할지 여부.
   * - false: 락이 불가능하면 기존 DB 경로(낙관적 락)로 진행(안전성/부하 트레이드오프)
   * - true: 락이 불가능하면 빠르게 실패(정합성/보호 우선)
   */
  private boolean required = false;
}

