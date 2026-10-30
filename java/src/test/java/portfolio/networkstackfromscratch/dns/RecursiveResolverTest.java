package portfolio.networkstackfromscratch.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Iterative resolution against the simulated root, TLD and authoritative servers. */
class RecursiveResolverTest {
    private SimNet net;
    private AtomicLong clock;
    private DnsCache cache;
    private RecursiveResolver resolver;

    @BeforeEach
    void setUp() {
        net = SimNet.standard();
        clock = new AtomicLong(1_000_000);
        cache = new DnsCache(1_000, clock::get);
        resolver = new RecursiveResolver(net, cache, SimNet.roots(),
                RecursiveResolver.Options.defaults().withTimeout(Duration.ofMillis(50)), new Random(7));
    }

    /** Address strings of a result. */
    private static List<String> ips(RecursiveResolver.Result r) {
        return r.addresses().stream().map(a -> a.getHostAddress()).toList();
    }

    @Test
    void walksRootThenTldThenAuthoritative() {
        RecursiveResolver.Result r = resolver.resolve("www.example.com", RecordType.A);
        assertEquals(Header.NOERROR, r.rcode());
        assertEquals(List.of("93.184.216.34"), ips(r));
        assertEquals(3, r.queries());
        assertEquals(1, net.queriesTo("198.41.0.4"));
        assertEquals(1, net.queriesTo("192.5.6.30"));
        assertEquals(1, net.queriesTo("192.0.2.53"));
        assertTrue(r.trace().get(0).contains("referral to com."), r.trace().toString());
        assertTrue(r.trace().get(1).contains("referral to example.com."), r.trace().toString());
    }

    @Test
    void secondLookupIsServedFromCache() {
        resolver.resolve("www.example.com", RecordType.A);
        int before = net.total();
        RecursiveResolver.Result r = resolver.resolve("www.example.com", RecordType.A);
        assertEquals(before, net.total());
        assertEquals(0, r.queries());
        assertEquals(List.of("93.184.216.34"), ips(r));
    }

    @Test
    void cachedTtlCountsDownAndExpiryRestartsAtTheClosestKnownZone() {
        resolver.resolve("www.example.com", RecordType.A);
        clock.addAndGet(100_000);
        RecursiveResolver.Result aged = resolver.resolve("www.example.com", RecordType.A);
        assertEquals(200, aged.answers().get(0).ttl());
        clock.addAndGet(201_000);
        RecursiveResolver.Result again = resolver.resolve("www.example.com", RecordType.A);
        // The A record expired (TTL 300) but the example.com NS and glue (172800) did not: one query.
        assertEquals(1, again.queries());
        assertEquals(2, net.queriesTo("192.0.2.53"));
        assertEquals(1, net.queriesTo("198.41.0.4"));
    }

    @Test
    void siblingNameReusesCachedDelegation() {
        resolver.resolve("www.example.com", RecordType.A);
        RecursiveResolver.Result r = resolver.resolve("txt.example.com", RecordType.TXT);
        assertEquals(1, r.queries());
        assertEquals("\"v=spf1 -all\"", r.answers().get(0).data().toString());
    }

    @Test
    void followsCnameInsideTheZone() {
        RecursiveResolver.Result r = resolver.resolve("cdn.example.com", RecordType.A);
        assertEquals(Header.NOERROR, r.rcode());
        assertEquals(RecordType.CNAME, r.answers().get(0).type());
        assertEquals(List.of("93.184.216.34"), ips(r));
    }

    @Test
    void followsCnameIntoAnotherZoneWithGluelessDelegation() {
        RecursiveResolver.Result r = resolver.resolve("out.example.com", RecordType.A);
        assertEquals(Header.NOERROR, r.rcode(), r.trace().toString());
        assertEquals(List.of("203.0.113.7"), ips(r));
        assertTrue(r.trace().stream().anyMatch(l -> l.contains("glueless delegation: resolving ns.dns-host.net.")),
                r.trace().toString());
        assertEquals(1, net.queriesTo("192.0.2.90"));
        assertEquals(1, net.queriesTo("192.0.2.91"));
    }

