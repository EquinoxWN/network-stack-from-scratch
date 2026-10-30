package portfolio.networkstackfromscratch.http;

/** A fully received request; {@code chunked} tells how the body was framed on the wire. */
public record HttpRequest(String method, String target, String version, Headers headers, byte[] body,
                          boolean chunked, Headers trailers) {
    /** Defensive copy of the body. */
    public HttpRequest {
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Body length without copying. */
    public int bodyLength() {
        return body.length;
    }

    /** Path part of the target (before '?'). */
    public String path() {
        int q = target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }

    /** Value of a query parameter, or null. */
    public String query(String key) {
        int q = target.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(key)) {
                return eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** True when the connection may stay open after this request (RFC 9112 9.3). */
    public boolean keepAlive() {
        if (headers.hasToken("Connection", "close")) {
            return false;
        }
        return "HTTP/1.1".equals(version) || headers.hasToken("Connection", "keep-alive");
    }
}
