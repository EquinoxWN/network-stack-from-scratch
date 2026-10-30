package portfolio.networkstackfromscratch.dns;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Domain names in presentation form: lower case, no trailing dot, "" for the root.
 * Bytes that are not printable, '.' and '\' inside a label are written as \DDD (RFC 1035 5.1).
 */
public final class DnsName {
    /** Longest label in octets (RFC 1035 2.3.4). */
    public static final int MAX_LABEL = 63;
    /** Longest name in wire octets, including length bytes and the root label. */
    public static final int MAX_WIRE = 255;

    private DnsName() {}

    /** Canonical form of a user-supplied name; throws on invalid input. */
    public static String normalize(String name) {
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (n.isEmpty()) {
            return "";
        }
        List<byte[]> labels = labels(n);
        StringBuilder out = new StringBuilder();
        for (byte[] label : labels) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(present(label));
        }
        return out.toString();
    }

    /** Wire labels of a presentation name, validating lengths and escapes. */
    public static List<byte[]> labels(String name) {
        List<byte[]> out = new ArrayList<>();
        if (name.isEmpty()) {
            return out;
        }
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        int wire = 1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                wire += finishLabel(out, cur);
            } else if (c == '\\') {
                if (i + 3 < name.length() && isDigits(name, i + 1)) {
                    int v = Integer.parseInt(name.substring(i + 1, i + 4));
                    if (v > 255) {
                        throw new DnsFormatException("bad escape in name: " + name);
                    }
                    cur.write(v);
                    i += 3;
                } else if (i + 1 < name.length()) {
                    cur.write(name.charAt(++i) & 0xFF);
                } else {
                    throw new DnsFormatException("dangling escape in name: " + name);
                }
            } else if (c > 0x7E || c < 0x21) {
                throw new DnsFormatException("non-printable character in name: " + name);
            } else {
                cur.write(Character.toLowerCase(c));
            }
        }
        wire += finishLabel(out, cur);
        if (wire > MAX_WIRE) {
            throw new DnsFormatException("name longer than 255 octets: " + name);
        }
        return out;
    }

    /** Close the current label; returns its wire length. */
    private static int finishLabel(List<byte[]> out, ByteArrayOutputStream cur) {
        byte[] label = cur.toByteArray();
        if (label.length == 0) {
            throw new DnsFormatException("empty label");
        }
        if (label.length > MAX_LABEL) {
            throw new DnsFormatException("label longer than 63 octets");
        }
        out.add(label);
        cur.reset();
        return label.length + 1;
    }

    /** Presentation text of one wire label, lower-cased and escaped. */
    public static String present(byte[] label) {
        StringBuilder sb = new StringBuilder();
        for (byte b : label) {
            int v = b & 0xFF;
            if (v >= 'A' && v <= 'Z') {
                v = v + ('a' - 'A');
            }
            if (v == '.' || v == '\\') {
                sb.append('\\').append((char) v);
            } else if (v < 0x21 || v > 0x7E) {
                sb.append('\\').append(String.format(Locale.ROOT, "%03d", v));
            } else {
                sb.append((char) v);
            }
        }
        return sb.toString();
    }

    /** True when {@code name} equals {@code zone} or sits below it. */
    public static boolean isWithin(String name, String zone) {
        if (zone.isEmpty() || name.equals(zone)) {
            return true;
        }
        return name.endsWith("." + zone);
    }

    /** The name with its leftmost label removed ("" for a top-level name). */
    public static String parent(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("the root has no parent");
        }
        int i = indexOfUnescapedDot(name);
        return i < 0 ? "" : name.substring(i + 1);
    }

    /** Number of labels in a presentation name. */
    public static int labelCount(String name) {
        return name.isEmpty() ? 0 : labels(name).size();
    }

    /** Position of the first separator dot that is not escaped. */
    private static int indexOfUnescapedDot(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\') {
                i += (i + 1 < name.length() && Character.isDigit(name.charAt(i + 1))) ? 3 : 1;
            } else if (c == '.') {
                return i;
            }
        }
        return -1;
    }

    /** Display form with the trailing dot, "." for the root. */
    public static String display(String name) {
        return name.isEmpty() ? "." : name + ".";
    }

    /** True when the three characters at {@code from} are decimal digits. */
    private static boolean isDigits(String s, int from) {
        for (int k = from; k < from + 3; k++) {
            if (!Character.isDigit(s.charAt(k))) {
                return false;
            }
        }
        return true;
    }
}
