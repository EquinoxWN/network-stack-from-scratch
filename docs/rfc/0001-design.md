# RFC 0001: network-stack-from-scratch design

- **Status:** Accepted for M1 (DNS resolver and HTTP/1.1 server built and tested); M2 and M3 open
- **Author:** EquinoxWN
- **Created:** 2026-10-23

## Problem (Define)

"What happens when you type a URL?" is usually answered with a diagram. This project answers it with
code that does each step on the wire: find the address by asking the DNS hierarchy directly, then
speak HTTP/1.1 to a server that frames requests and responses itself. Libraries hide the parts that
cause real bugs: name compression, referrals, glue and bailiwick, negative caching, request framing,
keep-alive and pipelining. The users are developers who debug DNS or HTTP problems, and engineers
preparing for networking interview rounds.

### Critical-to-quality measures (targets for M1)

| CTQ | Target | How it is checked |
|---|---|---|
| Resolver walks root -> TLD -> authoritative itself | 3 queries for a cold `www.example.com A`, 0 when cached | `RecursiveResolverTest`, live `ResolveCli --twice` |
| Wire format correct on real data | every captured root, .com and authoritative reply decodes and re-encodes to the same message | `CaptureFixtureTest` (8 real captures) |
| Hostile DNS input never crashes the decoder | 20,000 mutated messages: only `DnsFormatException`, no other exception or hang | `DnsCodecTest` |
| Cache follows TTLs | TTL counts down, expiry re-queries, negative answers kept for min(SOA TTL, SOA minimum) | `DnsCacheTest`, `RecursiveResolverTest` |
| Poisoning defences | out-of-bailiwick answers, glue and sideways referrals ignored; spoofed id dropped | `RecursiveResolverTest`, `UdpResolverTest` |
| HTTP parser is incremental | same requests for every split point and for one byte at a time | `RequestParserTest` |
| HTTP framing is unambiguous | CL+TE, conflicting CL, bad chunk sizes rejected (400/501), limits enforced (413/414/431) | `RequestParserTest`, Python `test_raw_wire.py` |
| Keep-alive and chunked work with real clients | curl, Python `http.client`, Node `http`/`fetch` reuse one connection and exchange chunked bodies | Python and Node interop suites |

## Goals

- M1: a recursive DNS resolver (UDP with TCP fallback, EDNS0, TTL cache, negative cache) and an
  HTTP/1.1 server (incremental parser, keep-alive, pipelining, chunked encoding both ways), both on
  the JDK alone, tested against curl, Python and Node.js clients.
- M2: a reliable transport over UDP (sequence numbers, sliding window, retransmission, congestion
  control), proven under `tc netem` loss, delay and reordering.
- M3: annotated packet captures for each step, and throughput under loss compared with TCP.

## Non-goals

- DNSSEC validation, DNS over TLS/HTTPS, IPv6 transport to name servers (AAAA records are decoded,
  but the resolver contacts servers over IPv4).
- TLS, HTTP/2 and HTTP/3; the HTTP server is plaintext HTTP/1.1 on loopback by default.
- Serving production traffic.

## Design

```
ResolveCli ──> RecursiveResolver ──> Transport (UdpTransport: DatagramChannel, TCP on TC bit)
                    │   ▲
                    ▼   │
                 DnsCache (TTL, negative, trust levels, LRU bound)
                    │
                 DnsCodec (wire format, compression) <── Zone / DnsServer (simulated authorities)

HttpServerMain ──> HttpServer (one NIO selector thread) ──> RequestParser (state machine)
                                         │                   └─> HttpRequest
                                         └──> Handler (DemoHandler) ──> HttpResponse (fixed or chunked)
```

