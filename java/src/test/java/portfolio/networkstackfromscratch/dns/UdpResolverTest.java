package portfolio.networkstackfromscratch.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The resolver over real UDP and TCP sockets on loopback, one {@link DnsServer} per simulated address. */
class UdpResolverTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private final List<DnsServer> started = new ArrayList<>();

    @AfterEach
    void stopServers() throws IOException {
        for (DnsServer s : started) {
            s.close();
        }
    }

    /** Start one loopback server per simulated address; returns the address-to-port routing table. */
    private Map<InetAddress, InetSocketAddress> startAll(SimNet net, boolean truncate, String... ips) throws IOException {
        Map<InetAddress, InetSocketAddress> routes = new HashMap<>();
        for (String ip : ips) {
            InetAddress a = SimNet.ip(ip);
            DnsServer s = DnsServer.start(new InetSocketAddress(LOOPBACK, 0), q -> net.answer(a, q), truncate);
            started.add(s);
            routes.put(a, s.address());
        }
        return routes;
    }

    /** Transport that maps simulated addresses to loopback ports, like a NAT table. */
    private static UdpTransport routed(Map<InetAddress, InetSocketAddress> routes) {
        return new UdpTransport(addr -> routes.getOrDefault(addr.getAddress(), new InetSocketAddress(LOOPBACK, 9)));
    }

    @Test
    void resolvesOverRealUdpSockets() throws IOException {
        SimNet net = SimNet.standard();
        Map<InetAddress, InetSocketAddress> routes = startAll(net, false, "198.41.0.4", "192.5.6.30", "192.0.2.53");
        UdpTransport t = routed(routes);
        RecursiveResolver r = new RecursiveResolver(t, new DnsCache(100, System::currentTimeMillis), SimNet.roots(),
                RecursiveResolver.Options.defaults().withTimeout(Duration.ofSeconds(2)), new Random(5));
        RecursiveResolver.Result res = r.resolve("www.example.com", RecordType.A);
        assertEquals(Header.NOERROR, res.rcode(), res.trace().toString());
        assertEquals("93.184.216.34", res.addresses().get(0).getHostAddress());
        assertEquals(3, t.udpQueries());
        assertEquals(0, t.tcpQueries());
        assertEquals(3, started.stream().mapToInt(DnsServer::udpQueries).sum());
    }

    @Test
    void truncatedReplyIsRetriedOverTcp() throws IOException {
        SimNet net = SimNet.standard();
        Map<InetAddress, InetSocketAddress> routes = startAll(net, true, "198.41.0.4", "192.5.6.30", "192.0.2.53");
        UdpTransport t = routed(routes);
        RecursiveResolver r = new RecursiveResolver(t, new DnsCache(100, System::currentTimeMillis), SimNet.roots(),
                RecursiveResolver.Options.defaults(), new Random(5));
        RecursiveResolver.Result res = r.resolve("www.example.com", RecordType.A);
        assertEquals(Header.NOERROR, res.rcode(), res.trace().toString());
        assertEquals(3, t.tcpQueries());
        assertEquals(3, started.stream().mapToInt(DnsServer::tcpQueries).sum());
    }

    @Test
    void largeReplyWithoutEdnsIsTruncatedAndFetchedOverTcp() throws IOException {
        StringBuilder zone = new StringBuilder("@ 60 SOA ns.big hostmaster.big 1 2 3 4 5\n");
        for (int i = 0; i < 60; i++) {
            zone.append("many.big 60 A 192.0.2.").append(i).append('\n');
        }
        Zone big = Zone.parse("big", zone.toString());
        DnsServer s = DnsServer.start(new InetSocketAddress(LOOPBACK, 0), big::answer, false);
        started.add(s);
        UdpTransport t = new UdpTransport();
        byte[] q = DnsCodec.encode(Message.query(77, Question.of("many.big", RecordType.A), false, false));
        Message m = DnsCodec.decode(t.exchange(s.address(), q, Duration.ofSeconds(2)));
        assertEquals(60, m.answers().size());
        assertEquals(1, t.tcpQueries());
    }

    @Test
    void spoofedDatagramWithWrongIdIsDropped() throws IOException, InterruptedException {
        try (DatagramChannel fake = DatagramChannel.open()) {
            fake.bind(new InetSocketAddress(LOOPBACK, 0));
            Thread th = Thread.ofPlatform().start(() -> {
                try {
                    ByteBuffer buf = ByteBuffer.allocate(512);
                    SocketAddress from = fake.receive(buf);
                    buf.flip();
                    byte[] query = new byte[buf.remaining()];
                    buf.get(query);
                    Message q = DnsCodec.decode(query);
                    Header h = q.header();
                    Message good = new Message(new Header(h.id(), true, 0, true, false, false, false, 0),
                            q.questions(), List.of(SimNet.rr("x.test", RecordType.A, "192.0.2.7")), List.of(), List.of());
                    Message forged = new Message(new Header(h.id() ^ 1, true, 0, true, false, false, false, 0),
                            q.questions(), List.of(SimNet.rr("x.test", RecordType.A, "6.6.6.6")), List.of(), List.of());
                    fake.send(ByteBuffer.wrap(DnsCodec.encode(forged)), from);
                    fake.send(ByteBuffer.wrap(DnsCodec.encode(good)), from);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            UdpTransport t = new UdpTransport();
            byte[] q = DnsCodec.encode(Message.query(4242, Question.of("x.test", RecordType.A), false, false));
            Message m = DnsCodec.decode(t.exchange((InetSocketAddress) fake.getLocalAddress(), q, Duration.ofSeconds(2)));
            th.join();
            assertEquals("192.0.2.7", m.answers().get(0).data().toString());
            assertEquals(1, t.dropped());
        }
    }

    @Test
    void silentServerTimesOut() throws IOException {
        try (DatagramChannel silent = DatagramChannel.open()) {
            silent.bind(new InetSocketAddress(LOOPBACK, 0));
            UdpTransport t = new UdpTransport();
            byte[] q = DnsCodec.encode(Message.query(1, Question.of("x.test", RecordType.A), false, false));
            long start = System.nanoTime();
            assertThrows(SocketTimeoutException.class,
                    () -> t.exchange((InetSocketAddress) silent.getLocalAddress(), q, Duration.ofMillis(200)));
            assertTrue((System.nanoTime() - start) / 1_000_000 >= 150);
        }
    }
}
