package portfolio.networkstackfromscratch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import portfolio.networkstackfromscratch.dns.DnsCache;
import portfolio.networkstackfromscratch.dns.DnsServer;
import portfolio.networkstackfromscratch.dns.RecordType;
import portfolio.networkstackfromscratch.dns.RecursiveResolver;
import portfolio.networkstackfromscratch.dns.Zone;
import portfolio.networkstackfromscratch.http.DemoHandler;
import portfolio.networkstackfromscratch.http.HttpServer;

/**
 * {@code make bench}: on loopback only, (1) cold vs cached recursive resolution through simulated root,
 * TLD and authoritative servers over real UDP sockets, (2) HTTP requests on one keep-alive connection vs
 * a new TCP connection per request. Prints a Markdown table.
 */
public final class Bench {
    private static final int NAMES = 300;
    private static final int REQUESTS = 500;

    private Bench() {}

    /** Entry point. */
    public static void main(String[] args) throws IOException {
        System.out.println("| Measurement | Runs | Median | p95 |");
        System.out.println("|---|---|---|---|");
        dns();
        http();
    }

    /** Cold and warm resolution of distinct names in a three-level simulated hierarchy. */
    private static void dns() throws IOException {
        StringBuilder example = new StringBuilder("""
                @ 3600 SOA ns1.example.com hostmaster.example.com 1 7200 3600 1209600 300
                @ 3600 NS ns1.example.com
                ns1.example.com 3600 A 192.0.2.53
                """);
        for (int i = 0; i < NAMES; i++) {
            example.append("h").append(i).append(".example.com 300 A 198.51.100.").append(i % 250).append('\n');
        }
        Map<String, Zone> zones = Map.of(
                "198.41.0.4", Zone.parse("", """
                        @ 86400 SOA a.root-servers.net nstld.verisign-grs.com 1 1800 900 604800 86400
                        @ 518400 NS a.root-servers.net
                        a.root-servers.net 518400 A 198.41.0.4
                        com 172800 NS a.gtld-servers.net
                        a.gtld-servers.net 172800 A 192.5.6.30
                        """),
                "192.5.6.30", Zone.parse("com", """
                        @ 900 SOA a.gtld-servers.net nstld.verisign-grs.com 1 1800 900 604800 86400
                        @ 172800 NS a.gtld-servers.net
                        example.com 172800 NS ns1.example.com
                        ns1.example.com 172800 A 192.0.2.53
                        """),
                "192.0.2.53", Zone.parse("example.com", example.toString()));
        Map<InetAddress, InetSocketAddress> routes = new HashMap<>();
        List<DnsServer> servers = new java.util.ArrayList<>();
        try {
            for (Map.Entry<String, Zone> e : zones.entrySet()) {
                Zone z = e.getValue();
                DnsServer s = DnsServer.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), z::answer, false);
                servers.add(s);
                routes.put(InetAddress.getByName(e.getKey()), s.address());
            }
            var transport = new portfolio.networkstackfromscratch.dns.UdpTransport(a -> routes.get(a.getAddress()));
            double[] cold = new double[NAMES];
            double[] warm = new double[NAMES];
            for (int i = 0; i < NAMES; i++) {
                // A fresh cache per name: every lookup walks root -> com -> example.com.
                RecursiveResolver r = new RecursiveResolver(transport, new DnsCache(100, System::currentTimeMillis),
                        List.of(new RecursiveResolver.ServerAddress("a.root-servers.net", InetAddress.getByName("198.41.0.4"))),
                        RecursiveResolver.Options.defaults(), new SecureRandom());
                long t0 = System.nanoTime();
                r.resolve("h" + i + ".example.com", RecordType.A);
                long t1 = System.nanoTime();
                r.resolve("h" + i + ".example.com", RecordType.A);
                long t2 = System.nanoTime();
                cold[i] = (t1 - t0) / 1e3;
                warm[i] = (t2 - t1) / 1e3;
            }
            row("DNS cold resolution (3 UDP queries: root, com, example.com)", cold, "us");
            row("DNS same name again (served from cache, 0 queries)", warm, "us");
        } finally {
            for (DnsServer s : servers) {
                s.close();
            }
        }
    }

    /** Sequential GETs on one connection vs one connection per request. */
    private static void http() throws IOException {
        try (HttpServer server = HttpServer.start(HttpServer.Config.loopback(0), new DemoHandler())) {
            int port = server.address().getPort();
            byte[] keep = "GET / HTTP/1.1\r\nHost: bench\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] close = "GET / HTTP/1.1\r\nHost: bench\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            double[] reuse = new double[REQUESTS];
            try (Socket s = new Socket("127.0.0.1", port)) {
                s.setTcpNoDelay(true);
                for (int i = 0; i < REQUESTS; i++) {
                    long t0 = System.nanoTime();
                    s.getOutputStream().write(keep);
                    readOne(s.getInputStream());
                    reuse[i] = (System.nanoTime() - t0) / 1e3;
                }
            }
            double[] fresh = new double[REQUESTS];
            for (int i = 0; i < REQUESTS; i++) {
                long t0 = System.nanoTime();
                try (Socket s = new Socket("127.0.0.1", port)) {
                    OutputStream out = s.getOutputStream();
                    out.write(close);
                    s.getInputStream().readAllBytes();
                }
                fresh[i] = (System.nanoTime() - t0) / 1e3;
            }
            row("HTTP GET on one keep-alive connection", reuse, "us");
            row("HTTP GET with a new TCP connection each time", fresh, "us");
        }
    }

    /** Read one response with a Content-Length body from a keep-alive stream. */
    private static void readOne(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed");
            }
            head.write(b);
            String h = head.toString(StandardCharsets.ISO_8859_1);
            if (h.endsWith("\r\n\r\n")) {
                int i = h.indexOf("Content-Length: ") + 16;
                int len = Integer.parseInt(h.substring(i, h.indexOf("\r\n", i)));
                in.readNBytes(len);
                return;
            }
        }
    }

    /** Print one table row with median and p95. */
    private static void row(String what, double[] samples, String unit) {
        double[] s = samples.clone();
        Arrays.sort(s);
        System.out.printf(Locale.ROOT, "| %s | %d | %.0f %s | %.0f %s |%n", what, s.length, s[s.length / 2], unit,
                s[(int) (s.length * 0.95)], unit);
    }
}
