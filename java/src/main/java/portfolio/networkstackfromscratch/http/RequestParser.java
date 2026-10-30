package portfolio.networkstackfromscratch.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Incremental HTTP/1.1 request parser (RFC 9112). Bytes can arrive in any split, down to one byte at a
 * time; each call returns the requests completed so far, so pipelined requests come out in order.
 * Ambiguous framing that enables request smuggling is rejected instead of guessed.
 */
public final class RequestParser {
    /** Size limits; every one is enforced before memory is used. */
    public record Limits(int maxRequestLine, int maxHeaderBytes, int maxHeaders, int maxBodyBytes) {
        /** 8 KiB request line, 32 KiB of headers, 100 fields, 1 MiB body. */
        public static Limits defaults() {
            return new Limits(8 * 1024, 32 * 1024, 100, 1024 * 1024);
        }
    }

    private enum State { REQUEST_LINE, HEADERS, BODY, CHUNK_SIZE, CHUNK_DATA, CHUNK_END, TRAILERS }

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern VERSION = Pattern.compile("HTTP/(\\d)\\.(\\d)");
    private static final int MAX_EMPTY_LINES = 8;

    private final Limits limits;
    private State state = State.REQUEST_LINE;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private boolean sawCr;
    private int emptyLines;
    private int headerBytes;
    private String method;
    private String target;
    private String version;
    private Headers headers;
    private Headers trailers;
    private long remaining;
    private boolean chunked;
    private boolean continuePending;

    /** Parser with the given limits. */
    public RequestParser(Limits limits) {
        this.limits = limits;
    }

    /** Parser with default limits. */
    public RequestParser() {
        this(Limits.defaults());
    }

    /** Consume all of {@code in}; returns every request completed by these bytes. */
    public List<HttpRequest> feed(ByteBuffer in) throws HttpParseException {
        List<HttpRequest> done = new ArrayList<>();
        while (in.hasRemaining()) {
            switch (state) {
                case BODY, CHUNK_DATA -> {
                    int n = (int) Math.min(remaining, in.remaining());
                    byte[] chunk = new byte[n];
                    in.get(chunk);
                    body.writeBytes(chunk);
                    remaining -= n;
                    if (remaining == 0) {
                        if (state == State.BODY) {
                            done.add(finish());
                        } else {
                            state = State.CHUNK_END;
                        }
                    }
                }
                default -> {
                    String l = readLine(in);
                    if (l != null) {
                        HttpRequest r = onLine(l);
                        if (r != null) {
                            done.add(r);
                        }
                    }
                }
            }
        }
        return done;
    }

    /** Convenience overload for a byte array. */
    public List<HttpRequest> feed(byte[] bytes) throws HttpParseException {
        return feed(ByteBuffer.wrap(bytes));
    }

    /** True once after the headers of a request with "Expect: 100-continue" whose body is still due. */
    public boolean takeContinue() {
        boolean c = continuePending;
        continuePending = false;
        return c;
    }

    /** True when no partial request is buffered (a safe point to close an idle connection). */
    public boolean idle() {
        return state == State.REQUEST_LINE && line.size() == 0 && !sawCr;
    }

    /** Accumulate bytes up to LF; returns the line without CRLF, or null if more bytes are needed. */
    private String readLine(ByteBuffer in) throws HttpParseException {
        int max = state == State.REQUEST_LINE ? limits.maxRequestLine() : limits.maxHeaderBytes();
        while (in.hasRemaining()) {
            byte b = in.get();
            if (b == '\n') {
                String s = new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
                line.reset();
                sawCr = false;
                return s;
            }
            if (sawCr) {
                throw new HttpParseException(400, "bare CR in message");
            }
            if (b == '\r') {
                sawCr = true;
                continue;
            }
            line.write(b);
            if (line.size() > max) {
                throw state == State.REQUEST_LINE
                        ? new HttpParseException(414, "request line longer than " + max + " bytes")
                        : new HttpParseException(431, "header section larger than " + max + " bytes");
            }
            if (state == State.HEADERS || state == State.TRAILERS) {
                if (++headerBytes > limits.maxHeaderBytes()) {
                    throw new HttpParseException(431, "header section larger than " + limits.maxHeaderBytes() + " bytes");
                }
            }
        }
        return null;
    }

    /** Handle one complete line in the current state. */
    private HttpRequest onLine(String l) throws HttpParseException {
        switch (state) {
            case REQUEST_LINE -> {
                if (l.isEmpty()) {
                    if (++emptyLines > MAX_EMPTY_LINES) {
                        throw new HttpParseException(400, "too many empty lines before the request line");
                    }
                    return null;
                }
                requestLine(l);
                return null;
            }
            case HEADERS -> {
                if (l.isEmpty()) {
                    return endOfHeaders();
                }
                field(l, headers);
                return null;
            }
            case CHUNK_SIZE -> {
                chunkSize(l);
                return null;
            }
            case CHUNK_END -> {
                if (!l.isEmpty()) {
                    throw new HttpParseException(400, "chunk data longer than its declared size");
                }
                state = State.CHUNK_SIZE;
                return null;
            }
            case TRAILERS -> {
                if (l.isEmpty()) {
                    return finish();
                }
                field(l, trailers);
                return null;
            }
            default -> throw new IllegalStateException("line in state " + state);
        }
    }

