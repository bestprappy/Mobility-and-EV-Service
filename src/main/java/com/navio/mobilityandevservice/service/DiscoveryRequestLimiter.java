package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.exception.DiscoveryBusyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;

/** Per-instance, bounded-memory budget shared by discovery and reader requests. */
@Component
public class DiscoveryRequestLimiter {
    private final int requestsPerMinute;
    private final int maxConcurrent;
    private final Clock clock;
    private double tokens;
    private long lastRefill;
    private int active;

    @Autowired
    public DiscoveryRequestLimiter(@Value("${navio.discovery.requests-per-minute:60}") int requestsPerMinute,
                                   @Value("${navio.discovery.max-concurrent:4}") int maxConcurrent) {
        this(requestsPerMinute, maxConcurrent, Clock.systemUTC());
    }

    DiscoveryRequestLimiter(int requestsPerMinute, int maxConcurrent, Clock clock) {
        if (requestsPerMinute < 1 || maxConcurrent < 1) throw new IllegalArgumentException("Discovery limits must be positive");
        this.requestsPerMinute = requestsPerMinute;
        this.maxConcurrent = maxConcurrent;
        this.clock = clock;
        this.tokens = requestsPerMinute;
        this.lastRefill = clock.millis();
    }

    public synchronized Permit acquire() {
        long now = clock.millis();
        long elapsed = Math.max(0, now - lastRefill);
        tokens = Math.min(requestsPerMinute, tokens + elapsed * (requestsPerMinute / 60000.0));
        lastRefill = Math.max(now, lastRefill);
        if (active >= maxConcurrent || tokens < 1) throw new DiscoveryBusyException();
        tokens -= 1;
        active++;
        return new Permit();
    }

    public final class Permit implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            synchronized (DiscoveryRequestLimiter.this) {
                if (!closed) { active--; closed = true; }
            }
        }
    }
}
