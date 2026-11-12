# Keycloak for Floor42's IAM mode: the stock image plus the Floor42 protocol mappers (iam/keycloak-extensions).
# providers/ holds the mapper jar once built; it is empty (only .gitkeep) until then.
FROM quay.io/keycloak/keycloak:26.8.1 AS builder
ENV KC_DB=postgres
COPY providers/ /opt/keycloak/providers/
RUN test -f /opt/keycloak/providers/floor42-keycloak-extensions.jar \
    || { echo "missing iam/keycloak/providers/floor42-keycloak-extensions.jar: build iam/keycloak-extensions first (see iam/README.md)" >&2; exit 1; }
RUN /opt/keycloak/bin/kc.sh build --db=postgres

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=builder /opt/keycloak/ /opt/keycloak/
# The base image already runs as uid 1000 (keycloak); stated here so a scan (Trivy DS-0002) sees it and a change of the
# base image cannot silently bring root back.
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
