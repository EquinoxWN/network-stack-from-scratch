package portfolio.networkstackfromscratch.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Binary wire format of DNS messages (RFC 1035 4), with name compression in both directions. */
public final class DnsCodec {
    /** Largest message the codec reads or writes (TCP length prefix limit). */
    public static final int MAX_MESSAGE = 65535;
    private static final int HEADER = 12;
    private static final int MAX_POINTER_JUMPS = 64;

    private DnsCodec() {}

    // ----------------------------------------------------------------------------------- decoding

    /** Parse a complete message; throws {@link DnsFormatException} on any malformed input. */
    public static Message decode(byte[] wire) {
        if (wire.length < HEADER) {
            throw new DnsFormatException("message shorter than the 12-byte header");
        }
        if (wire.length > MAX_MESSAGE) {
            throw new DnsFormatException("message longer than 65535 bytes");
        }
        Reader r = new Reader(wire);
        int id = r.u16();
        int flags = r.u16();
        int qd = r.u16();
        int an = r.u16();
        int ns = r.u16();
        int ar = r.u16();
        // Each question needs at least 5 bytes and each record 11, so huge counts fail before allocating.
        if ((long) qd * 5 + (long) (an + ns + ar) * 11 > wire.length - HEADER) {
            throw new DnsFormatException("section counts exceed the message length");
        }
        Header header = Header.fromFlags(id, flags);
        List<Question> questions = new ArrayList<>(qd);
        for (int i = 0; i < qd; i++) {
            String name = r.name();
            questions.add(new Question(name, r.u16(), r.u16()));
        }
        return new Message(header, questions, records(r, an), records(r, ns), records(r, ar));
    }

