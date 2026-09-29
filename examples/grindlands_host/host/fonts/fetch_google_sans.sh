#!/usr/bin/env sh
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUT="$DIR/GoogleSans-Regular.ttf"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/openrpgator-google-sans.XXXXXX")"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

if [ ! -d "$DIR" ] || [ ! -w "$DIR" ]; then
    echo "Google Sans: font directory is not writable: $DIR" >&2
    echo "If the project is on Termux shared storage, run this script from a writable project directory." >&2
    exit 1
fi

if command -v curl >/dev/null 2>&1; then
    FETCH='curl -fsSL -L'
elif command -v wget >/dev/null 2>&1; then
    FETCH='wget -qO-'
else
    echo "Google Sans: install curl or wget first." >&2
    exit 1
fi

# Avoid the GitHub Releases API: it can reject unauthenticated/rate-limited
# requests from Termux. The public release page exposes the same asset links.
RELEASE_HTML="$TMP/release.html"
if command -v curl >/dev/null 2>&1; then
    curl -fsSL -L https://github.com/googlefonts/googlesans/releases/latest -o "$RELEASE_HTML"
else
    wget -qO "$RELEASE_HTML" https://github.com/googlefonts/googlesans/releases/latest
fi

URL=$(sed 's/[<>" ]/\n/g' "$RELEASE_HTML" \
    | grep -E 'https://github\.com/googlefonts/googlesans/releases/download/[^"[:space:]]+\.zip' \
    | head -n 1 || true)

if [ -z "$URL" ]; then
    echo "Google Sans: could not find a release ZIP on the latest release page." >&2
    exit 1
fi

ARCHIVE="$TMP/google-sans.zip"
if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ARCHIVE"
else
    wget -qO "$ARCHIVE" "$URL"
fi

EXTRACT="$TMP/ttf"
mkdir -p "$EXTRACT"

if command -v unzip >/dev/null 2>&1; then
    unzip -j -o "$ARCHIVE" '*.ttf' -d "$EXTRACT" >/dev/null
elif command -v python3 >/dev/null 2>&1; then
    python3 - "$ARCHIVE" "$EXTRACT" <<'PY'
import sys, zipfile, os
archive, out = sys.argv[1:]
with zipfile.ZipFile(archive) as z:
    for name in z.namelist():
        if name.lower().endswith('.ttf'):
            with z.open(name) as src, open(os.path.join(out, os.path.basename(name)), 'wb') as dst:
                dst.write(src.read())
PY
else
    echo "Google Sans: install unzip (recommended) or python3 to extract the release archive." >&2
    exit 1
fi

FONT=$(find "$EXTRACT" -type f -iname '*GoogleSans*Regular*.ttf' -print -quit 2>/dev/null || true)
[ -n "$FONT" ] || FONT=$(find "$EXTRACT" -type f -iname '*GoogleSans*.ttf' -print -quit 2>/dev/null || true)
[ -n "$FONT" ] || { echo "Google Sans: no suitable TTF found in release archive." >&2; exit 1; }

# Copy to a temporary file in the destination directory, then rename it so a
# failed download never leaves a half-written font visible to the server.
STAGE="$DIR/.GoogleSans-Regular.ttf.tmp"
cp "$FONT" "$STAGE"
mv -f "$STAGE" "$OUT"
printf 'Installed %s\n' "$OUT"
