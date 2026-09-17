package org.example.seatrace.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedSupplier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.example.seatrace.config.RedisResilienceProperties;
import org.springframework.stereotype.Component;

@Component
public class RedisResilienceExecutor {

  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final BulkheadRegistry bulkheadRegistry;
  private final RedisResilienceProperties properties;
  private final MeterRegistry meterRegistry;

  public RedisResilienceExecutor(
      CircuitBreakerRegistry circuitBreakerRegistry,
      BulkheadRegistry bulkheadRegistry,
      RedisResilienceProperties properties,
      MeterRegistry meterRegistry
  ) {
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.bulkheadRegistry = bulkheadRegistry;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  public <T> T execute(RedisOperationFeature feature, CheckedSupplier<T> supplier) throws Throwable {
    CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(feature.resilienceName());
    Bulkhead bulkhead = bulkheadRegistry.bulkhead(feature.resilienceName());
    CheckedSupplier<T> decorated = supplier;
    if (properties.isCircuitBreakerEnabled()) {
      decorated = CircuitBreaker.decorateCheckedSupplier(circuitBreaker, decorated);
    }
    decorated = Bulkhead.decorateCheckedSupplier(bulkhead, decorated);
    try {
      return decorated.get();
    } catch (BulkheadFullException ex) {
      recordFailure(feature, "bulkhead_rejected");
      throw ex;
    } catch (CallNotPermittedException ex) {
      recordFailure(feature, "circuit_open");
      throw ex;
    } catch (Throwable ex) {
      recordFailure(feature, "redis_failure");
      throw ex;
    }
  }

  private void recordFailure(RedisOperationFeature feature, String reason) {
    Counter.builder("seatrace.redis.resilience.failure")
        .tag("feature", feature.resilienceName())
        .tag("reason", reason)
        .register(meterRegistry)
        .increment();
  }
}
