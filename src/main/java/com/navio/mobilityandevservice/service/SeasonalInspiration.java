package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.domain.place.*;
import java.util.*;

/** Navio's editorial category preferences. These are not weather or seasonal-availability facts. */
final class SeasonalInspiration {
    static final String CALENDAR_SOURCE = "https://www.ncei.noaa.gov/news/meteorological-versus-astronomical-seasons";
    private static final Set<String> SOUTH = Set.of("AU","NZ","ZA","AR","CL","UY","LS","SZ");
    private static final Set<String> NORTH = Set.of("US","CA","GB","IE","FR","DE","ES","PT","IT","NL","BE","LU","CH","AT","DK","NO","SE","FI","IS","PL","CZ","SK","HU","RO","BG","GR","UA","HR","RS","SI","BA","ME","AL","MK","EE","LV","LT","JP","KR","CN","MN","RU","BY","MD","TR");
    // Order: popular, hidden gems, indoor, scenic, food, things to do, seasonal.
    private static final Map<String,List<String>> QUERIES = Map.of(
        "spring",List.of("botanical gardens","gardens","art galleries","botanical gardens","bakeries","gardens","botanical gardens"),
        "summer",List.of("parks","gardens","aquariums","beaches","ice cream shops","amusement parks","parks"),
        "autumn",List.of("historic places","local museums","museums","gardens","cafes","historic places","gardens"),
        "winter",List.of("museums","art galleries","museums","observation decks","cafes","museums","museums"),
        "wet",List.of("museums","art galleries","aquariums","observation decks","cafes","aquariums","museums"),
        "dry",List.of("parks","gardens","art galleries","national parks","restaurants","tourist attractions","parks"));
    static String themeForMonth(int month,double latitude) {
        // A calendar approximation is not a regional climate inference. No invented tropical wet/dry seasons.
        if (Math.abs(latitude)<23.5) return "all-year";
        int quarter=(month%12)/3; // Dec-Feb winter; Mar-May spring; Jun-Aug summer; Sep-Nov autumn.
        if(latitude<0) quarter=(quarter+2)%4;
        return List.of("winter","spring","summer","autumn").get(quarter);
    }
    static String requestTheme(PlaceExploreRequest r) {
        if(r.time().startsWith("season:")) return r.time().substring(7);
        if(!r.time().startsWith("month:")) return "all-year";
        int month=Integer.parseInt(r.time().substring(6));
        if(NORTH.contains(r.country())) return themeForMonth(month,40);
        if(SOUTH.contains(r.country())) return themeForMonth(month,-40);
        return "all-year";
    }
    static int sectionIndex(String collection) {return switch(collection) {
        case "hidden-gems"->1;case "indoor"->2;case "scenic"->3;case "food"->4;case "things-to-do"->5;case "seasonal"->6;default->0;
    };}
    static String query(PlaceExploreRequest r) {
        if(r.results()||r.time().equals("any"))return null;
        String theme=requestTheme(r);
        if(!QUERIES.containsKey(theme))return null;
        return QUERIES.get(theme).get(sectionIndex(r.collection()));
    }
    private static Set<String> evidence(String theme,String section) {
        if(section.equals("indoor")) return switch(theme) {
            case "summer","wet" -> Set.of("aquarium"); case "spring","dry"->Set.of("art_gallery");default->Set.of("museum","art_gallery");
        };
        if(section.equals("food"))return switch(theme) {
            case "summer" -> Set.of("ice_cream_shop","cafe","coffee_shop");
            case "spring" -> Set.of("bakery","cafe");default->Set.of("cafe","coffee_shop","bakery","restaurant");
        };
        if(section.equals("scenic"))return switch(theme) {
            case "winter","wet"->Set.of("observation_deck");case "summer"->Set.of("beach","park","national_park");default->Set.of("botanical_garden","garden","park","national_park");
        };
        return switch(theme) {
            case "summer" -> Set.of("park","beach","national_park","botanical_garden","aquarium","amusement_park","ice_cream_shop");
            case "spring" -> Set.of("botanical_garden","garden","park","art_gallery","bakery");
            case "autumn" -> Set.of("park","garden","botanical_garden","historical_place","museum","cafe");
            case "winter","wet" -> Set.of("museum","art_gallery","aquarium","observation_deck","cafe","coffee_shop","bakery");
            case "dry" -> Set.of("park","national_park","garden","botanical_garden","tourist_attraction");
            default -> Set.of("museum","art_gallery","aquarium","cafe","coffee_shop","bakery");
        };
    }
    static PlaceExplorePolicy.Seasonal evaluate(PlaceExploreCandidate item,String time,String section) {
        if(time.equals("any"))return null;
        var location=item.candidate().place().location();
        String theme=time.startsWith("season:")?time.substring(7):location==null?"all-year":themeForMonth(Integer.parseInt(time.substring(6)),location.lat());
        boolean suitable=!Collections.disjoint(item.types(),evidence(theme,section));
        boolean calendar=time.startsWith("month:")&&!theme.equals("all-year");
        return new PlaceExplorePolicy.Seasonal(suitable,calendar?CALENDAR_SOURCE:null,calendar?"SEASON_CALENDAR":time.startsWith("month:")?"ALL_YEAR_FALLBACK":"NAVIO_THEME");
    }
}
