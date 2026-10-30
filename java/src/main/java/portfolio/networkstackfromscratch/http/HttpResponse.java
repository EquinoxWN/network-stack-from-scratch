package portfolio.networkstackfromscratch.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A response with either a fixed body (sent with Content-Length) or a list of chunks (sent with
 * Transfer-Encoding: chunked and optional trailers).
 */
public final class HttpResponse {
    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(100, "Continue"), Map.entry(200, "OK"), Map.entry(204, "No Content"),
            Map.entry(400, "Bad Request"), Map.entry(404, "Not Found"), Map.entry(405, "Method Not Allowed"),
            Map.entry(408, "Request Timeout"), Map.entry(413, "Content Too Large"), Map.entry(414, "URI Too Long"),
            Map.entry(431, "Request Header Fields Too Large"), Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"), Map.entry(503, "Service Unavailable"),
            Map.entry(505, "HTTP Version Not Supported"));

    private final int status;
    private final Headers headers = new Headers();
    private final byte[] body;
    private final List<byte[]> chunks;
    private final Headers trailers = new Headers();

    private HttpResponse(int status, byte[] body, List<byte[]> chunks) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status out of range: " + status);
        }
        this.status = status;
        this.body = body;
        this.chunks = chunks;
    }

    /** Response with a fixed body. */
    public static HttpResponse of(int status, String contentType, byte[] body) {
        HttpResponse r = new HttpResponse(status, body.clone(), null);
        r.headers.set("Content-Type", contentType);
        return r;
    }

    /** Plain-text response. */
    public static HttpResponse text(int status, String text) {
        return of(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    /** Response streamed as chunks; empty chunks are skipped because a zero-size chunk ends the body. */
    public static HttpResponse chunked(int status, String contentType, List<byte[]> chunks) {
        List<byte[]> copy = new ArrayList<>();
        for (byte[] c : chunks) {
            if (c.length > 0) {
                copy.add(c.clone());
            }
        }
        HttpResponse r = new HttpResponse(status, null, copy);
        r.headers.set("Content-Type", contentType);
        return r;
    }

    /** Status code. */
    public int status() {
        return status;
    }

    /** Mutable response headers. */
    public Headers headers() {
        return headers;
    }

    /** Mutable trailer fields (sent only with a chunked body). */
    public Headers trailers() {
        return trailers;
    }

    /** True when the body is sent chunked. */
    public boolean isChunked() {
        return chunks != null;
    }

    /** Standard reason phrase. */
    public static String reason(int status) {
        return REASONS.getOrDefault(status, "Status " + status);
    }

    /** Wire bytes: status line, headers, framing headers, then the body unless {@code head}. */
    public byte[] serialize(String version, boolean keepAlive, boolean head) {
        // HTTP/1.0 clients do not understand chunked framing, so they get the joined body instead.
        boolean sendChunked = isChunked() && "HTTP/1.1".equals(version);
        byte[] fixed = isChunked() && !sendChunked ? joined() : body;
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 + (fixed == null ? 0 : fixed.length));
        StringBuilder sb = new StringBuilder();
        sb.append(version).append(' ').append(status).append(' ').append(reason(status)).append("\r\n");
        for (Headers.Field f : headers.fields()) {
            appendField(sb, f.name(), f.value());
        }
        boolean noBody = status == 204 || status == 304 || status < 200;
        if (!noBody) {
            if (sendChunked) {
                appendField(sb, "Transfer-Encoding", "chunked");
                if (trailers.size() > 0) {
                    List<String> names = trailers.fields().stream().map(Headers.Field::name).distinct().toList();
                    appendField(sb, "Trailer", String.join(", ", names));
                }
            } else {
                appendField(sb, "Content-Length", Integer.toString(fixed.length));
            }
        }
        if (!keepAlive) {
            appendField(sb, "Connection", "close");
        } else if ("HTTP/1.0".equals(version)) {
            appendField(sb, "Connection", "keep-alive");
        }
        sb.append("\r\n");
        out.writeBytes(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (head || noBody) {
            return out.toByteArray();
        }
        if (!sendChunked) {
            out.writeBytes(fixed);
            return out.toByteArray();
        }
        for (byte[] c : chunks) {
            out.writeBytes((Integer.toHexString(c.length).toUpperCase(Locale.ROOT) + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(c);
            out.writeBytes(new byte[] {'\r', '\n'});
        }
        StringBuilder end = new StringBuilder("0\r\n");
        for (Headers.Field f : trailers.fields()) {
            appendField(end, f.name(), f.value());
        }
        end.append("\r\n");
        out.writeBytes(end.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /** All chunks concatenated. */
    private byte[] joined() {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        chunks.forEach(all::writeBytes);
        return all.toByteArray();
    }

    /** "name: value" line; CR or LF in a value would split the response, so it is refused. */
    private static void appendField(StringBuilder sb, String name, String value) {
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("CR or LF in header " + name);
        }
        sb.append(name).append(": ").append(value).append("\r\n");
    }
}
