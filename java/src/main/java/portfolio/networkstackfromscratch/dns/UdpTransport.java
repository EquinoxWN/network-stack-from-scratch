package portfolio.networkstackfromscratch.dns;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * DNS over UDP with a {@link DatagramChannel}, falling back to TCP when the reply is truncated (TC bit).
 * Every query uses a fresh socket, so the source port is random; replies from another address or with
 * another message id are dropped, as RFC 5452 asks.
 */
public final class UdpTransport implements Transport {
    private static final int MAX_UDP = 65_535;

    private final UnaryOperator<InetSocketAddress> route;
    private final AtomicLong udpQueries = new AtomicLong();
    private final AtomicLong tcpQueries = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /** Transport that talks to servers at their own address. */
    public UdpTransport() {
        this(UnaryOperator.identity());
    }

    /** Transport that maps each server address first (tests map public addresses to loopback ports). */
    public UdpTransport(UnaryOperator<InetSocketAddress> route) {
        this.route = route;
    }

    @Override
    public byte[] exchange(InetSocketAddress server, byte[] query, Duration timeout) throws IOException {
        if (query.length < 12) {
            throw new IllegalArgumentException("query shorter than a DNS header");
        }
        InetSocketAddress target = route.apply(server);
        udpQueries.incrementAndGet();
        byte[] reply = udp(target, query, timeout);
        if ((reply[2] & 0x02) != 0) {
            tcpQueries.incrementAndGet();
            return tcp(target, query, timeout);
        }
        return reply;
    }

    /** One UDP round trip, ignoring datagrams that do not match (blocking receive with a timeout). */
    private byte[] udp(InetSocketAddress target, byte[] query, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (DatagramChannel ch = DatagramChannel.open()) {
            ch.bind(null);
            ch.send(ByteBuffer.wrap(query), target);
            DatagramSocket sock = ch.socket();
            byte[] buf = new byte[MAX_UDP];
            while (true) {
                long waitMs = (deadline - System.nanoTime()) / 1_000_000;
                if (waitMs <= 0) {
                    throw new SocketTimeoutException("no reply from " + target + " within " + timeout.toMillis() + " ms");
                }
                sock.setSoTimeout((int) Math.min(Integer.MAX_VALUE, waitMs));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                byte[] reply = Arrays.copyOf(p.getData(), p.getLength());
                if (target.equals(p.getSocketAddress()) && sameId(query, reply)) {
                    return reply;
                }
                dropped.incrementAndGet();
            }
        }
    }

    /** One TCP round trip with the two-byte length prefix (RFC 1035 4.2.2). */
    private static byte[] tcp(InetSocketAddress target, byte[] query, Duration timeout) throws IOException {
        int ms = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        try (Socket s = new Socket()) {
            s.connect(target, ms);
            s.setSoTimeout(ms);
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeShort(query.length);
            out.write(query);
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int len = in.readUnsignedShort();
            byte[] reply = new byte[len];
            in.readFully(reply);
            if (len < 12 || !sameId(query, reply)) {
                throw new IOException("TCP reply from " + target + " does not match the query");
            }
            return reply;
        }
    }

    /** True when both messages carry the same 16-bit id. */
    private static boolean sameId(byte[] query, byte[] reply) {
        return reply.length >= 12 && Arrays.equals(query, 0, 2, reply, 0, 2);
    }

    /** UDP queries sent. */
    public long udpQueries() {
        return udpQueries.get();
    }

    /** Queries retried over TCP after a truncated reply. */
    public long tcpQueries() {
        return tcpQueries.get();
    }

    /** Datagrams dropped because the sender or id did not match. */
    public long dropped() {
        return dropped.get();
    }
}
