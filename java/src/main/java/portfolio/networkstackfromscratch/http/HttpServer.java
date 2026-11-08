package portfolio.networkstackfromscratch.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP/1.1 server on one NIO selector thread. Connections stay open between requests (keep-alive),
 * pipelined requests are answered in order, idle connections are closed after a timeout, and a client
 * that stops reading is not read from until its responses drain (backpressure).
 */
public final class HttpServer implements AutoCloseable {
    /** Server settings. */
    public record Config(InetSocketAddress bind, Duration idleTimeout, int maxConnections,
                         int maxRequestsPerConnection, RequestParser.Limits limits) {
        /** Loopback on {@code port}, 5 s idle timeout, 256 connections, 1,000 requests per connection. */
        public static Config loopback(int port) {
            return new Config(new InetSocketAddress("127.0.0.1", port), Duration.ofSeconds(5), 256, 1_000,
                    RequestParser.Limits.defaults());
        }

        /** Same settings with another idle timeout. */
        public Config withIdleTimeout(Duration d) {
            return new Config(bind, d, maxConnections, maxRequestsPerConnection, limits);
        }

        /** Same settings with another connection limit. */
        public Config withMaxConnections(int n) {
            return new Config(bind, idleTimeout, n, maxRequestsPerConnection, limits);
        }
    }

    private static final int READ_BUFFER = 16 * 1024;
    private static final int MAX_PENDING_OUTPUT = 1024 * 1024;
    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** Per-connection state. */
    private static final class Conn {
        final long id;
        final SocketChannel channel;
        final RequestParser parser;
        final ArrayDeque<ByteBuffer> out = new ArrayDeque<>();
        long pendingBytes;
        long lastActivityNanos = System.nanoTime();
        int requests;
        boolean closeAfterWrite;
        boolean inputClosed;
        boolean draining;

        /** State for a newly accepted channel. */
        Conn(long id, SocketChannel channel, RequestParser.Limits limits) {
            this.id = id;
            this.channel = channel;
            this.parser = new RequestParser(limits);
        }

        /** Queue bytes for writing. */
        void queue(byte[] bytes) {
            out.add(ByteBuffer.wrap(bytes));
            pendingBytes += bytes.length;
        }
    }

    private final Config config;
    private final Handler handler;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final Thread loop;
    // Read by other threads (openConnections, close), so a concurrent set rather than an ArrayList.
    private final Set<Conn> conns = ConcurrentHashMap.newKeySet();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean running = true;

    private HttpServer(Config config, Handler handler) throws IOException {
        this.config = config;
        this.handler = handler;
        this.selector = Selector.open();
        this.listener = ServerSocketChannel.open();
        listener.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        listener.bind(config.bind(), 128);
        listener.configureBlocking(false);
        listener.register(selector, SelectionKey.OP_ACCEPT);
        this.loop = Thread.ofPlatform().name("http-loop").daemon().unstarted(this::run);
    }

    /** Bind and start the event loop. */
    public static HttpServer start(Config config, Handler handler) throws IOException {
        HttpServer s = new HttpServer(config, handler);
        s.loop.start();
        return s;
    }

