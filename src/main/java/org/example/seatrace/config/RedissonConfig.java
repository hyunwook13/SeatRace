package org.example.seatrace.config;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(ReservationLockProperties.class)
public class RedissonConfig {

  @Bean(destroyMethod = "shutdown")
  public RedissonClient redissonClient(RedisProperties redisProperties) {
    String host = redisProperties.getHost();
    int port = redisProperties.getPort();
    String address = "redis://" + host + ":" + port;

    Duration commandTimeout = redisProperties.getTimeout();
    Duration connectTimeout = redisProperties.getConnectTimeout();

    Config config = new Config();
    SingleServerConfig single = config.useSingleServer()
        .setAddress(address)
        // fail-fast
        .setRetryAttempts(0)
        .setRetryInterval(0);

    if (commandTimeout != null) {
      single.setTimeout(Math.toIntExact(commandTimeout.toMillis()));
    }
    if (connectTimeout != null) {
      single.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
    }

    log.info("Redisson configured: address={}, connectTimeout={}, commandTimeout={}",
        address, connectTimeout, commandTimeout);

    return Redisson.create(config);
  }
}

