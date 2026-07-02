package org.example.seatrace.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "toss-payment")
public class TossPaymentProperties {

  /**
   * Toss Payments 결제 승인용 시크릿 키.
   * 로컬 테스트에서는 테스트 시크릿 키를 환경변수로 주입하는 것을 권장한다.
   */
  private String secretKey = "";

  private String confirmUrl = "https://api.tosspayments.com/v1/payments/confirm";
}
