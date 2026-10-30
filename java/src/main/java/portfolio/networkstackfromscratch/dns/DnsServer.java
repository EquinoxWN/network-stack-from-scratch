package portfolio.networkstackfromscratch.dns;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * A small DNS server on UDP and TCP (same port) that answers with a handler, for example
 * {@link Zone#answer}. Replies over 512 bytes to queries without EDNS0 are truncated (TC bit) so the
 * client retries over TCP; {@code truncateAllUdp} forces that path for tests.
 */
public final class DnsServer implements AutoCloseable {
    private static final int CLASSIC_UDP = 512;

    private final DatagramChannel udp;
    private final ServerSocket tcp;
    private final UnaryOperator<Message> handler;
    private final boolean truncateAllUdp;
    private final AtomicInteger udpQueries = new AtomicInteger();
    private final AtomicInteger tcpQueries = new AtomicInteger();
    private final Thread udpThread;
    private final Thread tcpThread;

    private DnsServer(InetSocketAddress bind, UnaryOperator<Message> handler, boolean truncateAllUdp)
            throws IOException {
        this.handler = handler;
        this.truncateAllUdp = truncateAllUdp;
        this.udp = DatagramChannel.open();
        this.udp.bind(bind);
        InetSocketAddress bound = (InetSocketAddress) udp.getLocalAddress();
        this.tcp = new ServerSocket();
        try {
            this.tcp.bind(new InetSocketAddress(bound.getAddress(), bound.getPort()), 16);
        } catch (IOException e) {
            udp.close();
            tcp.close();
            throw e;
        }
        this.udpThread = Thread.ofPlatform().daemon().name("dns-udp-" + bound.getPort()).unstarted(this::serveUdp);
        this.tcpThread = Thread.ofPlatform().daemon().name("dns-tcp-" + bound.getPort()).unstarted(this::serveTcp);
    }

    /** Start serving on {@code bind} (port 0 picks a free port). */
    public static DnsServer start(InetSocketAddress bind, UnaryOperator<Message> handler, boolean truncateAllUdp)
            throws IOException {
        DnsServer s = null;
        // With port 0 the free UDP port may be taken for TCP by another program: pick again.
        for (int attempt = 1; s == null; attempt++) {
            try {
                s = new DnsServer(bind, handler, truncateAllUdp);
            } catch (BindException e) {
                if (bind.getPort() != 0 || attempt >= 20) {
                    throw e;
                }
            }
        }
        s.udpThread.start();
        s.tcpThread.start();
        return s;
    }

    /** Address the server listens on. */
    public InetSocketAddress address() {
        try {
            return (InetSocketAddress) udp.getLocalAddress();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Queries received over UDP. */
    public int udpQueries() {
        return udpQueries.get();
    }

    /** Queries received over TCP. */
    public int tcpQueries() {
        return tcpQueries.get();
    }

    /** UDP loop: decode, answer, truncate if needed; malformed datagrams are dropped. */
    private void serveUdp() {
        ByteBuffer buf = ByteBuffer.allocate(DnsCodec.MAX_MESSAGE);
        while (udp.isOpen()) {
            try {
                buf.clear();
                SocketAddress from = udp.receive(buf);
                buf.flip();
                byte[] wire = new byte[buf.remaining()];
                buf.get(wire);
                udpQueries.incrementAndGet();
                Message query;
                try {
                    query = DnsCodec.decode(wire);
                } catch (DnsFormatException e) {
                    continue;
                }
                Message response = handler.apply(query);
                byte[] out = DnsCodec.encode(response);
                if (truncateAllUdp || out.length > udpLimit(query)) {
                    out = DnsCodec.encode(truncated(response));
                }
                udp.send(ByteBuffer.wrap(out), from);
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (!udp.isOpen()) {
                    return;
                }
            }
        }
    }

    /** Largest UDP reply the client accepts: its EDNS0 size, or 512 without OPT. */
    private static int udpLimit(Message query) {
        for (ResourceRecord rr : query.additionals()) {
            if (rr.type() == RecordType.OPT) {
                return Math.max(CLASSIC_UDP, rr.rclass());
            }
        }
        return CLASSIC_UDP;
    }

    /** Same response with the TC bit and empty record sections. */
    private static Message truncated(Message m) {
        Header h = m.header();
        Header t = new Header(h.id(), true, h.opcode(), h.authoritative(), true, h.recursionDesired(),
                h.recursionAvailable(), h.rcode());
        return new Message(t, m.questions(), List.of(), List.of(), List.of());
    }

    /** TCP loop: one length-prefixed query per connection is enough for a resolver's fallback. */
    private void serveTcp() {
        while (!tcp.isClosed()) {
            try (Socket s = tcp.accept()) {
                s.setSoTimeout(2_000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                int len = in.readUnsignedShort();
                byte[] wire = new byte[len];
                in.readFully(wire);
                tcpQueries.incrementAndGet();
                byte[] out = DnsCodec.encode(handler.apply(DnsCodec.decode(wire)));
                DataOutputStream o = new DataOutputStream(s.getOutputStream());
                o.writeShort(out.length);
                o.write(out);
                o.flush();
            } catch (IOException | DnsFormatException e) {
                if (tcp.isClosed()) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        udp.close();
        tcp.close();
        try {
            udpThread.join(2_000);
            tcpThread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
