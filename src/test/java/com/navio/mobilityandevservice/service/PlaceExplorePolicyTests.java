package com.navio.mobilityandevservice.service;
import com.navio.mobilityandevservice.domain.place.*;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlaceExplorePolicyTests {
    @Test void countryLockRequiresStructuredEvidenceAndTypeGroupsIntersectInterests() {
        var request = new PlaceExploreRequest("JP","sushi",List.of("scenic"),List.of("restaurant","cafe"),"any","", "");
        assertThat(PlaceExplorePolicy.matches(candidate("a","JP","restaurant"), request, Set.of())).isFalse();
        assertThat(PlaceExplorePolicy.matches(candidate("a","TH","park"), request, Set.of())).isFalse();
        assertThat(PlaceExplorePolicy.matches(candidate("a",null,"park"), request, Set.of())).isFalse();
        var food = new PlaceExploreRequest("JP","sushi",List.of("food","indoor"),List.of("restaurant","cafe"),"any","", "");
        assertThat(PlaceExplorePolicy.matches(candidate("a","JP","restaurant"), food, Set.of())).isTrue();
    }
    @Test void invalidScopeAndNonexclusiveTimeAreRejectedBeforeFetching() {
        assertThatThrownBy(() -> new PlaceExploreRequest("XX","",List.of(),List.of(),"any","", "")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new PlaceExploreRequest("JP","",List.of(),List.of(),"month:13","", "")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new PlaceExploreRequest("JP","",List.of("fake"),List.of(),"any","", "")).isInstanceOf(InvalidRequestException.class);
    }
    @Test void recommendationsRequireEvidenceAndExcludeClosedPlaces() {
        assertThat(PlaceExplorePolicy.recommended(candidate("a","JP","museum"))).isTrue();
        var missing = new PlaceExploreCandidate(new PlaceDiscoveryCandidate(new PlaceDetailResponse(PlaceProviderName.GOOGLE,"x","x",null,new PlaceLocationResponse(1,1,null,"x"),null,null,null,OpeningHoursResponse.empty(),null,null,null,null,null,"museum"),null,null,List.of()),List.of("museum"));
        assertThat(PlaceExplorePolicy.recommended(missing)).isFalse();
    }
    @Test void seasonDoesNotExcludeOrdinaryMatchesAndUnsupportedRegionsStayExplicit() {
        var request = new PlaceExploreRequest("AU","",List.of(),List.of(),"season:autumn","", "");
        assertThat(PlaceExplorePolicy.matches(candidate("a","AU","museum"),request,Set.of())).isTrue();
        assertThat(PlaceExplorePolicy.seasonal(candidate("a","AU","museum"),request.time()).basis()).isEqualTo("NAVIO_THEME");
        assertThat(PlaceExplorePolicy.seasonal(candidate("a","JP","park"),"season:autumn")).isNotNull();
        assertThat(PlaceExplorePolicy.seasonal(candidate("a","JP","park"),"season:wet").basis()).isEqualTo("NAVIO_THEME");
        assertThat(PlaceExplorePolicy.seasonal(candidate("a","JP","park"),"any")).isNull();
    }
    @Test void namedSeasonsAdaptCandidateSearchesForEveryDiscoverySection() {
        for (String section:List.of("popular","hidden-gems","indoor","scenic","food","things-to-do","seasonal")) {
            var summer=new PlaceExploreRequest("US","",List.of(),List.of(),"season:summer",section,"");
            var winter=new PlaceExploreRequest("US","",List.of(),List.of(),"season:winter",section,"");
            assertThat(PlaceExplorePolicy.searchText(summer,"")).as(section).isNotEqualTo(PlaceExplorePolicy.searchText(winter,""));
        }
        assertThat(PlaceExplorePolicy.searchText(new PlaceExploreRequest("US","",List.of(),List.of(),"season:summer","food",""),"")).contains("ice cream");
        assertThat(PlaceExplorePolicy.searchText(new PlaceExploreRequest("US","specific sushi",List.of(),List.of("restaurant"),"season:summer","",""),"restaurant")).contains("specific sushi").doesNotContain("ice cream");
    }
    @Test void calendarFallbackReversesHemispheresAndDoesNotInventTropicalRainSeasons() {
        assertThat(SeasonalInspiration.themeForMonth(7,40)).isEqualTo("summer");
        assertThat(SeasonalInspiration.themeForMonth(7,-34)).isEqualTo("winter");
        assertThat(SeasonalInspiration.themeForMonth(7,1.3)).isEqualTo("all-year");
    }
    @Test void seasonalFallbackHasCategoryEvidenceAndIsDistinctFromVerifiedGuidance() {
        var iceCream=candidate("ice","US","ice_cream_shop");
        var summer=PlaceExplorePolicy.seasonal(iceCream,"season:summer","food");
        assertThat(summer.suitable()).isTrue(); assertThat(summer.basis()).isEqualTo("NAVIO_THEME");
        assertThat(PlaceExplorePolicy.seasonal(candidate("museum","JP","museum"),"month:7","indoor").basis()).isEqualTo("REGIONAL_GUIDANCE");
        assertThat(PlaceExplorePolicy.seasonal(candidate("park","US","park"),"season:winter","scenic").suitable()).isFalse();
    }
    static PlaceExploreCandidate candidate(String id,String country,String type) {
        return new PlaceExploreCandidate(new PlaceDiscoveryCandidate(new PlaceDetailResponse(PlaceProviderName.GOOGLE,id,id,null,
                new PlaceLocationResponse(35.7,139.7,null,id),null,null,null,OpeningHoursResponse.empty(),type,null,4.7,100L,
                country == null ? null : new PlaceLocation("Tokyo","Tokyo",country,country),type),"OPERATIONAL",null,List.of()),List.of(type));
    }
}
