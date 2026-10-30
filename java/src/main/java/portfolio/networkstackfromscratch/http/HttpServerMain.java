package portfolio.networkstackfromscratch.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Runs the demo server: {@code [--host 127.0.0.1] [--port 8080] [--idle-ms 5000] [--exit-on-stdin-eof]}.
 * Prints "listening on http://host:port" once bound. With {@code --exit-on-stdin-eof} it stops when its
 * standard input closes, so a test harness that dies never leaves the server running.
 */
public final class HttpServerMain {
    private HttpServerMain() {}

    /** Entry point. */
    public static void main(String[] args) throws IOException, InterruptedException {
        String host = "127.0.0.1";
        int port = 8080;
        long idleMs = 5_000;
        boolean exitOnEof = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--idle-ms" -> idleMs = Long.parseLong(args[++i]);
                case "--exit-on-stdin-eof" -> exitOnEof = true;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        HttpServer.Config cfg = HttpServer.Config.loopback(port).withIdleTimeout(Duration.ofMillis(idleMs));
        cfg = new HttpServer.Config(new InetSocketAddress(host, port), cfg.idleTimeout(), cfg.maxConnections(),
                cfg.maxRequestsPerConnection(), cfg.limits());
        CountDownLatch stop = new CountDownLatch(1);
        try (HttpServer server = HttpServer.start(cfg, new DemoHandler())) {
            InetSocketAddress a = server.address();
            System.out.println("listening on http://" + a.getAddress().getHostAddress() + ":" + a.getPort());
            System.out.flush();
            if (exitOnEof) {
                Thread.ofPlatform().daemon().start(() -> {
                    drain(System.in);
                    stop.countDown();
                });
            }
            Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
            stop.await();
            System.out.println("served " + server.requestsServed() + " requests on "
                    + server.connectionsAccepted() + " connections");
        }
    }

    /** Read until end of stream. */
    private static void drain(InputStream in) {
        byte[] buf = new byte[256];
        try {
            while (in.read(buf) >= 0) {
                // Keep reading until the parent closes the pipe.
            }
        } catch (IOException e) {
            // A broken pipe means the parent is gone, which is the signal to stop.
        }
    }
}
