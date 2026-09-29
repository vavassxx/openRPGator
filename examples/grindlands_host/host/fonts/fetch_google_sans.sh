#!/bin/sh
set -eu
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP="$DIR/.google-sans-download"
mkdir -p "$TMP"
JSON=$(curl -fsSL https://api.github.com/repos/googlefonts/googlesans/releases/latest)
URL=$(printf '%s' "$JSON" | python3 -c 'import json,sys; d=json.load(sys.stdin); a=next((x for x in d["assets"] if x["name"].lower().endswith(".zip")),None); print(a["browser_download_url"] if a else "")')
[ -n "$URL" ] || { echo "Could not find a Google Sans release archive" >&2; exit 1; }
curl -fL "$URL" -o "$TMP/google-sans.zip"
unzip -j -o "$TMP/google-sans.zip" '*.ttf' -d "$TMP/ttf" >/dev/null
FONT=$(find "$TMP/ttf" -type f -iname '*GoogleSans*Regular*.ttf' | head -n1)
[ -n "$FONT" ] || FONT=$(find "$TMP/ttf" -type f -iname '*GoogleSans*.ttf' | head -n1)
[ -n "$FONT" ] || { echo "No Google Sans TTF found in release" >&2; exit 1; }
cp "$FONT" "$DIR/GoogleSans-Regular.ttf"
rm -rf "$TMP"
printf 'Installed %s\n' "$DIR/GoogleSans-Regular.ttf"
