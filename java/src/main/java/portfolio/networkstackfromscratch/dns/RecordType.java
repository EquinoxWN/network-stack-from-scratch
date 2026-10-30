package portfolio.networkstackfromscratch.dns;

import java.util.Locale;
import java.util.Map;

/** DNS record types and classes used by the resolver (RFC 1035, RFC 3596, RFC 6891). */
public final class RecordType {
    public static final int A = 1;
    public static final int NS = 2;
    public static final int CNAME = 5;
    public static final int SOA = 6;
    public static final int PTR = 12;
    public static final int MX = 15;
    public static final int TXT = 16;
    public static final int AAAA = 28;
    public static final int OPT = 41;
    public static final int DS = 43;
    public static final int RRSIG = 46;
    public static final int NSEC = 47;
    public static final int DNSKEY = 48;
    public static final int NSEC3 = 50;
    public static final int ANY = 255;

    /** Internet class. */
    public static final int CLASS_IN = 1;

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(A, "A"), Map.entry(NS, "NS"), Map.entry(CNAME, "CNAME"), Map.entry(SOA, "SOA"),
            Map.entry(PTR, "PTR"), Map.entry(MX, "MX"), Map.entry(TXT, "TXT"), Map.entry(AAAA, "AAAA"),
            Map.entry(OPT, "OPT"), Map.entry(DS, "DS"), Map.entry(RRSIG, "RRSIG"), Map.entry(NSEC, "NSEC"),
            Map.entry(DNSKEY, "DNSKEY"), Map.entry(NSEC3, "NSEC3"), Map.entry(ANY, "ANY"));

    private RecordType() {}

    /** Mnemonic for a type code, or TYPEnnn (RFC 3597) when unknown. */
    public static String name(int type) {
        String n = NAMES.get(type);
        return n != null ? n : "TYPE" + type;
    }

    /** Type code for a mnemonic such as "AAAA" or "TYPE65". */
    public static int parse(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (Map.Entry<Integer, String> e : NAMES.entrySet()) {
            if (e.getValue().equals(upper)) {
                return e.getKey();
            }
        }
        if (upper.startsWith("TYPE")) {
            try {
                int code = Integer.parseInt(upper.substring(4));
                if (code >= 0 && code <= 0xFFFF) {
                    return code;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("unknown record type: " + text, e);
            }
        }
        throw new IllegalArgumentException("unknown record type: " + text);
    }
}
