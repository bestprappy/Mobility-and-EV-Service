package com.navio.mobilityandevservice.service;
import com.navio.mobilityandevservice.domain.place.*;
import com.navio.mobilityandevservice.provider.google.GooglePlacesAdapter;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlaceExploreServiceTests {
    @Test void globalTypesMixDestinationsAndTypesWithoutRepeatingCountryWideQueries() {
        var provider=mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenAnswer(call->new GooglePlacesAdapter.ExplorePage(List.of(PlaceExplorePolicyTests.candidate(call.getArgument(2),call.getArgument(2),call.getArgument(1))),null));
        when(provider.withPhoto(any())).thenAnswer(call->call.getArgument(0));
        var r=new PlaceExploreRequest("","",List.of(),List.of("cafe","restaurant"),"any","","");
        var result=new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4)).explore(r,"");
        assertThat(result.items()).extracting(i->i.place().placeLocation().countryCode()).containsExactly("JP","FR");
        verify(provider).exploreSearch(contains("Tokyo"),eq("cafe"),eq("JP"),eq(""));
        verify(provider).exploreSearch(contains("Paris"),eq("restaurant"),eq("FR"),eq(""));
    }
    @Test void unselectedDiscoverySamplesExplicitDifferentDestinationsWithinTheProviderBudget() {
        var provider=mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenAnswer(call -> new GooglePlacesAdapter.ExplorePage(List.of(PlaceExplorePolicyTests.candidate(call.getArgument(2),call.getArgument(2),"museum")),null));
        when(provider.withPhoto(any())).thenAnswer(call->call.getArgument(0));
        var r=new PlaceExploreRequest("","",List.of(),List.of(),"any","popular","");
        var result=new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4)).explore(r,"");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(i->i.place().placeLocation().countryCode()).doesNotHaveDuplicates().doesNotContain("");
        verify(provider,times(2)).exploreSearch(contains(" in "),eq(""),argThat(c->!c.isBlank()),eq(""));
    }
    @Test void supportedSeasonWithNoSuitablePlacesDoesNotClaimMissingClimateCoverage() {
        var provider=mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(PlaceExplorePolicyTests.candidate("hotel","JP","hotel")),null));
        var r=new PlaceExploreRequest("JP","",List.of(),List.of(),"season:autumn","seasonal","");
        var result=new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4)).explore(r,"");
        assertThat(result.items()).isEmpty(); assertThat(result.seasonalSources()).hasSize(1);
        assertThat(result.seasonalNotice()).doesNotContain("not available");
    }
    @Test void regionalEvidenceRanksBeforeThemeFillAndEverySectionUsesTwoBoundedPools() {
        var provider=mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(PlaceExplorePolicyTests.candidate("park","JP","park"),PlaceExplorePolicyTests.candidate("museum","JP","museum")),null));
        when(provider.withPhoto(any())).thenAnswer(call->call.getArgument(0));
        var r=new PlaceExploreRequest("JP","",List.of(),List.of(),"month:7","popular","");
        var result=new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4)).explore(r,"");
        assertThat(result.items()).extracting(i->i.place().providerPlaceId()).containsExactly("museum","park");
        assertThat(result.seasonalNotice()).contains("regional guidance");
        verify(provider,times(2)).exploreSearch(anyString(),eq(""),eq("JP"),eq(""));
    }
    @Test void fallbackContinuationPreservesTheExactProviderQuery() {
        var provider=mock(GooglePlacesAdapter.class);
        var geographic=PlaceExplorePolicyTests.candidate("geo","JP","locality");
        when(provider.exploreSearch(eq("Osaka"),eq(""),eq("JP"),eq(""))).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(geographic),null));
        when(provider.exploreSearch(eq("Osaka in Japan"),eq(""),eq("JP"),eq(""))).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(geographic),null));
        when(provider.exploreSearch(contains("places to visit"),eq(""),eq("JP"),eq(""))).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(),"fallback-next"));
        when(provider.exploreSearch(contains("places to visit"),eq(""),eq("JP"),eq("fallback-next"))).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(),null));
        var service=new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4));
        var r=new PlaceExploreRequest("JP","Osaka",List.of(),List.of(),"any","","");
        var page=service.explore(r,""); service.explore(r,page.cursor());
        verify(provider).exploreSearch("places to visit in Osaka in Japan","","JP","fallback-next");
    }
    @Test void exactNonGeographicPlacesDoNotTriggerAReplacementSearch() {
        var provider=mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(PlaceExplorePolicyTests.candidate("temple","JP","shinto_shrine")),null));
        when(provider.withPhoto(any())).thenAnswer(call->call.getArgument(0));
        var result=new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4)).explore(new PlaceExploreRequest("JP","Meiji Jingu",List.of(),List.of(),"any","",""),"");
        assertThat(result.items()).hasSize(1); verify(provider,times(1)).exploreSearch(anyString(),anyString(),anyString(),anyString());
    }
    @Test void typesInterleaveByProviderPositionRatherThanAlphabeticalPageOrder() {
        var provider=mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),eq("cafe"),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(PlaceExplorePolicyTests.candidate("c1","JP","cafe"),PlaceExplorePolicyTests.candidate("c2","JP","cafe")),null));
        when(provider.exploreSearch(anyString(),eq("restaurant"),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(PlaceExplorePolicyTests.candidate("r1","JP","restaurant"),PlaceExplorePolicyTests.candidate("r2","JP","restaurant")),null));
        when(provider.withPhoto(any())).thenAnswer(call->call.getArgument(0));
        var result=new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4)).explore(request("JP",List.of("cafe","restaurant")),"");
        assertThat(result.items()).extracting(i->i.place().providerPlaceId()).containsExactly("c1","r1","c2","r2");
    }
    private PlaceExploreRequest request(String country, List<String> types) { return new PlaceExploreRequest(country,"Osaka sushi",List.of(),types,"any","",""); }
    @Test void scopesBeforeRecommendationsAndLoadsPhotosForVisibleMatchesOnly() {
        var provider = mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(
                PlaceExplorePolicyTests.candidate("jp","JP","restaurant"),PlaceExplorePolicyTests.candidate("th","TH","restaurant")),null));
        when(provider.withPhoto(any())).thenAnswer(call -> call.getArgument(0));
        var service = new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4));
        var result = service.explore(request("JP",List.of("restaurant")),"");
        assertThat(result.items()).extracting(i -> i.place().providerPlaceId()).containsExactly("jp");
        assertThat(result.recommendationIds()).containsExactly("jp"); verify(provider,times(1)).withPhoto(any());
    }
    @Test void sparsePagesRemainPageableCursorBindsQueryAndFanoutIsAtMostTwo() {
        var provider = mock(GooglePlacesAdapter.class);
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(),"next"));
        var service = new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4));
        var result = service.explore(request("JP",List.of("restaurant","cafe","bakery")),"");
        assertThat(result.items()).isEmpty(); assertThat(result.cursor()).isNotBlank();
        verify(provider,times(2)).exploreSearch(anyString(),anyString(),anyString(),anyString());
        assertThatThrownBy(() -> service.explore(request("TH",List.of("restaurant")),result.cursor())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.explore(request("JP",List.of()),"missing")).isInstanceOf(InvalidRequestException.class);
    }
    @Test void continuationDeduplicatesPreviouslyReturnedPlaceIds() {
        var provider = mock(GooglePlacesAdapter.class); var p = PlaceExplorePolicyTests.candidate("jp","JP","restaurant");
        when(provider.exploreSearch(anyString(),anyString(),anyString(),anyString())).thenReturn(new GooglePlacesAdapter.ExplorePage(List.of(p),"next"));
        when(provider.withPhoto(any())).thenAnswer(call -> call.getArgument(0));
        var service = new PlaceExploreService(provider,new DiscoveryRequestLimiter(60,4)); var r=request("JP",List.of());
        var first=service.explore(r,""); var next=service.explore(r,first.cursor());
        assertThat(first.items()).hasSize(1); assertThat(next.items()).isEmpty(); assertThat(next.cursor()).isNotNull();
        assertThat(next.recommendationIds()).isEmpty();
    }
}
