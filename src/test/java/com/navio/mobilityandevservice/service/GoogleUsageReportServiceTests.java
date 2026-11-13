package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.config.GoogleFreeTierProperties;
import com.navio.mobilityandevservice.domain.usage.GoogleUsageResponse;
import com.navio.mobilityandevservice.exception.AdminAccessDeniedException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.usage.GoogleUsageMeter;
import com.navio.mobilityandevservice.provider.usage.ProviderUsageStore;
import com.navio.mobilityandevservice.provider.usage.ProviderUsageStore.MeterUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleUsageReportServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-09T05:00:00Z");
    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);

    private final ProviderUsageStore store = mock(ProviderUsageStore.class);

    private GoogleUsageReportService service(GoogleFreeTierProperties properties, boolean discovery, Instant now) {
        return new GoogleUsageReportService(store, properties, discovery, Clock.fixed(now, ZoneOffset.UTC));
    }

    private GoogleUsageReportService service() {
        return service(new GoogleFreeTierProperties(true, Map.of()), false, NOW);
    }

    @Test
    void reportsEveryMeterEvenWhenItHasNoCallsYet() {
        Instant counted = Instant.parse("2026-11-08T10:00:00Z");
        when(store.usageFor(NOVEMBER)).thenReturn(List.of(new MeterUsage("PLACE_PHOTOS", 412, counted)));

        GoogleUsageResponse report = service().currentMonth("ADMIN");

        assertThat(report.meters()).hasSize(GoogleUsageMeter.values().length);
        assertThat(report.meters()).contains(new GoogleUsageResponse.Meter("PLACE_PHOTOS", 412, 950, counted));
        assertThat(report.meters()).contains(new GoogleUsageResponse.Meter("ROUTES", 0, 4_750, null));
    }

    @Test
    void reportsAConfiguredLimitInsteadOfTheDefault() {
        when(store.usageFor(NOVEMBER)).thenReturn(List.of());
        var properties = new GoogleFreeTierProperties(true, Map.of(GoogleUsageMeter.PLACE_PHOTOS, 300));

        GoogleUsageResponse report = service(properties, false, NOW).currentMonth("ADMIN");

        assertThat(report.meters()).contains(new GoogleUsageResponse.Meter("PLACE_PHOTOS", 0, 300, null));
    }

    @Test
    void theMonthRunsOnGooglesPacificBillingCalendar() {
        when(store.usageFor(NOVEMBER)).thenReturn(List.of());

        GoogleUsageResponse report = service().currentMonth("ADMIN");

        assertThat(report.monthStart()).isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
        assertThat(report.resetsAt()).isEqualTo(Instant.parse("2026-12-01T08:00:00Z"));
        assertThat(report.asOf()).isEqualTo(NOW);
    }

    @Test
    void itIsStillTheOldMonthUntilMidnightPacificEvenOnceUtcHasRolledOver() {
        when(store.usageFor(LocalDate.of(2026, 10, 1))).thenReturn(List.of());
        var justAfterUtcMidnight = Instant.parse("2026-11-01T01:00:00Z");

        GoogleUsageResponse report = service(new GoogleFreeTierProperties(true, Map.of()), false, justAfterUtcMidnight)
                .currentMonth("ADMIN");

        assertThat(report.resetsAt()).isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
    }

    @Test
    void reportsWhenLimitsAreOffAndWhetherDiscoveryCanCallGoogle() {
        when(store.usageFor(NOVEMBER)).thenReturn(List.of());

        GoogleUsageResponse report = service(new GoogleFreeTierProperties(false, Map.of()), true, NOW)
                .currentMonth("OWNER");

        assertThat(report.limitsEnforced()).isFalse();
        assertThat(report.placesDiscoveryEnabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OWNER", "USER,ADMIN", "USER, OWNER", "admin"})
    void administratorsCanReadTheReport(String roles) {
        when(store.usageFor(any())).thenReturn(List.of());

        assertThat(service().currentMonth(roles).meters()).isNotEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "USER", "MODERATOR", "USER,MODERATOR", "NOTADMIN", "ADMINISTRATOR"})
    void everyoneElseIsRefusedBeforeAnyCountIsRead(String roles) {
        assertThatThrownBy(() -> service().currentMonth(roles)).isInstanceOf(AdminAccessDeniedException.class);

        verifyNoInteractions(store);
    }

    @Test
    void anUnreadableCounterIsAnOutageNotAZero() {
        when(store.usageFor(any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> service().currentMonth("ADMIN")).isInstanceOf(ProviderUnavailableException.class);
    }
}
