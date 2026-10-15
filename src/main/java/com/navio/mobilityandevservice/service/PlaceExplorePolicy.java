package com.navio.mobilityandevservice.service;
import com.navio.mobilityandevservice.domain.place.*;
import java.util.*;

public final class PlaceExplorePolicy {
    private PlaceExplorePolicy() {}
    public static final Set<String> INDOOR = Set.of("museum","art_gallery","aquarium","shopping_mall");
    public static final Set<String> SCENIC = Set.of("park","botanical_garden","national_park","garden","observation_deck","scenic_spot","beach");
    public static final Set<String> FOOD = Set.of("restaurant","cafe","bakery","coffee_shop","food_court","ice_cream_shop");
    private static final Set<String> ACTIVITIES = Set.of("museum","art_gallery","aquarium","amusement_park","tourist_attraction","zoo","historical_place","garden","botanical_garden");
    public static boolean matches(PlaceExploreCandidate item, PlaceExploreRequest r, Set<String> gems) {
        var candidate = item.candidate(); var p = candidate.place();
        if ("CLOSED_PERMANENTLY".equals(candidate.businessStatus())) return false;
        if (!r.country().isEmpty() && (p.placeLocation() == null || !r.country().equals(p.placeLocation().countryCode()))) return false;
        if (!r.types().isEmpty() && Collections.disjoint(r.types(), item.types())) return false;
        var selected = r.results() ? r.interests() : r.collection().isEmpty() ? r.interests() : List.of(r.collection());
        return selected.isEmpty() || selected.stream().anyMatch(interest -> switch(interest) {
            case "hidden-gems" -> gems.contains(p.providerPlaceId());
            case "indoor" -> !Collections.disjoint(INDOOR,item.types());
            case "scenic" -> !Collections.disjoint(SCENIC,item.types()) || (p.description()!=null && p.description().toLowerCase(Locale.ROOT).matches(".*(scenic|panoramic|river views|sea views|ocean views|mountain views).*$"));
            case "food" -> !Collections.disjoint(FOOD,item.types());
            case "things-to-do" -> !Collections.disjoint(ACTIVITIES,item.types());
            case "seasonal" -> seasonal(item,r.time(),r.collection()) != null && seasonal(item,r.time(),r.collection()).suitable();
            default -> true;
        });
    }
    public static boolean recommended(PlaceExploreCandidate item) {
        var p = item.candidate().place();
        return !"CLOSED_PERMANENTLY".equals(item.candidate().businessStatus()) && p.rating()!=null && p.rating()>=4.5 && p.reviewCount()!=null && p.reviewCount()>=50;
    }
    public record Seasonal(boolean suitable, String source, String basis) {
        public Seasonal(boolean suitable,String source){this(suitable,source,"REGIONAL_GUIDANCE");}
    }
    public static Seasonal seasonal(PlaceExploreCandidate item, String time) {
        return seasonal(item,time,"");
    }
    public static Seasonal seasonal(PlaceExploreCandidate item,String time,String section) {
        var regional=regionalSeasonal(item,time);
        if(regional!=null&&regional.suitable())return regional;
        var fallback=SeasonalInspiration.evaluate(item,time,section);
        if(fallback!=null&&fallback.suitable())return fallback;
        return regional!=null?regional:fallback;
    }
    public static int seasonalPriority(PlaceExploreCandidate item,String time,String section) {
        var preference=seasonal(item,time,section);
        if(preference==null||!preference.suitable())return 0;
        return switch(preference.basis()){case "REGIONAL_GUIDANCE"->4;case "SEASON_CALENDAR"->3;case "NAVIO_THEME"->2;default->1;};
    }
    private static Seasonal regionalSeasonal(PlaceExploreCandidate item, String time) {
        if (time.equals("any")) return null;
        var p = item.candidate().place(); var location = p.location(); var address = p.placeLocation();
        if (address == null || location == null) return null;
        // Japan's calendar is sourced from JMA. Only temperate main-island coordinates participate;
        // it is a category preference, never a bloom/snow/date/forecast claim.
        if ("JP".equals(address.countryCode()) && location.lat()>=30 && location.lat()<=46 && location.lng()>=129 && location.lng()<=146) {
            if (time.equals("season:wet") || time.equals("season:dry")) return null;
            int month = time.startsWith("month:") ? Integer.parseInt(time.substring(6)) : switch(time) {
                case "season:spring" -> 4; case "season:summer" -> 7; case "season:autumn" -> 10; default -> 1;
            };
            boolean indoor = month==6 || month==7 || month==8 || month==12 || month<=2;
            return new Seasonal(!Collections.disjoint(indoor?INDOOR:SCENIC,item.types()),"https://ds.data.jma.go.jp/gmd/cpd/longfcst/en/tourist_japan.html");
        }
        if (!"TH".equals(address.countryCode())) return null;
        if (!time.startsWith("month:") && !time.equals("season:wet") && !time.equals("season:dry")) return null;
        String city = address.city() == null ? "" : address.city();
        int month = time.startsWith("month:") ? Integer.parseInt(time.substring(6)) : 9;
        var climate = PlaceDiscoveryService.seasonal(city,location.lat(),location.lng(),month);
        if (climate==null) return null;
        boolean indoor = time.equals("season:wet") || (!time.equals("season:dry") && climate.label().equals("Indoor discoveries"));
        return new Seasonal(!Collections.disjoint(indoor?INDOOR:SCENIC,item.types()),climate.sourceUrl());
    }
    public static String searchText(PlaceExploreRequest r, String type) {
        String query = r.query();
        if (query.isBlank()) query = type.isEmpty() ? SeasonalInspiration.query(r)!=null?SeasonalInspiration.query(r):switch(r.collection()) {
            case "indoor" -> "museums galleries aquariums"; case "scenic" -> "parks gardens scenic spots";
            case "food" -> "restaurants cafes"; case "things-to-do" -> "attractions activities";
            default -> "places to visit";
        } : type.replace('_',' ');
        else if (PlaceExploreRequest.COUNTRIES.stream().anyMatch(code -> queryCountryPrefix(r.query(),code))) query = "places to visit in " + query;
        if (!type.isEmpty() && !query.toLowerCase(Locale.ROOT).contains(type.replace('_',' '))) query += " " + type.replace('_',' ');
        if (!r.destination().isEmpty() && !r.results()) query += " in " + r.destination();
        if (!r.country().isEmpty()) query += " in " + r.countryName();
        return query;
    }
    private static boolean queryCountryPrefix(String query, String code) {
        String country = Locale.of("",code).getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT);
        String text = query.toLowerCase(Locale.ROOT);
        return (text.equals(country) || text.startsWith(country+" ")) && !text.matches(".*(restaurant|sushi|cafe|hotel|museum|park|temple|bakery|pizza|ramen|coffee).*");
    }
}
