package portfolio.networkstackfromscratch.dns;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A simulated Internet for the resolver: root, TLD and authoritative servers keyed by IP address,
 * reached through the real wire codec. Unknown addresses time out, like an unreachable server.
 */
final class SimNet implements Transport {
    static final String ROOT = """
            @ 86400 SOA a.root-servers.net nstld.verisign-grs.com 2026102300 1800 900 604800 86400
            @ 518400 NS a.root-servers.net
            a.root-servers.net 518400 A 198.41.0.4
            com 172800 NS a.gtld-servers.net
            net 172800 NS a.gtld-servers.net
            a.gtld-servers.net 172800 A 192.5.6.30
            """;
    static final String COM = """
            @ 900 SOA a.gtld-servers.net nstld.verisign-grs.com 1 1800 900 604800 86400
            @ 172800 NS a.gtld-servers.net
            example.com 172800 NS ns1.example.com
            ns1.example.com 172800 A 192.0.2.53
            glueless.com 172800 NS ns.dns-host.net
            lame.com 172800 NS ns1.lame.com
            lame.com 172800 NS ns2.lame.com
            ns1.lame.com 172800 A 192.0.2.70
            ns2.lame.com 172800 A 192.0.2.71
            poison.com 172800 NS ns1.poison.com
            ns1.poison.com 172800 A 192.0.2.80
            sideways.com 172800 NS ns1.sideways.com
            ns1.sideways.com 172800 A 192.0.2.81
            """;
    static final String NET = """
            @ 900 SOA a.gtld-servers.net nstld.verisign-grs.com 1 1800 900 604800 86400
            @ 172800 NS a.gtld-servers.net
            dns-host.net 172800 NS ns1.dns-host.net
            ns1.dns-host.net 172800 A 192.0.2.90
            """;
    static final String DNS_HOST = """
            @ 3600 SOA ns1.dns-host.net admin.dns-host.net 1 7200 3600 1209600 300
            @ 3600 NS ns1.dns-host.net
            ns1.dns-host.net 3600 A 192.0.2.90
            ns.dns-host.net 3600 A 192.0.2.91
            """;
    static final String GLUELESS = """
            @ 3600 SOA ns.dns-host.net admin.dns-host.net 1 7200 3600 1209600 300
            @ 3600 NS ns.dns-host.net
            www.glueless.com 600 A 203.0.113.7
            """;
    static final String EXAMPLE = """
            @ 3600 SOA ns1.example.com hostmaster.example.com 1 7200 3600 1209600 300
            @ 3600 NS ns1.example.com
            @ 3600 MX 10 mail.example.com
            ns1.example.com 3600 A 192.0.2.53
            www.example.com 300 A 93.184.216.34
            www.example.com 300 AAAA 2001:db8::34
            cdn.example.com 120 CNAME www.example.com
            out.example.com 120 CNAME www.glueless.com
            loop1.example.com 60 CNAME loop2.example.com
            loop2.example.com 60 CNAME loop1.example.com
            txt.example.com 60 TXT "v=spf1 -all"
            a.b.deep.example.com 60 A 192.0.2.200
            """;
    static final String LAME = """
            @ 3600 SOA ns2.lame.com admin.lame.com 1 7200 3600 1209600 300
            @ 3600 NS ns1.lame.com
            @ 3600 NS ns2.lame.com
            www.lame.com 300 A 203.0.113.9
            """;
    static final String POISON = """
            @ 3600 SOA ns1.poison.com admin.poison.com 1 7200 3600 1209600 300
            @ 3600 NS ns1.poison.com
            www.poison.com 300 A 203.0.113.66
            """;

    private final Map<InetAddress, List<Zone>> servers = new HashMap<>();
    private final Map<InetAddress, UnaryOperator<Message>> overrides = new HashMap<>();
    private final Map<InetAddress, Integer> queries = new HashMap<>();
    private int total;

