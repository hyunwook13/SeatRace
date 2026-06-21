package org.example.seatrace.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.springframework.stereotype.Component;

@Component
public class RedisResilienceExecutor {

  private final CircuitBreaker circuitBreaker;
  private final Bulkhead bulkhead;

  public RedisResilienceExecutor(
      CircuitBreakerRegistry circuitBreakerRegistry,
      BulkheadRegistry bulkheadRegistry
  ) {
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCore");
    this.bulkhead = bulkheadRegistry.bulkhead("redisCore");
  }

  public <T> T execute(CheckedSupplier<T> supplier) throws Throwable {
    CheckedSupplier<T> decorated = CircuitBreaker.decorateCheckedSupplier(circuitBreaker, supplier);
    decorated = Bulkhead.decorateCheckedSupplier(bulkhead, decorated);
    return decorated.get();
  }
}
