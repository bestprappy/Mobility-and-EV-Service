package com.navio.mobilityandevservice.provider.usage;

import com.navio.mobilityandevservice.config.GoogleFreeTierProperties;
import com.navio.mobilityandevservice.exception.FreeTierExhaustedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleFreeTierBudgetTests {

    // 2026-11-01 05:00 UTC is still 31 October 22:00 in Google's Pacific billing calendar.
    private static final Clock FIRST_OF_NOVEMBER_UTC = Clock.fixed(Instant.parse("2026-11-01T05:00:00Z"), ZoneOffset.UTC);
    private static final GoogleFreeTierProperties DEFAULTS = new GoogleFreeTierProperties(null, null);

    private final ProviderUsageStore store = mock(ProviderUsageStore.class);

    @Test
    void allowsTheCallWhileTheMonthIsUnderTheFreeTier() {
        when(store.tryConsume(any(), anyString(), anyInt())).thenReturn(true);
        var budget = new GoogleFreeTierBudget(store, DEFAULTS, FIRST_OF_NOVEMBER_UTC);

        assertThatCode(() -> budget.consume(GoogleUsageMeter.PLACE_PHOTOS)).doesNotThrowAnyException();
    }

    @Test
    void refusesTheCallOnceTheMonthReachedItsLimit() {
        when(store.tryConsume(any(), anyString(), anyInt())).thenReturn(false);
        var budget = new GoogleFreeTierBudget(store, DEFAULTS, FIRST_OF_NOVEMBER_UTC);

        assertThatThrownBy(() -> budget.consume(GoogleUsageMeter.PLACE_DETAILS))
                .isInstanceOfSatisfying(FreeTierExhaustedException.class,
                        exception -> assertThat(exception.meter()).isEqualTo(GoogleUsageMeter.PLACE_DETAILS));
    }

    @Test
    void countsAgainstGooglesPacificBillingMonthAndTheDefaultLimit() {
        when(store.tryConsume(any(), anyString(), anyInt())).thenReturn(true);
        new GoogleFreeTierBudget(store, DEFAULTS, FIRST_OF_NOVEMBER_UTC).consume(GoogleUsageMeter.PLACE_PHOTOS);

        verify(store).tryConsume(LocalDate.of(2026, 10, 1), "PLACE_PHOTOS", 950);
    }

    @Test
    void usesAConfiguredLimitInsteadOfTheDefault() {
        when(store.tryConsume(any(), anyString(), anyInt())).thenReturn(true);
        var properties = new GoogleFreeTierProperties(true, Map.of(GoogleUsageMeter.ROUTES, 100));
        new GoogleFreeTierBudget(store, properties, FIRST_OF_NOVEMBER_UTC).consume(GoogleUsageMeter.ROUTES);

        verify(store).tryConsume(LocalDate.of(2026, 10, 1), "ROUTES", 100);
    }

    @Test
    void refusesTheCallWhenTheCounterCannotBeReached() {
        when(store.tryConsume(any(), anyString(), anyInt())).thenThrow(new DataAccessResourceFailureException("down"));
        var budget = new GoogleFreeTierBudget(store, DEFAULTS, FIRST_OF_NOVEMBER_UTC);

        assertThatThrownBy(() -> budget.consume(GoogleUsageMeter.ROUTES)).isInstanceOf(FreeTierExhaustedException.class);
    }

    @Test
    void aDisabledBudgetNeverTouchesTheCounter() {
        var budget = new GoogleFreeTierBudget(store, new GoogleFreeTierProperties(false, null), FIRST_OF_NOVEMBER_UTC);

        budget.consume(GoogleUsageMeter.PLACE_PHOTOS);

        verifyNoInteractions(store);
    }

    @Test
    void aNegativeConfiguredLimitBlocksEveryCall() {
        assertThat(new GoogleFreeTierProperties(true, Map.of(GoogleUsageMeter.PLACE_PHOTOS, -5))
                .limitFor(GoogleUsageMeter.PLACE_PHOTOS)).isZero();
    }
}
