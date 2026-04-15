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

  // -------- Value ops
  public String get(String key) {
    try {
      return stringRedisTemplate.opsForValue().get(key);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis GET 실패: key=" + key, ex);
    }
  }

  public void set(String key, String value, Duration ttl) {
    try {
      stringRedisTemplate.opsForValue().set(key, value, ttl);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis SET 실패: key=" + key, ex);
    }
  }

  public void set(String key, String value) {
    try {
      stringRedisTemplate.opsForValue().set(key, value);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis SET 실패: key=" + key, ex);
    }
  }

  public Boolean setIfAbsent(String key, String value, Duration ttl) {
    try {
      return stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis SETNX 실패: key=" + key, ex);
    }
  }

  public Boolean hasKey(String key) {
    try {
      return stringRedisTemplate.hasKey(key);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis EXISTS 실패: key=" + key, ex);
    }
  }

  public Boolean delete(String key) {
    try {
      return stringRedisTemplate.delete(key);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis DEL 실패: key=" + key, ex);
    }
  }

  public Long delete(Collection<String> keys) {
    try {
      return stringRedisTemplate.delete(keys);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis DEL(multi) 실패", ex);
    }
  }

  public Long increment(String key) {
    try {
      return stringRedisTemplate.opsForValue().increment(key);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis INCR 실패: key=" + key, ex);
    }
  }

  public Boolean expire(String key, Duration ttl) {
    try {
      return stringRedisTemplate.expire(key, ttl);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis EXPIRE 실패: key=" + key, ex);
    }
  }

  // -------- ZSet ops
  public Double zScore(String key, String member) {
    try {
      return stringRedisTemplate.opsForZSet().score(key, member);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis ZSCORE 실패: key=" + key, ex);
    }
  }

  public Boolean zAdd(String key, String member, double score) {
    try {
      return stringRedisTemplate.opsForZSet().add(key, member, score);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis ZADD 실패: key=" + key, ex);
    }
  }

  public java.util.Set<String> zRange(String key, long start, long end) {
    try {
      return stringRedisTemplate.opsForZSet().range(key, start, end);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis ZRANGE 실패: key=" + key, ex);
    }
  }

  public Long zRemove(String key, String member) {
    try {
      return stringRedisTemplate.opsForZSet().remove(key, member);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis ZREM 실패: key=" + key, ex);
    }
  }

  public Long zRemoveRangeByScore(String key, double min, double max) {
    try {
      return stringRedisTemplate.opsForZSet().removeRangeByScore(key, min, max);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis ZREMRANGEBYSCORE 실패: key=" + key, ex);
    }
  }

  public Long zRank(String key, String member) {
    try {
      return stringRedisTemplate.opsForZSet().rank(key, member);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis ZRANK 실패: key=" + key, ex);
    }
  }

  public Long zSize(String key) {
    try {
      return stringRedisTemplate.opsForZSet().size(key);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis ZCARD 실패: key=" + key, ex);
    }
  }

  // -------- Stream ops
  public void xAdd(String streamKey, String recordId, Map<String, String> body) {
    try {
      RecordId id = RecordId.of(recordId);
      MapRecord<String, String, String> record =
          StreamRecords.newRecord().in(streamKey).ofMap(body).withId(id);
      stringRedisTemplate.opsForStream().add(record);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis XADD 실패: key=" + streamKey, ex);
    }
  }

  public List<MapRecord<String, Object, Object>> xRange(
      String streamKey,
      Range<String> range,
      int count
  ) {
    try {
      return stringRedisTemplate.opsForStream().range(streamKey, range, Limit.limit().count(count));
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis XRANGE 실패: key=" + streamKey, ex);
    }
  }

  public Long xDel(String streamKey, RecordId... ids) {
    try {
      return stringRedisTemplate.opsForStream().delete(streamKey, ids);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis XDEL 실패: key=" + streamKey, ex);
    }
  }

  // -------- Script ops
  public Long evalUnlockScript(String key, String token) {
    String scriptText =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "  return redis.call('del', KEYS[1]) " +
        "else " +
        "  return 0 " +
        "end";
    try {
      DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptText, Long.class);
      return stringRedisTemplate.execute(script, List.of(key), token);
    } catch (Exception ex) {
      throw new RedisUnavailableException("Redis UNLOCK script 실패: key=" + key, ex);
    }
  }
}
