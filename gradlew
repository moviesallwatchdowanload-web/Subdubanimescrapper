#!/usr/bin/env sh
GRADLE_VERSION=8.7
DIST_URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_HOME_DIR="$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION"

if [ ! -d "$GRADLE_HOME_DIR" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    mkdir -p "$HOME/.gradle/wrapper/dists"
    mkdir -p "$HOME/.gradle"
    curl -L "$DIST_URL" -o "$HOME/.gradle/gradle.zip"
    unzip -q "$HOME/.gradle/gradle.zip" -d "$HOME/.gradle/wrapper/dists/"
    rm -f "$HOME/.gradle/gradle.zip"
fi

BIN_DIR=$(find "$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION" -name "bin" -type d | head -n 1)
exec "$BIN_DIR/gradle" "$@"
