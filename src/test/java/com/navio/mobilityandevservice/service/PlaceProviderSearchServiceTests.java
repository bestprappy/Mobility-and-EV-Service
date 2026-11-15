package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.domain.place.PlaceProviderSearchResponse;
import com.navio.mobilityandevservice.exception.AuthenticationRequiredException;
import com.navio.mobilityandevservice.exception.DiscoveryBusyException;
import com.navio.mobilityandevservice.exception.FreeTierExhaustedException;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.exception.ProviderSearchDisabledException;
import com.navio.mobilityandevservice.exception.ProviderSearchLimitException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.google.GooglePlacesAdapter;
import com.navio.mobilityandevservice.provider.usage.GoogleUsageMeter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaceProviderSearchServiceTests {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final GooglePlacesAdapter provider = mock(GooglePlacesAdapter.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));

    private PlaceProviderSearchService service(boolean enabled, int daily, int perUser) {
        return new PlaceProviderSearchService(provider, enabled, 3, daily, perUser,
                new DiscoveryRequestLimiter(true, 1000, 4, clock), clock);
    }

    private static PlaceProviderSearchResponse.Item item(String id, String country) {
        return new PlaceProviderSearchResponse.Item(id, "Place " + id, "Address", 13.7, 100.5,
                "Museum", "museum", country, "Bangkok", "https://maps.google.com/?cid=1");
    }

    private void respond(String nextToken, PlaceProviderSearchResponse.Item... items) {
        when(provider.providerSearch(any(), any(), anyInt(), any()))
                .thenReturn(new GooglePlacesAdapter.ProviderSearchPage(List.of(items), nextToken));
    }

    @Test void aDisabledSearchNeverReachesGoogle() {
        assertThatThrownBy(() -> service(false, 30, 10).search(USER, "museum", "", ""))
                .isInstanceOf(ProviderSearchDisabledException.class);
        verifyNoInteractions(provider);
    }

    @Test void anonymousCallersAreRefusedBeforeAnyProviderCall() {
        assertThatThrownBy(() -> service(true, 30, 10).search(null, "museum", "", ""))
                .isInstanceOf(AuthenticationRequiredException.class);
        verifyNoInteractions(provider);
    }

    @Test void queriesShorterThanTwoCharactersOrLongerThan120AreRejectedWithoutACall() {
        var service = service(true, 30, 10);
        assertThatThrownBy(() -> service.search(USER, " a ", "", "")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(USER, "x".repeat(121), "", "")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.search(USER, "museum", "XX", "")).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(provider);
    }

    @Test void oneClickMakesExactlyOneTextSearchCallWithACountryScopedQuery() {
        respond("tok2", item("ChIJa", "TH"));
        var page = service(true, 30, 10).search(USER, "  floating   market ", "th", "");
        verify(provider, times(1)).providerSearch(eq("floating market in Thailand"), eq("TH"),
                eq(PlaceProviderSearchService.PAGE_SIZE), isNull());
        assertThat(page.items()).extracting(PlaceProviderSearchResponse.Item::providerPlaceId).containsExactly("ChIJa");
        assertThat(page.source()).isEqualTo("GOOGLE");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.cursor()).isEqualTo("2.tok2");
    }

    @Test void aQueryThatAlreadyNamesTheCountryIsNotRepeated() {
        assertThat(PlaceProviderSearchService.textQuery("ramen Japan", "JP")).isEqualTo("ramen Japan");
        assertThat(PlaceProviderSearchService.textQuery("ramen", "")).isEqualTo("ramen");
    }

    @Test void resultsVerifiedInAnotherCountryAreDroppedButUnverifiedOnesStay() {
        respond(null, item("ChIJth", "TH"), item("ChIJjp", "JP"), item("ChIJunknown", null));
        var page = service(true, 30, 10).search(USER, "night market", "TH", "");
        assertThat(page.items()).extracting(PlaceProviderSearchResponse.Item::providerPlaceId)
                .containsExactly("ChIJth", "ChIJunknown");
    }

    @Test void theCursorCarriesTheGoogleTokenToTheNextPageAndStopsAtMaxPages() {
        respond("tok4", item("ChIJc", null));
        var service = service(true, 30, 10);
        var third = service.search(USER, "museum", "", "3.tok3");
        verify(provider).providerSearch("museum", null, PlaceProviderSearchService.PAGE_SIZE, "tok3");
        assertThat(third.page()).isEqualTo(3);
        assertThat(third.cursor()).as("page 3 of 3 offers no further page").isNull();
    }

    @Test void forgedOrOutOfRangeCursorsAreRejectedWithoutACall() {
        var service = service(true, 30, 10);
        for (String cursor : List.of("1.tok", "4.tok", "0.tok", "abc", "2.", "2.has space")) {
            assertThatThrownBy(() -> service.search(USER, "museum", "", cursor))
                    .as(cursor).isInstanceOf(InvalidRequestException.class);
        }
        verifyNoInteractions(provider);
    }

    @Test void eachUserHasADailyAllowanceThatOthersDoNotShare() {
        respond(null);
        var service = service(true, 30, 2);
        service.search(USER, "museum", "", "");
        service.search(USER, "museum", "", "");
        assertThatThrownBy(() -> service.search(USER, "museum", "", ""))
                .isInstanceOfSatisfying(ProviderSearchLimitException.class,
                        e -> assertThat(e.scope()).isEqualTo(ProviderSearchLimitException.Scope.USER_DAY));
        service.search(OTHER, "museum", "", "");
        verify(provider, times(3)).providerSearch(any(), any(), anyInt(), any());
    }

    @Test void theGlobalDailyAllowanceStopsEveryoneAndResetsOnTheNextBillingDay() {
        respond(null);
        var service = service(true, 1, 10);
        service.search(USER, "museum", "", "");
        assertThatThrownBy(() -> service.search(OTHER, "museum", "", ""))
                .isInstanceOfSatisfying(ProviderSearchLimitException.class,
                        e -> assertThat(e.scope()).isEqualTo(ProviderSearchLimitException.Scope.DAY));
        clock.advance(Duration.ofDays(1));
        service.search(OTHER, "museum", "", "");
        verify(provider, times(2)).providerSearch(any(), any(), anyInt(), any());
    }

    @Test void anExhaustedMonthlyMeterBecomesAMonthLimitNotAnOutage() {
        when(provider.providerSearch(any(), any(), anyInt(), any()))
                .thenThrow(new FreeTierExhaustedException(GoogleUsageMeter.PLACES_TEXT_SEARCH));
        assertThatThrownBy(() -> service(true, 30, 10).search(USER, "museum", "", ""))
                .isInstanceOfSatisfying(ProviderSearchLimitException.class,
                        e -> assertThat(e.scope()).isEqualTo(ProviderSearchLimitException.Scope.MONTH));
    }

    @Test void aProviderFailureIsReportedOnceAndNeverRetried() {
        when(provider.providerSearch(any(), any(), anyInt(), any()))
                .thenThrow(new ProviderClientException("Google Places provider search failed"));
        assertThatThrownBy(() -> service(true, 30, 10).search(USER, "museum", "", ""))
                .isInstanceOf(ProviderUnavailableException.class);
        verify(provider, times(1)).providerSearch(any(), any(), anyInt(), any());
    }

    @Test void aBusyInstanceRefusesWithoutSpendingTheUsersAllowance() {
        respond(null);
        var limiter = new DiscoveryRequestLimiter(true, 1, 4, clock);
        var service = new PlaceProviderSearchService(provider, true, 3, 30, 1, limiter, clock);
        limiter.acquire().close();
        assertThatThrownBy(() -> service.search(USER, "museum", "", "")).isInstanceOf(DiscoveryBusyException.class);
        verifyNoInteractions(provider);
        clock.advance(Duration.ofMinutes(1));
        service.search(USER, "museum", "", "");
        verify(provider, times(1)).providerSearch(any(), any(), anyInt(), any());
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
