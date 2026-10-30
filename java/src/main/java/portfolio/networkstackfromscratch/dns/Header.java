package portfolio.networkstackfromscratch.dns;

/** The fixed 12-byte message header (RFC 1035 4.1.1), without the section counts. */
public record Header(int id, boolean response, int opcode, boolean authoritative, boolean truncated,
                     boolean recursionDesired, boolean recursionAvailable, int rcode) {
    public static final int NOERROR = 0;
    public static final int FORMERR = 1;
    public static final int SERVFAIL = 2;
    public static final int NXDOMAIN = 3;
    public static final int NOTIMP = 4;
    public static final int REFUSED = 5;

    /** Validate field ranges. */
    public Header {
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException("id out of range: " + id);
        }
        if (opcode < 0 || opcode > 15 || rcode < 0 || rcode > 15) {
            throw new IllegalArgumentException("opcode or rcode out of range");
        }
    }

    /** The 16 flag bits as sent on the wire (Z bits zero). */
    public int flags() {
        return (response ? 0x8000 : 0) | (opcode << 11) | (authoritative ? 0x0400 : 0)
                | (truncated ? 0x0200 : 0) | (recursionDesired ? 0x0100 : 0)
                | (recursionAvailable ? 0x0080 : 0) | rcode;
    }

    /** Header decoded from an id and the flag bits. */
    public static Header fromFlags(int id, int flags) {
        return new Header(id, (flags & 0x8000) != 0, (flags >> 11) & 0xF, (flags & 0x0400) != 0,
                (flags & 0x0200) != 0, (flags & 0x0100) != 0, (flags & 0x0080) != 0, flags & 0xF);
    }

    /** Mnemonic of a response code. */
    public static String rcodeName(int rcode) {
        return switch (rcode) {
            case NOERROR -> "NOERROR";
            case FORMERR -> "FORMERR";
            case SERVFAIL -> "SERVFAIL";
            case NXDOMAIN -> "NXDOMAIN";
            case NOTIMP -> "NOTIMP";
            case REFUSED -> "REFUSED";
            default -> "RCODE" + rcode;
        };
    }
}
