package portfolio.networkstackfromscratch.dns;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

/** Sends one query to one server and returns the raw response bytes. */
@FunctionalInterface
public interface Transport {
    /** Exchange a query with {@code server}; throws on timeout or I/O failure. */
    byte[] exchange(InetSocketAddress server, byte[] query, Duration timeout) throws IOException;
}
