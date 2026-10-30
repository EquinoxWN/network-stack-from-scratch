# ADR 0003: One NIO selector thread for the HTTP server

- **Status:** Accepted

## Context

The server must keep connections open between requests, answer pipelined requests in order, close idle
connections and stop reading from a client that does not read its responses. Two designs fit the JDK:
a blocking socket per connection on a (virtual) thread, or non-blocking channels on one `Selector`.

## Decision

Use one selector thread. Each connection is an explicit state object: its incremental parser, a queue
of response buffers, a request counter, a last-activity time and flags for close-after-write,
half-closed input and lingering close. Handlers run on the selector thread.

## Consequences

- Keep-alive, pipelining, idle timeout (408 for a half-sent request) and backpressure (stop reading
  above 1 MiB of queued output) are visible in about 300 lines and tested directly.
- A slow handler blocks every connection. The demo handlers do no I/O, so this does not show in M1;
  a handler pool would be needed before any handler does blocking work.
- Virtual threads would give simpler, sequential code per connection with similar loopback numbers; the
  selector was chosen because it makes the connection state machine the subject of the code.
- The same trade-off applies to the DNS side, where the resolver uses a blocking `DatagramChannel` per
  query (see `docs/results/m1.md`: the first version with a `Selector` per query cost about 12 ms per
  query on Windows).
