package portfolio.networkstackfromscratch.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Incremental parsing, framing rules and limits of {@link RequestParser}. */
class RequestParserTest {
    private static final String PIPELINE = "GET /a HTTP/1.1\r\nHost: x\r\n\r\n"
            + "POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello"
            + "POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "4;ext=1\r\nWiki\r\n5\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\nX-Sum: 42\r\n\r\n";

    /** Parse a whole string. */
    private static List<HttpRequest> parse(String s) throws HttpParseException {
        return new RequestParser().feed(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Status code of the rejection for a request. */
    private static int rejection(String s) {
        return assertThrows(HttpParseException.class, () -> parse(s)).status();
    }

    @Test
    void parsesPipelinedRequestsWithBothFramings() throws HttpParseException {
        List<HttpRequest> rs = parse(PIPELINE);
        assertEquals(3, rs.size());
        assertEquals("/a", rs.get(0).target());
        assertEquals("hello", new String(rs.get(1).body(), StandardCharsets.US_ASCII));
        assertFalse(rs.get(1).chunked());
        // The RFC 9112 chunked example: "Wikipedia in\r\n\r\nchunks."
        assertEquals("Wikipedia in\r\n\r\nchunks.", new String(rs.get(2).body(), StandardCharsets.US_ASCII));
        assertTrue(rs.get(2).chunked());
        assertEquals("42", rs.get(2).trailers().first("X-Sum").orElseThrow());
    }

    @Test
    void everySplitPointGivesTheSameRequests() throws HttpParseException {
        byte[] all = PIPELINE.getBytes(StandardCharsets.ISO_8859_1);
        List<HttpRequest> whole = parse(PIPELINE);
        for (int cut = 0; cut <= all.length; cut++) {
            RequestParser p = new RequestParser();
            List<HttpRequest> got = new ArrayList<>(p.feed(ByteBuffer.wrap(all, 0, cut)));
            got.addAll(p.feed(ByteBuffer.wrap(all, cut, all.length - cut)));
            assertSame(whole, got);
            assertTrue(p.idle());
        }
    }

    @Test
    void oneByteAtATimeAndRandomSplitsGiveTheSameRequests() throws HttpParseException {
        byte[] all = PIPELINE.getBytes(StandardCharsets.ISO_8859_1);
        List<HttpRequest> whole = parse(PIPELINE);
        Random rnd = new Random(11);
        for (int iter = 0; iter < 300; iter++) {
            RequestParser p = new RequestParser();
            List<HttpRequest> got = new ArrayList<>();
            int pos = 0;
            while (pos < all.length) {
                int n = iter == 0 ? 1 : 1 + rnd.nextInt(24);
                n = Math.min(n, all.length - pos);
                got.addAll(p.feed(ByteBuffer.wrap(all, pos, n)));
                pos += n;
            }
            assertSame(whole, got);
        }
    }

    /** Requests equal in every parsed field. */
    private static void assertSame(List<HttpRequest> want, List<HttpRequest> got) {
        assertEquals(want.size(), got.size());
        for (int i = 0; i < want.size(); i++) {
            assertEquals(want.get(i).method(), got.get(i).method());
            assertEquals(want.get(i).target(), got.get(i).target());
            assertEquals(want.get(i).headers().fields(), got.get(i).headers().fields());
            assertArrayEquals(want.get(i).body(), got.get(i).body());
            assertEquals(want.get(i).trailers().fields(), got.get(i).trailers().fields());
        }
    }

    @Test
    void acceptsBareLfAndLeadingEmptyLines() throws HttpParseException {
        List<HttpRequest> rs = parse("\r\n\nGET / HTTP/1.1\nHost: x\n\n");
        assertEquals(1, rs.size());
    }

    @Test
    void http10NeedsNoHostAndClosesByDefault() throws HttpParseException {
        HttpRequest r = parse("GET / HTTP/1.0\r\n\r\n").get(0);
        assertFalse(r.keepAlive());
        assertTrue(parse("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").get(0).keepAlive());
        assertTrue(parse("GET / HTTP/1.1\r\nHost: x\r\n\r\n").get(0).keepAlive());
        assertFalse(parse("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").get(0).keepAlive());
    }

    @Test
    void expectContinueIsReportedOnceBeforeTheBody() throws HttpParseException {
        RequestParser p = new RequestParser();
        assertTrue(p.feed("PUT /echo HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII)).isEmpty());
        assertTrue(p.takeContinue());
        assertFalse(p.takeContinue());
        assertEquals(1, p.feed("ok".getBytes(StandardCharsets.US_ASCII)).size());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "GET / HTTP/1.1\\r\\nHost: x\\r\\nContent-Length: 3\\r\\nTransfer-Encoding: chunked\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: x\\r\\nContent-Length: 3\\r\\nContent-Length: 4\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: x\\r\\nContent-Length: -1\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: x\\r\\nContent-Length: 0x10\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: x\\r\\nTransfer-Encoding: gzip\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: x\\r\\nTransfer-Encoding: gzip, chunked\\r\\n\\r\\n | 501",
        "GET / HTTP/1.0\\r\\nTransfer-Encoding: chunked\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: a\\r\\nHost: b\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost : x\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: x\\r\\n folded\\r\\n\\r\\n | 400",
        "GET / HTTP/1.1\\r\\nHost: x\\rY: z\\r\\n\\r\\n | 400",
        "GET / HTTP/2.0\\r\\nHost: x\\r\\n\\r\\n | 505",
        "GET / HTTX/1.1\\r\\nHost: x\\r\\n\\r\\n | 400",
        "GET  / HTTP/1.1\\r\\nHost: x\\r\\n\\r\\n | 400",
        "G(T / HTTP/1.1\\r\\nHost: x\\r\\n\\r\\n | 400",
        "GET nopath HTTP/1.1\\r\\nHost: x\\r\\n\\r\\n | 400",
        "POST / HTTP/1.1\\r\\nHost: x\\r\\nTransfer-Encoding: chunked\\r\\n\\r\\nzz\\r\\n | 400",
        "POST / HTTP/1.1\\r\\nHost: x\\r\\nTransfer-Encoding: chunked\\r\\n\\r\\n2\\r\\nabc\\r\\n | 400",
        "POST / HTTP/1.1\\r\\nHost: x\\r\\nTransfer-Encoding: chunked\\r\\n\\r\\n123456789\\r\\n | 400",
    })
    void rejectsAmbiguousOrMalformedRequests(String raw, int status) {
        assertEquals(status, rejection(raw.replace("\\r", "\r").replace("\\n", "\n")));
    }

    @Test
    void enforcesSizeLimits() {
        RequestParser.Limits small = new RequestParser.Limits(64, 128, 3, 10);
        assertEquals(414, assertThrows(HttpParseException.class, () -> new RequestParser(small)
                .feed(("GET /" + "a".repeat(100) + " HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII))).status());
        assertEquals(431, assertThrows(HttpParseException.class, () -> new RequestParser(small)
                .feed(("GET / HTTP/1.1\r\nHost: x\r\nX: " + "v".repeat(200) + "\r\n").getBytes(StandardCharsets.US_ASCII)))
                .status());
        assertEquals(431, assertThrows(HttpParseException.class, () -> new RequestParser(small)
                .feed("GET / HTTP/1.1\r\nHost: x\r\nA: 1\r\nB: 2\r\nC: 3\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))
                .status());
        assertEquals(413, assertThrows(HttpParseException.class, () -> new RequestParser(small)
                .feed("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))
                .status());
        assertEquals(413, assertThrows(HttpParseException.class, () -> new RequestParser(small)
                .feed("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n6\r\nabcdef\r\n6\r\n"
                        .getBytes(StandardCharsets.US_ASCII))).status());
    }

    @Test
    void responseSerializationFramesBodies() {
        HttpResponse fixed = HttpResponse.text(200, "hi");
        String s = new String(fixed.serialize("HTTP/1.1", true, false), StandardCharsets.US_ASCII);
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(s.contains("Content-Length: 2\r\n") && s.endsWith("\r\n\r\nhi"));
        HttpResponse ch = HttpResponse.chunked(200, "text/plain", List.of("ab".getBytes(StandardCharsets.US_ASCII),
                new byte[0], "cde".getBytes(StandardCharsets.US_ASCII)));
        ch.trailers().add("X-T", "1");
        String c = new String(ch.serialize("HTTP/1.1", false, false), StandardCharsets.US_ASCII);
        assertTrue(c.contains("Transfer-Encoding: chunked\r\nTrailer: X-T\r\nConnection: close\r\n\r\n"));
        assertTrue(c.endsWith("\r\n\r\n2\r\nab\r\n3\r\ncde\r\n0\r\nX-T: 1\r\n\r\n"));
        String old = new String(ch.serialize("HTTP/1.0", true, false), StandardCharsets.US_ASCII);
        assertTrue(old.contains("Content-Length: 5") && old.contains("Connection: keep-alive") && old.endsWith("abcde"));
        String head = new String(fixed.serialize("HTTP/1.1", true, true), StandardCharsets.US_ASCII);
        assertTrue(head.endsWith("Content-Length: 2\r\n\r\n"));
        HttpResponse bad = HttpResponse.text(200, "x");
        bad.headers().set("X-Evil", "a\r\nSet-Cookie: y");
        assertThrows(IllegalArgumentException.class, () -> bad.serialize("HTTP/1.1", true, false));
    }
}
