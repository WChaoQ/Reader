#!/bin/sh
# Gradle start up script. Downloads gradle-wrapper.jar if missing.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar not found; downloading..."
  mkdir -p "$DIR/gradle/wrapper"
  URL="https://github.com/gradle/gradle/raw/v9.6.0/gradle/wrapper/gradle-wrapper.jar"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$JAR" "$URL" || exit 1
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$JAR" "$URL" || exit 1
  else
    echo "Need curl or wget to download gradle-wrapper.jar" >&2
    exit 1
  fi
fi
exec java -jar "$JAR" "$@"
