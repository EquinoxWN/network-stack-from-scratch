package portfolio.networkstackfromscratch.dns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Wire format encoding and decoding, including hostile input. */
class DnsCodecTest {

    @Test
    void queryMatchesRfc1035ByteLayout() {
        byte[] wire = DnsCodec.encode(Message.query(0xABCD, Question.of("Example.COM.", RecordType.A), true, false));
        String expected = "abcd" + "0100" + "0001" + "0000" + "0000" + "0000"
                + "07" + "6578616d706c65" + "03" + "636f6d" + "00" + "0001" + "0001";
        assertEquals(expected, HexFormat.of().formatHex(wire));
    }

    @Test
    void ednsQueryCarriesOptRecordWith1232Bytes() {
        byte[] wire = DnsCodec.encode(Message.query(1, Question.of("a.b", RecordType.NS), false, true));
        Message m = DnsCodec.decode(wire);
        ResourceRecord opt = m.additionals().get(0);
        assertEquals(RecordType.OPT, opt.type());
        assertEquals(1232, opt.rclass());
        assertEquals("", opt.name());
    }

    @Test
    void repeatedSuffixesAreCompressed() {
        List<ResourceRecord> ns = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ns.add(new ResourceRecord("example.com", RecordType.NS, 1, 3600, new RData.Name("ns" + i + ".example.com")));
        }
        Message m = new Message(new Header(9, true, 0, true, false, false, false, 0),
                List.of(Question.of("example.com", RecordType.NS)), ns, List.of(), List.of());
        byte[] wire = DnsCodec.encode(m);
        // Compressed: each record is a 2-byte owner pointer, 10 fixed bytes and "nsN" + pointer (6 bytes).
        // Uncompressed, every owner (13 bytes) and target (17 bytes) would be spelled out: 189 bytes.
        assertEquals(12 + 17 + 4 * (2 + 10 + 6), wire.length);
        assertEquals(m, DnsCodec.decode(wire));
    }

    @Test
    void everyRecordTypeRoundTrips() {
        List<ResourceRecord> an = List.of(
                SimNet.rr("h.example", RecordType.A, "192.0.2.1"),
                SimNet.rr("h.example", RecordType.AAAA, "2001:db8::1"),
                new ResourceRecord("c.example", RecordType.CNAME, 1, 60, new RData.Name("h.example")),
                new ResourceRecord("example", RecordType.MX, 1, 60, new RData.Mx(5, "mx.example")),
                new ResourceRecord("example", RecordType.TXT, 1, 60, new RData.Txt(List.of("a b", "\"q\""))),
                new ResourceRecord("example", RecordType.SOA, 1, 60,
                        new RData.Soa("ns.example", "admin.example", 4294967295L, 1, 2, 3, 4)),
                new ResourceRecord("example", 99, 1, 60, new RData.Opaque(new byte[] {1, 2, 3})));
        Message m = new Message(new Header(0xFFFF, true, 0, true, false, true, true, 0),
                List.of(Question.of("example", RecordType.ANY)), an, List.of(), List.of());
        assertEquals(m, DnsCodec.decode(DnsCodec.encode(m)));
    }

    @Test
    void randomMessagesRoundTrip() {
        Random rnd = new Random(42);
        for (int iter = 0; iter < 500; iter++) {
            List<ResourceRecord> an = new ArrayList<>();
            int n = rnd.nextInt(6);
            for (int i = 0; i < n; i++) {
                String name = randomName(rnd);
                an.add(switch (rnd.nextInt(3)) {
                    case 0 -> SimNet.rr(name, RecordType.A, rnd.nextInt(256) + ".0.2." + rnd.nextInt(256));
                    case 1 -> new ResourceRecord(name, RecordType.NS, 1, rnd.nextInt(1 << 30), new RData.Name(randomName(rnd)));
                    default -> new ResourceRecord(name, RecordType.TXT, 1, 5, new RData.Txt(List.of("t" + rnd.nextInt())));
                });
            }
            Message m = new Message(new Header(rnd.nextInt(0x10000), rnd.nextBoolean(), 0, rnd.nextBoolean(), false,
                    rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextInt(6)),
                    List.of(Question.of(randomName(rnd), RecordType.A)), an, List.of(), List.of());
            assertEquals(m, DnsCodec.decode(DnsCodec.encode(m)));
        }
    }

    /** Random name from a small alphabet so suffixes repeat and compression is exercised. */
    private static String randomName(Random rnd) {
        String[] labels = {"a", "www", "example", "com", "net", "x-1", "ns"};
        int k = 1 + rnd.nextInt(4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(labels[rnd.nextInt(labels.length)]);
        }
        return sb.toString();
    }

    @Test
    void randomBytesNeverCrashTheDecoderWithAnythingButFormatErrors() {
        Random rnd = new Random(1);
        byte[] base = DnsCodec.encode(Message.query(1, Question.of("www.example.com", RecordType.A), false, true));
        for (int i = 0; i < 20_000; i++) {
            byte[] b = base.clone();
            int flips = 1 + rnd.nextInt(4);
            for (int f = 0; f < flips; f++) {
                b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
            }
            try {
                DnsCodec.decode(b);
            } catch (DnsFormatException expected) {
                // Rejected cleanly: this is the required behaviour for malformed input.
            }
        }
    }

    @Test
    void rejectsTruncatedHeader() {
        assertThrows(DnsFormatException.class, () -> DnsCodec.decode(new byte[11]));
    }

    @Test
    void rejectsPointerLoop() {
        // Header with one question whose name is a pointer to itself (offset 12).
        byte[] b = HexFormat.of().parseHex("000081000001000000000000c00c00010001");
        assertThrows(DnsFormatException.class, () -> DnsCodec.decode(b));
    }

    @Test
    void rejectsForwardPointer() {
        byte[] b = HexFormat.of().parseHex("000081000001000000000000c01200010001" + "0161" + "00");
        assertThrows(DnsFormatException.class, () -> DnsCodec.decode(b));
    }

    @Test
    void rejectsReservedLabelType() {
        byte[] b = HexFormat.of().parseHex("0000810000010000000000004100010001");
        assertThrows(DnsFormatException.class, () -> DnsCodec.decode(b));
    }

    @Test
    void rejectsCountsLargerThanTheMessage() {
        byte[] b = HexFormat.of().parseHex("00008100ffffffffffffffff");
        assertThrows(DnsFormatException.class, () -> DnsCodec.decode(b));
    }

    @Test
    void rejectsRdataLengthMismatch() {
        Message m = new Message(new Header(1, true, 0, false, false, false, false, 0), List.of(),
                List.of(SimNet.rr("a", RecordType.A, "192.0.2.1")), List.of(), List.of());
        byte[] wire = DnsCodec.encode(m);
        wire[wire.length - 5] = 5; // RDLENGTH low byte: 4 -> 5 for an A record
        byte[] longer = java.util.Arrays.copyOf(wire, wire.length + 1);
        assertThrows(DnsFormatException.class, () -> DnsCodec.decode(longer));
    }

    @Test
    void rejectsOverlongNamesAndLabels() {
        assertThrows(DnsFormatException.class, () -> DnsName.normalize("a".repeat(64) + ".com"));
        String longName = String.join(".", java.util.Collections.nCopies(128, "a"));
        assertThrows(DnsFormatException.class, () -> DnsName.normalize(longName));
        assertThrows(DnsFormatException.class, () -> DnsName.normalize("a..b"));
    }

    @Test
    void namesAreCaseInsensitiveAndEscaped() {
        assertEquals("www.example.com", DnsName.normalize("WWW.Example.COM."));
        assertEquals("", DnsName.normalize("."));
        assertEquals("a\\.b.c", DnsName.normalize("a\\.b.c"));
        assertEquals(2, DnsName.labelCount("a\\.b.c"));
        assertEquals("c", DnsName.parent("a\\.b.c"));
        assertEquals("\\000x.example", DnsName.normalize("\\000x.example"));
        assertArrayEquals(new byte[] {0, 'x'}, DnsName.labels("\\000x.example").get(0));
        assertTrue(DnsName.isWithin("www.example.com", "example.com"));
        assertTrue(!DnsName.isWithin("badexample.com", "example.com"));
    }

    @Test
    void longChainOfBackwardPointersIsRejected() {
        // 80 questions; question k's name is a pointer to question k-1's name, so the last ones need
        // more than 64 jumps even though every pointer points backwards.
        int n = 80;
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.writeBytes(new byte[] {0, 1, 0, 0, 0, (byte) n, 0, 0, 0, 0, 0, 0});
        b.writeBytes(new byte[] {0, 0, 1, 0, 1});
        int prev = 12;
        for (int k = 1; k < n; k++) {
            int here = b.size();
            b.writeBytes(new byte[] {(byte) (0xC0 | (prev >> 8)), (byte) prev, 0, 1, 0, 1});
            prev = here;
        }
        byte[] wire = b.toByteArray();
        assertThrows(DnsFormatException.class, () -> DnsCodec.decode(wire));
    }
}
