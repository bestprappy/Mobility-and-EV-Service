package com.navio.mobilityandevservice.service;
import com.navio.mobilityandevservice.exception.DiscoveryBusyException;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class DiscoveryRequestLimiterTests {
    static class MutableClock extends Clock {
        long millis;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
    @Test void tokenBucketRefillsWithoutSleepsAndCapsIdleCapacity() {
        var clock = new MutableClock();
        var limiter = new DiscoveryRequestLimiter(2,4,clock);
        limiter.acquire().close(); limiter.acquire().close();
        assertThatThrownBy(limiter::acquire).isInstanceOf(DiscoveryBusyException.class);
        clock.millis = 30000;
        limiter.acquire().close();
        assertThatThrownBy(limiter::acquire).isInstanceOf(DiscoveryBusyException.class);
        clock.millis = 600000;
        limiter.acquire().close(); limiter.acquire().close();
        assertThatThrownBy(limiter::acquire).isInstanceOf(DiscoveryBusyException.class);
    }
    @Test void concurrencyRejectionDoesNotConsumeTokensAndCloseIsIdempotent() {
        var limiter = new DiscoveryRequestLimiter(2,1,new MutableClock());
        var first = limiter.acquire();
        assertThatThrownBy(limiter::acquire).isInstanceOf(DiscoveryBusyException.class);
        first.close(); first.close();
        var second = limiter.acquire();
        second.close();
        assertThatThrownBy(limiter::acquire).isInstanceOf(DiscoveryBusyException.class);
    }
}
