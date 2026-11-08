package portfolio.networkstackfromscratch.dns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Bad command lines end with the usage exit code and a message, before any query is sent. */
class ResolveCliTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "--bogus", "--timeout-ms", "--timeout-ms abc", "--timeout-ms 0",
        "--timeout-ms -5", "--timeout-ms 60001", "TYPEXYZ"})
    void badArgumentsGiveUsage(String extra) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        String[] args = extra.isEmpty() ? new String[0] : ("example.com " + extra).split(" ");
        int code = ResolveCli.run(args, new PrintStream(buf, true, StandardCharsets.UTF_8));
        assertEquals(64, code);
        assertTrue(buf.toString(StandardCharsets.UTF_8).startsWith("error: "));
    }
}
