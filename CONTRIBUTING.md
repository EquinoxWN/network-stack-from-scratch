# Contributing

Thanks for helping. The core is Java; Python and Node.js hold the interoperability clients. Issues
and pull requests are welcome.

## Set up and check your change

Needs JDK 21+ with Maven, Python 3.11+, Node.js 24+ and curl. Caches and virtual environments stay in
git-ignored folders inside the repo.

```bash
make setup   # install Python and npm dev dependencies
make lint    # javac -Werror, ruff + mypy --strict, tsc --checkJs --strict
make test    # Java tests, then the Python and Node suites against the Java server
make audit   # known-vulnerability check (as in CI)
make bench   # loopback DNS and HTTP timings
```

A pull request is ready when `make lint` and `make test` pass and CI is green.

## Guidelines

- Keep each change focused; explain the problem it solves in the pull request.
- Add or update a test for every behaviour change, and update `docs/results/` when numbers change.
- Parser changes need a test that feeds the input in every split (see `RequestParserTest`).
- Significant design changes need an ADR in `docs/adr/` (copy `0001` for the format).
- Keep function comments to one short phrase; let names and tests explain the rest.
- Report security problems privately, as described in [SECURITY.md](SECURITY.md).

By contributing you agree that your work is released under the [MIT License](LICENSE).
