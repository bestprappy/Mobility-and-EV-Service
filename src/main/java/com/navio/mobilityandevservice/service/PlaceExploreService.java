package com.navio.mobilityandevservice.service;
import com.navio.mobilityandevservice.domain.place.*;
import com.navio.mobilityandevservice.exception.*;
import com.navio.mobilityandevservice.provider.google.GooglePlacesAdapter;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.Instant;

@Service
public class PlaceExploreService {
    private final GooglePlacesAdapter provider;
    private final DiscoveryRequestLimiter limiter;
    // Only opaque provider tokens, query parameters and place IDs, never cached provider content.
    private record Stream(String type, String token, String query, String country) {}
    private record Continuation(PlaceExploreRequest request, List<Stream> streams, Set<String> seen, Instant expires, int depth) {}
    private final Map<String,Continuation> cursors = new LinkedHashMap<>();
    public PlaceExploreService(GooglePlacesAdapter provider, DiscoveryRequestLimiter limiter) { this.provider=provider; this.limiter=limiter; }

    public PlaceExploreResponse explore(PlaceExploreRequest r, String cursor) {
        if (cursor!=null && cursor.length()>4096) throw new InvalidRequestException("Invalid continuation");
        boolean initial = cursor==null || cursor.isBlank();
        Continuation state = initial ? new Continuation(r,initialStreams(r),Set.of(),Instant.now().plusSeconds(600),0) : readCursor(r,cursor);
        var streams = new ArrayDeque<>(state.streams()); var seen = new HashSet<>(state.seen());
        List<PlaceExploreCandidate> pool = new ArrayList<>();
        List<List<PlaceExploreCandidate>> batches = new ArrayList<>();
        Set<String> firstPageIds = new HashSet<>();
        Map<String,Integer> relevance = new HashMap<>();
        try (var permit = limiter.acquire()) {
            for (int call=0;call<2 && !streams.isEmpty();call++) {
                var stream=streams.removeFirst();
                var page=provider.exploreSearch(stream.query(),stream.type(),stream.country(),stream.token());
                batches.add(page.items());
                if (stream.token().isEmpty()) page.items().forEach(p->firstPageIds.add(p.candidate().place().providerPlaceId()));
                if (page.nextPageToken()!=null && !page.nextPageToken().isBlank()) streams.addLast(new Stream(stream.type(),page.nextPageToken(),stream.query(),stream.country()));
                // A geography-only provider answer needs an explicit discovery query. This counts
                // against the same two-call budget, rather than triggering unbounded fallback.
                if (stream.token().isEmpty() && !page.items().isEmpty() && page.items().stream().allMatch(p ->
                        p.types().stream().anyMatch(Set.of("country","locality","administrative_area_level_1","administrative_area_level_2","political")::contains)) && !r.query().isEmpty() && call==0) {
                    var fallback = new PlaceExploreRequest(r.country(),"places to visit in "+r.query(),r.interests(),r.types(),r.time(),r.collection(),r.destination());
                    String fallbackQuery=PlaceExplorePolicy.searchText(fallback,stream.type());
                    var replacement=provider.exploreSearch(fallbackQuery,stream.type(),stream.country(),"");
                    batches.add(replacement.items()); call++;
                    replacement.items().forEach(p->firstPageIds.add(p.candidate().place().providerPlaceId()));
                    streams.removeIf(s->s.type().equals(stream.type())&&s.query().equals(stream.query()));
                    if (replacement.nextPageToken()!=null) streams.addLast(new Stream(stream.type(),replacement.nextPageToken(),fallbackQuery,stream.country()));
                }
            }
            // Interleave type streams at equal provider relevance positions.
            int longest=batches.stream().mapToInt(List::size).max().orElse(0);
            for (int position=0;position<longest;position++) for (var batch:batches) if(position<batch.size()) {
                var candidate=batch.get(position); pool.add(candidate);
                relevance.putIfAbsent(candidate.candidate().place().providerPlaceId(),position);
            }
            // Compute local review evidence only inside verified country scope.
            var scoped = pool.stream().filter(p -> r.country().isEmpty() || (p.candidate().place().placeLocation()!=null && r.country().equals(p.candidate().place().placeLocation().countryCode()))).toList();
            var gems = new HashSet<>(PlaceDiscoveryService.rank(scoped.stream().map(PlaceExploreCandidate::candidate).toList(),"hidden-gems").stream().map(p->p.place().providerPlaceId()).toList());
            var matching = pool.stream().filter(p->PlaceExplorePolicy.matches(p,r,gems))
                    .filter(p->!Collections.disjoint(p.types(),PlaceExploreRequest.TYPES) || p.candidate().place().primaryType()!=null && !Set.of("country","locality","administrative_area_level_1","administrative_area_level_2","political").contains(p.candidate().place().primaryType()))
                    .filter(p->seen.add(p.candidate().place().providerPlaceId())).toList();
            // Seasonal preference is gentle and only reorders discovery. Ordinary result relevance stays intact.
            if (!r.results() && !r.time().equals("any")) matching = matching.stream().sorted(Comparator.comparingInt(p->-PlaceExplorePolicy.seasonalPriority(p,r.time(),r.collection()))).toList();
            var qualityOrder=PlaceDiscoveryService.rank(matching.stream().map(PlaceExploreCandidate::candidate).toList(),"worth-a-visit").stream().map(p->p.place().providerPlaceId()).toList();
            var recommended = initial && r.results() ? matching.stream().filter(PlaceExplorePolicy::recommended)
                    .filter(p->firstPageIds.contains(p.candidate().place().providerPlaceId()))
                    .sorted(Comparator.<PlaceExploreCandidate>comparingInt(p->relevance.getOrDefault(p.candidate().place().providerPlaceId(),Integer.MAX_VALUE))
                            .thenComparingInt(p->-PlaceExplorePolicy.seasonalPriority(p,r.time(),r.collection()))
                            .thenComparingInt(p->qualityOrder.indexOf(p.candidate().place().providerPlaceId())))
                    .map(p->p.candidate().place().providerPlaceId()).limit(20).toList() : List.<String>of();
            var seasonEvidence = r.collection().equals("seasonal") ? scoped : matching;
            var preferences=seasonEvidence.stream().map(p->PlaceExplorePolicy.seasonal(p,r.time(),r.collection())).filter(Objects::nonNull).toList();
            var sources = preferences.stream().map(PlaceExplorePolicy.Seasonal::source).filter(Objects::nonNull).distinct().toList();
            long regional = preferences.stream().filter(p->p.basis().equals("REGIONAL_GUIDANCE")).count();
            boolean evergreen=preferences.stream().anyMatch(p->p.basis().equals("ALL_YEAR_FALLBACK"));
            String notice = r.time().equals("any") ? null : regional>0
                    ? "Reviewed regional guidance is prioritised; Navio's category-based seasonal ideas fill the remaining selection. Typical conditions, not a forecast."
                    : evergreen ? "Navio's seasonal ideas are supplemented with all-year options where the month's local climate is not verified."
                    : "Navio's seasonal inspiration uses place categories and local season calendars where applicable. These are suggestions, not verified weather or seasonal availability.";
            var items=matching.stream().map(p->provider.withPhoto(p.candidate())).map(p->new PlaceDiscoveryResponse.Item(p.place(),null,p.businessStatus(),p.photoAttributions(),p.photoGoogleMapsUri())).toList();
            String next = streams.isEmpty() || seen.size()>=1200 || state.depth()>=59 ? null : saveCursor(new Continuation(r,List.copyOf(streams),Set.copyOf(seen),state.expires(),state.depth()+1));
            return new PlaceExploreResponse(items,recommended,next,r.results()?"results":"discovery",notice,sources);
        } catch (ProviderClientException failure) { throw new ProviderUnavailableException("Places are temporarily unavailable",failure); }
    }
    private static List<Stream> initialStreams(PlaceExploreRequest r) {
        var types=r.types().isEmpty()?List.of(""):r.types();
        if (r.country().isEmpty() && r.query().isEmpty() && r.destination().isEmpty()) {
            var streams=new ArrayList<Stream>();
            var destinations=GlobalDiscoveryDestinations.forCollection(r.collection());
            // A diagonal schedule varies both destination and type on the first page, while
            // eventually visiting every combination once. City scope makes repeated countries distinct.
            for(int round=0;round<types.size();round++) for(int index=0;index<destinations.size();index++) {
                var destination=destinations.get(index);
                String type=types.get((index+round)%types.size());
                var scoped=new PlaceExploreRequest(destination.country(),"",r.interests(),List.of(),r.time(),r.collection(),destination.city());
                streams.add(new Stream(type,"",PlaceExplorePolicy.searchText(scoped,type),destination.country()));
            }
            return List.copyOf(streams);
        }
        if(!r.results()&&!r.time().equals("any")) {
            var base=new PlaceExploreRequest(r.country(),r.query(),r.interests(),r.types(),"any",r.collection(),r.destination());
            String ordinary=PlaceExplorePolicy.searchText(base,"");
            String themed=PlaceExplorePolicy.searchText(r,"");
            if(!ordinary.equals(themed)) return List.of(new Stream("","",ordinary,r.country()),new Stream("","",themed,r.country()));
        }
        return types.stream().map(t->new Stream(t,"",PlaceExplorePolicy.searchText(r,t),r.country())).toList();
    }
    private synchronized Continuation readCursor(PlaceExploreRequest r,String id) {
        var state=cursors.get(id);
        if (state==null || state.expires().isBefore(Instant.now()) || !state.request().equals(r)) throw new InvalidRequestException("This search has expired or changed. Start the search again.");
        return state;
    }
    private synchronized String saveCursor(Continuation state) {
        cursors.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
        while (cursors.size()>=256) cursors.remove(cursors.keySet().iterator().next());
        String id=UUID.randomUUID().toString(); cursors.put(id,state); return id;
    }
    public record Suggestions(List<PlaceExploreSuggestion> items, boolean providerAvailable) {}
    public Suggestions suggestions(String query,String country) {
        var r=new PlaceExploreRequest(country,query,List.of(),List.of(),"any","","");
        if (r.query().length()<2) return new Suggestions(List.of(),true);
        List<PlaceExploreSuggestion> items=new ArrayList<>();
        items.add(new PlaceExploreSuggestion("query","Search for “"+r.query()+"”",r.query(),null,null));
        boolean available=true;
        try (var permit=limiter.acquire()) { items.addAll(provider.exploreSuggestions(r.query(),r.country())); }
        catch (ProviderClientException failure) { available=false; }
        items.add(new PlaceExploreSuggestion("query","Restaurants · "+r.query(),"restaurants in "+r.query(),null,null));
        items.add(new PlaceExploreSuggestion("query","Scenic spots · "+r.query(),r.query(),null,"scenic"));
        items.add(new PlaceExploreSuggestion("query","Hidden gems · "+r.query(),r.query(),null,"hidden-gems"));
        return new Suggestions(List.copyOf(items),available);
    }
}
