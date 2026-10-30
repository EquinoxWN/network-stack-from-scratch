# OSV-Scanner (https://google.github.io/osv-scanner/) checks every resolved Maven dependency.
OSV ?= osv-scanner

.PHONY: setup lint build test bench resolve serve audit ci

# PY is the Python to use (the repo's .venv locally, setup-python's in CI); MVNLOCAL (set by the
# author's repoenv helper) keeps the Maven repository inside the repo and is empty in CI.
PY ?= python
MVN = mvn -B -q $(MVNLOCAL)
CP = java/target/classes

setup:
	cd python && $(PY) -m pip install --upgrade pip && $(PY) -m pip install -e ".[dev]"
	cd js && npm ci

# javac with every warning as an error; ruff, ruff format and mypy --strict; tsc --checkJs --strict.
lint:
	cd java && $(MVN) -DskipTests compile
	cd python && $(PY) -m ruff check . && $(PY) -m ruff format --check . && $(PY) -m mypy
	cd js && npm run -s lint

build:
	cd java && $(MVN) -DskipTests package

# Java unit and loopback tests first; the Python and Node suites start the Java server from $(CP).
test:
	cd java && $(MVN) verify
	cd python && $(PY) -m pytest -q
	cd js && npm test

# Loopback benchmark: cold vs cached DNS resolution, keep-alive vs new connection per HTTP request.
bench: build
	java -Xmx256m -cp $(CP) portfolio.networkstackfromscratch.Bench

# Live recursive resolution from the root servers (needs outbound UDP/TCP port 53): make resolve NAME=example.com
NAME ?= example.com
TYPE ?= A
resolve: build
	java -Xmx64m -cp $(CP) portfolio.networkstackfromscratch.dns.ResolveCli $(NAME) $(TYPE) --trace --twice

serve: build
	java -Xmx128m -cp $(CP) portfolio.networkstackfromscratch.http.HttpServerMain --port 8080

# Known vulnerabilities in Python, npm and Maven dependencies.
audit:
	cd python && $(PY) -m pip_audit --skip-editable --cache-dir ../.tmp/pip-audit
	cd js && npm audit --audit-level=low
	cd java && mvn -B -q $(MVNLOCAL) org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -DoutputFormat=json -DoutputName=bom -DincludeTestScope=true
	$(OSV) scan source -L java/target/bom.json

ci: setup lint test
