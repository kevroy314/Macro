#!/bin/bash
set -e

export JAVA_HOME=/home/kevin/.sdkman/candidates/java/current
export PATH="$JAVA_HOME/bin:$PATH"

cd /home/kevin/macro/MacroPad

# Read the version straight out of the gradle config so releases can never be
# tagged with a stale number.
VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "Could not read versionName/versionCode from app/build.gradle.kts" >&2
    exit 1
fi
echo "Building MacroPad $VERSION_NAME (build $VERSION_CODE)"

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

APK="MacroPad-v${VERSION_NAME}-release.apk"
AAB="MacroPad-v${VERSION_NAME}-release.aab"

# Copy APK and AAB to project root
cp app/build/outputs/apk/release/app-release.apk "./$APK"
cp app/build/outputs/bundle/release/app-release.aab "./$AAB"

# Publish to the local AI daemon so the phone can update itself over the air.
RELEASES_DIR="../macropad-ai/data/releases"
if [ -d "../macropad-ai/data" ]; then
    mkdir -p "$RELEASES_DIR"
    RELEASE_FILE="MacroPad-${VERSION_NAME}-${VERSION_CODE}.apk"
    cp "./$APK" "$RELEASES_DIR/$RELEASE_FILE"

    NOTES="${RELEASE_NOTES:-}"
    VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" \
    RELEASE_FILE="$RELEASE_FILE" RELEASES_DIR="$RELEASES_DIR" NOTES="$NOTES" \
    python3 - <<'PY'
import hashlib, json, os, time
from pathlib import Path

releases_dir = Path(os.environ["RELEASES_DIR"])
index_path = releases_dir / "releases.json"
filename = os.environ["RELEASE_FILE"]
code = int(os.environ["VERSION_CODE"])

apk = releases_dir / filename
digest = hashlib.sha256()
with apk.open("rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(chunk)

entry = {
    "version_name": os.environ["VERSION_NAME"],
    "version_code": code,
    "file": filename,
    "notes": os.environ.get("NOTES", ""),
    "sha256": digest.hexdigest(),
    "published_at": int(time.time() * 1000),
}

try:
    existing = json.loads(index_path.read_text())
    if isinstance(existing, dict):
        existing = existing.get("releases", [])
except (OSError, json.JSONDecodeError):
    existing = []

# Rebuilding the same version replaces its entry rather than duplicating it.
merged = [r for r in existing if r.get("version_code") != code]
merged.append(entry)
merged.sort(key=lambda r: r.get("version_code", 0), reverse=True)

# Keep the last few builds around; older APKs are just disk.
keep = merged[:5]
index_path.write_text(json.dumps(keep, indent=2) + "\n")
for stale in merged[5:]:
    (releases_dir / stale["file"]).unlink(missing_ok=True)

print(f"Published {filename} to the AI daemon ({len(keep)} release(s) hosted)")
PY
else
    echo "macropad-ai/data not found; skipping release publish"
fi

ls -la *.apk *.aab 2>/dev/null
