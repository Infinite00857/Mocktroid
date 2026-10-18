#!/usr/bin/env bash
# Builds (if needed) and launches Mocktroid on macOS (double-click this file in Finder).
# Needs: a JDK 21+ ('brew install openjdk@21'). Maven is optional: without it the bundled wrapper downloads it.
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

# An installed Maven, else the bundled wrapper (downloads Maven once).
if [ -z "$MVN" ]; then
    if command -v mvn >/dev/null 2>&1; then MVN=mvn; else MVN="$PWD/source/mvnw"; chmod +x "$MVN" 2>/dev/null || true; fi
fi

"$MVN" -q -f "source/pom.xml" compile javafx:run || read -r -p "Mocktroid exited with an error. Press Enter to close."