**DNS.** `DnsCodec` reads and writes RFC 1035 messages. Names are decoded with pointer following
where every pointer must point strictly backwards (so loops are impossible) and section counts are
checked against the message length before any allocation. `RecursiveResolver` starts from the closest
zone whose NS and addresses are cached, otherwise from the 13 IANA root servers, sends non-recursive
queries with a random id from a fresh socket, and classifies each reply as answer, CNAME, NXDOMAIN,
NODATA, referral or lame. A referral is followed only if it points to a zone strictly below the
current one and above the question; glue is used only for listed name servers inside the referring
zone. Glueless delegations trigger a nested resolution of the name server's address. Limits: 64
queries and depth 8 per resolution, 8 CNAME hops. `DnsCache` stores RRsets with their expiry and a
trust level (glue < referral < answer), never lets lower trust replace live higher trust, caps TTLs
at one day and negative TTLs at three hours.

**HTTP.** `RequestParser` is a byte-level state machine (request line, headers, fixed body, chunk
size, chunk data, trailers). It returns every request completed by the bytes it is given, so
pipelining needs no special case. It enforces RFC 9112 framing: exactly one Host in HTTP/1.1,
`Transfer-Encoding` and `Content-Length` together rejected, chunked must be the final coding,
conflicting lengths rejected, obs-fold and whitespace before the colon rejected. `HttpServer` runs one
selector thread; each connection has its parser and an output queue. Responses are serialized with
`Content-Length` or chunked framing (joined into a fixed body for HTTP/1.0 clients), the connection
stays open unless the client or the request limit says otherwise, idle connections close after a
timeout (408 for a half-sent request), and errors use a lingering close so the error response is not
destroyed by a TCP reset.

## Alternatives considered

| Option | Why not (yet) |
|---|---|
| Use dnsjava or Netty codecs | They would hide exactly the parts this project exists to show (compression, framing, referral handling). Both stay as upstream contribution targets. |
| Forwarding (stub) resolver that asks 1.1.1.1 with RD=1 | One query instead of a walk; it would not show root, TLD and authoritative servers or bailiwick rules. |
| Thread per connection (or a virtual thread per connection) with blocking sockets | Simpler code, but keep-alive and pipelining become implicit; the selector design makes connection state, backpressure and idle timeouts explicit (see ADR 0003). |
| Lenient HTTP parsing (accept CL+TE, guess) | Leniency is how request smuggling happens between a proxy and a server; rejecting is safer (see ADR 0002). |
| Tests against public DNS servers only | Results would change with the Internet; simulated zones on loopback make every case (glueless, lame, poisoning) deterministic, and real captures cover real-world bytes. |

## Measurement plan (Measure, Analyze, Improve, Control)

- **Measure:** `make test` counts and pass rates per language; `make bench` (loopback): cold vs cached
  resolution through simulated root, TLD and authoritative servers over real UDP sockets, and HTTP
  GET latency on a keep-alive connection vs a new TCP connection per request; `make resolve` against
  the real root servers.
- **Analyze:** compare cold and cached resolution, keep-alive and new connections; record root causes
  of any defect found in `docs/results/m1.md`.
- **Improve:** change the design where the numbers show a cost (the first measured case was the UDP
  receive path; see the results).
- **Control:** CI runs lint (javac `-Werror`, ruff, mypy `--strict`, tsc `--strict`), all tests,
  the benchmark into the job summary, a live resolution job with a `::notice`, and dependency audits.
- M2/M3: throughput-vs-loss chart against TCP and annotated captures (not measured yet).

## Milestones

- **M1 (done):** recursive DNS resolver with TTL cache; HTTP/1.1 server with keep-alive and chunked
  encoding, tested with curl, Python and Node clients.
- **M2:** reliable transport over UDP; `tc netem` loss, delay and reordering tests (Linux, CI).
- **M3:** annotated Wireshark captures; throughput under loss compared with TCP.

## Risks and open questions

- Some authoritative servers refuse or drop queries from some networks (seen live with
  wikimedia.org servers from this machine); the resolver tries every listed server and then returns
  SERVFAIL, but it does not yet remember lame servers between resolutions.
- No DNSSEC: a reply from an on-path attacker that guesses the 16-bit id and the random source port
  would be accepted.
- The server keeps a whole request body in memory (bounded at 1 MiB by default); streaming bodies to
  the handler would be needed for large uploads.
