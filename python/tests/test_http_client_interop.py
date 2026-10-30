"""Python's http.client against the Java server: keep-alive, chunked upload and download."""

from __future__ import annotations

import http.client
from collections.abc import Iterator

from network_stack_from_scratch.javaserver import Endpoint


def connect(server: Endpoint) -> http.client.HTTPConnection:
    """A persistent HTTP/1.1 connection."""
    return http.client.HTTPConnection(server.host, server.port, timeout=5)


def test_one_connection_serves_many_requests(server: Endpoint) -> None:
    conn = connect(server)
    ids = set()
    for i in range(1, 51):
        conn.request("GET", "/conn")
        resp = conn.getresponse()
        assert resp.status == 200
        assert resp.read() == f"connection={resp.getheader('X-Connection-Id')} request={i}\n".encode()
        ids.add(resp.getheader("X-Connection-Id"))
    conn.close()
    assert len(ids) == 1


def test_chunked_upload_with_encode_chunked(server: Endpoint) -> None:
    conn = connect(server)

    def parts() -> Iterator[bytes]:
        yield b"first,"
        yield b"second,"
        yield b"third"

    conn.request("POST", "/echo", body=parts(), headers={"Content-Type": "text/plain"}, encode_chunked=True)
    resp = conn.getresponse()
    assert resp.status == 200
    assert resp.getheader("X-Request-Framing") == "chunked"
    assert resp.read() == b"first,second,third"
    conn.close()


def test_content_length_upload_of_binary_data(server: Endpoint) -> None:
    body = bytes(range(256)) * 1000
    conn = connect(server)
    conn.request("PUT", "/echo", body=body, headers={"Content-Type": "application/octet-stream"})
    resp = conn.getresponse()
    assert resp.getheader("X-Request-Framing") == "content-length"
    assert resp.read() == body
    conn.close()


def test_chunked_download_is_reassembled(server: Endpoint) -> None:
    conn = connect(server)
    conn.request("GET", "/stream?chunks=200&size=500")
    resp = conn.getresponse()
    assert resp.getheader("Transfer-Encoding") == "chunked"
    data = resp.read()
    assert len(data) == 100_000
    assert data[:4] == b"abcd"
    conn.request("GET", "/conn")
    follow = conn.getresponse()
    assert follow.read().endswith(b"request=2\n")
    conn.close()


def test_connection_close_is_honoured(server: Endpoint) -> None:
    conn = connect(server)
    conn.request("GET", "/", headers={"Connection": "close"})
    resp = conn.getresponse()
    assert resp.getheader("Connection") == "close"
    assert resp.read() == b"hello from network-stack-from-scratch\n"
    conn.close()


def test_error_statuses(server: Endpoint) -> None:
    conn = connect(server)
    conn.request("GET", "/missing")
    resp = conn.getresponse()
    assert resp.status == 404
    resp.read()
    conn.request("POST", "/conn", body=b"x")
    resp = conn.getresponse()
    assert resp.status == 405
    assert resp.getheader("Allow") == "GET, HEAD"
    resp.read()
    conn.close()
