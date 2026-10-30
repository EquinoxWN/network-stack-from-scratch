"""Shared fixture: one Java server per test module."""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from network_stack_from_scratch.javaserver import Endpoint, JavaServer


@pytest.fixture(scope="module")
def server() -> Iterator[Endpoint]:
    """Run the Java demo server for the module and stop it afterwards."""
    with JavaServer(idle_ms=3000) as endpoint:
        yield endpoint
