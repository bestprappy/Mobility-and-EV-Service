package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.config.GoogleFreeTierProperties;
import com.navio.mobilityandevservice.domain.usage.GoogleUsageResponse;
import com.navio.mobilityandevservice.exception.AdminAccessDeniedException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.usage.GoogleFreeTierBudget;
import com.navio.mobilityandevservice.provider.usage.GoogleUsageMeter;
import com.navio.mobilityandevservice.provider.usage.ProviderUsageStore;
import com.navio.mobilityandevservice.provider.usage.ProviderUsageStore.MeterUsage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reads the counters behind {@link GoogleFreeTierBudget}. Never calls Google, so viewing costs nothing. */
@Service
public class GoogleUsageReportService {

    private static final Set<String> ADMINISTRATOR_ROLES = Set.of("ADMIN", "OWNER");

    private final ProviderUsageStore store;
    private final GoogleFreeTierProperties properties;
    private final boolean placesDiscoveryEnabled;
    private final Clock clock;

    @Autowired
    public GoogleUsageReportService(
            ProviderUsageStore store,
            GoogleFreeTierProperties properties,
            @Value("${navio.discovery.enabled:false}") boolean placesDiscoveryEnabled
    ) {
        this(store, properties, placesDiscoveryEnabled, Clock.systemUTC());
    }

    GoogleUsageReportService(
            ProviderUsageStore store,
            GoogleFreeTierProperties properties,
            boolean placesDiscoveryEnabled,
            Clock clock
    ) {
        this.store = store;
        this.properties = properties;
        this.placesDiscoveryEnabled = placesDiscoveryEnabled;
        this.clock = clock;
    }

    /** @param callerRoles the gateway's comma-separated {@code X-User-Roles}; null when none were asserted */
    public GoogleUsageResponse currentMonth(String callerRoles) {
        requireAdministrator(callerRoles);

        YearMonth month = YearMonth.now(clock.withZone(GoogleFreeTierBudget.BILLING_ZONE));
        Map<String, MeterUsage> counted = read(month);
        List<GoogleUsageResponse.Meter> meters = Arrays.stream(GoogleUsageMeter.values())
                .map(meter -> toMeter(meter, counted.get(meter.name())))
                .toList();

        return new GoogleUsageResponse(
                startOf(month),
                startOf(month.plusMonths(1)),
                clock.instant(),
                properties.enabled(),
                placesDiscoveryEnabled,
                meters
        );
    }

    private static void requireAdministrator(String callerRoles) {
        boolean administrator = callerRoles != null && Arrays.stream(callerRoles.split(","))
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .anyMatch(ADMINISTRATOR_ROLES::contains);
        if (!administrator) {
            throw new AdminAccessDeniedException();
        }
    }

    private Map<String, MeterUsage> read(YearMonth month) {
        try {
            return store.usageFor(month.atDay(1)).stream()
                    .collect(Collectors.toMap(MeterUsage::meter, Function.identity()));
        } catch (DataAccessException exception) {
            throw new ProviderUnavailableException("Google usage counts could not be read", exception);
        }
    }

    private GoogleUsageResponse.Meter toMeter(GoogleUsageMeter meter, MeterUsage usage) {
        return new GoogleUsageResponse.Meter(
                meter.name(),
                usage == null ? 0 : usage.requestCount(),
                properties.limitFor(meter),
                usage == null ? null : usage.updatedAt()
        );
    }

    private static Instant startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(GoogleFreeTierBudget.BILLING_ZONE).toInstant();
    }
}
