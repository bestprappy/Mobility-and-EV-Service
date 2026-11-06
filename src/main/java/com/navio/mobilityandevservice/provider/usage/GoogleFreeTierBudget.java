package com.navio.mobilityandevservice.provider.usage;

import com.navio.mobilityandevservice.config.GoogleFreeTierProperties;
import com.navio.mobilityandevservice.exception.FreeTierExhaustedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops Google Maps Platform calls once a month's free tier is used up. Every adapter call
 * consumes one unit before its request is sent; a refused unit means no request and no charge.
 * If the counter cannot be reached the call is refused too: spending is never the fallback.
 */
@Slf4j
@Component
public class GoogleFreeTierBudget {

    /** Google resets monthly free usage on its billing calendar, which runs on Pacific time. */
    static final ZoneId BILLING_ZONE = ZoneId.of("America/Los_Angeles");

    private final ProviderUsageStore store;
    private final GoogleFreeTierProperties properties;
    private final Clock clock;
    private final Map<GoogleUsageMeter, YearMonth> reportedExhausted = new ConcurrentHashMap<>();

    @Autowired
    public GoogleFreeTierBudget(ProviderUsageStore store, GoogleFreeTierProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    GoogleFreeTierBudget(ProviderUsageStore store, GoogleFreeTierProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /** A budget that never refuses, for adapters used without a counter (tests and tools). */
    public static GoogleFreeTierBudget unlimited() {
        return new GoogleFreeTierBudget(null, new GoogleFreeTierProperties(false, Map.of()), Clock.systemUTC());
    }

    public void consume(GoogleUsageMeter meter) {
        if (!properties.enabled()) {
            return;
        }
        YearMonth month = YearMonth.now(clock.withZone(BILLING_ZONE));
        boolean allowed;
        try {
            allowed = store.tryConsume(month.atDay(1), meter.name(), properties.limitFor(meter));
        } catch (DataAccessException exception) {
            log.error("Google usage counter unavailable; refusing the {} call", meter, exception);
            throw new FreeTierExhaustedException(meter, exception);
        }
        if (!allowed) {
            if (!month.equals(reportedExhausted.put(meter, month))) {
                log.warn("Google {} free tier reached for {}; further calls are refused until next month", meter, month);
            }
            throw new FreeTierExhaustedException(meter);
        }
    }
}
