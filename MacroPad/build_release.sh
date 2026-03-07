#!/bin/bash
set -e

export JAVA_HOME=/home/kevin/.sdkman/candidates/java/current
export PATH="$JAVA_HOME/bin:$PATH"

cd /home/kevin/macro/MacroPad

# Clean build directories
rm -rf app/build .gradle build

# Stop any running daemons
./gradlew --stop 2>/dev/null || true

# Build with no daemon and single threaded to avoid issues
./gradlew assembleRelease bundleRelease \
    --no-daemon \
    -Dorg.gradle.parallel=false \
    -Dorg.gradle.workers.max=1

echo "Build complete!"

# Copy APK and AAB to project root
cp app/build/outputs/apk/release/app-release.apk ./MacroPad-v2.0.6-release.apk 2>/dev/null || true
cp app/build/outputs/bundle/release/app-release.aab ./MacroPad-v2.0.6-release.aab 2>/dev/null || true

ls -la *.apk *.aab 2>/dev/null
