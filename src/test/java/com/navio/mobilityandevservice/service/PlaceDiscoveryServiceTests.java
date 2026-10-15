package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.domain.place.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Function;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import com.navio.mobilityandevservice.provider.google.GooglePlacesAdapter;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceDiscoveryServiceTests {
    private PlaceDiscoveryService service(GooglePlacesAdapter provider) { return service(provider,new DiscoveryRequestLimiter(60,4)); }
    @SuppressWarnings("unchecked")
    private PlaceDiscoveryService service(GooglePlacesAdapter provider, DiscoveryRequestLimiter limiter) {
        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker breaker = mock(CircuitBreaker.class);
        when(factory.create("googlePlaces")).thenReturn(breaker);
        when(breaker.run(any(Supplier.class),any(Function.class))).thenAnswer(invocation -> {
            Supplier<Object> call = invocation.getArgument(0);
            Function<Throwable,Object> fallback = invocation.getArgument(1);
            try { return call.get(); } catch (Throwable error) { return fallback.apply(error); }
        });
        return new PlaceDiscoveryService(provider,factory,limiter);
    }
    @Test void exhaustedBudgetDoesNotCallProvider() {
        var provider = mock(GooglePlacesAdapter.class);
        var limiter = new DiscoveryRequestLimiter(1,4,new DiscoveryRequestLimiterTests.MutableClock());
        limiter.acquire().close();
        assertThatThrownBy(() -> service(provider,limiter).discover("Tokyo",35.6,139.6,1,"popular",0,4))
                .isInstanceOf(com.navio.mobilityandevservice.exception.DiscoveryBusyException.class);
        verifyNoInteractions(provider);
    }
    @Test void malformedNullCandidatesAreIgnored() {
        assertThat(PlaceDiscoveryService.rank(java.util.Arrays.asList(null, new PlaceDiscoveryCandidate(null,null,null,List.of())),"popular")).isEmpty();
    }
    @Test void invalidInputIsRejectedBeforeProviderCall() {
        var provider = mock(GooglePlacesAdapter.class);
        var service = service(provider);
        assertThatThrownBy(() -> service.discover("Bangkok",Double.NaN,100.5,1,"popular",0,4)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.discover("Bangkok",13.7,100.5,13,"popular",0,4)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.discover("Bangkok",13.7,100.5,1,"trending",0,4)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.discover("Bangkok",13.7,100.5,1,"popular",Integer.MAX_VALUE,4)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(provider);
    }
    @Test void pageLoadsPhotosOnlyForVisibleResultsAndReportsHasMore() {
        var provider = mock(GooglePlacesAdapter.class);
        when(provider.discovery(anyDouble(),anyDouble(),any())).thenReturn(List.of(candidate("a",4.5,20L,null),candidate("b",4.5,30L,null),candidate("c",4.5,40L,null)));
        when(provider.withPhoto(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service(provider).discover("Tokyo",35.6,139.6,1,"popular",0,2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.seasonal()).isNull();
        verify(provider,times(2)).withPhoto(any());
    }
    @Test void unavailableProviderNeverReturnsFabricatedOrStaleResults() {
        var provider = mock(GooglePlacesAdapter.class);
        when(provider.discovery(anyDouble(),anyDouble(),any())).thenThrow(new IllegalStateException("provider offline"));
        assertThatThrownBy(() -> service(provider).discover("Tokyo",35.6,139.6,1,"popular",0,4))
                .isInstanceOf(ProviderUnavailableException.class);
    }
    @Test void unsupportedSeasonalRegionDoesNotSpendProviderRequests() {
        var provider = mock(GooglePlacesAdapter.class);
        assertThat(service(provider).discover("Tokyo",35.6,139.6,1,"seasonal",0,4).items()).isEmpty();
        verifyNoInteractions(provider);
    }

    private static PlaceDiscoveryCandidate candidate(String id, Double rating, Long reviews, String status) {
        return new PlaceDiscoveryCandidate(new PlaceDetailResponse(PlaceProviderName.GOOGLE,id,id,null,
                new PlaceLocationResponse(13.7,100.5,null,id),null,null,null,OpeningHoursResponse.empty(),
                "Museum",null,rating,reviews,null,"museum"),status,null,List.of());
    }
    @Test void popularPreservesProviderOrderAndRemovesClosedDuplicates() {
        var a = candidate("a",4.0,30L,"OPERATIONAL");
        var b = candidate("b",4.9,400L,null);
        assertThat(PlaceDiscoveryService.rank(List.of(a,b,a,candidate("c",5.0,900L,"CLOSED_PERMANENTLY")),"popular"))
                .extracting(c -> c.place().providerPlaceId()).containsExactly("a","b");
    }
    @Test void hiddenGemsRequireReviewEvidenceAndBelowMedianCount() {
        var pool = List.of(candidate("gem",4.5,50L,null), candidate("few",4.9,19L,null),
                candidate("low",4.2,40L,null), candidate("median",4.8,100L,null),
                candidate("large",4.9,200L,null),candidate("largest",4.9,400L,null),
                candidate("missing",null,null,null));
        assertThat(PlaceDiscoveryService.rank(pool,"hidden-gems"))
                .extracting(c -> c.place().providerPlaceId()).containsExactly("gem");
    }
    @Test void bayesianRankingDoesNotTreatMissingRatingAsZeroOrPromoteOneReview() {
        var pool = List.of(candidate("one",5.0,1L,null), candidate("established",4.7,1000L,null),candidate("other",4.0,1000L,null),candidate("unknown",null,null,null));
        assertThat(PlaceDiscoveryService.rank(pool,"worth-a-visit"))
                .extracting(c -> c.place().providerPlaceId()).containsExactly("established","one","other","unknown");
    }
    @Test void seasonalProfilesRespectRegionalRainfallAndUnknownDestinations() {
        assertThat(PlaceDiscoveryService.seasonal("Bangkok, Thailand",13.75,100.5,11).label()).isEqualTo("Fresh-air discoveries");
        assertThat(PlaceDiscoveryService.seasonal("Koh Samui, Thailand",9.51,100.01,11).label()).isEqualTo("Indoor discoveries");
        assertThat(PlaceDiscoveryService.seasonal("Phuket, Thailand",7.88,98.39,8).label()).isEqualTo("Indoor discoveries");
        assertThat(PlaceDiscoveryService.seasonal("Tokyo",35.6,139.6,11)).isNull();
        assertThat(PlaceDiscoveryService.seasonal("Bangkok",35.6,139.6,11)).isNull();
    }
}
