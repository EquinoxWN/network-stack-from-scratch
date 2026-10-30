package portfolio.networkstackfromscratch.dns;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command line: {@code resolve <name> [type] [--trace] [--twice] [--timeout-ms N] [--capture DIR]}.
 * Resolves from the root servers over the real network; {@code --twice} repeats the lookup to show the
 * cache, {@code --capture} saves every reply as hex for use as a test fixture.
 */
public final class ResolveCli {
    private ResolveCli() {}

    /** Entry point; exit code 0 for NOERROR, 3 for NXDOMAIN, 2 for SERVFAIL, 64 for usage errors. */
    public static void main(String[] args) throws IOException {
        System.exit(run(args, System.out));
    }

    /** Parse arguments, resolve and print; returns the exit code. */
    static int run(String[] args, PrintStream out) throws IOException {
        String name = null;
        int type = RecordType.A;
        boolean trace = false;
        boolean twice = false;
        long timeoutMs = 2_000;
        Path capture = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--trace" -> trace = true;
                case "--twice" -> twice = true;
                case "--timeout-ms" -> timeoutMs = Long.parseLong(value(args, ++i));
                case "--capture" -> capture = Path.of(value(args, ++i));
                default -> {
                    if (a.startsWith("--")) {
                        return usage(out, "unknown option " + a);
                    } else if (name == null) {
                        name = a;
                    } else {
                        type = RecordType.parse(a);
                    }
                }
            }
        }
        if (name == null) {
            return usage(out, "missing name");
        }
        UdpTransport udp = new UdpTransport();
        Transport transport = capture == null ? udp : capturing(udp, capture);
        DnsCache cache = new DnsCache(10_000, System::currentTimeMillis);
        RecursiveResolver resolver = new RecursiveResolver(transport, cache, RecursiveResolver.IANA_ROOTS,
                RecursiveResolver.Options.defaults().withTimeout(Duration.ofMillis(timeoutMs)),
                new java.security.SecureRandom());
        int rcode = Header.NOERROR;
        for (int round = 1; round <= (twice ? 2 : 1); round++) {
            long start = System.nanoTime();
            RecursiveResolver.Result r = resolver.resolve(name, type);
            double ms = (System.nanoTime() - start) / 1e6;
            out.printf(Locale.ROOT, ";; %s %s, lookup %d: %s, %d upstream queries, %.1f ms%n",
                    DnsName.display(DnsName.normalize(name)), RecordType.name(type), round,
                    Header.rcodeName(r.rcode()), r.queries(), ms);
            if (trace) {
                r.trace().forEach(line -> out.println(";;   " + line));
            }
            r.answers().forEach(out::println);
            rcode = r.rcode();
        }
        out.printf(Locale.ROOT, ";; cache: %d entries, %d hits, %d misses; UDP %d, TCP fallback %d%n",
                cache.size(), cache.hits(), cache.misses(), udp.udpQueries(), udp.tcpQueries());
        return switch (rcode) {
            case Header.NOERROR -> 0;
            case Header.NXDOMAIN -> 3;
            default -> 2;
        };
    }

    /** Transport that also writes each reply to {@code dir} as hex. */
    private static Transport capturing(Transport inner, Path dir) throws IOException {
        Files.createDirectories(dir);
        AtomicInteger n = new AtomicInteger();
        return (server, query, timeout) -> {
            byte[] reply = inner.exchange(server, query, timeout);
            Question q = DnsCodec.decode(query).question();
            String file = String.format(Locale.ROOT, "%02d-%s-%s-%s.hex", n.incrementAndGet(),
                    q.name().isEmpty() ? "root" : q.name().replaceAll("[^a-z0-9.-]", "_"),
                    RecordType.name(q.type()), server.getAddress().getHostAddress().replace(':', '_'));
            Files.writeString(dir.resolve(file), HexFormat.of().formatHex(reply) + "\n", StandardCharsets.US_ASCII);
            return reply;
        };
    }

    /** Value following an option. */
    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("option needs a value");
        }
        return args[i];
    }

    /** Print usage and return the usage exit code. */
    private static int usage(PrintStream out, String problem) {
        out.println("error: " + problem);
        out.println("usage: resolve <name> [type] [--trace] [--twice] [--timeout-ms N] [--capture DIR]");
        return 64;
    }
}
