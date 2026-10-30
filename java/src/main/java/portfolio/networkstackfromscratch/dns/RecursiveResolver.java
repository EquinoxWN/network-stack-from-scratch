package portfolio.networkstackfromscratch.dns;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Iterative resolution from the root: ask a root server, follow referrals to the TLD and then the
 * authoritative servers, chase CNAMEs and glueless delegations, and cache everything by TTL.
 * Only data inside the zone of the server that sent it (its bailiwick) is believed.
 */
public final class RecursiveResolver {
    /** One root server from the IANA root hints file (named.root), IPv4 only. */
    public record ServerAddress(String name, InetAddress address) {}

    /** The 13 root servers (IPv4) from https://www.internic.net/domain/named.root. */
    public static final List<ServerAddress> IANA_ROOTS = List.of(
            root("a", "198.41.0.4"), root("b", "170.247.170.2"), root("c", "192.33.4.12"),
            root("d", "199.7.91.13"), root("e", "192.203.230.10"), root("f", "192.5.5.241"),
            root("g", "192.112.36.4"), root("h", "198.97.190.53"), root("i", "192.36.148.17"),
            root("j", "192.58.128.30"), root("k", "193.0.14.129"), root("l", "199.7.83.42"),
            root("m", "202.12.27.33"));

    /** Limits and wire options for one resolver. */
    public record Options(int port, Duration timeout, int maxQueries, int maxDepth, int maxCnameHops, boolean edns) {
        /** Defaults: port 53, 2 s per query, 64 queries and depth 8 per resolution, 8 CNAME hops, EDNS0 on. */
        public static Options defaults() {
            return new Options(53, Duration.ofSeconds(2), 64, 8, 8, true);
        }

        /** Same options with another per-query timeout. */
        public Options withTimeout(Duration t) {
            return new Options(port, t, maxQueries, maxDepth, maxCnameHops, edns);
        }

        /** Same options with another server port. */
        public Options withPort(int p) {
            return new Options(p, timeout, maxQueries, maxDepth, maxCnameHops, edns);
        }
    }

    /** Outcome of one resolution: rcode, the answer chain (CNAMEs first) and a step-by-step trace. */
    public record Result(int rcode, List<ResourceRecord> answers, List<String> trace, int queries) {
        /** Defensive copies. */
        public Result {
            answers = List.copyOf(answers);
            trace = List.copyOf(trace);
        }

        /** Addresses (A and AAAA) in the answer. */
        public List<InetAddress> addresses() {
            List<InetAddress> out = new ArrayList<>();
            for (ResourceRecord rr : answers) {
                if (rr.data() instanceof RData.Address a) {
                    out.add(a.address());
                }
            }
            return out;
        }
    }

    private enum Kind { ANSWER, CNAME, NXDOMAIN, NODATA, REFERRAL, LAME }

    private record Outcome(Kind kind, List<ResourceRecord> records, String next, String zone,
                           List<ServerAddress> servers) {}