    /** Bound address (useful with port 0). */
    public InetSocketAddress address() {
        try {
            return (InetSocketAddress) listener.getLocalAddress();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Connections accepted so far. */
    public long connectionsAccepted() {
        return accepted.get();
    }

    /** Requests answered so far (including error responses). */
    public long requestsServed() {
        return served.get();
    }

    /** Connections refused with 503 because the limit was reached. */
    public long connectionsRejected() {
        return rejected.get();
    }

    /** Event loop: accept, read, write, and close idle connections. */
    private void run() {
        long tickMs = Math.max(10, Math.min(250, config.idleTimeout().toMillis() / 4));
        while (running) {
            try {
                selector.select(tickMs);
                for (SelectionKey key : selector.selectedKeys()) {
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        accept();
                    } else {
                        Conn c = (Conn) key.attachment();
                        try {
                            if (key.isReadable()) {
                                read(c, key);
                            }
                            if (key.isValid() && key.isWritable()) {
                                write(c, key);
                            }
                        } catch (IOException e) {
                            close(c);
                        }
                    }
                }
                selector.selectedKeys().clear();
                expireIdle();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                errors.incrementAndGet();
                System.err.println("http-loop: " + e);
            }
        }
    }

    /** Accept every pending connection; over the limit, answer 503 and close. */
    private void accept() throws IOException {
        SocketChannel ch;
        while ((ch = listener.accept()) != null) {
            accepted.incrementAndGet();
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            Conn c = new Conn(accepted.get(), ch, config.limits());
            SelectionKey key = ch.register(selector, SelectionKey.OP_READ, c);
            conns.add(c);
            if (conns.size() > config.maxConnections()) {
                rejected.incrementAndGet();
                respond(c, key, HttpResponse.text(503, "too many connections\n"), "HTTP/1.1", false, false);
            }
        }
    }

    /** Read available bytes, parse them, and queue one response per complete request. */
    private void read(Conn c, SelectionKey key) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(READ_BUFFER);
        int n = c.channel.read(buf);
        if (c.draining) {
            if (n < 0) {
                close(c);
            }
            return;
        }
        if (n < 0) {
            c.inputClosed = true;
            if (c.out.isEmpty()) {
                close(c);
            } else {
                c.closeAfterWrite = true;
                key.interestOps(SelectionKey.OP_WRITE);
            }
            return;
        }
        c.lastActivityNanos = System.nanoTime();
        buf.flip();
        List<HttpRequest> requests;
        try {
            requests = c.parser.feed(buf);
        } catch (HttpParseException e) {
            respond(c, key, HttpResponse.text(e.status(), e.getMessage() + "\n"), "HTTP/1.1", false, false);
            return;
        }
        for (HttpRequest r : requests) {
            if (c.closeAfterWrite) {
                break;
            }
            c.requests++;
            boolean keepAlive = r.keepAlive() && c.requests < config.maxRequestsPerConnection();
            HttpResponse resp;
            try {
                resp = handler.handle(r, new Handler.Connection(c.id, c.requests));
            } catch (RuntimeException e) {
                errors.incrementAndGet();
                System.err.println("handler failed on " + r.method() + " " + r.target() + ": " + e);
                resp = HttpResponse.text(500, "internal error\n");
            }
            respond(c, key, resp, r.version(), keepAlive, "HEAD".equals(r.method()));
        }
        if (!c.closeAfterWrite && c.parser.takeContinue()) {
            c.queue(CONTINUE);
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    /** Queue a serialized response and switch the key to writing. */
    private void respond(Conn c, SelectionKey key, HttpResponse resp, String version, boolean keepAlive,
                         boolean head) {
        c.queue(resp.serialize(version, keepAlive, head));
        served.incrementAndGet();
        if (!keepAlive) {
            c.closeAfterWrite = true;
        }
        int ops = SelectionKey.OP_WRITE;
        if (!c.closeAfterWrite && !c.inputClosed && c.pendingBytes < MAX_PENDING_OUTPUT) {
            ops |= SelectionKey.OP_READ;
        }
        key.interestOps(ops);
    }

    /** Write queued bytes; resume reading when drained, or close if the connection is done. */
    private void write(Conn c, SelectionKey key) throws IOException {
        while (!c.out.isEmpty()) {
            ByteBuffer b = c.out.peek();
            int n = c.channel.write(b);
            c.pendingBytes -= n;
            if (b.hasRemaining()) {
                break;
            }
            c.out.poll();
        }
        c.lastActivityNanos = System.nanoTime();
        if (c.out.isEmpty()) {
            if (c.closeAfterWrite) {
                if (c.inputClosed) {
                    close(c);
                } else {
                    // Lingering close: send FIN, then discard input until the client closes, so unread
                    // pipelined bytes do not turn into a reset that destroys the response in flight.
                    c.channel.shutdownOutput();
                    c.draining = true;
                    key.interestOps(SelectionKey.OP_READ);
                }
                return;
            }
            key.interestOps(c.inputClosed ? 0 : SelectionKey.OP_READ);
        } else if (c.pendingBytes < MAX_PENDING_OUTPUT && !c.closeAfterWrite && !c.inputClosed) {
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        } else {
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    /** Close connections idle longer than the timeout (a half-received request gets 408 first). */
    private void expireIdle() {
        long now = System.nanoTime();
        long limit = config.idleTimeout().toNanos();
        long drainLimit = Math.min(limit, Duration.ofSeconds(1).toNanos());
        for (Conn c : new ArrayList<>(conns)) {
            if (c.draining && now - c.lastActivityNanos > drainLimit) {
                close(c);
            } else if (now - c.lastActivityNanos > limit) {
                if (!c.parser.idle() && c.out.isEmpty() && !c.closeAfterWrite) {
                    SelectionKey key = c.channel.keyFor(selector);
                    if (key != null && key.isValid()) {
                        respond(c, key, HttpResponse.text(408, "request timeout\n"), "HTTP/1.1", false, false);
                        c.lastActivityNanos = now;
                        continue;
                    }
                }
                close(c);
            }
        }
    }

    /** Close one connection and forget it. */
    private void close(Conn c) {
        conns.remove(c);
        try {
            c.channel.close();
        } catch (IOException e) {
            // Already closed by the peer; nothing left to release.
        }
    }

    /** Handler exceptions (answered with 500) and event-loop I/O errors so far. */
    public long errors() {
        return errors.get();
    }

    /** Number of open connections (for tests and stats). */
    public int openConnections() {
        return conns.size();
    }

    @Override
    public void close() throws IOException {
        running = false;
        selector.wakeup();
        try {
            loop.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Conn c : new ArrayList<>(conns)) {
            close(c);
        }
        listener.close();
        selector.close();
    }
}
