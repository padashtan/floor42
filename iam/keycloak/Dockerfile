# Keycloak for Floor42's IAM mode: the stock image plus the Floor42 protocol mappers (iam/keycloak-extensions).
# providers/ holds the mapper jar once built; it is empty (only .gitkeep) until then.
# Pinned by digest (the multi-platform index, read from quay.io on 2026-11-12), like the base images of /Dockerfile.
FROM quay.io/keycloak/keycloak:26.8.2-2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0 AS builder
ENV KC_DB=postgres
COPY providers/ /opt/keycloak/providers/
RUN test -f /opt/keycloak/providers/floor42-keycloak-extensions.jar \
    || { echo "missing iam/keycloak/providers/floor42-keycloak-extensions.jar: build iam/keycloak-extensions first (see iam/README.md)" >&2; exit 1; }
RUN /opt/keycloak/bin/kc.sh build --db=postgres

FROM quay.io/keycloak/keycloak:26.8.2-2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
# The base image already runs as uid 1000 (keycloak); stated here so a scan (Trivy DS-0002) sees it and a change of the
# base image cannot silently bring root back.
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
