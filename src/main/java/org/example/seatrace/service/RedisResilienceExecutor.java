package org.example.seatrace.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.springframework.stereotype.Component;

@Component
public class RedisResilienceExecutor {

  private final CircuitBreakerRegistry circuitBreakerRegistry;
  private final BulkheadRegistry bulkheadRegistry;

  public RedisResilienceExecutor(
      CircuitBreakerRegistry circuitBreakerRegistry,
      BulkheadRegistry bulkheadRegistry
  ) {
    this.circuitBreakerRegistry = circuitBreakerRegistry;
    this.bulkheadRegistry = bulkheadRegistry;
  }

  public <T> T execute(RedisOperationFeature feature, CheckedSupplier<T> supplier) throws Throwable {
    CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(feature.resilienceName());
    Bulkhead bulkhead = bulkheadRegistry.bulkhead(feature.resilienceName());
    CheckedSupplier<T> decorated = CircuitBreaker.decorateCheckedSupplier(circuitBreaker, supplier);
    decorated = Bulkhead.decorateCheckedSupplier(bulkhead, decorated);
    return decorated.get();
  }
}
