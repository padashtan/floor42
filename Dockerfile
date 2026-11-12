# Floor42 in a container, for Docker Engine in WSL 2 or on Linux (docs/docker.md).
#
# The container is also a confinement: agents' shell commands run inside it and see only the folders mounted into it.
# The daemon listens on all interfaces inside the container; publish it on the host's loopback only
# (127.0.0.1:7420:7420), as compose.yaml does: the daemon refuses any Host that is not a loopback name.

# ---- build: Java 25 and Node 24 (Quinoa builds the web UI with the system npm) ----
# Base images are pinned by digest (the multi-platform index; looked up 2026-11-01 with `docker buildx imagetools
# inspect <tag>`). Dependabot's docker ecosystem proposes the new tag and digest together.
FROM node:24.22.2-bookworm-slim@sha256:01d566e8d94d8c619591d78652babfccd3c6992a3dffa9ed5ab39e11cf6c70e9 AS node

FROM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm
WORKDIR /src
# Dependencies first, so a source change does not download them again.
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY floor42-domain/pom.xml floor42-domain/
COPY floor42-server/pom.xml floor42-server/
# Only a cache: whatever go-offline misses (some plugins) is downloaded by the package step below.
RUN ./mvnw -B -ntp -q dependency:go-offline -pl floor42-domain,floor42-server || true
COPY floor42-web/package.json floor42-web/package-lock.json floor42-web/
WORKDIR /src/floor42-web
RUN npm ci --no-audit --no-fund
WORKDIR /src
COPY . .
# The commit the image is built from: no .git reaches the build, so whoever builds passes it (make docker-build,
# bin/floor42-docker, the release workflow). /api/status then names it (build.commit); without it, null, never guessed.
ARG FLOOR42_COMMIT=""
ARG FLOOR42_COMMIT_TIME=""
ARG FLOOR42_COMMIT_DIRTY=""
RUN sh bin/build-commit-properties floor42-server/src/main/resources \
    && ./mvnw -B -ntp -q package -DskipTests

# ---- Claude Code: a native binary; npm only fetches it, so neither npm, its cache nor Node reach the runtime ----
FROM node AS claude
ARG CLAUDE_CODE_VERSION=latest
RUN npm install -g --no-audit --no-fund "@anthropic-ai/claude-code@${CLAUDE_CODE_VERSION}" \
    && cp "$(readlink -f "$(command -v claude)")" /claude \
    && /claude --version

# ---- floor42: a JRE, git and the daemon, without Claude Code ----
# The image published to ghcr.io/padashtan/floor42 (`--target floor42`): the office and the simulated demo. Claude Code
# is Anthropic's software under its own terms: it is never redistributed in a published image.
FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0 AS floor42
LABEL org.opencontainers.image.title="Floor42" \
      org.opencontainers.image.description="A pixel-art office where you direct real Claude Code agents" \
      org.opencontainers.image.source="https://github.com/padashtan/floor42" \
      org.opencontainers.image.licenses="Apache-2.0"
# Unpinned on purpose: the packages follow the base image's Debian release, a pinned version would break the build at
# each security update, and the base image is already pinned by digest.
# apt itself is upgraded first: the base image carries apt 3.2.0, which CVE-2026-109518 (remote code execution by hard
# link reuse during a download, fixed in 3.2.1ubuntu0.1) affects. Drop that step once the base image carries the fix.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade apt libapt-pkg7.0 \
    && apt-get install -y --no-install-recommends git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
# A plain user 1000 in no other group (the base image's `ubuntu` is in sudo and adm): files the agents write in mounted
# folders belong to the usual WSL user.
# Floor42's data directory; compose.yaml keeps the whole home in the office-home volume (data and Claude sign-in). No
# VOLUME here: a volume nested in that one would hold the data instead, and lose it at the next `compose down`.
RUN userdel -r ubuntu \
    && useradd -m -d /home/office -u 1000 -U -s /bin/bash floor42 \
    && install -d -o floor42 -g floor42 -m 0755 /home/office/.floor42 \
    && git config --system safe.directory '*'
# Agents run as uid 1000, the daemon's own user (ADR-0044): nothing the daemon reads at start may be theirs to write.
# The application belongs to root (directories 0755, files 0644), so an agent cannot replace the jar or a library; only
# the home volume is writable by uid 1000. The daemon runs from /opt/floor42, root-owned too: Quarkus reads `.env` and
# `config/` from its working directory, and an agent could write those in /home/office (ConfigGuard also refuses them).
# The build stage wrote them as root with umask 022: COPY keeps those modes (checked in the image, docs/docker.md).
COPY --from=build --chown=root:root /src/floor42-server/target/quarkus-app /opt/floor42
WORKDIR /opt/floor42
USER floor42
# Claude Code updates come with a new image, not from inside a running container.
# The sign-in link goes to the container's standard output: the host reads it (docker compose logs), agents inside
# the container do not (ADR-0034).
ENV QUARKUS_HTTP_HOST=0.0.0.0 \
    FLOOR42_DATA_DIR=/home/office/.floor42 \
    FLOOR42_SIGN_IN_DELIVERY=stdout \
    DISABLE_AUTOUPDATER=1
EXPOSE 7420
# The shell form is needed for `|| exit 1`: Docker reads only 0 and 1 as the probe's result.
# hadolint ignore=DL3025
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s CMD curl -fs -H 'Host: 127.0.0.1' http://127.0.0.1:7420/api/status || exit 1
# No dynamic attach: non-dumpable does not cover the JVM's attach mechanism, through which a same-user process (an
# agent) could load code into the daemon (/tmp/.attach_pid1 + SIGQUIT, checked on 2026-11-01). /api/status says.
ENTRYPOINT ["java", "-XX:+DisableAttachMechanism", "-Djdk.httpclient.allowRestrictedHeaders=host", "-jar", "/opt/floor42/quarkus-run.jar"]

# ---- with-claude-code: the default target, for real agents; built on your machine (make docker-up), never published ----
FROM floor42 AS with-claude-code
COPY --from=claude /claude /usr/local/bin/claude
