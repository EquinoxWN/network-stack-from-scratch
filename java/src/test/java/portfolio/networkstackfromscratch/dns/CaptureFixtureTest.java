package portfolio.networkstackfromscratch.dns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Real replies captured from the Internet by {@code ResolveCli --capture} (2026-10-23): root, .com
 * and authoritative servers. They prove the decoder handles what real servers send, compression included.
 */
class CaptureFixtureTest {

    /** Load a capture from test resources. */
    private static byte[] capture(String name) throws IOException {
        try (InputStream in = CaptureFixtureTest.class.getResourceAsStream("/captures/" + name + ".hex")) {
            if (in == null) {
                throw new IOException("missing capture " + name);
            }
            return HexFormat.of().parseHex(new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip());
        }
    }

    @Test
    void rootReferralListsThirteenTldServersWithGlue() throws IOException {
        Message m = DnsCodec.decode(capture("root-referral-example.com-A"));
        assertTrue(m.header().response());
        assertFalse(m.header().authoritative());
        assertEquals("example.com", m.question().name());
        assertEquals(13, m.authorities().size());
        assertTrue(m.authorities().stream().allMatch(r -> r.type() == RecordType.NS && r.name().equals("com")));
        assertTrue(m.authorities().stream().anyMatch(r -> r.data().toString().equals("a.gtld-servers.net.")));
        assertEquals(13, m.additionals().stream().filter(r -> r.type() == RecordType.A).count());
        assertEquals(13, m.additionals().stream().filter(r -> r.type() == RecordType.AAAA).count());
        assertEquals(1, m.additionals().stream().filter(r -> r.type() == RecordType.OPT).count());
    }

    @Test
    void comReferralDelegatesExampleCom() throws IOException {
        Message m = DnsCodec.decode(capture("com-referral-example.com-A"));
        assertEquals(2, m.authorities().size());
        assertTrue(m.authorities().stream().allMatch(r -> r.name().equals("example.com")));
    }

    @Test
    void authoritativeAnswerHasAaBitAndAddresses() throws IOException {
        Message m = DnsCodec.decode(capture("auth-answer-example.com-A"));
        assertTrue(m.header().authoritative());
        assertEquals(2, m.answers().size());
        assertTrue(m.answers().stream().allMatch(r -> r.type() == RecordType.A && r.ttl() == 300));
    }

    @Test
    void cnameAnswerPointsToAnotherZone() throws IOException {
        Message m = DnsCodec.decode(capture("auth-cname-www.wikipedia.org-A"));
        assertEquals(RecordType.CNAME, m.answers().get(0).type());
        assertEquals("dyna.wikimedia.org.", m.answers().get(0).data().toString());
    }

    @Test
    void nxdomainCarriesTheSoaForNegativeCaching() throws IOException {
        Message m = DnsCodec.decode(capture("auth-nxdomain-no-such-name-zz9.example.com-A"));
        assertEquals(Header.NXDOMAIN, m.header().rcode());
        assertEquals(RecordType.SOA, m.authorities().get(0).type());
        assertTrue(m.authorities().get(0).data() instanceof RData.Soa);
    }

    @Test
    void mxAnswerDecodesPreferences() throws IOException {
        Message m = DnsCodec.decode(capture("auth-answer-gmail.com-MX"));
        assertEquals(5, m.answers().size());
        assertTrue(m.answers().stream().anyMatch(r -> r.data().toString().equals("5 gmail-smtp-in.l.google.com.")));
    }

    @Test
    void refusedReplyIsRecognised() throws IOException {
        assertEquals(Header.REFUSED, DnsCodec.decode(capture("refused-www.wikipedia.org-A")).header().rcode());
    }

    @Test
    void reencodingRealRepliesKeepsTheirMeaning() throws IOException {
        for (String name : List.of("root-referral-example.com-A", "com-referral-example.com-A",
                "com-referral-github.com-A", "auth-answer-gmail.com-MX", "auth-nxdomain-no-such-name-zz9.example.com-A")) {
            byte[] raw = capture(name);
            Message m = DnsCodec.decode(raw);
            byte[] again = DnsCodec.encode(m);
            assertEquals(m, DnsCodec.decode(again), name);
            assertTrue(again.length <= raw.length + 16, name + " compressed " + again.length + " vs " + raw.length);
        }
    }

    @Test
    void resolverFollowsTheCapturedReferralChain() throws IOException {
        byte[] root = capture("root-referral-example.com-A");
        byte[] com = capture("com-referral-example.com-A");
        byte[] auth = capture("auth-answer-example.com-A");
        // Replay: patch each captured reply with the id of the live query, chosen by phase (root, TLD, auth).
        int[] phase = {0};
        Transport replay = (server, query, timeout) -> {
            byte[] reply = (phase[0] == 0 ? root : phase[0] == 1 ? com : auth).clone();
            phase[0]++;
            reply[0] = query[0];
            reply[1] = query[1];
            return reply;
        };
        RecursiveResolver r = new RecursiveResolver(replay, new DnsCache(100, System::currentTimeMillis),
                RecursiveResolver.IANA_ROOTS, RecursiveResolver.Options.defaults(), new Random(3));
        RecursiveResolver.Result res = r.resolve("example.com", RecordType.A);
        assertEquals(Header.NOERROR, res.rcode(), res.trace().toString());
        assertEquals(3, res.queries());
        assertEquals(2, res.addresses().size());
        assertArrayEquals(new int[] {3}, phase);
    }

    @Test
    void outOfBailiwickGlueInRealReferralIsNotUsed() throws IOException {
        Message m = DnsCodec.decode(capture("com-referral-github.com-A"));
        assertEquals(8, m.authorities().size());
        // Only the in-bailiwick .com name server has glue; the others need their own resolution.
        long glueA = m.additionals().stream().filter(r -> r.type() == RecordType.A).count();
        assertEquals(1, glueA);
        assertTrue(m.additionals().stream().filter(r -> r.type() == RecordType.A)
                .allMatch(r -> DnsName.isWithin(r.name(), "com")));
    }
}
