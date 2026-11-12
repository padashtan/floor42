#!/bin/sh
# Lints every workflow in .github/workflows with the official actionlint image (CI and `make actionlint`).
set -eu
cd "$(dirname "$0")/../.."
IMAGE=rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
exec docker run --rm -v "$PWD:/repo" -w /repo "$IMAGE" -color
