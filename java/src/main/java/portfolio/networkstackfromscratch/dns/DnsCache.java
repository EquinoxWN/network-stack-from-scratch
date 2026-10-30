package portfolio.networkstackfromscratch.dns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * TTL cache for RRsets and negative answers (RFC 2308), bounded in size with LRU eviction.
 * Records are returned with their remaining TTL, so a cached answer ages like the original.
 */
public final class DnsCache {
    /** Longest time any record is kept, whatever its TTL says (one day). */
    public static final long MAX_TTL_SECONDS = 86_400;
    /** Longest time a negative answer is kept (RFC 2308 7 suggests one to three hours). */
    public static final long MAX_NEGATIVE_TTL_SECONDS = 3 * 3_600;

    /** How much a cached RRset is trusted (RFC 2181 5.4.1, simplified). */
    public enum Trust {
        /** Address records from the additional section of a referral. */
        GLUE,
        /** NS records from the authority section of a referral. */
        REFERRAL,
        /** Data from the answer section of an authoritative response. */
        ANSWER
    }

    /** What a lookup found: records, or a negative answer (NXDOMAIN or NODATA). */
    public record Entry(List<ResourceRecord> records, boolean negative, int rcode, Trust trust) {
        /** Defensive copy. */
        public Entry {
            records = List.copyOf(records);
        }
    }

    private record Stored(List<ResourceRecord> records, boolean negative, int rcode, Trust trust,
                          long storedAtMillis, long expiresAtMillis) {}

    private final LongSupplier clockMillis;
    private final Map<String, Stored> map;
    private long hits;
    private long misses;

    /** Cache holding at most {@code maxEntries} RRsets, reading time from {@code clockMillis}. */
    public DnsCache(int maxEntries, LongSupplier clockMillis) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.clockMillis = clockMillis;
        this.map = new LinkedHashMap<>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Stored> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** Cache key for a name and type. */
    private static String key(String name, int type) {
        return name + "|" + type;
    }

    /** Store an RRset (all records share name and type); lower trust never replaces live higher trust. */
    public synchronized void put(List<ResourceRecord> rrset, Trust trust) {
        if (rrset.isEmpty()) {
            return;
        }
        ResourceRecord first = rrset.get(0);
        long ttl = MAX_TTL_SECONDS;
        for (ResourceRecord rr : rrset) {
            if (!rr.name().equals(first.name()) || rr.type() != first.type()) {
                throw new IllegalArgumentException("records of one RRset must share name and type");
            }
            ttl = Math.min(ttl, rr.ttl());
        }
        store(key(first.name(), first.type()), rrset, false, Header.NOERROR, trust, ttl);
    }

    /** Store a negative answer for a name and type, lasting min(SOA TTL, SOA minimum). */
    public synchronized void putNegative(String name, int type, int rcode, ResourceRecord soa) {
        long ttl = MAX_NEGATIVE_TTL_SECONDS;
        if (soa.data() instanceof RData.Soa s) {
            ttl = Math.min(ttl, Math.min(soa.ttl(), s.minimum()));
        }
        store(key(name, type), List.of(soa), true, rcode, Trust.ANSWER, ttl);
    }

    /** Insert unless a live entry with higher trust exists. */
    private void store(String key, List<ResourceRecord> records, boolean negative, int rcode, Trust trust,
                       long ttlSeconds) {
        long now = clockMillis.getAsLong();
        Stored old = map.get(key);
        if (old != null && old.expiresAtMillis > now && old.trust.compareTo(trust) > 0) {
            return;
        }
        if (ttlSeconds == 0) {
            map.remove(key);
            return;
        }
        map.put(key, new Stored(List.copyOf(records), negative, rcode, trust, now, now + ttlSeconds * 1000));
    }

    /** Live entry for a name and type, with TTLs counted down; expired entries are dropped. */
    public synchronized Optional<Entry> get(String name, int type) {
        String k = key(name, type);
        Stored s = map.get(k);
        long now = clockMillis.getAsLong();
        if (s == null || s.expiresAtMillis <= now) {
            if (s != null) {
                map.remove(k);
            }
            misses++;
            return Optional.empty();
        }
        hits++;
        long elapsed = (now - s.storedAtMillis) / 1000;
        List<ResourceRecord> aged = new ArrayList<>(s.records.size());
        for (ResourceRecord rr : s.records) {
            aged.add(rr.withTtl(Math.max(0, Math.min(rr.ttl(), MAX_TTL_SECONDS) - elapsed)));
        }
        return Optional.of(new Entry(aged, s.negative, s.rcode, s.trust));
    }

    /** Number of entries currently held (expired ones included until touched). */
    public synchronized int size() {
        return map.size();
    }

    /** Lookups answered from the cache. */
    public synchronized long hits() {
        return hits;
    }

    /** Lookups that found nothing live. */
    public synchronized long misses() {
        return misses;
    }

    /** Remove everything. */
    public synchronized void clear() {
        map.clear();
    }
}
