package org.example.seatrace.service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.example.seatrace.exception.RedisUnavailableException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisFacade {

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisResilienceExecutor redisResilienceExecutor;

  private <T> T execute(RedisOperationFeature feature, String op, String keyForMsg,
      io.github.resilience4j.core.functions.CheckedSupplier<T> supplier) {
    try {
      return redisResilienceExecutor.execute(feature, supplier);
    } catch (Throwable ex) {
      String suffix = keyForMsg == null ? "" : ": key=" + keyForMsg;
      throw new RedisUnavailableException("Redis " + op + " 실패" + suffix, ex);
    }
  }

  // -------- Value ops
  public String get(RedisOperationFeature feature, String key) {
    return execute(feature, "GET", key, () -> stringRedisTemplate.opsForValue().get(key));
  }

  public void set(RedisOperationFeature feature, String key, String value, Duration ttl) {
    execute(feature, "SET", key, () -> {
      stringRedisTemplate.opsForValue().set(key, value, ttl);
      return null;
    });
  }

  public void set(RedisOperationFeature feature, String key, String value) {
    execute(feature, "SET", key, () -> {
      stringRedisTemplate.opsForValue().set(key, value);
      return null;
    });
  }

  public Boolean setIfAbsent(RedisOperationFeature feature, String key, String value, Duration ttl) {
    return execute(feature, "SETNX", key, () -> stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl));
  }

  public Boolean hasKey(RedisOperationFeature feature, String key) {
    return execute(feature, "EXISTS", key, () -> stringRedisTemplate.hasKey(key));
  }

  public Boolean delete(RedisOperationFeature feature, String key) {
    return execute(feature, "DEL", key, () -> stringRedisTemplate.delete(key));
  }

  public Long delete(RedisOperationFeature feature, Collection<String> keys) {
    return execute(feature, "DEL(multi)", null, () -> stringRedisTemplate.delete(keys));
  }

  public Long increment(RedisOperationFeature feature, String key) {
    return execute(feature, "INCR", key, () -> stringRedisTemplate.opsForValue().increment(key));
  }

  public Boolean expire(RedisOperationFeature feature, String key, Duration ttl) {
    return execute(feature, "EXPIRE", key, () -> stringRedisTemplate.expire(key, ttl));
  }

  // -------- ZSet ops
  public Double zScore(RedisOperationFeature feature, String key, String member) {
    return execute(feature, "ZSCORE", key, () -> stringRedisTemplate.opsForZSet().score(key, member));
  }

  public Boolean zAdd(RedisOperationFeature feature, String key, String member, double score) {
    return execute(feature, "ZADD", key, () -> stringRedisTemplate.opsForZSet().add(key, member, score));
  }

  public java.util.Set<String> zRange(RedisOperationFeature feature, String key, long start, long end) {
    return execute(feature, "ZRANGE", key, () -> stringRedisTemplate.opsForZSet().range(key, start, end));
  }

  public Long zRemove(RedisOperationFeature feature, String key, String member) {
    return execute(feature, "ZREM", key, () -> stringRedisTemplate.opsForZSet().remove(key, member));
  }

  public Long zRemoveRangeByScore(RedisOperationFeature feature, String key, double min, double max) {
    return execute(feature, "ZREMRANGEBYSCORE", key,
        () -> stringRedisTemplate.opsForZSet().removeRangeByScore(key, min, max));
  }

  public Long zRank(RedisOperationFeature feature, String key, String member) {
    return execute(feature, "ZRANK", key, () -> stringRedisTemplate.opsForZSet().rank(key, member));
  }

  public Long zSize(RedisOperationFeature feature, String key) {
    return execute(feature, "ZCARD", key, () -> stringRedisTemplate.opsForZSet().size(key));
  }

  // -------- Stream ops
  public void xAdd(RedisOperationFeature feature, String streamKey, String recordId, Map<String, String> body) {
    execute(feature, "XADD", streamKey, () -> {
      RecordId id = RecordId.of(recordId);
      MapRecord<String, String, String> record =
          StreamRecords.newRecord().in(streamKey).ofMap(body).withId(id);
      stringRedisTemplate.opsForStream().add(record);
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
        () -> stringRedisTemplate.opsForStream().range(streamKey, range, Limit.limit().count(count)));
  }

  public Long xDel(RedisOperationFeature feature, String streamKey, RecordId... ids) {
    return execute(feature, "XDEL", streamKey, () -> stringRedisTemplate.opsForStream().delete(streamKey, ids));
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
      return stringRedisTemplate.execute(script, List.of(key), token);
    });
  }
}
