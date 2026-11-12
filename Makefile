# Floor42: common tasks, for Linux, macOS and WSL 2. On Windows without make, the commands are in README.md.
# Building needs Java 25 (JAVA_HOME) and Node 24; Docker needs only Docker Engine (docs/docker.md).

MVNW := ./mvnw -B -ntp

.PHONY: assurance oidc-conformance quality fuzz semgrep security-static trivy hadolint actionlint zap api-fuzz mutation help install build test test-fast test-slow quick gate verify release-gate e2e run doctor dev web-dev demo-tape demo-build demo-preview docker-build docker-up docker-login docker-open docker-logs docker-down clean

help: ## List the tasks
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F ':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

install: ## Install the web dependencies
	cd floor42-web && npm ci

# The fast targets install the web dependencies only when the lock file changed since the last install.
floor42-web/node_modules/.package-lock.json: floor42-web/package-lock.json
	cd floor42-web && npm ci

build: install ## Build the daemon and its UI, without tests
	$(MVNW) -q package -DskipTests

test-fast: floor42-web/node_modules/.package-lock.json ## The everyday check: web unit tests, then the server tests without the web build nor the format check
	cd floor42-web && npm test
	$(MVNW) -q -pl floor42-server -am test -Dspotless.check.skip=true

quick: floor42-web/node_modules/.package-lock.json ## The tests the diff touches (Java classes by name, Vitest --changed); not a gate
	bin/floor42-quick

test-slow: ## The tests tagged slow (real WSL sandbox, real timeouts), left out of verify: part of the release gate
	$(MVNW) -pl floor42-server -am test -Pslow -Dspotless.check.skip=true -Dquarkus.quinoa=false

release-gate: verify test-slow ## Before a release: the full gate, then the slow tests (docs/releases.md)
	cd floor42-web && npm test

gate: verify ## The full suite, as CI runs it: format, every Java test, then the web unit tests
	cd floor42-web && npm test

test: test-fast ## Every test (no test calls a paid model): the same as test-fast

verify: install ## The full gate (merge to main, release): format, then the full build with every test, as CI runs it
	$(MVNW) -q spotless:apply
	$(MVNW) verify

quality: floor42-web/node_modules/.package-lock.json ## Static analysis and coverage reports (JaCoCo, PMD, CPD, SpotBugs, ESLint); docs/quality.md. Add SONAR_TOKEN and `sonar:sonar` by hand for SonarQube
	cd floor42-web && npm run lint:report
	$(MVNW) -Pquality verify -Dspotless.check.skip=true

semgrep: ## Semgrep (registry packs and tools/quality/semgrep.yml) in the official Docker image; SARIF in target/semgrep.sarif; docs/security-scanning.md
	sh tools/quality/semgrep-run.sh

trivy: ## Trivy on the lock files, target/bom.json, the Docker and compose files and the image (TRIVY_EXIT=1 to fail on HIGH/CRITICAL); needs `make build` for the SBOM
	sh tools/quality/trivy-run.sh all

security-static: ## OSV-Scanner, gitleaks (history and tree) and Bearer in Docker; reports in target/static; docs/security-scanning.md
	sh tools/quality/static-run.sh all

hadolint: ## Lint the Dockerfile with the official hadolint image
	sh tools/quality/hadolint-run.sh

actionlint: ## Lint the GitHub workflows with the official actionlint image
	sh tools/quality/actionlint-run.sh

# The local layers in the order of the assurance graph (README "Quality and security assurance"). Run it without -j: the
# order is the point. Hours, Docker and Java 25 needed. `make -k assurance` goes on after a failed layer. Schemathesis is
# pending (docs/quality.md), so it is not listed.
assurance: build quality semgrep security-static trivy hadolint actionlint test-fast test-slow mutation fuzz e2e zap oidc-conformance ## Every layer of the assurance chain, in order (hours); the CI equivalent is .github/workflows/assurance.yml
oidc-conformance: ## Floor42's OIDC sign-in against the OpenID Foundation conformance suite (Docker, Python 3, Java 25); docs/oidc-conformance.md
	$(MVNW) -q package -DskipTests -Dquarkus.quinoa=false -Dspotless.check.skip=true
	sh tools/conformance/suite.sh up && python3 tools/conformance/run.py; code=$$?; sh tools/conformance/suite.sh down; exit $$code

zap: build ## OWASP ZAP baseline against a test daemon (packaged jar, fake Claude Code); reports in target/zap
	sh tools/quality/zap-scan.sh

fuzz: ## Real coverage-guided fuzzing (Jazzer) of the parsers and guards, FUZZ_SECONDS each (default 120); crashes become seeds
	bin/floor42-fuzz $(FUZZ_TARGET)

api-fuzz: build ## Schemathesis contract fuzzing of the OpenAPI document against a test daemon; reports in target/api-fuzz
	sh tools/security/api-fuzz.sh

mutation: ## PIT mutation tests on the security code (slow); reports in target/pit-reports of each module
	$(MVNW) -Pquality -Dspotless.check.skip=true -Dquarkus.quinoa=false test-compile org.pitest:pitest-maven:mutationCoverage

e2e: build ## Browser journeys on a daemon of their own (installed Chrome; FLOOR42_E2E_BROWSER=msedge for Edge)
	cd floor42-web && npm run e2e

run: build ## Start the daemon from the build and open the office
	bin/floor42

doctor: ## What this machine lacks for Floor42, from the build (the daemon may be stopped); installs nothing
	bin/floor42 doctor

dev: floor42-web/node_modules/.package-lock.json ## Fast loop: dev mode on http://127.0.0.1:7421 (Java live reload, UI hot reload, the fake Claude Code)
	bin/floor42-dev

web-dev: install ## The UI alone with Vite, talking to a running daemon
	cd floor42-web && npx vite

demo-tape: ## Record the online demo's tape from a FRESH daemon (FLOOR42_URL, FLOOR42_TOKEN_FILE)
	node tools/demo/record.mjs

demo-build: install ## Build the online demo (the UI replaying the tape) into floor42-web/dist-demo
	cd floor42-web && npm run build:demo

demo-preview: demo-build ## Serve the online demo locally on http://localhost:4173
	cd floor42-web && npx vite preview --outDir dist-demo

docker-build: ## Build the Docker image with Claude Code, as floor42:local
	FLOOR42_COMMIT=$$(git rev-parse HEAD) FLOOR42_COMMIT_TIME=$$(git log -1 --format=%cI) \
	FLOOR42_COMMIT_DIRTY=$$(test -z "$$(git status --porcelain --untracked-files=no)" && echo false || echo true) \
	FLOOR42_IMAGE=$${FLOOR42_IMAGE:-floor42:local} docker compose build

docker-up: ## Start Floor42 in Docker and open it (needs FLOOR42_PROJECTS)
	bin/floor42-docker up

docker-login: ## Sign Claude Code in, inside the container (once)
	bin/floor42-docker login

docker-open: ## Open the office running in Docker
	bin/floor42-docker open

docker-logs: ## Follow the daemon's log in Docker
	bin/floor42-docker logs

docker-down: ## Stop Floor42 in Docker (data and sign-in are kept)
	bin/floor42-docker down

clean: ## Remove build outputs
	$(MVNW) -q clean
	rm -rf floor42-web/dist
