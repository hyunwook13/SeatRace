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

  private <T> T execute(String op, String keyForMsg, io.github.resilience4j.core.functions.CheckedSupplier<T> supplier) {
    try {
      return redisResilienceExecutor.execute(supplier);
    } catch (Throwable ex) {
      String suffix = keyForMsg == null ? "" : ": key=" + keyForMsg;
      throw new RedisUnavailableException("Redis " + op + " 실패" + suffix, ex);
    }
  }

  // -------- Value ops
  public String get(String key) {
    return execute("GET", key, () -> stringRedisTemplate.opsForValue().get(key));
  }

  public void set(String key, String value, Duration ttl) {
    execute("SET", key, () -> {
      stringRedisTemplate.opsForValue().set(key, value, ttl);
      return null;
    });
  }

  public void set(String key, String value) {
    execute("SET", key, () -> {
      stringRedisTemplate.opsForValue().set(key, value);
      return null;
    });
  }

  public Boolean setIfAbsent(String key, String value, Duration ttl) {
    return execute("SETNX", key, () -> stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl));
  }

  public Boolean hasKey(String key) {
    return execute("EXISTS", key, () -> stringRedisTemplate.hasKey(key));
  }

  public Boolean delete(String key) {
    return execute("DEL", key, () -> stringRedisTemplate.delete(key));
  }

  public Long delete(Collection<String> keys) {
    return execute("DEL(multi)", null, () -> stringRedisTemplate.delete(keys));
  }

  public Long increment(String key) {
    return execute("INCR", key, () -> stringRedisTemplate.opsForValue().increment(key));
  }

  public Boolean expire(String key, Duration ttl) {
    return execute("EXPIRE", key, () -> stringRedisTemplate.expire(key, ttl));
  }

  // -------- ZSet ops
  public Double zScore(String key, String member) {
    return execute("ZSCORE", key, () -> stringRedisTemplate.opsForZSet().score(key, member));
  }

  public Boolean zAdd(String key, String member, double score) {
    return execute("ZADD", key, () -> stringRedisTemplate.opsForZSet().add(key, member, score));
  }

  public java.util.Set<String> zRange(String key, long start, long end) {
    return execute("ZRANGE", key, () -> stringRedisTemplate.opsForZSet().range(key, start, end));
  }

  public Long zRemove(String key, String member) {
    return execute("ZREM", key, () -> stringRedisTemplate.opsForZSet().remove(key, member));
  }

  public Long zRemoveRangeByScore(String key, double min, double max) {
    return execute("ZREMRANGEBYSCORE", key,
        () -> stringRedisTemplate.opsForZSet().removeRangeByScore(key, min, max));
  }

  public Long zRank(String key, String member) {
    return execute("ZRANK", key, () -> stringRedisTemplate.opsForZSet().rank(key, member));
  }

  public Long zSize(String key) {
    return execute("ZCARD", key, () -> stringRedisTemplate.opsForZSet().size(key));
  }

  // -------- Stream ops
  public void xAdd(String streamKey, String recordId, Map<String, String> body) {
    execute("XADD", streamKey, () -> {
      RecordId id = RecordId.of(recordId);
      MapRecord<String, String, String> record =
          StreamRecords.newRecord().in(streamKey).ofMap(body).withId(id);
      stringRedisTemplate.opsForStream().add(record);
      return null;
    });
  }

  public List<MapRecord<String, Object, Object>> xRange(
      String streamKey,
      Range<String> range,
      int count
  ) {
    return execute("XRANGE", streamKey,
        () -> stringRedisTemplate.opsForStream().range(streamKey, range, Limit.limit().count(count)));
  }

  public Long xDel(String streamKey, RecordId... ids) {
    return execute("XDEL", streamKey, () -> stringRedisTemplate.opsForStream().delete(streamKey, ids));
  }

  // -------- Script ops
  public Long evalUnlockScript(String key, String token) {
    String scriptText =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";
    return execute("UNLOCK script", key, () -> {
      DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptText, Long.class);
      return stringRedisTemplate.execute(script, List.of(key), token);
    });
  }
}
