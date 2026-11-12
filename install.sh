#!/bin/sh
# Floor42 installer for Linux and macOS (POSIX sh, no sudo, nothing outside your home folder).
#   curl -fsSL https://raw.githubusercontent.com/padashtan/floor42/v<version>/install.sh | sh                 install the latest release
#   curl -fsSL https://raw.githubusercontent.com/padashtan/floor42/v<version>/install.sh | sh -s -- --uninstall
# It checks that Java 25 is there (it never installs a JDK or a system package), downloads the release zip, verifies
# its SHA-256, puts the daemon in ~/.floor42/app and a launcher in ~/.local/bin/floor42. Run it again to upgrade.
#
# Environment:
#   FLOOR42_REPO      GitHub repository "owner/name" the release comes from (default padashtan/floor42)
#   FLOOR42_VERSION   version to install, e.g. 1.17.0 (default: the latest release of FLOOR42_REPO)
#   FLOOR42_BASE_URL  folder that holds floor42-<version>.zip and its .sha256, instead of GitHub (file:// works)
#   FLOOR42_JAVA      the java to check, else JAVA_HOME, else java on the PATH
#   FLOOR42_BIN_DIR   where the launcher goes (default ~/.local/bin)
# The uninstall removes the program and the launcher and keeps your data (~/.floor42: journal, settings, workspaces).
set -eu

# Everything runs from main, called on the last line: a download cut short by `curl | sh` runs nothing.
main() {
REPO=${FLOOR42_REPO:-padashtan/floor42}
DATA_DIR=$HOME/.floor42
APP_DIR=$DATA_DIR/app
BIN_DIR=${FLOOR42_BIN_DIR:-$HOME/.local/bin}
LAUNCHER=$BIN_DIR/floor42

say() { printf '%s\n' "$*"; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

if [ "${1:-}" = "--uninstall" ]; then
  rm -rf "$APP_DIR"
  rm -f "$LAUNCHER"
  say "Floor42 program and launcher removed."
  say "Your data is kept in $DATA_DIR: delete it yourself if you want it gone."
  exit 0
fi
[ $# -eq 0 ] || die "unknown argument: $1 (only --uninstall is supported)"

case "$(uname -s)" in
  Linux) ;;
  Darwin) say "macOS: untested. Floor42 has no pseudo-terminal support there yet, so the director's terminal will not open." ;;
  *) die "unsupported system $(uname -s). On Windows use bin\\floor42.ps1 from the release zip." ;;
esac
say "System: $(uname -s) $(uname -m)"

command -v curl > /dev/null 2>&1 || die "curl is required."
command -v unzip > /dev/null 2>&1 || die "unzip is required (install it with your package manager)."
if command -v sha256sum > /dev/null 2>&1; then sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum > /dev/null 2>&1; then sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else die "sha256sum or shasum is required."
fi

# Java 25 or later: the first of FLOOR42_JAVA, JAVA_HOME, PATH that answers.
java_ok=""
for candidate in "${FLOOR42_JAVA:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" java; do
  [ -n "$candidate" ] || continue
  major=$("$candidate" -version 2>&1 | head -n 1 | sed -n 's/.*"\([0-9][0-9]*\).*/\1/p') || continue
  if [ "${major:-0}" -ge 25 ] 2> /dev/null; then java_ok=$candidate; break; fi
done
if [ -z "$java_ok" ]; then
  say "Java 25 or later was not found. Floor42 needs it, and this installer does not install it."
  say "Get a JDK 25 from your distribution's packages or from https://adoptium.net, then either put it on the PATH,"
  say "or set JAVA_HOME or FLOOR42_JAVA, and run this installer again."
  exit 1
fi
say "Java: $java_ok"

if [ -n "${FLOOR42_BASE_URL:-}" ]; then
  [ -n "${FLOOR42_VERSION:-}" ] || die "FLOOR42_BASE_URL needs FLOOR42_VERSION."
  BASE=${FLOOR42_BASE_URL%/}
  VERSION=$FLOOR42_VERSION
else
  VERSION=${FLOOR42_VERSION:-}
  if [ -z "$VERSION" ]; then
    tag_url=$(curl -fsSIL -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest") \
      || die "cannot find the latest release of $REPO."
    VERSION=${tag_url##*/}
    VERSION=${VERSION#v}
  fi
  BASE=https://github.com/$REPO/releases/download/v$VERSION
fi
case "$VERSION" in *[!0-9A-Za-z.+-]* | "") die "invalid version: $VERSION" ;; esac

NAME=floor42-$VERSION
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
say "Downloading $NAME.zip"
curl -fsSL -o "$work/$NAME.zip" "$BASE/$NAME.zip" || die "download failed: $BASE/$NAME.zip"
curl -fsSL -o "$work/$NAME.zip.sha256" "$BASE/$NAME.zip.sha256" || die "download failed: $BASE/$NAME.zip.sha256"

want=$(cut -d' ' -f1 "$work/$NAME.zip.sha256")
have=$(sha256 "$work/$NAME.zip")
[ -n "$want" ] && [ "$want" = "$have" ] || die "checksum mismatch for $NAME.zip: expected '$want', got '$have'. Nothing installed."
say "SHA-256 verified."

unzip -q "$work/$NAME.zip" -d "$work/x"
[ -f "$work/x/$NAME/quarkus-app/quarkus-run.jar" ] || die "the archive does not hold quarkus-app/quarkus-run.jar."
[ -f "$work/x/$NAME/bin/floor42" ] || die "the archive does not hold bin/floor42."

if curl -fs -o /dev/null --max-time 2 http://127.0.0.1:7420/api/status; then
  say "A Floor42 daemon is running: restart it after this upgrade to load the new version."
fi
mkdir -p "$DATA_DIR" "$BIN_DIR"
rm -rf "$APP_DIR.new"
cp -R "$work/x/$NAME/quarkus-app" "$APP_DIR.new"
rm -rf "$APP_DIR"
mv "$APP_DIR.new" "$APP_DIR"
cp "$work/x/$NAME/bin/floor42" "$LAUNCHER"
chmod 755 "$LAUNCHER"
say "Installed Floor42 $VERSION: program in $APP_DIR, launcher $LAUNCHER"

case ":$PATH:" in
  *":$BIN_DIR:"*) say "Run: floor42" ;;
  *) say "Add $BIN_DIR to your PATH, then run: floor42" ;;
esac
say "Check what this machine lacks with: floor42 doctor"
}

main "$@"
