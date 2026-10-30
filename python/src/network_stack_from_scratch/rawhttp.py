"""A minimal HTTP/1.1 response reader over a raw socket, independent of the Java code.

It lets tests send pipelined requests byte for byte and check exactly what the server put on the
wire, including chunk boundaries and trailers.
"""

from __future__ import annotations

import socket
from dataclasses import dataclass, field

MAX_LINE = 8192
MAX_BODY = 8 * 1024 * 1024


class ProtocolError(Exception):
    """The server sent something that is not valid HTTP/1.1."""


@dataclass
class Response:
    """One parsed response."""

    version: str
    status: int
    reason: str
    headers: list[tuple[str, str]]
    body: bytes
    chunk_sizes: list[int] = field(default_factory=list)
    trailers: list[tuple[str, str]] = field(default_factory=list)

    def header(self, name: str) -> str | None:
        """First value of a header, case-insensitive."""
        for key, value in self.headers:
            if key.lower() == name.lower():
                return value
        return None


class Reader:
    """Buffered reader that parses responses from a byte stream."""

    def __init__(self, sock: socket.socket) -> None:
        """Wrap a connected socket."""
        self._sock = sock
        self._buf = b""

    def _fill(self) -> None:
        """Read more bytes; raise when the peer closed the connection."""
        data = self._sock.recv(65536)
        if not data:
            raise ProtocolError("connection closed mid-response")
        self._buf += data

    def _line(self) -> str:
        """One CRLF-terminated line without the CRLF."""
        while b"\r\n" not in self._buf:
            if len(self._buf) > MAX_LINE:
                raise ProtocolError("line too long")
            self._fill()
        line, self._buf = self._buf.split(b"\r\n", 1)
        return line.decode("iso-8859-1")

    def _exact(self, n: int) -> bytes:
        """Exactly n bytes."""
        while len(self._buf) < n:
            self._fill()
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _fields(self) -> list[tuple[str, str]]:
        """Header or trailer fields up to the empty line."""
        out: list[tuple[str, str]] = []
        while True:
            line = self._line()
            if not line:
                return out
            name, sep, value = line.partition(":")
            if not sep or not name or name != name.strip():
                raise ProtocolError(f"bad field line {line!r}")
            out.append((name, value.strip()))

    def read(self, head: bool = False) -> Response:
        """Parse the next response; interim 1xx responses are returned like any other."""
        version, _, rest = self._line().partition(" ")
        code, _, reason = rest.partition(" ")
        if not version.startswith("HTTP/1.") or not code.isdigit():
            raise ProtocolError(f"bad status line {version} {rest}")
        resp = Response(version, int(code), reason, self._fields(), b"")
        if head or resp.status in (204, 304) or resp.status < 200:
            return resp
        te = resp.header("Transfer-Encoding")
        if te is not None:
            if te.lower() != "chunked":
                raise ProtocolError(f"unsupported transfer coding {te}")
            parts: list[bytes] = []
            while True:
                size_text = self._line().split(";", 1)[0].strip()
                size = int(size_text, 16)
                if size == 0:
                    break
                if sum(resp.chunk_sizes) + size > MAX_BODY:
                    raise ProtocolError("body too large")
                parts.append(self._exact(size))
                resp.chunk_sizes.append(size)
                if self._exact(2) != b"\r\n":
                    raise ProtocolError("chunk not followed by CRLF")
            resp.trailers = self._fields()
            resp.body = b"".join(parts)
            return resp
        length = resp.header("Content-Length")
        if length is None:
            raise ProtocolError("response without framing")
        if int(length) > MAX_BODY:
            raise ProtocolError("body too large")
        resp.body = self._exact(int(length))
        return resp


def pipeline(host: str, port: int, raw: bytes, count: int, timeout: float = 5.0) -> list[Response]:
    """Send raw request bytes at once and read count responses from one connection."""
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.sendall(raw)
        reader = Reader(sock)
        return [reader.read() for _ in range(count)]
