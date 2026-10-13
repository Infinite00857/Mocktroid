#!/usr/bin/env bash
# Builds (if needed) and launches Mocktroid on macOS (double-click this file in Finder).
# Needs: a JDK 21+ and Maven -- with Homebrew: 'brew install openjdk@21 maven'.
# Optional: 'brew install vgmstream ffmpeg' for sound previews, WebM videos and extra export formats.
set -e
cd "$(dirname "$0")"

# Homebrew's bin folders aren't on PATH for Finder-launched scripts.
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"
fi
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
if [ -n "$JAVA_HOME" ]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi

MVN="${MVN:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1; then
    echo "Maven not found. Install it with 'brew install maven'."
    read -r -p "Press Enter to close."
    exit 1
fi

"$MVN" -q -f "source/pom.xml" compile javafx:run || read -r -p "Mocktroid exited with an error. Press Enter to close."
