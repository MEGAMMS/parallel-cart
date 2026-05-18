package com.parallelcart.observability;

import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class PerformanceTimingAspect {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTimingAspect.class);

    @Around("execution(* com.parallelcart.service.impl.ProductServiceImpl.listProducts(..)) || " +
            "execution(* com.parallelcart.service.impl.ProductServiceImpl.getProduct(..))")
    public Object timeProductReads(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "product_read");
    }

    @Around("execution(* com.parallelcart.service.impl.CartServiceImpl.checkout(..))")
    public Object timeCheckout(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "checkout");
    }

    @Around("execution(* com.parallelcart.infra.repository.InventoryRepository.saveAndFlush(..))")
    public Object timeInventoryUpdate(ProceedingJoinPoint joinPoint) throws Throwable {
        return time(joinPoint, "inventory_update");
    }

    private Object time(ProceedingJoinPoint joinPoint, String operation) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            log.info("perf_timing operation={} method={} status=success duration_ms={}",
                    operation,
                    joinPoint.getSignature().toShortString(),
                    durationMs);
            return result;
        } catch (Throwable ex) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            log.warn("perf_timing operation={} method={} status=error duration_ms={} error_type={}",
                    operation,
                    joinPoint.getSignature().toShortString(),
                    durationMs,
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }
}
