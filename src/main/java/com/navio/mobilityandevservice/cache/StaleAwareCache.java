package com.navio.mobilityandevservice.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class StaleAwareCache<T> {

    private final Cache<String, CacheEntry<T>> entries;
    private final Duration freshTtl;
    private final Clock clock;

    public StaleAwareCache(
            Duration freshTtl,
            Duration staleTtl,
            long maximumSize,
            Clock clock
    ) {
        if (freshTtl.isZero() || freshTtl.isNegative()) {
            throw new IllegalArgumentException("freshTtl must be positive");
        }
        if (staleTtl.compareTo(freshTtl) <= 0) {
            throw new IllegalArgumentException("staleTtl must exceed freshTtl");
        }

        this.freshTtl = freshTtl;
        this.clock = clock;
        this.entries = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(staleTtl)
                .build();
    }

    public Optional<T> getFresh(String key) {
        CacheEntry<T> entry = entries.getIfPresent(key);
        if (entry == null || !entry.freshUntil().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    public Optional<T> getStale(String key) {
        return Optional.ofNullable(entries.getIfPresent(key)).map(CacheEntry::value);
    }

    public void put(String key, T value) {
        entries.put(key, new CacheEntry<>(value, clock.instant().plus(freshTtl)));
    }

    private record CacheEntry<T>(T value, Instant freshUntil) {
    }
}