    /** Read {@code count} resource records. */
    private static List<ResourceRecord> records(Reader r, int count) {
        List<ResourceRecord> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = r.name();
            int type = r.u16();
            int rclass = r.u16();
            long ttl = r.u32();
            int rdlength = r.u16();
            int end = r.pos + rdlength;
            if (end > r.wire.length) {
                throw new DnsFormatException("record data runs past the end of the message");
            }
            RData data = rdata(r, type, end);
            if (r.pos != end) {
                throw new DnsFormatException("record data length does not match its content for "
                        + RecordType.name(type));
            }
            out.add(new ResourceRecord(name, type, rclass, ttl, data));
        }
        return out;
    }

    /** Decode the data of one record ending at {@code end}. */
    private static RData rdata(Reader r, int type, int end) {
        int len = end - r.pos;
        switch (type) {
            case RecordType.A, RecordType.AAAA -> {
                int want = type == RecordType.A ? 4 : 16;
                if (len != want) {
                    throw new DnsFormatException(RecordType.name(type) + " record with " + len + " data bytes");
                }
                return new RData.Address(address(r.bytes(want)));
            }
            case RecordType.NS, RecordType.CNAME, RecordType.PTR -> {
                return new RData.Name(r.name());
            }
            case RecordType.SOA -> {
                String mname = r.name();
                String rname = r.name();
                return new RData.Soa(mname, rname, r.u32(), r.u32(), r.u32(), r.u32(), r.u32());
            }
            case RecordType.MX -> {
                int pref = r.u16();
                return new RData.Mx(pref, r.name());
            }
            case RecordType.TXT -> {
                if (len == 0) {
                    throw new DnsFormatException("TXT record without strings");
                }
                List<String> strings = new ArrayList<>();
                while (r.pos < end) {
                    int n = r.u8();
                    strings.add(new String(r.bytes(n), StandardCharsets.ISO_8859_1));
                }
                return new RData.Txt(strings);
            }
            default -> {
                return new RData.Opaque(r.bytes(len));
            }
        }
    }

    /** Address object for 4 or 16 raw bytes (no lookup happens). */
    private static InetAddress address(byte[] raw) {
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            throw new DnsFormatException("bad address length " + raw.length);
        }
    }

    /** Bounds-checked cursor over a received message. */
    private static final class Reader {
        private final byte[] wire;
        private int pos;

        /** Cursor at the start of {@code wire}. */
        Reader(byte[] wire) {
            this.wire = wire;
        }

        /** Fail unless {@code n} more bytes are available. */
        private void need(int n) {
            if (pos + n > wire.length) {
                throw new DnsFormatException("message truncated at offset " + pos);
            }
        }

        /** One unsigned byte. */
        int u8() {
            need(1);
            return wire[pos++] & 0xFF;
        }

        /** Big-endian unsigned 16-bit value. */
        int u16() {
            need(2);
            int v = ((wire[pos] & 0xFF) << 8) | (wire[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        /** Big-endian unsigned 32-bit value. */
        long u32() {
            need(4);
            long v = ((long) (wire[pos] & 0xFF) << 24) | ((wire[pos + 1] & 0xFF) << 16)
                    | ((wire[pos + 2] & 0xFF) << 8) | (wire[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        /** The next {@code n} bytes. */
        byte[] bytes(int n) {
            need(n);
            byte[] out = Arrays.copyOfRange(wire, pos, pos + n);
            pos += n;
            return out;
        }

        /** A possibly compressed name; pointers must point strictly backwards, so loops are impossible. */
        String name() {
            StringBuilder sb = new StringBuilder();
            int p = pos;
            int resume = -1;
            int jumps = 0;
            int wireLength = 1;
            int lowestTarget = p;
            while (true) {
                if (p >= wire.length) {
                    throw new DnsFormatException("name runs past the end of the message");
                }
                int len = wire[p] & 0xFF;
                if ((len & 0xC0) == 0xC0) {
                    if (p + 1 >= wire.length) {
                        throw new DnsFormatException("truncated compression pointer");
                    }
                    int target = ((len & 0x3F) << 8) | (wire[p + 1] & 0xFF);
                    if (target >= lowestTarget || ++jumps > MAX_POINTER_JUMPS) {
                        throw new DnsFormatException("compression pointer does not point backwards");
                    }
                    if (resume < 0) {
                        resume = p + 2;
                    }
                    lowestTarget = target;
                    p = target;
                } else if ((len & 0xC0) != 0) {
                    throw new DnsFormatException("reserved label type 0x" + Integer.toHexString(len & 0xC0));
                } else if (len == 0) {
                    pos = resume >= 0 ? resume : p + 1;
                    return sb.toString();
                } else {
                    if (p + 1 + len > wire.length) {
                        throw new DnsFormatException("label runs past the end of the message");
                    }
                    wireLength += len + 1;
                    if (wireLength > DnsName.MAX_WIRE) {
                        throw new DnsFormatException("name longer than 255 octets");
                    }
                    if (!sb.isEmpty()) {
                        sb.append('.');
                    }
                    sb.append(DnsName.present(Arrays.copyOfRange(wire, p + 1, p + 1 + len)));
                    p += len + 1;
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------- encoding

    /** Serialize a message, compressing repeated name suffixes. */
    public static byte[] encode(Message m) {
        Writer w = new Writer();
        Header h = m.header();
        w.u16(h.id());
        w.u16(h.flags());
        w.u16(m.questions().size());
        w.u16(m.answers().size());
        w.u16(m.authorities().size());
        w.u16(m.additionals().size());
        for (Question q : m.questions()) {
            w.name(q.name());
            w.u16(q.type());
            w.u16(q.qclass());
        }
        for (List<ResourceRecord> section : List.of(m.answers(), m.authorities(), m.additionals())) {
            for (ResourceRecord rr : section) {
                w.record(rr);
            }
        }
        byte[] out = w.toByteArray();
        if (out.length > MAX_MESSAGE) {
            throw new DnsFormatException("encoded message longer than 65535 bytes");
        }
        return out;
    }

    /** Growable output buffer that remembers where each name suffix was written. */
    private static final class Writer {
        private final Buf out = new Buf();
        private final Map<String, Integer> suffixes = new HashMap<>();

        /** Append a 16-bit value. */
        void u16(int v) {
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        /** Append a 32-bit value. */
        void u32(long v) {
            u16((int) ((v >> 16) & 0xFFFF));
            u16((int) (v & 0xFFFF));
        }

        /** Append a name, replacing the longest suffix already written with a pointer. */
        void name(String name) {
            List<byte[]> labels = DnsName.labels(name);
            for (int i = 0; i < labels.size(); i++) {
                String suffix = join(labels, i);
                Integer at = suffixes.get(suffix);
                if (at != null) {
                    u16(0xC000 | at);
                    return;
                }
                if (out.size() < 0x4000) {
                    suffixes.put(suffix, out.size());
                }
                out.write(labels.get(i).length);
                out.writeBytes(labels.get(i));
            }
            out.write(0);
        }

        /** Key for the suffix starting at label {@code from}. */
        private static String join(List<byte[]> labels, int from) {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < labels.size(); i++) {
                sb.append(DnsName.present(labels.get(i))).append('.');
            }
            return sb.toString();
        }

        /** Append one resource record with a back-patched RDLENGTH. */
        void record(ResourceRecord rr) {
            name(rr.name());
            u16(rr.type());
            u16(rr.rclass());
            u32(rr.ttl());
            int lengthAt = out.size();
            u16(0);
            int start = out.size();
            rdata(rr.type(), rr.data());
            int len = out.size() - start;
            if (len > 0xFFFF) {
                throw new DnsFormatException("record data longer than 65535 bytes");
            }
            patch(lengthAt, len);
        }

        /** Append record data. */
        private void rdata(int type, RData data) {
            switch (data) {
                case RData.Address a -> {
                    byte[] raw = a.address().getAddress();
                    if ((type == RecordType.A && raw.length != 4) || (type == RecordType.AAAA && raw.length != 16)) {
                        throw new DnsFormatException("address family does not match " + RecordType.name(type));
                    }
                    out.writeBytes(raw);
                }
                case RData.Name n -> name(n.target());
                case RData.Soa s -> {
                    name(s.mname());
                    name(s.rname());
                    u32(s.serial());
                    u32(s.refresh());
                    u32(s.retry());
                    u32(s.expire());
                    u32(s.minimum());
                }
                case RData.Mx mx -> {
                    u16(mx.preference());
                    name(mx.exchange());
                }
                case RData.Txt t -> {
                    for (String s : t.strings()) {
                        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
                        if (b.length > 255) {
                            throw new DnsFormatException("TXT string longer than 255 bytes");
                        }
                        out.write(b.length);
                        out.writeBytes(b);
                    }
                }
                case RData.Opaque o -> out.writeBytes(o.bytes());
            }
        }

        /** Overwrite a 16-bit value already written. */
        private void patch(int at, int v) {
            out.set(at, v >> 8);
            out.set(at + 1, v);
        }

        /** Finished bytes. */
        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** Growable byte array that allows overwriting earlier bytes. */
    private static final class Buf {
        private byte[] data = new byte[512];
        private int size;

        /** Append one byte. */
        void write(int b) {
            ensure(1);
            data[size++] = (byte) b;
        }

        /** Append a byte array. */
        void writeBytes(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, data, size, b.length);
            size += b.length;
        }

        /** Overwrite the byte at {@code at}. */
        void set(int at, int b) {
            data[at] = (byte) b;
        }

        /** Bytes written so far. */
        int size() {
            return size;
        }

        /** Grow the array for {@code n} more bytes. */
        private void ensure(int n) {
            if (size + n > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, size + n));
            }
        }

        /** Copy of the written bytes. */
        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
