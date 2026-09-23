package org.example.seatrace.config;

import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class QueueRedisConfig {

  @Bean
  @Primary
  LettuceConnectionFactory redisConnectionFactory(
      @Value("${spring.data.redis.host:localhost}") String host,
      @Value("${spring.data.redis.port:6379}") int port,
      @Value("${spring.data.redis.password:}") String password,
      @Value("${spring.data.redis.timeout:100ms}") Duration commandTimeout,
      @Value("${spring.data.redis.connect-timeout:100ms}") Duration connectTimeout
  ) {
    return connectionFactory(host, port, password, commandTimeout, connectTimeout);
  }

  @Bean("queueRedisConnectionFactory")
  LettuceConnectionFactory queueRedisConnectionFactory(
      @Value("${spring.data.redis.host:localhost}") String host,
      @Value("${spring.data.redis.port:6379}") int port,
      @Value("${spring.data.redis.password:}") String password,
      @Value("${queue-redis.timeout:300ms}") Duration commandTimeout,
      @Value("${queue-redis.connect-timeout:100ms}") Duration connectTimeout
  ) {
    return connectionFactory(host, port, password, commandTimeout, connectTimeout);
  }

  private LettuceConnectionFactory connectionFactory(
      String host,
      int port,
      String password,
      Duration commandTimeout,
      Duration connectTimeout
  ) {
    RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(host, port);
    if (!password.isBlank()) {
      server.setPassword(password);
    }
    LettuceClientConfiguration client = LettuceClientConfiguration.builder()
        .commandTimeout(commandTimeout)
        .clientOptions(io.lettuce.core.ClientOptions.builder()
            .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
            .build())
        .shutdownTimeout(Duration.ZERO)
        .build();
    return new LettuceConnectionFactory(server, client);
  }

  @Bean("queueRedisTemplate")
  RedisTemplate<String, String> queueRedisTemplate(
      @Qualifier("queueRedisConnectionFactory") LettuceConnectionFactory queueRedisConnectionFactory
  ) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    StringRedisSerializer serializer = new StringRedisSerializer();
    template.setConnectionFactory(queueRedisConnectionFactory);
    template.setKeySerializer(serializer);
    template.setValueSerializer(serializer);
    template.setHashKeySerializer(serializer);
    template.setHashValueSerializer(serializer);
    template.afterPropertiesSet();
    return template;
  }
}
