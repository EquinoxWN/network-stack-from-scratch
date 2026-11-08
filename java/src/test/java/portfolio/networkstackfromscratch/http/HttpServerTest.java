package portfolio.networkstackfromscratch.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The NIO server end to end over loopback TCP: raw sockets and the JDK HTTP client. */
class HttpServerTest {
    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.start(HttpServer.Config.loopback(0).withIdleTimeout(Duration.ofMillis(800)),
                new DemoHandler());
        port = server.address().getPort();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    /** Open a raw socket with a read timeout. */
    private Socket socket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        s.setSoTimeout(3_000);
        return s;
    }

    /** Read everything until the server closes the connection. */
    private static String readToEof(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    /** Count occurrences of a substring. */
    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void pipelinedRequestsAreAnsweredInOrderOnOneConnection() throws IOException {
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            out.write(("GET /conn HTTP/1.1\r\nHost: x\r\n\r\n"
                    + "POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nabc"
                    + "GET /conn HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String all = readToEof(s.getInputStream());
            assertEquals(3, count(all, "HTTP/1.1 200 OK"));
            int first = all.indexOf("request=1");
            int echo = all.indexOf("\r\n\r\nabc");
            int third = all.indexOf("request=3");
            assertTrue(first >= 0 && first < echo && echo < third, all);
            assertTrue(all.contains("Connection: close"));
        }
        assertEquals(1, server.connectionsAccepted());
    }

    @Test
    void chunkedUploadSentInPiecesIsReassembled() throws IOException, InterruptedException {
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            String[] pieces = {"POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n",
                "\r\n5\r\nhel", "lo\r\n", "6\r\n world\r\n0\r\nX-Done: yes\r\n", "\r\n"};
            for (String p : pieces) {
                out.write(p.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(20);
            }
            String all = readToEof(s.getInputStream());
            assertTrue(all.endsWith("\r\n\r\nhello world"), all);
            assertTrue(all.contains("X-Request-Framing: chunked"));
            assertTrue(all.contains("X-Request-Trailers: 1"));
        }
    }

    @Test
    void chunkedResponseCarriesTrailerWhenAsked() throws IOException {
        try (Socket s = socket()) {
            s.getOutputStream().write("GET /stream?chunks=3&size=4 HTTP/1.1\r\nHost: x\r\nTE: trailers\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            String all = readToEof(s.getInputStream());
            assertTrue(all.contains("Transfer-Encoding: chunked\r\n"));
            assertTrue(all.contains("\r\n4\r\nabcd\r\n4\r\nbcde\r\n4\r\ncdef\r\n0\r\nX-Byte-Sum: "), all);
        }
    }

    @Test
    void malformedRequestGets400AndTheConnectionCloses() throws IOException {
        try (Socket s = socket()) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            String all = readToEof(s.getInputStream());
            assertTrue(all.startsWith("HTTP/1.1 400 Bad Request\r\n"), all);
            assertTrue(all.contains("Connection: close"));
        }
    }

    @Test
    void expectContinueGets100BeforeTheBodyIsSent() throws IOException {
        try (Socket s = socket()) {
            OutputStream out = s.getOutputStream();
            out.write("PUT /echo HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\nContent-Length: 4\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            byte[] interim = new byte[25];
            int got = 0;
            InputStream in = s.getInputStream();
            while (got < interim.length) {
                got += in.read(interim, got, interim.length - got);
            }
            assertEquals("HTTP/1.1 100 Continue\r\n\r\n", new String(interim, StandardCharsets.US_ASCII));
            out.write("data".getBytes(StandardCharsets.US_ASCII));
            assertTrue(readToEof(in).endsWith("\r\n\r\ndata"));
        }
    }

    @Test
    void idleKeepAliveConnectionIsClosedByTheServer() throws IOException {
        try (Socket s = socket()) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            long start = System.nanoTime();
            String all = readToEof(s.getInputStream());
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(all.contains("hello from network-stack-from-scratch"));
            assertTrue(ms >= 600 && ms < 3_000, "closed after " + ms + " ms");
        }
    }

    @Test
    void halfSentRequestTimesOutWith408() throws IOException {
        try (Socket s = socket()) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHo".getBytes(StandardCharsets.US_ASCII));
            assertTrue(readToEof(s.getInputStream()).startsWith("HTTP/1.1 408 "));
        }
    }

    @Test
    void http10ClientGetsContentLengthAndClose() throws IOException {
        try (Socket s = socket()) {
            s.getOutputStream().write("GET /stream?chunks=2&size=3 HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String all = readToEof(s.getInputStream());
            assertTrue(all.startsWith("HTTP/1.0 200 OK"));
            assertTrue(all.contains("Content-Length: 6") && !all.contains("chunked"));
            assertTrue(all.endsWith("abcbcd"));
        }
    }

    @Test
    void headAndMethodErrors() throws IOException {
        try (Socket s = socket()) {
            s.getOutputStream().write(("HEAD / HTTP/1.1\r\nHost: x\r\n\r\nDELETE /echo HTTP/1.1\r\nHost: x\r\n\r\n"
                    + "GET /missing HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String all = readToEof(s.getInputStream());
            assertTrue(all.startsWith("HTTP/1.1 200 OK"));
            assertTrue(!all.contains("hello from"), "HEAD must not send a body");
            assertTrue(all.contains("HTTP/1.1 405 Method Not Allowed") && all.contains("Allow: POST, PUT"));
            assertTrue(all.contains("HTTP/1.1 404 Not Found"));
        }
    }

    @Test
    void connectionLimitAnswers503() throws IOException {
        server.close();
        server = HttpServer.start(HttpServer.Config.loopback(0).withMaxConnections(1), new DemoHandler());
        port = server.address().getPort();
        try (Socket first = socket(); Socket second = socket()) {
            first.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            first.getInputStream().read();
            assertTrue(readToEof(second.getInputStream()).startsWith("HTTP/1.1 503 "));
        }
        assertEquals(1, server.connectionsRejected());
    }

    @Test
    void jdkHttpClientReusesOneConnectionForManyRequests() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Set<String> connections = new HashSet<>();
        for (int i = 1; i <= 20; i++) {
            var resp = client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/conn"))
                    .build(), BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            connections.add(resp.headers().firstValue("X-Connection-Id").orElseThrow());
            assertEquals(Integer.toString(i), resp.headers().firstValue("X-Request-Number").orElseThrow());
        }
        assertEquals(1, connections.size());
        var post = client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/echo"))
                .POST(BodyPublishers.ofString("x".repeat(100_000))).build(), BodyHandlers.ofString());
        assertEquals(100_000, post.body().length());
        var stream = client.send(java.net.http.HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/stream?chunks=1000&size=1000")).build(), BodyHandlers.ofByteArray());
        assertEquals(1_000_000, stream.body().length);
        client.close();
    }
    @Test
    void handlerExceptionGets500AndIsCountedNotSwallowed() throws IOException {
        server.close();
        server = HttpServer.start(HttpServer.Config.loopback(0), (req, conn) -> {
            throw new IllegalStateException("boom");
        });
        port = server.address().getPort();
        try (Socket s = socket()) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            assertTrue(readToEof(s.getInputStream()).startsWith("HTTP/1.1 500 "));
        }
        assertEquals(1, server.errors());
    }

    @Test
    void openConnectionsIsReadableFromAnotherThread() throws IOException {
        try (Socket a = socket(); Socket b = socket()) {
            a.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            b.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            a.getInputStream().read();
            b.getInputStream().read();
            assertEquals(2, server.openConnections());
        }
        assertEquals(0, server.errors());
    }

    @Test
    void lastRequestAllowedOnAConnectionClosesIt() throws IOException {
        server.close();
        HttpServer.Config base = HttpServer.Config.loopback(0);
        server = HttpServer.start(new HttpServer.Config(base.bind(), base.idleTimeout(), 8, 2, base.limits()),
                new DemoHandler());
        port = server.address().getPort();
        try (Socket s = socket()) {
            s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: x\r\n\r\n".repeat(2))
                    .getBytes(StandardCharsets.US_ASCII));
            String all = readToEof(s.getInputStream());
            assertEquals(2, count(all, "HTTP/1.1 200 "));
            assertEquals(1, count(all, "Connection: close"));
        }
    }
}
