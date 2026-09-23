package org.example.seatrace.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.example.seatrace.exception.RedisUnavailableException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RedisFacade {

  private final StringRedisTemplate stringRedisTemplate;
  @Qualifier("queueRedisTemplate")
  private final org.springframework.data.redis.core.RedisTemplate<String, String> queueRedisTemplate;
  private final RedisResilienceExecutor redisResilienceExecutor;
  private final MeterRegistry meterRegistry;

  public RedisFacade(
      StringRedisTemplate stringRedisTemplate,
      @Qualifier("queueRedisTemplate") org.springframework.data.redis.core.RedisTemplate<String, String> queueRedisTemplate,
      RedisResilienceExecutor redisResilienceExecutor,
      MeterRegistry meterRegistry
  ) {
    this.stringRedisTemplate = stringRedisTemplate;
    this.queueRedisTemplate = queueRedisTemplate;
    this.redisResilienceExecutor = redisResilienceExecutor;
    this.meterRegistry = meterRegistry;
  }

  private <T> T execute(RedisOperationFeature feature, String op, String keyForMsg,
      io.github.resilience4j.core.functions.CheckedSupplier<T> supplier) {
    Timer.Sample sample = Timer.start(meterRegistry);
    String outcome = "success";
    try {
      return redisResilienceExecutor.execute(feature, op, supplier);
    } catch (Throwable ex) {
      outcome = "failure";
      String suffix = keyForMsg == null ? "" : ": key=" + keyForMsg;
      throw new RedisUnavailableException("Redis " + op + " 실패" + suffix, ex);
    } finally {
      sample.stop(Timer.builder("seatrace.redis.operation")
          .description("Redis operation latency including resilience guards")
          .tag("feature", feature.resilienceName())
          .tag("operation", op)
          .tag("outcome", outcome)
          .publishPercentileHistogram()
          .register(meterRegistry));
    }
  }

  private org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplateFor(
      RedisOperationFeature feature
  ) {
    return feature.usesQueueRedisConnection() ? queueRedisTemplate : stringRedisTemplate;
  }

  // -------- Value ops
  public String get(RedisOperationFeature feature, String key) {
    return execute(feature, "GET", key, () -> redisTemplateFor(feature).opsForValue().get(key));
  }

  public void set(RedisOperationFeature feature, String key, String value, Duration ttl) {
    execute(feature, "SET", key, () -> {
      redisTemplateFor(feature).opsForValue().set(key, value, ttl);
      return null;
    });
  }

  public void set(RedisOperationFeature feature, String key, String value) {
    execute(feature, "SET", key, () -> {
      redisTemplateFor(feature).opsForValue().set(key, value);
      return null;
    });
  }

  public Boolean setIfAbsent(RedisOperationFeature feature, String key, String value, Duration ttl) {
    return execute(feature, "SETNX", key, () -> redisTemplateFor(feature).opsForValue().setIfAbsent(key, value, ttl));
  }

  public Boolean hasKey(RedisOperationFeature feature, String key) {
    return execute(feature, "EXISTS", key, () -> redisTemplateFor(feature).hasKey(key));
  }

  public Boolean delete(RedisOperationFeature feature, String key) {
    return execute(feature, "DEL", key, () -> redisTemplateFor(feature).delete(key));
  }

  public Long delete(RedisOperationFeature feature, Collection<String> keys) {
    return execute(feature, "DEL(multi)", null, () -> redisTemplateFor(feature).delete(keys));
  }

  public Long increment(RedisOperationFeature feature, String key) {
    return execute(feature, "INCR", key, () -> redisTemplateFor(feature).opsForValue().increment(key));
  }

  public Boolean expire(RedisOperationFeature feature, String key, Duration ttl) {
    return execute(feature, "EXPIRE", key, () -> redisTemplateFor(feature).expire(key, ttl));
  }

  // -------- ZSet ops
  public Double zScore(RedisOperationFeature feature, String key, String member) {
    return execute(feature, "ZSCORE", key, () -> redisTemplateFor(feature).opsForZSet().score(key, member));
  }

  public Boolean zAdd(RedisOperationFeature feature, String key, String member, double score) {
    return execute(feature, "ZADD", key, () -> redisTemplateFor(feature).opsForZSet().add(key, member, score));
  }

  public java.util.Set<String> zRange(RedisOperationFeature feature, String key, long start, long end) {
    return execute(feature, "ZRANGE", key, () -> redisTemplateFor(feature).opsForZSet().range(key, start, end));
  }

  public Long zRemove(RedisOperationFeature feature, String key, String member) {
    return execute(feature, "ZREM", key, () -> redisTemplateFor(feature).opsForZSet().remove(key, member));
  }

  public Long zRemoveRangeByScore(RedisOperationFeature feature, String key, double min, double max) {
    return execute(feature, "ZREMRANGEBYSCORE", key,
        () -> redisTemplateFor(feature).opsForZSet().removeRangeByScore(key, min, max));
  }

  public Long zRank(RedisOperationFeature feature, String key, String member) {
    return execute(feature, "ZRANK", key, () -> redisTemplateFor(feature).opsForZSet().rank(key, member));
  }

  public Long zSize(RedisOperationFeature feature, String key) {
    return execute(feature, "ZCARD", key, () -> redisTemplateFor(feature).opsForZSet().size(key));
  }

  // -------- Stream ops
  public void xAdd(RedisOperationFeature feature, String streamKey, String recordId, Map<String, String> body) {
    execute(feature, "XADD", streamKey, () -> {
      RecordId id = RecordId.of(recordId);
      MapRecord<String, String, String> record =
          StreamRecords.newRecord().in(streamKey).ofMap(body).withId(id);
      redisTemplateFor(feature).opsForStream().add(record);
      return null;
    });
  }

  public List<MapRecord<String, Object, Object>> xRange(
      RedisOperationFeature feature,
      String streamKey,
      Range<String> range,
      int count
  ) {
    return execute(feature, "XRANGE", streamKey,
        () -> redisTemplateFor(feature).opsForStream().range(streamKey, range, Limit.limit().count(count)));
  }

  public Long xDel(RedisOperationFeature feature, String streamKey, RecordId... ids) {
    return execute(feature, "XDEL", streamKey, () -> redisTemplateFor(feature).opsForStream().delete(streamKey, ids));
  }

  // -------- Script ops
  public Long evalUnlockScript(RedisOperationFeature feature, String key, String token) {
    String scriptText =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";
    return execute(feature, "UNLOCK script", key, () -> {
      DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptText, Long.class);
      return redisTemplateFor(feature).execute(script, List.of(key), token);
    });
  }

  public String evalStringScript(
      RedisOperationFeature feature,
      String scriptText,
      List<String> keys,
      String... args
  ) {
    return execute(feature, "Lua script", null, () -> {
      DefaultRedisScript<String> script = new DefaultRedisScript<>(scriptText, String.class);
      return redisTemplateFor(feature).execute(script, keys, (Object[]) args);
    });
  }
}