    @Test
    void nxdomainIsCachedNegativelyForTheSoaMinimum() {
        RecursiveResolver.Result r = resolver.resolve("nope.example.com", RecordType.A);
        assertEquals(Header.NXDOMAIN, r.rcode());
        int before = net.total();
        assertEquals(Header.NXDOMAIN, resolver.resolve("nope.example.com", RecordType.A).rcode());
        assertEquals(before, net.total());
        clock.addAndGet(301_000);
        resolver.resolve("nope.example.com", RecordType.A);
        assertEquals(before + 1, net.total());
    }

    @Test
    void nodataReturnsNoerrorWithEmptyAnswer() {
        RecursiveResolver.Result r = resolver.resolve("txt.example.com", RecordType.A);
        assertEquals(Header.NOERROR, r.rcode());
        assertTrue(r.answers().isEmpty());
        assertTrue(cache.get("txt.example.com", RecordType.A).orElseThrow().negative());
    }

    @Test
    void emptyNonTerminalIsNodataNotNxdomain() {
        assertEquals(Header.NOERROR, resolver.resolve("deep.example.com", RecordType.A).rcode());
        assertEquals(List.of("192.0.2.200"), ips(resolver.resolve("a.b.deep.example.com", RecordType.A)));
    }

    @Test
    void otherTypesDecode() {
        RecursiveResolver.Result mx = resolver.resolve("example.com", RecordType.MX);
        assertEquals("10 mail.example.com.", mx.answers().get(0).data().toString());
        RecursiveResolver.Result aaaa = resolver.resolve("www.example.com", RecordType.AAAA);
        assertEquals("2001:db8:0:0:0:0:0:34", ips(aaaa).get(0));
    }

    @Test
    void unreachableServerFallsBackToTheNextOne() {
        RecursiveResolver.Result r = resolver.resolve("www.lame.com", RecordType.A);
        assertEquals(List.of("203.0.113.9"), ips(r), r.trace().toString());
        assertTrue(net.queriesTo("192.0.2.71") >= 1);
    }

    @Test
    void cnameLoopEndsInServfail() {
        RecursiveResolver.Result r = resolver.resolve("loop1.example.com", RecordType.A);
        assertEquals(Header.SERVFAIL, r.rcode());
        assertTrue(r.trace().get(r.trace().size() - 1).contains("CNAME chain"), r.trace().toString());
    }

    @Test
    void outOfBailiwickRecordsAreIgnored() {
        RecursiveResolver.Result r = resolver.resolve("www.poison.com", RecordType.A);
        assertEquals(List.of("203.0.113.66"), ips(r));
        assertTrue(cache.get("a.gtld-servers.net", RecordType.A).map(e -> !e.records().get(0).data()
                .toString().equals("6.6.6.6")).orElse(true));
        assertEquals(List.of("93.184.216.34"), ips(resolver.resolve("www.example.com", RecordType.A)));
    }

    @Test
    void sidewaysReferralIsRejected() {
        RecursiveResolver.Result r = resolver.resolve("www.sideways.com", RecordType.A);
        assertEquals(Header.SERVFAIL, r.rcode());
        assertTrue(r.trace().stream().anyMatch(l -> l.contains("unusable")), r.trace().toString());
    }

    @Test
    void nothingReachableEndsInServfailWithinTheBudget() {
        RecursiveResolver dead = new RecursiveResolver(net, cache,
                List.of(new RecursiveResolver.ServerAddress("x.invalid", SimNet.ip("192.0.2.250"))),
                RecursiveResolver.Options.defaults().withTimeout(Duration.ofMillis(10)), new Random(1));
        RecursiveResolver.Result r = dead.resolve("www.example.com", RecordType.A);
        assertEquals(Header.SERVFAIL, r.rcode());
        assertEquals(1, r.queries());
    }

    @Test
    void queryBudgetStopsRunawayResolution() {
        RecursiveResolver tight = new RecursiveResolver(net, new DnsCache(100, clock::get), SimNet.roots(),
                new RecursiveResolver.Options(53, Duration.ofMillis(10), 2, 8, 8, true), new Random(1));
        RecursiveResolver.Result r = tight.resolve("www.example.com", RecordType.A);
        assertEquals(Header.SERVFAIL, r.rcode());
        assertTrue(r.trace().get(r.trace().size() - 1).contains("more than 2 queries"));
    }
}
