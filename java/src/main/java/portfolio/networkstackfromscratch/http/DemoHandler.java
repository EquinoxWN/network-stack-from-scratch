package portfolio.networkstackfromscratch.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes used by the interop tests: {@code /} greeting, {@code /echo} body echo, {@code /stream}
 * chunked response, {@code /headers} request headers, {@code /conn} connection reuse report.
 * Every response carries X-Connection-Id and X-Request-Number so clients can prove keep-alive.
 */
public final class DemoHandler implements Handler {
    /** Most chunks {@code /stream} will send. */
    public static final int MAX_CHUNKS = 1_000;
    /** Largest chunk {@code /stream} will send. */
    public static final int MAX_CHUNK_SIZE = 64 * 1024;

    @Override
    public HttpResponse handle(HttpRequest r, Connection c) {
        HttpResponse resp = route(r, c);
        resp.headers().set("X-Connection-Id", Long.toString(c.id()));
        resp.headers().set("X-Request-Number", Integer.toString(c.requestNumber()));
        return resp;
    }

    /** Pick the response for a path and method. */
    private static HttpResponse route(HttpRequest r, Connection c) {
        String path = r.path();
        return switch (path) {
            case "/" -> onlyGet(r, () -> HttpResponse.text(200, "hello from network-stack-from-scratch\n"));
            case "/conn" -> onlyGet(r, () -> HttpResponse.text(200,
                    "connection=" + c.id() + " request=" + c.requestNumber() + "\n"));
            case "/headers" -> onlyGet(r, () -> {
                StringBuilder sb = new StringBuilder();
                for (Headers.Field f : r.headers().fields()) {
                    sb.append(f.name()).append(": ").append(f.value()).append('\n');
                }
                return HttpResponse.text(200, sb.toString());
            });
            case "/echo" -> echo(r);
            case "/stream" -> onlyGet(r, () -> stream(r));
            default -> HttpResponse.text(404, "no route for " + path + "\n");
        };
    }

    /** Return the request body with the request's content type and how it was framed. */
    private static HttpResponse echo(HttpRequest r) {
        if (!"POST".equals(r.method()) && !"PUT".equals(r.method())) {
            HttpResponse resp = HttpResponse.text(405, "use POST or PUT\n");
            resp.headers().set("Allow", "POST, PUT");
            return resp;
        }
        String type = r.headers().first("Content-Type").orElse("application/octet-stream");
        HttpResponse resp = HttpResponse.of(200, type, r.body());
        resp.headers().set("X-Request-Framing", r.chunked() ? "chunked" : "content-length");
        resp.headers().set("X-Request-Trailers", Integer.toString(r.trailers().size()));
        return resp;
    }

    /** {@code /stream?chunks=N&size=M}: N chunks of M bytes, with a trailer if the client sent TE: trailers. */
    private static HttpResponse stream(HttpRequest r) {
        int chunks = bounded(r.query("chunks"), 5, MAX_CHUNKS);
        int size = bounded(r.query("size"), 16, MAX_CHUNK_SIZE);
        List<byte[]> parts = new ArrayList<>(chunks);
        long sum = 0;
        for (int i = 0; i < chunks; i++) {
            byte[] part = new byte[size];
            for (int j = 0; j < size; j++) {
                part[j] = (byte) ('a' + (i + j) % 26);
                sum += part[j];
            }
            parts.add(part);
        }
        HttpResponse resp = HttpResponse.chunked(200, "text/plain; charset=us-ascii", parts);
        if (r.headers().hasToken("TE", "trailers")) {
            resp.trailers().add("X-Byte-Sum", Long.toString(sum));
        }
        return resp;
    }

    /** Integer query value clamped to 1..max, or the default. */
    private static int bounded(String value, int dflt, int max) {
        if (value == null || value.isEmpty() || value.length() > 9 || !value.chars().allMatch(Character::isDigit)) {
            return dflt;
        }
        return Math.max(1, Math.min(max, Integer.parseInt(value)));
    }

    /** Allow GET and HEAD only. */
    private static HttpResponse onlyGet(HttpRequest r, java.util.function.Supplier<HttpResponse> ok) {
        if (!"GET".equals(r.method()) && !"HEAD".equals(r.method())) {
            HttpResponse resp = HttpResponse.of(405, "text/plain; charset=utf-8",
                    "use GET\n".getBytes(StandardCharsets.UTF_8));
            resp.headers().set("Allow", "GET, HEAD");
            return resp;
        }
        return ok.get();
    }
}
