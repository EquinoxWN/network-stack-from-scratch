package portfolio.networkstackfromscratch.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** TTL expiry, negative caching, trust ranking and the size bound. */
class DnsCacheTest {
    private final AtomicLong clock = new AtomicLong(0);
    private final DnsCache cache = new DnsCache(3, clock::get);

    /** A record with a TTL. */
    private static ResourceRecord a(String name, String ip, long ttl) {
        return SimNet.rr(name, RecordType.A, ip).withTtl(ttl);
    }

    @Test
    void ttlCountsDownThenExpires() {
        cache.put(List.of(a("x", "192.0.2.1", 10)), DnsCache.Trust.ANSWER);
        clock.set(4_000);
        assertEquals(6, cache.get("x", RecordType.A).orElseThrow().records().get(0).ttl());
        clock.set(10_000);
        assertTrue(cache.get("x", RecordType.A).isEmpty());
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void rrsetLivesAsLongAsItsShortestTtl() {
        cache.put(List.of(a("x", "192.0.2.1", 100), a("x", "192.0.2.2", 5)), DnsCache.Trust.ANSWER);
        clock.set(5_000);
        assertTrue(cache.get("x", RecordType.A).isEmpty());
    }

    @Test
    void hugeTtlIsCappedAtOneDay() {
        cache.put(List.of(a("x", "192.0.2.1", 0xFFFFFFFFL)), DnsCache.Trust.ANSWER);
        assertEquals(DnsCache.MAX_TTL_SECONDS, cache.get("x", RecordType.A).orElseThrow().records().get(0).ttl());
        clock.set(DnsCache.MAX_TTL_SECONDS * 1000);
        assertTrue(cache.get("x", RecordType.A).isEmpty());
    }

    @Test
    void zeroTtlIsNotCached() {
        cache.put(List.of(a("x", "192.0.2.1", 0)), DnsCache.Trust.ANSWER);
        assertTrue(cache.get("x", RecordType.A).isEmpty());
    }

    @Test
    void negativeEntryUsesSoaMinimum() {
        ResourceRecord soa = new ResourceRecord("example", RecordType.SOA, 1, 3600,
                new RData.Soa("ns.example", "h.example", 1, 2, 3, 4, 60));
        cache.putNegative("nope.example", RecordType.A, Header.NXDOMAIN, soa);
        DnsCache.Entry e = cache.get("nope.example", RecordType.A).orElseThrow();
        assertTrue(e.negative());
        assertEquals(Header.NXDOMAIN, e.rcode());
        clock.set(60_000);
        assertTrue(cache.get("nope.example", RecordType.A).isEmpty());
    }

    @Test
    void glueDoesNotOverwriteAnAuthoritativeAnswer() {
        cache.put(List.of(a("ns", "192.0.2.1", 100)), DnsCache.Trust.ANSWER);
        cache.put(List.of(a("ns", "6.6.6.6", 100)), DnsCache.Trust.GLUE);
        assertEquals("192.0.2.1", cache.get("ns", RecordType.A).orElseThrow().records().get(0).data().toString());
        cache.put(List.of(a("ns", "192.0.2.9", 100)), DnsCache.Trust.ANSWER);
        assertEquals("192.0.2.9", cache.get("ns", RecordType.A).orElseThrow().records().get(0).data().toString());
    }

    @Test
    void leastRecentlyUsedEntryIsEvicted() {
        cache.put(List.of(a("a", "192.0.2.1", 100)), DnsCache.Trust.ANSWER);
        cache.put(List.of(a("b", "192.0.2.2", 100)), DnsCache.Trust.ANSWER);
        cache.put(List.of(a("c", "192.0.2.3", 100)), DnsCache.Trust.ANSWER);
        cache.get("a", RecordType.A);
        cache.put(List.of(a("d", "192.0.2.4", 100)), DnsCache.Trust.ANSWER);
        assertEquals(3, cache.size());
        assertTrue(cache.get("b", RecordType.A).isEmpty());
        assertTrue(cache.get("a", RecordType.A).isPresent());
    }

    @Test
    void mixedRrsetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> cache.put(List.of(a("a", "192.0.2.1", 1), a("b", "192.0.2.2", 1)), DnsCache.Trust.ANSWER));
    }

    @Test
    void zeroTtlRemovesAnExistingEntry() {
        cache.put(List.of(a("x", "192.0.2.1", 10)), DnsCache.Trust.ANSWER);
        cache.put(List.of(a("x", "192.0.2.2", 0)), DnsCache.Trust.ANSWER);
        assertEquals(0, cache.size());
        assertTrue(cache.get("x", RecordType.A).isEmpty());
    }
}
