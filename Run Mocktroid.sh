#!/usr/bin/env bash
# Builds (if needed) and launches Mocktroid on Linux.
# Needs: a JDK 21+ (java on PATH or JAVA_HOME set) and Maven (mvn on PATH, or set MVN below).
# Optional: vgmstream-cli (sound previews) and ffmpeg (WebM videos, extra export formats) on PATH.
set -e
cd "$(dirname "$0")"

MVN="${MVN:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1; then
    echo "Maven not found. Install it (e.g. 'sudo apt install maven') or set MVN=/path/to/mvn."
    exit 1
fi
if [ -n "$JAVA_HOME" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null 2>&1; then
    echo "Java not found. Install a JDK 21 or newer (e.g. 'sudo apt install openjdk-21-jdk')."
    exit 1
fi

"$MVN" -q -f "source/pom.xml" compile javafx:run
