package com.navio.mobilityandevservice.config;

import com.navio.mobilityandevservice.provider.usage.GoogleUsageMeter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * Monthly Google request limits. On unless {@code navio.google.free-tier.enabled=false};
 * {@code monthly-limits.<METER>} overrides a default from {@link GoogleUsageMeter}.
 */
@ConfigurationProperties("navio.google.free-tier")
public record GoogleFreeTierProperties(Boolean enabled, Map<GoogleUsageMeter, Integer> monthlyLimits) {

    public GoogleFreeTierProperties {
        enabled = enabled == null || enabled;
        monthlyLimits = monthlyLimits == null || monthlyLimits.isEmpty()
                ? Map.of() : Map.copyOf(new EnumMap<>(monthlyLimits));
    }

    public int limitFor(GoogleUsageMeter meter) {
        Integer configured = monthlyLimits.get(meter);
        return configured == null ? meter.defaultMonthlyLimit() : Math.max(0, configured);
    }
}
