# Releases: what is published and how to verify it

A release is cut by pushing a tag `vX.Y.Z` (`.github/workflows/release.yml`). The tag's commit must set that version in
`pom.xml` and have a `## [X.Y.Z]` section in `CHANGELOG.md`. Set the same version in `floor42-web/package.json` by hand: the
workflow does not check it.

## The chain

1. `build`: format, build and every test (`./mvnw verify`), then the slow tests (`-Pslow`, below), then the zip, its checksum and the two SBOMs.
2. `security`: Trivy on the lock files, the SBOM, the Docker and compose files and the image
   (`.github/workflows/security-scan.yml`, [security-scanning.md](security-scanning.md)); a HIGH or CRITICAL finding that
   `.trivyignore` does not list (with a reason and an expiry) stops the release.
3. `publish`, only when both passed: it attests the zip and the SBOMs, creates the GitHub Release, pushes the image to
   `ghcr.io/padashtan/floor42` and attests it.

The browser journeys are not part of this chain: GitHub's runners have no GPU and the 3D scene is too slow there under
software WebGL. They are mandatory on the developer machine before every tag, on Windows (`make e2e`) and on Linux (WSL:
`FLOOR42_E2E_GPU=true`, see CLAUDE.md), against the packaged build of the tagged commit. In CI, the "Browser tests"
workflow (`.github/workflows/e2e.yml`) runs by hand only.

## First public release: repository settings

Three settings live on GitHub, not in the repository. Set them before announcing the release:

- Package `ghcr.io/padashtan/floor42`: visibility **public** (a package pushed for the first time is private), then
  `docker pull` it from a signed-out Docker.
- Pages: source **GitHub Actions**, for the demo (`demo.yml`).
- Security: **private vulnerability reporting** switched on (`SECURITY.md` and the issue template send reporters there).

A version with a hyphen (`1.1.0-rc.1`) is a prerelease: a GitHub prerelease, and the image keeps its own tag: `:latest`
moves only for a final version.

## The slow tests

`./mvnw verify` leaves out the tests tagged `@Tag("slow")`: the ones slow by nature (the real WSL sandbox, fake
sessions that wait for a real silence or reset timeout). They run with `./mvnw -pl floor42-server -am test -Pslow
-Dspotless.check.skip=true -Dquarkus.quinoa=false` (`make test-slow`), and only them. A release needs both: on a
developer machine `make release-gate` (= `verify`, `test-slow`, Vitest), in CI the "Slow tests" step of `release.yml`.
The WSL tests skip themselves on Linux CI, so run `make test-slow` on Windows before tagging.

## What a release holds

| File | What it is |
|---|---|
| `floor42-X.Y.Z.zip`, `.zip.sha256` | The daemon, its UI, the launch scripts. |
| `floor42-X.Y.Z-sbom-java.cdx.json` | CycloneDX 1.6 bill of materials of the Java modules (`cyclonedx-maven-plugin`, aggregate, no test scope). |
| `floor42-X.Y.Z-sbom-web.cdx.json` | CycloneDX 1.5 bill of materials of the web part's production dependencies (`npm sbom`, from the lock file). |
| `ghcr.io/padashtan/floor42:X.Y.Z` | The image (`floor42` target, without Claude Code; [docker.md](docker.md)). |

The zip, the two SBOMs and the image carry a signed build provenance (GitHub artifact attestation): which workflow, in
which repository, on which commit, built them. The SBOMs list what is in the build; the attestation says who built it.

## Verifying

You need the GitHub CLI (`gh`), signed in.

```sh
# The zip (and, the same way, an SBOM): provenance, then the checksum.
gh attestation verify floor42-X.Y.Z.zip --repo padashtan/floor42
sha256sum -c floor42-X.Y.Z.zip.sha256

# The image, by tag (the digest it resolves to is what is checked).
gh attestation verify oci://ghcr.io/padashtan/floor42:X.Y.Z --repo padashtan/floor42
```

A success names the workflow (`release.yml`) and the tag it ran on. To require both:
`--signer-workflow padashtan/floor42/.github/workflows/release.yml`. To use the image in production, pin its digest
(`docker buildx imagetools inspect ghcr.io/padashtan/floor42:X.Y.Z`), not the tag. No image signature other than the
attestation is made (no `cosign` step).

## Reproducible archives

Two builds of one commit give the same zip, byte for byte:

- `project.build.outputTimestamp` (`pom.xml`) fixes the time of the jar entries; the release passes the tagged commit's
  own time.
- `bin/package-release <version> [folder]` sets every file's time to `SOURCE_DATE_EPOCH` (the commit's time by default),
  adds the entries in byte order and uses `zip -X` (no owner ids or access times).

To check on your machine: build twice (`./mvnw -B -ntp clean package -DskipTests`), run `bin/package-release` after
each with the same `SOURCE_DATE_EPOCH`, and compare the `.sha256` files. The images are not byte-reproducible: their
base layers are pinned by digest (`Dockerfile`, updated by Dependabot's docker ecosystem), nothing more is claimed.

## Before a pull request is merged

`.github/workflows/dependency-review.yml` checks the dependencies a pull request adds or bumps against GitHub's
advisory database and fails on a known vulnerability of high severity or worse. It runs on pull requests only, which in
this repository means in practice the Dependabot ones: a change pushed straight to a branch is not checked by it.
