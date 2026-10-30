"""Byte-level checks over a raw socket: pipelining order, chunk boundaries, trailers, rejections."""

from __future__ import annotations

import socket

import pytest

from network_stack_from_scratch.javaserver import Endpoint
from network_stack_from_scratch.rawhttp import ProtocolError, Reader, pipeline


def test_pipelined_requests_come_back_in_order(server: Endpoint) -> None:
    raw = (
        b"GET /conn HTTP/1.1\r\nHost: x\r\n\r\n"
        b"POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\nping"
        b"POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n3\r\npon\r\n1\r\ng\r\n0\r\n\r\n"
        b"GET /conn HTTP/1.1\r\nHost: x\r\n\r\n"
    )
    responses = pipeline(server.host, server.port, raw, 4)
    assert [r.status for r in responses] == [200, 200, 200, 200]
    assert responses[0].body.endswith(b"request=1\n")
    assert responses[1].body == b"ping"
    assert responses[2].body == b"pong"
    assert responses[3].body.endswith(b"request=4\n")
    assert len({r.header("X-Connection-Id") for r in responses}) == 1


def test_chunk_boundaries_and_trailer_on_the_wire(server: Endpoint) -> None:
    raw = b"GET /stream?chunks=4&size=10 HTTP/1.1\r\nHost: x\r\nTE: trailers\r\n\r\n"
    (resp,) = pipeline(server.host, server.port, raw, 1)
    assert resp.chunk_sizes == [10, 10, 10, 10]
    assert resp.header("Trailer") == "X-Byte-Sum"
    assert resp.trailers == [("X-Byte-Sum", str(sum(resp.body)))]


def test_http10_request_gets_fixed_length_body(server: Endpoint) -> None:
    with socket.create_connection((server.host, server.port), timeout=5) as sock:
        sock.sendall(b"GET /stream?chunks=3&size=2 HTTP/1.0\r\n\r\n")
        resp = Reader(sock).read()
        assert resp.version == "HTTP/1.0"
        assert resp.header("Content-Length") == "6"
        assert resp.chunk_sizes == []
        assert sock.recv(1) == b""


@pytest.mark.parametrize(
    ("raw", "status"),
    [
        (b"GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n", 400),
        (b"GET / HTTP/1.1\r\n\r\n", 400),
        (b"GET / HTTP/3.0\r\nHost: x\r\n\r\n", 505),
        (b"GET /" + b"a" * 9000 + b" HTTP/1.1\r\n", 414),
        (b"POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 2000000\r\n\r\n", 413),
    ],
)
def test_rejections_close_the_connection(server: Endpoint, raw: bytes, status: int) -> None:
    with socket.create_connection((server.host, server.port), timeout=5) as sock:
        sock.sendall(raw)
        reader = Reader(sock)
        resp = reader.read()
        assert resp.status == status
        assert resp.header("Connection") == "close"
        sock.shutdown(socket.SHUT_WR)
        with pytest.raises(ProtocolError):
            reader.read()


def test_head_sends_headers_only(server: Endpoint) -> None:
    raw = b"HEAD / HTTP/1.1\r\nHost: x\r\n\r\nGET /conn HTTP/1.1\r\nHost: x\r\n\r\n"
    with socket.create_connection((server.host, server.port), timeout=5) as sock:
        sock.sendall(raw)
        reader = Reader(sock)
        head = reader.read(head=True)
        assert head.header("Content-Length") == str(len(b"hello from network-stack-from-scratch\n"))
        follow = reader.read()
        assert follow.body.endswith(b"request=2\n")
