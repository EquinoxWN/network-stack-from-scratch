package portfolio.networkstackfromscratch.http;

/** A request the parser rejects, with the status code the server should answer with. */
public final class HttpParseException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int status;

    /** Rejection with an HTTP status (400, 413, 414, 431, 501, 505). */
    public HttpParseException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Status code to send before closing the connection. */
    public int status() {
        return status;
    }
}