    /** method SP request-target SP HTTP-version (RFC 9112 3). */
    private void requestLine(String l) throws HttpParseException {
        String[] parts = l.split(" ", -1);
        if (parts.length != 3) {
            throw new HttpParseException(400, "request line must be 'method target version'");
        }
        if (!TOKEN.matcher(parts[0]).matches()) {
            throw new HttpParseException(400, "invalid method");
        }
        String t = parts[1];
        if (t.isEmpty() || !validTarget(t)) {
            throw new HttpParseException(400, "invalid request target");
        }
        var m = VERSION.matcher(parts[2]);
        if (!m.matches()) {
            throw new HttpParseException(400, "invalid HTTP version");
        }
        if (!parts[2].equals("HTTP/1.1") && !parts[2].equals("HTTP/1.0")) {
            throw new HttpParseException(505, "HTTP version " + parts[2] + " not supported");
        }
        method = parts[0];
        target = t;
        version = parts[2];
        headers = new Headers();
        trailers = new Headers();
        headerBytes = 0;
        emptyLines = 0;
        state = State.HEADERS;
    }

    /** Origin form ("/path?q"), asterisk form, or absolute form with visible ASCII only. */
    private static boolean validTarget(String t) {
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                return false;
            }
        }
        return t.startsWith("/") || t.equals("*") || t.startsWith("http://") || t.startsWith("https://");
    }

    /** field-name ":" OWS field-value OWS (RFC 9112 5); obs-fold and space before ':' are rejected. */
    private void field(String l, Headers into) throws HttpParseException {
        if (l.charAt(0) == ' ' || l.charAt(0) == '\t') {
            throw new HttpParseException(400, "obsolete line folding is not accepted");
        }
        int colon = l.indexOf(':');
        if (colon <= 0) {
            throw new HttpParseException(400, "header line without a name");
        }
        String name = l.substring(0, colon);
        if (!TOKEN.matcher(name).matches()) {
            throw new HttpParseException(400, "invalid header name '" + name.strip() + "'");
        }
        String value = l.substring(colon + 1).strip();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                throw new HttpParseException(400, "control character in header value");
            }
        }
        if (into.size() >= limits.maxHeaders()) {
            throw new HttpParseException(431, "more than " + limits.maxHeaders() + " header fields");
        }
        into.add(name, value);
    }

    /** Decide the body framing (RFC 9112 6.3) and reject ambiguous messages. */
    private HttpRequest endOfHeaders() throws HttpParseException {
        List<String> hosts = headers.all("Host");
        if ("HTTP/1.1".equals(version) && hosts.size() != 1) {
            throw new HttpParseException(400, "HTTP/1.1 requests need exactly one Host header");
        }
        boolean hasTe = headers.contains("Transfer-Encoding");
        boolean hasCl = headers.contains("Content-Length");
        if (hasTe && hasCl) {
            throw new HttpParseException(400, "both Transfer-Encoding and Content-Length (possible smuggling)");
        }
        if (hasTe) {
            if ("HTTP/1.0".equals(version)) {
                throw new HttpParseException(400, "Transfer-Encoding in an HTTP/1.0 request");
            }
            List<String> codings = headers.tokens("Transfer-Encoding");
            if (codings.isEmpty() || !codings.get(codings.size() - 1).equals("chunked")) {
                throw new HttpParseException(400, "chunked must be the final transfer coding");
            }
            if (codings.size() > 1) {
                throw new HttpParseException(501, "only the chunked transfer coding is supported");
            }
            chunked = true;
            state = State.CHUNK_SIZE;
            continuePending = expectsContinue();
            return null;
        }
        long length = 0;
        if (hasCl) {
            length = contentLength();
        }
        if (length == 0) {
            return finish();
        }
        remaining = length;
        state = State.BODY;
        continuePending = expectsContinue();
        return null;
    }

    /** True when the client waits for 100 Continue before sending the body. */
    private boolean expectsContinue() {
        return "HTTP/1.1".equals(version) && headers.hasToken("Expect", "100-continue");
    }

    /** The single Content-Length value; differing duplicates are an error (RFC 9112 6.3 step 5). */
    private long contentLength() throws HttpParseException {
        String agreed = null;
        for (String v : headers.all("Content-Length")) {
            for (String part : v.split(",", -1)) {
                String p = part.strip();
                if (p.isEmpty() || p.length() > 18 || !p.chars().allMatch(Character::isDigit)) {
                    throw new HttpParseException(400, "invalid Content-Length");
                }
                if (agreed != null && !agreed.equals(p)) {
                    throw new HttpParseException(400, "conflicting Content-Length values");
                }
                agreed = p;
            }
        }
        long n = Long.parseLong(agreed == null ? "0" : agreed);
        if (n > limits.maxBodyBytes()) {
            throw new HttpParseException(413, "body larger than " + limits.maxBodyBytes() + " bytes");
        }
        return n;
    }

    /** chunk-size [ chunk-ext ] (RFC 9112 7.1). */
    private void chunkSize(String l) throws HttpParseException {
        int semi = l.indexOf(';');
        String hex = (semi < 0 ? l : l.substring(0, semi)).strip();
        if (hex.isEmpty() || hex.length() > 8 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new HttpParseException(400, "invalid chunk size '" + hex + "'");
        }
        long size = Long.parseLong(hex, 16);
        if (body.size() + size > limits.maxBodyBytes()) {
            throw new HttpParseException(413, "body larger than " + limits.maxBodyBytes() + " bytes");
        }
        if (size == 0) {
            headerBytes = 0;
            state = State.TRAILERS;
        } else {
            remaining = size;
            state = State.CHUNK_DATA;
        }
    }

    /** Build the request and reset for the next one on the connection. */
    private HttpRequest finish() {
        HttpRequest r = new HttpRequest(method, target, version, headers, body.toByteArray(), chunked, trailers);
        body.reset();
        chunked = false;
        remaining = 0;
        continuePending = false;
        state = State.REQUEST_LINE;
        return r;
    }
}