    /** The standard simulated hierarchy described by the zone texts above. */
    static SimNet standard() {
        SimNet net = new SimNet();
        net.serve("198.41.0.4", Zone.parse("", ROOT));
        net.serve("192.5.6.30", Zone.parse("com", COM));
        net.serve("192.5.6.30", Zone.parse("net", NET));
        net.serve("192.0.2.90", Zone.parse("dns-host.net", DNS_HOST));
        net.serve("192.0.2.91", Zone.parse("glueless.com", GLUELESS));
        net.serve("192.0.2.53", Zone.parse("example.com", EXAMPLE));
        net.serve("192.0.2.71", Zone.parse("lame.com", LAME));
        Zone poison = Zone.parse("poison.com", POISON);
        net.override("192.0.2.80", q -> {
            Message m = poison.answer(q);
            // Out-of-bailiwick data a poisoning server slips in: must be ignored by the resolver.
            ResourceRecord fakeWww = rr("www.example.com", RecordType.A, "6.6.6.6");
            ResourceRecord fakeTld = rr("a.gtld-servers.net", RecordType.A, "6.6.6.6");
            List<ResourceRecord> an = new ArrayList<>(m.answers());
            an.add(fakeWww);
            return new Message(m.header(), m.questions(), an,
                    List.of(new ResourceRecord("com", RecordType.NS, 1, 999999, new RData.Name("evil.poison.com"))),
                    List.of(fakeTld));
        });
        net.override("192.0.2.81", q -> {
            // Referral to an unrelated zone (not an ancestor of the question): must be rejected.
            Header h = new Header(q.header().id(), true, 0, false, false, false, false, Header.NOERROR);
            return new Message(h, q.questions(), List.of(),
                    List.of(new ResourceRecord("example.com", RecordType.NS, 1, 3600, new RData.Name("ns1.sideways.com"))),
                    List.of());
        });
        return net;
    }

    /** Root hints for the simulated network. */
    static List<RecursiveResolver.ServerAddress> roots() {
        return List.of(new RecursiveResolver.ServerAddress("a.root-servers.net", ip("198.41.0.4")));
    }

    /** Add a zone served at an address (one address may serve several zones). */
    void serve(String ip, Zone zone) {
        servers.computeIfAbsent(ip(ip), k -> new ArrayList<>()).add(zone);
    }

    /** Replace the server at an address with a custom handler. */
    void override(String ip, UnaryOperator<Message> handler) {
        overrides.put(ip(ip), handler);
    }

    /** Answer from the deepest zone the server holds for the question. */
    Message answer(InetAddress server, Message query) {
        UnaryOperator<Message> o = overrides.get(server);
        if (o != null) {
            return o.apply(query);
        }
        Zone best = null;
        for (Zone z : servers.get(server)) {
            if (DnsName.isWithin(query.question().name(), z.origin())
                    && (best == null || DnsName.labelCount(z.origin()) > DnsName.labelCount(best.origin()))) {
                best = z;
            }
        }
        return best != null ? best.answer(query) : servers.get(server).get(0).answer(query);
    }

    /** True when something answers at this address. */
    boolean reachable(InetAddress a) {
        return servers.containsKey(a) || overrides.containsKey(a);
    }

    @Override
    public byte[] exchange(InetSocketAddress server, byte[] query, Duration timeout) throws IOException {
        InetAddress a = server.getAddress();
        total++;
        queries.merge(a, 1, Integer::sum);
        if (!reachable(a)) {
            throw new SocketTimeoutException("simulated timeout from " + a.getHostAddress());
        }
        return DnsCodec.encode(answer(a, DnsCodec.decode(query)));
    }

    /** Queries sent to one address. */
    int queriesTo(String ip) {
        return queries.getOrDefault(ip(ip), 0);
    }

    /** Queries sent in total. */
    int total() {
        return total;
    }

    /** Address literal. */
    static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** An address record. */
    static ResourceRecord rr(String name, int type, String ip) {
        return new ResourceRecord(name, type, RecordType.CLASS_IN, 300, new RData.Address(ip(ip)));
    }
}
