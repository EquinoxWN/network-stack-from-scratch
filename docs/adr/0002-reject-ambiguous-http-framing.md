# ADR 0002: Reject ambiguous HTTP framing instead of guessing

- **Status:** Accepted

## Context

RFC 9112 gives a server some room: a request with both `Transfer-Encoding` and `Content-Length` "ought
to be handled as an error", but a server may also let `Transfer-Encoding` win; bare LF line endings
and leading empty lines may be tolerated; obs-fold may be unfolded. Every place where two parsers can
read the same bytes differently is a request-smuggling gap when a proxy sits in front of the server.

## Decision

`RequestParser` rejects, with a 400 (or 501 for an unknown transfer coding) and a closed connection:
both `Transfer-Encoding` and `Content-Length`; differing `Content-Length` values; `Transfer-Encoding`
in HTTP/1.0; `chunked` not being the final coding; obs-fold; whitespace between a field name and the
colon; bare CR; chunk sizes that are not hex or longer than 8 digits; chunk data longer than declared.
It tolerates only what cannot change framing: bare LF as a line end and up to 8 empty lines before the
request line. Responses refuse CR or LF in header values (no response splitting).

## Consequences

- A small number of broken clients that real servers accept will get 400 here. That is acceptable for
  a server meant to show correct framing.
- Each rule has a test in `RequestParserTest` and an end-to-end check over a socket in Python and Node.
- Error responses always close the connection, using a lingering close (send FIN, discard input) so the
  client still receives the error instead of a TCP reset.
