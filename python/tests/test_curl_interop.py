"""curl against the Java server: connection reuse, chunked upload, chunked download, HTTP/1.0."""

from __future__ import annotations

import shutil
import subprocess

import pytest

from network_stack_from_scratch.javaserver import Endpoint

CURL = shutil.which("curl")
pytestmark = pytest.mark.skipif(CURL is None, reason="curl is not installed")


def curl(*args: str) -> subprocess.CompletedProcess[str]:
    """Run curl with a timeout; output is decoded without newline translation, so CRLF stays visible."""
    assert CURL is not None
    raw = subprocess.run(  # noqa: S603 - fixed program, arguments built by the tests
        [CURL, "--silent", "--show-error", "--http1.1", "--max-time", "10", *args],
        capture_output=True,
        check=True,
    )
    return subprocess.CompletedProcess(
        raw.args, raw.returncode, raw.stdout.decode("latin-1"), raw.stderr.decode("latin-1")
    )


def test_curl_reuses_the_connection_for_several_urls(server: Endpoint) -> None:
    url = server.url("/conn")
    result = curl("--verbose", url, url, url)
    lines = result.stdout.splitlines()
    assert [line.split()[1] for line in lines] == ["request=1", "request=2", "request=3"]
    assert len({line.split()[0] for line in lines}) == 1
    assert "Re-using existing" in result.stderr or "Reusing existing" in result.stderr


def test_curl_chunked_upload(server: Endpoint) -> None:
    result = curl(
        "--header",
        "Transfer-Encoding: chunked",
        "--header",
        "Content-Type: text/plain",
        "--data-binary",
        "sent by curl in chunked encoding",
        "--dump-header",
        "-",
        server.url("/echo"),
    )
    assert "X-Request-Framing: chunked\r\n" in result.stdout
    assert result.stdout.endswith("sent by curl in chunked encoding")


def test_curl_raw_shows_chunk_framing(server: Endpoint) -> None:
    result = curl("--raw", server.url("/stream?chunks=2&size=3"))
    assert result.stdout == "3\r\nabc\r\n3\r\nbcd\r\n0\r\n\r\n"


def test_curl_decodes_large_chunked_body(server: Endpoint) -> None:
    result = curl(server.url("/stream?chunks=100&size=1000"))
    assert len(result.stdout) == 100_000


def test_curl_http10(server: Endpoint) -> None:
    result = curl("--http1.0", "--dump-header", "-", server.url("/"))
    assert result.stdout.startswith("HTTP/1.0 200 OK")
    assert "Connection: close" in result.stdout
