package portfolio.networkstackfromscratch.dns;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Replies must come from the server that was asked (RFC 5452): a spoofed datagram is dropped. */
class UdpTransportTest {
    @Test
    void replyFromAnotherAddressIsDroppedEvenWithTheRightId() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        try (DatagramSocket server = new DatagramSocket(0, lo); DatagramSocket spoofer = new DatagramSocket(0, lo)) {
            byte[] query = new byte[12];
            query[0] = 0x12;
            query[1] = 0x34;
            byte[] fake = query.clone();
            fake[3] = 0x05; // REFUSED
            byte[] real = query.clone();
            real[2] = (byte) 0x80; // QR
            Thread t = Thread.ofPlatform().start(() -> {
                try {
                    DatagramPacket p = new DatagramPacket(new byte[512], 512);
                    server.receive(p);
                    spoofer.send(new DatagramPacket(fake, fake.length, p.getSocketAddress()));
                    Thread.sleep(100);
                    server.send(new DatagramPacket(real, real.length, p.getSocketAddress()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            UdpTransport udp = new UdpTransport();
            byte[] reply = udp.exchange(new InetSocketAddress(lo, server.getLocalPort()), query, Duration.ofSeconds(3));
            t.join();
            assertArrayEquals(real, Arrays.copyOf(reply, reply.length));
            assertEquals(1, udp.dropped());
        }
    }
}
