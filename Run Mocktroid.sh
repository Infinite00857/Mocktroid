#!/usr/bin/env bash
# Builds (if needed) and launches Mocktroid on Linux.
# Needs: a JDK 21+ (java on PATH or JAVA_HOME set). Maven is optional: without it the bundled wrapper downloads it.
# Optional: vgmstream-cli (sound previews) and ffmpeg (WebM videos, extra export formats) on PATH.
set -e
cd "$(dirname "$0")"

# An installed Maven, else the bundled wrapper (downloads Maven once).
if [ -z "$MVN" ]; then
    if command -v mvn >/dev/null 2>&1; then MVN=mvn; else MVN="$PWD/source/mvnw"; chmod +x "$MVN" 2>/dev/null || true; fi
fi
if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null 2>&1; then
    echo "Java not found. Install a JDK 21 or newer (e.g. 'sudo apt install openjdk-21-jdk')."
    exit 1
fi

"$MVN" -q -f "source/pom.xml" compile javafx:run
