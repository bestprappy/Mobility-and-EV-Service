package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.domain.place.PlaceExploreRequest;
import com.navio.mobilityandevservice.domain.place.PlaceProviderSearchResponse;
import com.navio.mobilityandevservice.exception.AuthenticationRequiredException;
import com.navio.mobilityandevservice.exception.FreeTierExhaustedException;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.exception.ProviderSearchDisabledException;
import com.navio.mobilityandevservice.exception.ProviderSearchLimitException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.google.GooglePlacesAdapter;
import com.navio.mobilityandevservice.provider.usage.GoogleFreeTierBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Places Release 2: the only paid Places path Explore can reach, and only from an explicit
 * user action. Every request makes at most one Google Text Search call (no fallback query,
 * no photo, no detail, no retry) and pages stop at {@code max-pages}. Guards, in order:
 * feature switch, signed-in user, input, per-instance rate/concurrency, per-user and global
 * daily allowances, then the monthly free-tier meter inside the adapter. The daily
 * allowances are per instance and in memory: defence in depth, not a billing boundary.
 * Google Cloud daily quotas remain the real ceiling.
 */
@Service
public class PlaceProviderSearchService {
    static final int PAGE_SIZE = 10;
    /** "&lt;page&gt;.&lt;Google page token&gt;"; stateless, so it works across instances and restarts. */
    private static final Pattern CURSOR = Pattern.compile("([2-9])\\.(\\S{1,2048})");

    private final GooglePlacesAdapter provider;
    private final boolean enabled;
    private final int maxPages;
    private final int dailyLimit;
    private final int perUserDailyLimit;
    private final DiscoveryRequestLimiter limiter;
    private final Clock clock;
    private final Map<UUID, Integer> usedByUser = new HashMap<>();
    private LocalDate day;
    private int usedToday;

    @Autowired
    public PlaceProviderSearchService(GooglePlacesAdapter provider,
            @Value("${navio.places.provider-search.enabled:false}") boolean enabled,
            @Value("${navio.places.provider-search.requests-per-minute:20}") int requestsPerMinute,
            @Value("${navio.places.provider-search.max-concurrent:2}") int maxConcurrent,
            @Value("${navio.places.provider-search.max-pages:3}") int maxPages,
            @Value("${navio.places.provider-search.daily-limit:30}") int dailyLimit,
            @Value("${navio.places.provider-search.per-user-daily-limit:10}") int perUserDailyLimit) {
        this(provider, enabled, maxPages, dailyLimit, perUserDailyLimit,
                new DiscoveryRequestLimiter(true, requestsPerMinute, maxConcurrent, Clock.systemUTC()), Clock.systemUTC());
    }

    PlaceProviderSearchService(GooglePlacesAdapter provider, boolean enabled, int maxPages, int dailyLimit,
                               int perUserDailyLimit, DiscoveryRequestLimiter limiter, Clock clock) {
        if (maxPages < 1 || maxPages > 6 || dailyLimit < 1 || perUserDailyLimit < 1) {
            throw new IllegalArgumentException("Provider search limits must be positive and max-pages at most 6");
        }
        this.provider = provider;
        this.enabled = enabled;
        this.maxPages = maxPages;
        this.dailyLimit = dailyLimit;
        this.perUserDailyLimit = perUserDailyLimit;
        this.limiter = limiter;
        this.clock = clock;
    }

    public PlaceProviderSearchResponse search(UUID userId, String rawQuery, String rawCountry, String rawCursor) {
        if (!enabled) throw new ProviderSearchDisabledException();
        if (userId == null) throw new AuthenticationRequiredException("Sign in to search beyond Navio's catalogue");
        String query = rawQuery == null ? "" : rawQuery.strip().replaceAll("\\s+", " ");
        String country = rawCountry == null ? "" : rawCountry.strip().toUpperCase(Locale.ROOT);
        if (query.length() < 2 || query.length() > 120) {
            throw new InvalidRequestException("Enter between 2 and 120 characters to search beyond Navio's catalogue");
        }
        if (!country.isEmpty() && !PlaceExploreRequest.COUNTRIES.contains(country)) {
            throw new InvalidRequestException("Choose a valid country");
        }
        int page = 1;
        String token = null;
        if (rawCursor != null && !rawCursor.isBlank()) {
            var match = CURSOR.matcher(rawCursor);
            if (!match.matches() || Integer.parseInt(match.group(1)) > maxPages) {
                throw new InvalidRequestException("This search has expired or changed. Start the search again.");
            }
            page = Integer.parseInt(match.group(1));
            token = match.group(2);
        }
        String textQuery = textQuery(query, country);
        try (var permit = limiter.acquire()) {
            consumeAllowance(userId);
            var result = provider.providerSearch(textQuery, country.isEmpty() ? null : country, PAGE_SIZE, token);
            var items = result.items().stream()
                    .filter(item -> country.isEmpty() || item.countryCode() == null || country.equals(item.countryCode()))
                    .toList();
            String nextToken = result.nextPageToken();
            String next = page < maxPages && nextToken != null && !nextToken.isBlank()
                    && nextToken.length() <= 2048 && nextToken.chars().noneMatch(Character::isWhitespace)
                    ? (page + 1) + "." + nextToken : null;
            return new PlaceProviderSearchResponse(items, next, page, maxPages, "GOOGLE");
        } catch (FreeTierExhaustedException exhausted) {
            throw new ProviderSearchLimitException(ProviderSearchLimitException.Scope.MONTH, exhausted);
        } catch (ProviderClientException failure) {
            throw new ProviderUnavailableException("Google search is temporarily unavailable", failure);
        }
    }

    static String textQuery(String query, String country) {
        if (country.isEmpty()) return query;
        String name = Locale.of("", country).getDisplayCountry(Locale.ENGLISH);
        return query.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)) ? query : query + " in " + name;
    }

    private synchronized void consumeAllowance(UUID userId) {
        LocalDate today = LocalDate.now(clock.withZone(GoogleFreeTierBudget.BILLING_ZONE));
        if (!today.equals(day)) {
            day = today;
            usedToday = 0;
            usedByUser.clear();
        }
        int mine = usedByUser.getOrDefault(userId, 0);
        if (mine >= perUserDailyLimit) throw new ProviderSearchLimitException(ProviderSearchLimitException.Scope.USER_DAY);
        if (usedToday >= dailyLimit) throw new ProviderSearchLimitException(ProviderSearchLimitException.Scope.DAY);
        usedToday++;
        usedByUser.put(userId, mine + 1);
    }
}
