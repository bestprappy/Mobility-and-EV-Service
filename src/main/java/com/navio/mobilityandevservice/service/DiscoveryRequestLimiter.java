package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.exception.DiscoveryBusyException;
import com.navio.mobilityandevservice.exception.DiscoveryDisabledException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * Per-instance, bounded-memory budget shared by discovery and reader requests.
 * Every Places discovery/explore call acquires a permit before reaching Google, so a
 * disabled feature ({@code navio.discovery.enabled=false}) has no budget at all.
 */
@Component
public class DiscoveryRequestLimiter {
    private final boolean enabled;
    private final int requestsPerMinute;
    private final int maxConcurrent;
    private final Clock clock;
    private double tokens;
    private long lastRefill;
    private int active;

    @Autowired
    public DiscoveryRequestLimiter(@Value("${navio.discovery.enabled:false}") boolean enabled,
                                   @Value("${navio.discovery.requests-per-minute:60}") int requestsPerMinute,
                                   @Value("${navio.discovery.max-concurrent:4}") int maxConcurrent) {
        this(enabled, requestsPerMinute, maxConcurrent, Clock.systemUTC());
    }

    DiscoveryRequestLimiter(int requestsPerMinute, int maxConcurrent) {
        this(true, requestsPerMinute, maxConcurrent, Clock.systemUTC());
    }

    DiscoveryRequestLimiter(int requestsPerMinute, int maxConcurrent, Clock clock) {
        this(true, requestsPerMinute, maxConcurrent, clock);
    }

    DiscoveryRequestLimiter(boolean enabled, int requestsPerMinute, int maxConcurrent, Clock clock) {
        if (requestsPerMinute < 1 || maxConcurrent < 1) throw new IllegalArgumentException("Discovery limits must be positive");
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
        this.maxConcurrent = maxConcurrent;
        this.clock = clock;
        this.tokens = requestsPerMinute;
        this.lastRefill = clock.millis();
    }

    public synchronized Permit acquire() {
        if (!enabled) throw new DiscoveryDisabledException();
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
