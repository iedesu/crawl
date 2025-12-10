#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
TARGET="$ROOT/luaj-jse-3.0.1.jar"
if [ -f "$TARGET" ]; then
  echo "LuaJ jar already present at $TARGET"
  exit 0
fi
URL="https://github.com/luaj/luaj/raw/master/luaj-jse/target/luaj-jse-3.0.1.jar"
FALLBACK="https://repo1.maven.org/maven2/org/luaj/luaj-jse/3.0.1/luaj-jse-3.0.1.jar"
mkdir -p "$ROOT"
if curl -L --fail --output "$TARGET" "$URL"; then
  echo "Fetched LuaJ from upstream repository"
  exit 0
fi
curl -L --fail --output "$TARGET" "$FALLBACK"
echo "Fetched LuaJ from Maven Central fallback"
