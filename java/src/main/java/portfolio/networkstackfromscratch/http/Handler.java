package portfolio.networkstackfromscratch.http;

/** Application code: turns one request into one response. */
@FunctionalInterface
public interface Handler {
    /** Where a request came from: the server's connection number and the request's position on it. */
    record Connection(long id, int requestNumber) {}

    /** Produce the response; a thrown exception becomes a 500. */
    HttpResponse handle(HttpRequest request, Connection connection);
}
