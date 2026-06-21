package org.example.seatrace.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RedisLettuceConfig {

  @Bean
  public LettuceClientConfigurationBuilderCustomizer redisFailFastCustomizer(RedisProperties redisProperties) {
    return builder -> {
      Duration commandTimeout = redisProperties.getTimeout();
      Duration connectTimeout = redisProperties.getConnectTimeout();

      if (commandTimeout != null) {
        builder.commandTimeout(commandTimeout);
      }

      ClientOptions.Builder clientOptions = ClientOptions.builder();
      if (connectTimeout != null) {
        clientOptions.socketOptions(
            SocketOptions.builder()
                .connectTimeout(connectTimeout)
                .build()
        );
      }

      if (commandTimeout != null) {
        clientOptions.timeoutOptions(TimeoutOptions.enabled(commandTimeout));
      }

      builder.clientOptions(clientOptions.build());

      log.info("Redis lettuce fail-fast configured: connectTimeout={}, commandTimeout={}", connectTimeout, commandTimeout);
    };
  }
}