    private static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        /** Resolution cannot continue. */
        Failure(String message) {
            super(message);
        }
    }

    private static final class Context {
        private final List<String> trace = new ArrayList<>();
        private int queries;

        /** Record a trace line indented by depth. */
        void log(int depth, String line) {
            trace.add("  ".repeat(depth) + line);
        }
    }

    private final Transport transport;
    private final DnsCache cache;
    private final List<ServerAddress> roots;
    private final Options options;
    private final Random random;

    /** Resolver with the IANA roots, default options and a secure random source for ids. */
    public RecursiveResolver(Transport transport, DnsCache cache) {
        this(transport, cache, IANA_ROOTS, Options.defaults(), new SecureRandom());
    }

    /** Fully configured resolver; {@code random} picks message ids and server order. */
    public RecursiveResolver(Transport transport, DnsCache cache, List<ServerAddress> roots, Options options,
                             Random random) {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("at least one root server is needed");
        }
        this.transport = transport;
        this.cache = cache;
        this.roots = List.copyOf(roots);
        this.options = options;
        this.random = random;
    }

    /** Resolve {@code name} for {@code type}; failures come back as SERVFAIL with the reason in the trace. */
    public Result resolve(String name, int type) {
        String qname = DnsName.normalize(name);
        Context ctx = new Context();
        List<ResourceRecord> answers = new ArrayList<>();
        try {
            int rcode = lookup(qname, type, 0, ctx, answers);
            return new Result(rcode, answers, ctx.trace, ctx.queries);
        } catch (Failure f) {
            ctx.log(0, "SERVFAIL: " + f.getMessage());
            return new Result(Header.SERVFAIL, List.of(), ctx.trace, ctx.queries);
        }
    }

    /** Resolve with cache, CNAME chasing and iteration; appends the answer chain to {@code out}. */
    private int lookup(String qname, int qtype, int depth, Context ctx, List<ResourceRecord> out) throws Failure {
        if (depth > options.maxDepth()) {
            throw new Failure("resolution nested deeper than " + options.maxDepth());
        }
        String current = qname;
        for (int hop = 0; hop <= options.maxCnameHops(); hop++) {
            Optional<DnsCache.Entry> hit = cache.get(current, qtype);
            if (hit.isPresent()) {
                DnsCache.Entry e = hit.get();
                ctx.log(depth, "cache: " + new Question(current, qtype, RecordType.CLASS_IN) + " -> "
                        + (e.negative() ? Header.rcodeName(e.rcode()) + " (negative)" : e.records().size() + " record(s)"));
                if (e.negative()) {
                    return e.rcode();
                }
                out.addAll(e.records());
                return Header.NOERROR;
            }
            if (qtype != RecordType.CNAME) {
                Optional<DnsCache.Entry> alias = cache.get(current, RecordType.CNAME);
                if (alias.isPresent() && !alias.get().negative()
                        && alias.get().records().get(0).data() instanceof RData.Name n) {
                    ctx.log(depth, "cache: " + DnsName.display(current) + " CNAME " + DnsName.display(n.target()));
                    out.addAll(alias.get().records());
                    current = n.target();
                    continue;
                }
            }
            Outcome o = iterate(current, qtype, depth, ctx);
            out.addAll(o.records());
            switch (o.kind()) {
                case ANSWER, NODATA -> {
                    return Header.NOERROR;
                }
                case NXDOMAIN -> {
                    return Header.NXDOMAIN;
                }
                case CNAME -> current = o.next();
                default -> throw new Failure("unexpected outcome " + o.kind());
            }
        }
        throw new Failure("CNAME chain longer than " + options.maxCnameHops());
    }

    /** Walk referrals from the closest known zone until a final answer for one name. */
    private Outcome iterate(String qname, int qtype, int depth, Context ctx) throws Failure {
        String zone = "";
        List<ServerAddress> servers = roots;
        Optional<Outcome> known = closestKnownZone(qname);
        if (known.isPresent()) {
            zone = known.get().zone();
            servers = known.get().servers();
            ctx.log(depth, "cache: start at " + DnsName.display(zone) + " (" + servers.size() + " server(s))");
        }
        while (true) {
            Outcome o = askZone(zone, servers, qname, qtype, depth, ctx);
            if (o.kind() != Kind.REFERRAL) {
                return o;
            }
            zone = o.zone();
            servers = o.servers();
        }
    }

    /** Ask the servers of {@code zone} in turn until one gives a usable response. */
    private Outcome askZone(String zone, List<ServerAddress> servers, String qname, int qtype, int depth,
                            Context ctx) throws Failure {
        List<ServerAddress> order = new ArrayList<>(servers);
        Collections.shuffle(order, random);
        for (ServerAddress server : order) {
            Message response;
            long start = System.nanoTime();
            try {
                response = ask(server, qname, qtype, ctx);
            } catch (IOException | DnsFormatException e) {
                ctx.log(depth, describe(server) + " " + DnsName.display(qname) + " " + RecordType.name(qtype)
                        + ": failed (" + e.getMessage() + "), trying next server");
                continue;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            Outcome o = classify(zone, qname, qtype, response);
            ctx.log(depth, describe(server) + " " + DnsName.display(qname) + " " + RecordType.name(qtype) + ": "
                    + summary(o) + " (" + ms + " ms)");
            if (o.kind() == Kind.LAME) {
                continue;
            }
            if (o.kind() == Kind.REFERRAL && o.servers().isEmpty()) {
                return withAddresses(o, depth, ctx);
            }
            return o;
        }
        throw new Failure("no server for " + DnsName.display(zone) + " gave a usable answer");
    }

    /** Send one query and check that the reply belongs to it. */
    private Message ask(ServerAddress server, String qname, int qtype, Context ctx) throws IOException, Failure {
        if (++ctx.queries > options.maxQueries()) {
            throw new Failure("more than " + options.maxQueries() + " queries for one resolution");
        }
        int id = random.nextInt(0x10000);
        Question q = new Question(qname, qtype, RecordType.CLASS_IN);
        byte[] wire = DnsCodec.encode(Message.query(id, q, false, options.edns()));
        byte[] reply = transport.exchange(new InetSocketAddress(server.address(), options.port()), wire,
                options.timeout());
        Message m = DnsCodec.decode(reply);
        Header h = m.header();
        if (!h.response() || h.id() != id || h.opcode() != 0 || m.questions().size() != 1
                || !m.question().equals(q)) {
            throw new IOException("reply does not match the query");
        }
        return m;
    }

    /** Decide what a response means for the question, caching what is in bailiwick. */
    private Outcome classify(String zone, String qname, int qtype, Message m) {
        int rcode = m.header().rcode();
        if (rcode != Header.NOERROR && rcode != Header.NXDOMAIN) {
            return outcome(Kind.LAME, List.of(), Header.rcodeName(rcode));
        }
        // Answer section: follow the chain from qname through CNAMEs that stay inside the zone.
        List<ResourceRecord> chain = new ArrayList<>();
        String name = qname;
        for (int hop = 0; hop <= options.maxCnameHops(); hop++) {
            List<ResourceRecord> exact = select(m.answers(), name, qtype, zone);
            if (!exact.isEmpty()) {
                cache.put(exact, DnsCache.Trust.ANSWER);
                chain.addAll(exact);
                return new Outcome(Kind.ANSWER, chain, null, zone, List.of());
            }
            List<ResourceRecord> alias = qtype == RecordType.CNAME ? List.of()
                    : select(m.answers(), name, RecordType.CNAME, zone);
            if (alias.isEmpty() || !(alias.get(0).data() instanceof RData.Name target)) {
                break;
            }
            cache.put(alias.subList(0, 1), DnsCache.Trust.ANSWER);
            chain.add(alias.get(0));
            name = target.target();
        }
        if (!chain.isEmpty()) {
            return new Outcome(Kind.CNAME, chain, name, zone, List.of());
        }
        Optional<ResourceRecord> soa = m.authorities().stream()
                .filter(rr -> rr.type() == RecordType.SOA && DnsName.isWithin(rr.name(), zone)
                        && DnsName.isWithin(qname, rr.name()))
                .findFirst();
        if (rcode == Header.NXDOMAIN) {
            soa.ifPresent(s -> cache.putNegative(qname, qtype, Header.NXDOMAIN, s));
            return outcome(Kind.NXDOMAIN, List.of(), "");
        }
        Outcome referral = referral(zone, qname, m);
        if (referral != null) {
            return referral;
        }
        if (m.header().authoritative() || soa.isPresent()) {
            soa.ifPresent(s -> cache.putNegative(qname, qtype, Header.NOERROR, s));
            return outcome(Kind.NODATA, List.of(), "");
        }
        return outcome(Kind.LAME, List.of(), "no answer, no referral");
    }

    /** A referral to a child zone strictly below {@code zone} and above {@code qname}, or null. */
    private Outcome referral(String zone, String qname, Message m) {
        Map<String, List<ResourceRecord>> byOwner = new LinkedHashMap<>();
        for (ResourceRecord rr : m.authorities()) {
            if (rr.type() == RecordType.NS && rr.rclass() == RecordType.CLASS_IN && !rr.name().equals(zone)
                    && DnsName.isWithin(rr.name(), zone) && DnsName.isWithin(qname, rr.name())) {
                byOwner.computeIfAbsent(rr.name(), k -> new ArrayList<>()).add(rr);
            }
        }
        if (byOwner.isEmpty()) {
            return null;
        }
        // The deepest delegation wins if a server lists several.
        String child = byOwner.keySet().stream().max((a, b) -> DnsName.labelCount(a) - DnsName.labelCount(b))
                .orElseThrow();
        List<ResourceRecord> ns = byOwner.get(child);
        cache.put(ns, DnsCache.Trust.REFERRAL);
        Set<String> nsNames = new LinkedHashSet<>();
        for (ResourceRecord rr : ns) {
            if (rr.data() instanceof RData.Name n) {
                nsNames.add(n.target());
            }
        }
        List<ServerAddress> servers = new ArrayList<>();
        Map<String, List<ResourceRecord>> glue = new LinkedHashMap<>();
        for (ResourceRecord rr : m.additionals()) {
            // Glue is only believed for listed servers inside the referring zone's bailiwick.
            if (rr.type() == RecordType.A && nsNames.contains(rr.name()) && DnsName.isWithin(rr.name(), zone)
                    && rr.data() instanceof RData.Address a) {
                glue.computeIfAbsent(rr.name(), k -> new ArrayList<>()).add(rr);
                servers.add(new ServerAddress(rr.name(), a.address()));
            }
        }
        for (List<ResourceRecord> rrset : glue.values()) {
            cache.put(rrset, DnsCache.Trust.GLUE);
        }
        return new Outcome(Kind.REFERRAL, List.copyOf(ns), null, child, servers);
    }

    /** Resolve the addresses of a glueless referral's name servers (each a full resolution). */
    private Outcome withAddresses(Outcome referral, int depth, Context ctx) throws Failure {
        Set<String> nsNames = new LinkedHashSet<>();
        for (ResourceRecord rr : referral.records()) {
            if (rr.data() instanceof RData.Name n) {
                nsNames.add(n.target());
            }
        }
        for (String nsName : nsNames) {
            ctx.log(depth, "glueless delegation: resolving " + DnsName.display(nsName) + " A");
            List<ResourceRecord> found = new ArrayList<>();
            int rcode;
            try {
                rcode = lookup(nsName, RecordType.A, depth + 1, ctx, found);
            } catch (Failure f) {
                ctx.log(depth, "could not resolve " + DnsName.display(nsName) + ": " + f.getMessage());
                continue;
            }
            List<ServerAddress> servers = new ArrayList<>();
            for (ResourceRecord rr : found) {
                if (rr.type() == RecordType.A && rr.data() instanceof RData.Address a) {
                    servers.add(new ServerAddress(nsName, a.address()));
                }
            }
            if (rcode == Header.NOERROR && !servers.isEmpty()) {
                return new Outcome(Kind.REFERRAL, referral.records(), referral.next(), referral.zone(), servers);
            }
        }
        throw new Failure("no address found for any name server of " + DnsName.display(referral.zone()));
    }

    /** The deepest ancestor of {@code qname} whose NS set and server addresses are cached. */
    private Optional<Outcome> closestKnownZone(String qname) {
        String z = qname;
        while (!z.isEmpty()) {
            Optional<DnsCache.Entry> ns = cache.get(z, RecordType.NS);
            if (ns.isPresent() && !ns.get().negative()) {
                List<ServerAddress> servers = new ArrayList<>();
                for (ResourceRecord rr : ns.get().records()) {
                    if (rr.data() instanceof RData.Name n) {
                        cache.get(n.target(), RecordType.A).filter(e -> !e.negative()).ifPresent(e -> {
                            for (ResourceRecord a : e.records()) {
                                if (a.data() instanceof RData.Address addr && addr.address() instanceof Inet4Address) {
                                    servers.add(new ServerAddress(n.target(), addr.address()));
                                }
                            }
                        });
                    }
                }
                if (!servers.isEmpty()) {
                    return Optional.of(new Outcome(Kind.REFERRAL, List.of(), null, z, servers));
                }
            }
            z = DnsName.parent(z);
        }
        return Optional.empty();
    }

    /** Records of one name and type whose owner lies inside {@code zone}. */
    private static List<ResourceRecord> select(List<ResourceRecord> rrs, String name, int type, String zone) {
        List<ResourceRecord> out = new ArrayList<>();
        if (!DnsName.isWithin(name, zone)) {
            return out;
        }
        for (ResourceRecord rr : rrs) {
            if (rr.name().equals(name) && rr.type() == type && rr.rclass() == RecordType.CLASS_IN) {
                out.add(rr);
            }
        }
        return out;
    }

    /** Outcome without servers. */
    private static Outcome outcome(Kind kind, List<ResourceRecord> records, String note) {
        return new Outcome(kind, records, note, null, List.of());
    }

    /** One-line description of an outcome for the trace. */
    private static String summary(Outcome o) {
        return switch (o.kind()) {
            case ANSWER -> "answer, " + o.records().size() + " record(s)";
            case CNAME -> "CNAME to " + DnsName.display(o.next());
            case NXDOMAIN -> "NXDOMAIN";
            case NODATA -> "no data (NOERROR, empty answer)";
            case REFERRAL -> "referral to " + DnsName.display(o.zone()) + " [" + o.records().size() + " NS, "
                    + o.servers().size() + " glue]";
            case LAME -> "unusable (" + o.next() + ")";
        };
    }

    /** "name. (address)" for the trace. */
    private static String describe(ServerAddress s) {
        return "-> " + DnsName.display(s.name()) + " (" + s.address().getHostAddress() + ")";
    }

    /** Root hint entry. */
    private static ServerAddress root(String letter, String ip) {
        try {
            return new ServerAddress(letter + ".root-servers.net", InetAddress.getByName(ip));
        } catch (UnknownHostException e) {
            throw new IllegalStateException("bad root hint " + ip, e);
        }
    }
}
