package portfolio.networkstackfromscratch.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Authoritative data for one zone and the answers a server for it gives: data, CNAMEs, referrals with
 * glue for delegated children, NODATA and NXDOMAIN with the SOA (RFC 1034 4.3.2). Used to simulate the
 * root, TLD and authoritative servers in tests and in {@code make bench}.
 */
public final class Zone {
    private final String origin;
    private final List<ResourceRecord> records;
    private final ResourceRecord soa;

    /** Zone at {@code origin} holding {@code records}; exactly one SOA at the origin is required. */
    public Zone(String origin, List<ResourceRecord> records) {
        this.origin = DnsName.normalize(origin);
        this.records = List.copyOf(records);
        List<ResourceRecord> soas = this.records.stream()
                .filter(r -> r.type() == RecordType.SOA && r.name().equals(this.origin)).toList();
        if (soas.size() != 1) {
            throw new IllegalArgumentException("zone " + DnsName.display(this.origin) + " needs exactly one SOA");
        }
        for (ResourceRecord r : this.records) {
            if (!DnsName.isWithin(r.name(), this.origin)) {
                throw new IllegalArgumentException(DnsName.display(r.name()) + " is outside the zone");
            }
        }
        this.soa = soas.get(0);
    }

    /** The zone apex. */
    public String origin() {
        return origin;
    }

    /** Response to a query, as an authoritative server for this zone would send it. */
    public Message answer(Message query) {
        Header qh = query.header();
        if (qh.opcode() != 0) {
            return reply(query, Header.NOTIMP, false, List.of(), List.of(), List.of());
        }
        if (query.questions().size() != 1) {
            return reply(query, Header.FORMERR, false, List.of(), List.of(), List.of());
        }
        Question q = query.question();
        if (q.qclass() != RecordType.CLASS_IN || !DnsName.isWithin(q.name(), origin)) {
            return reply(query, Header.REFUSED, false, List.of(), List.of(), List.of());
        }
        String cut = delegation(q.name());
        if (cut != null) {
            List<ResourceRecord> ns = find(cut, RecordType.NS);
            List<ResourceRecord> glue = new ArrayList<>();
            for (ResourceRecord rr : ns) {
                if (rr.data() instanceof RData.Name n && DnsName.isWithin(n.target(), origin)) {
                    glue.addAll(find(n.target(), RecordType.A));
                    glue.addAll(find(n.target(), RecordType.AAAA));
                }
            }
            return reply(query, Header.NOERROR, false, List.of(), ns, glue);
        }
        List<ResourceRecord> answer = new ArrayList<>();
        String name = q.name();
        Set<String> seen = new HashSet<>();
        while (seen.add(name)) {
            List<ResourceRecord> exact = find(name, q.type());
            if (!exact.isEmpty()) {
                answer.addAll(exact);
                break;
            }
            List<ResourceRecord> alias = q.type() == RecordType.CNAME ? List.of() : find(name, RecordType.CNAME);
            if (alias.isEmpty()) {
                break;
            }
            answer.add(alias.get(0));
            String target = ((RData.Name) alias.get(0).data()).target();
            if (!DnsName.isWithin(target, origin) || delegation(target) != null) {
                break;
            }
            name = target;
        }
        if (!answer.isEmpty()) {
            return reply(query, Header.NOERROR, true, answer, List.of(), List.of());
        }
        int rcode = exists(q.name()) ? Header.NOERROR : Header.NXDOMAIN;
        return reply(query, rcode, true, List.of(), List.of(soa), List.of());
    }

    /** Deepest delegation point at or above {@code name} (below the apex), or null. */
    private String delegation(String name) {
        String best = null;
        String n = name;
        while (!n.equals(origin)) {
            if (!find(n, RecordType.NS).isEmpty()) {
                best = n;
            }
            if (n.isEmpty()) {
                break;
            }
            n = DnsName.parent(n);
        }
        return best;
    }

    /** Records with this owner and type. */
    private List<ResourceRecord> find(String name, int type) {
        return records.stream().filter(r -> r.name().equals(name) && r.type() == type).toList();
    }

    /** True when the name owns records or is an empty non-terminal. */
    private boolean exists(String name) {
        return records.stream().anyMatch(r -> r.name().equals(name) || r.name().endsWith("." + name));
    }

    /** Response message echoing the query's id, question and RD bit. */
    private static Message reply(Message query, int rcode, boolean aa, List<ResourceRecord> an,
                                 List<ResourceRecord> ns, List<ResourceRecord> ar) {
        Header qh = query.header();
        Header h = new Header(qh.id(), true, qh.opcode(), aa, false, qh.recursionDesired(), false, rcode);
        return new Message(h, query.questions(), an, ns, ar);
    }

    /**
     * Zone from simple master-file lines: {@code name ttl type data}, names absolute or "@" for the origin.
     * Supported types: A, AAAA, NS, CNAME, PTR, MX, TXT (one quoted string) and SOA.
     */
    public static Zone parse(String origin, String text) {
        String o = DnsName.normalize(origin);
        List<ResourceRecord> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }
            String[] f = line.split("\\s+", 4);
            if (f.length < 4) {
                throw new IllegalArgumentException("expected 'name ttl type data': " + line);
            }
            String name = f[0].equals("@") ? o : DnsName.normalize(f[0]);
            long ttl = Long.parseLong(f[1]);
            int type = RecordType.parse(f[2]);
            out.add(new ResourceRecord(name, type, RecordType.CLASS_IN, ttl, data(type, f[3].strip(), o)));
        }
        return new Zone(o, out);
    }

    /** Parse record data in presentation form. */
    private static RData data(int type, String text, String origin) {
        String[] f = text.split("\\s+");
        return switch (type) {
            case RecordType.A, RecordType.AAAA -> new RData.Address(literal(f[0], type));
            case RecordType.NS, RecordType.CNAME, RecordType.PTR -> new RData.Name(name(f[0], origin));
            case RecordType.MX -> new RData.Mx(Integer.parseInt(f[0]), name(f[1], origin));
            case RecordType.TXT -> new RData.Txt(List.of(text.replaceAll("^\"|\"$", "")));
            case RecordType.SOA -> new RData.Soa(name(f[0], origin), name(f[1], origin), Long.parseLong(f[2]),
                    Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[5]), Long.parseLong(f[6]));
            default -> throw new IllegalArgumentException("unsupported type " + RecordType.name(type));
        };
    }

    /** Name field, "@" meaning the origin. */
    private static String name(String text, String origin) {
        return text.equals("@") ? origin : DnsName.normalize(text);
    }

    /** IP literal of the right family; never triggers a lookup. */
    private static InetAddress literal(String ip, int type) {
        boolean v4 = ip.matches("[0-9.]+");
        boolean v6 = ip.toLowerCase(Locale.ROOT).matches("[0-9a-f:.]+") && ip.contains(":");
        if ((type == RecordType.A && !v4) || (type == RecordType.AAAA && !v6)) {
            throw new IllegalArgumentException("not an " + RecordType.name(type) + " literal: " + ip);
        }
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad address " + ip, e);
        }
    }
}
