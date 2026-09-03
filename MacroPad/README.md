# MacroPad — Android app

The app itself. For what MacroPad is, screenshots, and the optional self-hosted AI
features, see the [repository README](../README.md) and the
[documentation site](https://kevroy314.github.io/Macro/).

## Building

Requires **Java 17** and the Android SDK (compileSdk 35, minSdk 26). If your system Java
is older, point `JAVA_HOME` at a 17 install for the build only.

```bash
JAVA_HOME=/path/to/java17 ./gradlew assembleRelease

# or, to sign and publish to a configured daemon in one step:
RELEASE_NOTES="what changed" ./build_release.sh
```

## Before you open a pull request

```bash
JAVA_HOME=/path/to/java17 ./gradlew testReleaseUnitTest   # unit tests
/usr/bin/python3 tools/verify_migrations.py               # if you touched the schema
```

`verify_migrations.py` replays every migration from a seeded baseline and diffs the result
against the schema Room generates from the entities. This database is the only copy of
months of someone's food log and there is deliberately no destructive fallback, so a
schema change that doesn't verify is a schema change that isn't ready.

Contribution rules, including when a feature needs a Settings flag, are in
[CONTRIBUTING.md](../CONTRIBUTING.md).

## Layout

```
app/src/main/java/com/macropad/app/
  data/          Room entities, DAOs, migrations, MacroRepository
  ai/            the optional AI estimator, planning, updates, discovery
  net/           AiClient — everything that talks to the daemon
  sync/          backup formats, Dropbox, server backup, merge planning
  ui/screens/    Compose screens
  ui/widgets/    Glance home screen widgets
```
