# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub:
**Security > Report a vulnerability** on this repository
(`https://github.com/EquinoxWN/network-stack-from-scratch/security/advisories/new`). Do not open a public issue.

Include what you found, how to reproduce it, and the impact you expect. You will get an answer
within 7 days. Fixes are released as soon as they are ready, and you are credited unless you ask
not to be.

## Supported versions

This project is pre-1.0. Only the latest commit on `main` receives fixes.

## Scope

- **In scope:** DNS messages that crash or hang the decoder, cache poisoning through out-of-bailiwick
  data, spoofed replies that are accepted, HTTP requests that are framed differently than RFC 9112
  says (request smuggling), header injection in responses, and limits that can be bypassed to exhaust
  memory.
- **Out of scope:** Running the demo server on a public interface (it binds to loopback by default and
  is a teaching server), and DNSSEC validation, which the resolver does not do.

## How this repository protects itself

- Every GitHub Action is pinned to a full commit SHA, and workflows run with read-only
  permissions and without persisted credentials.
- Dependabot update pull requests are paused (`open-pull-requests-limit: 0`) so the repository keeps a single `main` branch; dependencies are updated by hand and checked by the CI audit.
- The CI workflow runs lint, tests and a known-vulnerability check (pip-audit, npm audit, and OSV-Scanner on a CycloneDX SBOM of the Maven dependencies) on every push and pull request (first CI run pending).
- The Java code has no runtime dependencies; JUnit is the only Maven dependency (test scope).
