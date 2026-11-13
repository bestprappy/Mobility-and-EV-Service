package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.place.OpeningHoursResponse;
import com.navio.mobilityandevservice.domain.place.PlaceDetailResponse;
import com.navio.mobilityandevservice.domain.place.PlaceLocationResponse;
import com.navio.mobilityandevservice.domain.place.PlaceProviderName;
import com.navio.mobilityandevservice.domain.place.PlaceSearchResponse;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.PlaceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceServiceDetailPhotoTests {

    private static final String ID = "place-1";

    private final PlaceProvider provider = mock(PlaceProvider.class);
    private PlaceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker breaker = mock(CircuitBreaker.class);
        when(factory.create(anyString())).thenReturn(breaker);
        when(breaker.run(any(Supplier.class), any(Function.class))).thenAnswer(call -> {
            try {
                return ((Supplier<?>) call.getArgument(0)).get();
            } catch (RuntimeException failure) {
                return ((Function<Throwable, ?>) call.getArgument(1)).apply(failure);
            }
        });
        service = new PlaceService(provider, factory, cache(), this.<PlaceSearchResponse>cache());
    }

    private <T> StaleAwareCache<T> cache() {
        return new StaleAwareCache<>(Duration.ofMinutes(15), Duration.ofHours(24), 100, Clock.systemUTC());
    }

    private static PlaceDetailResponse place(String photoUrl) {
        return new PlaceDetailResponse(PlaceProviderName.GOOGLE, ID, "Central Park", null,
                new PlaceLocationResponse(1, 2, null, ID), null, null, photoUrl, OpeningHoursResponse.empty(),
                null, null, null, null, null, null);
    }

    @Test
    void askingWithoutAPhotoNeverRequestsOne() {
        when(provider.getDetailWithoutPhoto(ID, null)).thenReturn(place(null));

        assertThat(service.getDetail(ID, PlaceProviderName.GOOGLE, null, false).photoUrl()).isNull();

        verify(provider, never()).getDetail(any(), any());
    }

    @Test
    void askingWithAPhotoKeepsTheOriginalLookup() {
        when(provider.getDetail(ID, null)).thenReturn(place("https://photo"));

        assertThat(service.getDetail(ID, PlaceProviderName.GOOGLE, null, true).photoUrl()).isEqualTo("https://photo");

        verify(provider, never()).getDetailWithoutPhoto(any(), any());
    }

    @Test
    void repeatedLookupsWithoutAPhotoCallGoogleOnce() {
        when(provider.getDetailWithoutPhoto(ID, null)).thenReturn(place(null));

        service.getDetail(ID, PlaceProviderName.GOOGLE, null, false);
        service.getDetail(ID, PlaceProviderName.GOOGLE, null, false);

        verify(provider, times(1)).getDetailWithoutPhoto(ID, null);
    }

    @Test
    void aCachedLookupWithAPhotoAnswersALaterOneWithoutAPhoto() {
        when(provider.getDetail(ID, null)).thenReturn(place("https://photo"));
        service.getDetail(ID, PlaceProviderName.GOOGLE, null, true);

        service.getDetail(ID, PlaceProviderName.GOOGLE, null, false);

        verify(provider, never()).getDetailWithoutPhoto(any(), any());
    }

    @Test
    void aCachedLookupWithoutAPhotoCannotAnswerOneThatNeedsAPhoto() {
        when(provider.getDetailWithoutPhoto(ID, null)).thenReturn(place(null));
        when(provider.getDetail(ID, null)).thenReturn(place("https://photo"));
        service.getDetail(ID, PlaceProviderName.GOOGLE, null, false);

        var withPhoto = service.getDetail(ID, PlaceProviderName.GOOGLE, null, true);

        assertThat(withPhoto.photoUrl()).isEqualTo("https://photo");
    }

    @Test
    void aSessionTokenStillReachesGoogleToCloseTheAutocompleteSession() {
        when(provider.getDetailWithoutPhoto(eq(ID), any())).thenReturn(place(null));
        service.getDetail(ID, PlaceProviderName.GOOGLE, null, false);

        service.getDetail(ID, PlaceProviderName.GOOGLE, "session", false);

        verify(provider).getDetailWithoutPhoto(ID, "session");
    }

    @Test
    void anOutageFallsBackToWhatWasLastCached() {
        when(provider.getDetailWithoutPhoto(eq(ID), any())).thenReturn(place(null));
        service.getDetail(ID, PlaceProviderName.GOOGLE, null, false);
        when(provider.getDetailWithoutPhoto(eq(ID), any())).thenThrow(new ProviderClientException("down"));

        var fallback = service.getDetail(ID, PlaceProviderName.GOOGLE, "session", false);

        assertThat(fallback.name()).isEqualTo("Central Park");
    }

    @Test
    void anOutageWithNothingCachedIsReportedAsUnavailable() {
        when(provider.getDetailWithoutPhoto(ID, null)).thenThrow(new ProviderClientException("down"));

        assertThatThrownBy(() -> service.getDetail(ID, PlaceProviderName.GOOGLE, null, false))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void anUnsupportedProviderIsRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> service.getDetail(ID, null, null, false))
                .isInstanceOf(InvalidRequestException.class);

        verify(provider, never()).getDetailWithoutPhoto(any(), any());
    }
}
