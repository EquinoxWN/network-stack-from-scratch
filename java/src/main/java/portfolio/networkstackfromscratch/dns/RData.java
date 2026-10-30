package portfolio.networkstackfromscratch.dns;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Decoded record data for the types the resolver understands; anything else stays opaque. */
public sealed interface RData {
    /** IPv4 or IPv6 address (A, AAAA). */
    record Address(InetAddress address) implements RData {
        @Override
        public String toString() {
            return address.getHostAddress();
        }
    }

    /** A single domain name (NS, CNAME, PTR). */
    record Name(String target) implements RData {
        @Override
        public String toString() {
            return DnsName.display(target);
        }
    }

    /** Start of authority; {@code minimum} bounds negative caching (RFC 2308). */
    record Soa(String mname, String rname, long serial, long refresh, long retry, long expire, long minimum)
            implements RData {
        @Override
        public String toString() {
            return DnsName.display(mname) + " " + DnsName.display(rname) + " " + serial + " " + refresh + " "
                    + retry + " " + expire + " " + minimum;
        }
    }

    /** Mail exchanger. */
    record Mx(int preference, String exchange) implements RData {
        @Override
        public String toString() {
            return preference + " " + DnsName.display(exchange);
        }
    }

    /** One or more character strings (Latin-1 decoded bytes). */
    record Txt(List<String> strings) implements RData {
        /** Defensive copy. */
        public Txt {
            strings = List.copyOf(strings);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (String s : strings) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
            return sb.toString();
        }
    }

    /** Uninterpreted bytes (RFC 3597 form). */
    record Opaque(byte[] bytes) implements RData {
        /** Defensive copy. */
        public Opaque {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Opaque other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "\\# " + bytes.length + (bytes.length > 0 ? " " + HexFormat.of().formatHex(bytes) : "");
        }
    }
}
