"""Start the Java demo server as a child process and stop it reliably."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from types import TracebackType

REPO = Path(__file__).resolve().parents[3]
CLASSES = REPO / "java" / "target" / "classes"
MAIN = "portfolio.networkstackfromscratch.http.HttpServerMain"
LISTENING = re.compile(r"listening on http://([\d.]+):(\d+)")


@dataclass(frozen=True)
class Endpoint:
    """Where the server listens."""

    host: str
    port: int

    def url(self, path: str = "/") -> str:
        """Absolute URL for a path."""
        return f"http://{self.host}:{self.port}{path}"


class JavaServer:
    """Context manager running HttpServerMain on a free loopback port."""

    def __init__(self, idle_ms: int = 5000) -> None:
        """Remember settings; the process starts in __enter__."""
        self._idle_ms = idle_ms
        self._proc: subprocess.Popen[str] | None = None
        self.endpoint = Endpoint("127.0.0.1", 0)

    def __enter__(self) -> Endpoint:
        """Launch java and wait for the 'listening on' line."""
        if not (CLASSES / Path(*MAIN.split("."))).with_suffix(".class").exists():
            raise RuntimeError(f"build the Java server first: cd java && mvn package ({CLASSES} missing)")
        java = shutil.which("java")
        if java is None:
            raise RuntimeError("java not found on PATH")
        cmd = [
            java,
            "-Xmx64m",
            "-cp",
            str(CLASSES),
            MAIN,
            "--port",
            "0",
            "--idle-ms",
            str(self._idle_ms),
            "--exit-on-stdin-eof",
        ]
        self._proc = subprocess.Popen(  # noqa: S603 - fixed argument list, no shell
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, env=os.environ.copy()
        )
        assert self._proc.stdout is not None
        line = self._proc.stdout.readline()
        match = LISTENING.search(line)
        if match is None:
            self._stop()
            raise RuntimeError(f"server did not start: {line!r}")
        self.endpoint = Endpoint(match.group(1), int(match.group(2)))
        return self.endpoint

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        """Close stdin (the server exits on EOF), then make sure the process is gone."""
        self._stop()

    def _stop(self) -> None:
        """Stop the child process, killing it if it does not exit in time."""
        proc = self._proc
        if proc is None:
            return
        if proc.stdin is not None:
            proc.stdin.close()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        if proc.stdout is not None:
            proc.stdout.close()
        self._proc = None
