# Contributing to MacroPad

MacroPad is a macro tracker that people use several times a day, every day, on their
home screen. Its whole appeal is that it stays out of the way. That shapes what gets
merged: a change that makes the app more capable but more talkative, or that moves
something someone's thumb already knows how to find, is not automatically an
improvement.

This document is the part that isn't obvious from reading the code.

## Approval

**[@kevroy314](https://github.com/kevroy314) is the maintainer and approves features.**
Open an issue before building anything large so the shape can be agreed first — it's
cheaper than a rewrite after review.

Ordinary work — a bug fix, a new preset control, a widget layout tweak, a performance
fix, documentation — needs nothing beyond a PR and review.

### Significant features need a feature flag, defaulted off

Some changes are approved *and* still ship behind a switch in Settings that starts
disabled. Everyone who already has the app installed gets an update; nobody asked for
their app to start behaving differently overnight. A flag means the maintainer turns it
on deliberately, on one device, and can turn it off without a release.

Treat a feature as significant if it does any of these:

- **Speaks without being spoken to** — notifications, reminders, nudges, badges, any
  alert the user did not directly ask for by tapping something.
- **Changes navigation or information architecture** — a new bottom-nav destination, a
  reordered tab bar, a new default screen, moving an existing control somewhere else.
- **Changes what the dashboard shows by default.** The dashboard is the thing people
  see fifty times a day. Additions there are opt-in.
- **Spends the user's money or quota on their behalf** — anything that calls the AI
  daemon on its own schedule rather than in response to a tap.
- **Writes their data in a new way, or sends it anywhere.** New sync targets, new
  export destinations, new columns that other features start depending on.

If you're unsure, assume it needs a flag and say so in the PR; being told it doesn't is
a two-line review comment. Ship it unflagged only when the maintainer says so
explicitly.

The exception is a fix. If a feature is already on and it's broken, fixing it is not a
new feature and does not need a flag.

### How to add a flag

The codebase already works this way — the entire AI estimator is gated on
`AiSettings.enabled`, which is `false` until someone fills in a server, and the
self-updater is gated on `AiSettings.isConfigured`. Follow that:

1. Add a `Boolean` to the relevant singleton settings entity (`WidgetSettings` for app
   behaviour, `AiSettings` for anything daemon-related, `PresetDisplaySettings` for
   list presentation) with **`= false`** as the default.
2. Add the column in a Room migration with `DEFAULT 0`. Never rely on the Kotlin
   default to populate existing rows — see *Migrations* below.
3. Add the toggle to `SettingsScreen`, in the card the feature belongs to, with a line
   of body text saying what turning it on does.
4. Gate the feature at the point where it becomes visible or starts work — the
   composable, the `WorkManager` enqueue, the notification post. Not deep inside a
   helper, where a future caller will miss it.
5. Say in the PR what the flag is called and what happens when it's off.

A flag is a commitment to the *off* path working. Both states have to be correct: off
means the feature is inert, not half-initialised.

## Layout

```
MacroPad/       the Android app (Kotlin, Compose, Room, Glance widgets)
macropad-ai/    the self-hosted estimation daemon (FastAPI + Claude Agent SDK)
docs/           the GitHub Pages site and store assets
.claude/skills/ agent instructions, including the server setup walkthrough
```

If you have Claude Code, opening this repo and saying *"set up the MacroPad server for
me"* loads `.claude/skills/setup-macropad-server`, which does the install. Keep that skill
in step with `docs/self-hosting.md` — they describe the same process to two different
audiences, and a change to one usually needs a change to the other.

The app works completely without the daemon. That is not an accident and shouldn't
become one — the AI features are an optional add-on for people who want to run a
server, and every code path in the app has to behave when there isn't one.

## Building and verifying

The app needs **Java 17**. If your system Java is older, point `JAVA_HOME` at a 17
install for the build only:

```bash
cd MacroPad
JAVA_HOME=/path/to/java17 ./gradlew assembleRelease
./build_release.sh                 # signs, and publishes to the daemon if one is configured
```

Before opening a PR:

```bash
cd MacroPad
JAVA_HOME=/path/to/java17 ./gradlew testReleaseUnitTest   # unit tests
/usr/bin/python3 tools/verify_migrations.py               # if you touched the schema
```

`verify_migrations.py` replays every migration from a seeded baseline and diffs the
result against the schema Room generates from the entities, checking column types,
nullability, indices, and that no rows are lost. It catches the class of mistake that
silently corrupts someone's history, so run it on any schema change.

### What can't be tested here

There is no device or emulator in this loop, so **Compose UI changes ship unverified by
automated means**. Say so in your PR rather than implying otherwise, and describe what
you checked by hand. Logic that can be pulled out of a composable into a pure function
should be — `BackupMerge` and `PresetSearch` both exist as plain objects with unit tests
for exactly this reason, and that's the pattern to copy for anything where being wrong
costs the user data.

## Invariants

These are all things that have already gone wrong once. Please don't re-derive them.

### Migrations

Never add `fallbackToDestructiveMigration()`. It turns a schema mistake into silent
total data loss, and this app is the only copy of months of someone's food log.

Every schema change needs a hand-written migration whose result exactly matches what
Room generates from the entities — same types, same nullability, same indices. Add
columns with an explicit SQL `DEFAULT`; Kotlin default arguments do not reach existing
rows. Then run the verifier.

### Backups

`BackupData` is versioned (currently 4). When you add a field:

- **Declare it nullable with a normalising accessor.** Gson allocates the object
  without running the Kotlin constructor, so a field missing from an older backup lands
  as `null` no matter what `= emptyList()` says, and the first loop over it throws. See
  `presetList`, `entryList`, `dailyMacroList`.
- Bump `BackupData.CURRENT_VERSION`, and keep reading the previous version.

**Restore fills gaps; it never mirrors.** A day already on the phone is left alone.
Overwriting it with the backup's totals drops anything logged since the backup was
taken and leaves the day's total disagreeing with the entries listed under it in
History. Both restore and the Dropbox migration go through `BackupMerge.plan`, which is
pure and unit-tested — put new merge behaviour there, with a test, not inline in a
screen.

Entries are only inserted for days being added, or they double-count. Entry identity
across devices is `date|timestamp|protein|carbs|fat`, which is what makes a restore
idempotent.

### Preset ids are load-bearing

`preset_usages` (the 7-day sort) and `AiJob.presetId` both reference preset ids. Never
clear the table and reinsert — match presets by trimmed, lowercased name and update in
place.

### The agent's tool allowlist is the security boundary

The daemon runs a Claude Code session in a container that holds live credentials, on
input that includes photographs of text a stranger could have written. The agent gets
`Read`, `WebSearch`, `WebFetch` and nothing else: no shell, no writes, no subagents.
`Read` is confined to the job's own directory and `WebFetch` is blocked from private,
loopback, and link-local hosts, enforced by the `can_use_tool` callback in `runner.py`
and again by the CLI's tool set.

Widening that allowlist is a security change, not a feature. Don't, without saying
plainly in the PR what new input can now reach what.

### Per-user isolation

Two phones can point at one daemon. Every job, thread, and backup is owned by whoever
created it, and every read is filtered by owner. Cross-user access returns **404, not
403** — 403 confirms the row exists. Any new owned resource follows the same rule, in
both directions, and gets tested in both directions.

### The signing key

`MacroPad/macropad-release-key.jks` signs releases, and the in-app updater verifies
downloads against it. Anyone holding that file can sign an update this app will install
as genuine. It is git-ignored and must stay that way.

Credentials come from `MacroPad/keystore.properties`, which is git-ignored — copy
`keystore.properties.example` and fill it in. They are deliberately *not* in
`build.gradle.kts`: they used to be, in a file tracked in a public repository, and that
published password has since been changed. The signing key itself was never committed
and is unchanged.

Never put a password back into a tracked file, and never relax the ignore rules for
`*.jks`, `*.jks.*`, or `keystore.properties`.

### Google Play

An app distributed on Play may not update itself by another route. The in-app updater is
gated on an AI server being configured, which a Play install never has. Keep that gate.

### Glance widgets don't recompose just because you called `update()`

The widgets update by writing data *into* the widget's
`PreferencesGlanceStateDefinition` with `updateAppWidgetState()` and *then* calling
`update()`. Glance notices the preferences change and recomposes. Calling `update()`
alone does not re-run `provideGlance()`, and the widget silently keeps showing stale
numbers — which took a long time to work out the first time. See `MacroStatusWidget.kt`,
`IncrementWidget.kt`, `PresetWidget.kt`.

### Two small ones that cost real debugging time

- `MaterialTheme.colorScheme.outline` is this app's **secondary text colour**
  (`TextMuted`), used that way in dozens of places. It is not a border colour. Don't
  darken it.
- `BitmapFactory.decodeStream` returns `null` by design when `inJustDecodeBounds` is
  set, so `stream?.use { decode() } ?: return null` throws away every image. Check the
  stream and the result separately.

## Pull requests

- One concern per PR. A refactor bundled with a feature is two reviews at once.
- Match the surrounding code — its naming, its comment density, and its habit of
  explaining *why* rather than restating the line below.
- Comment the non-obvious. The reason a rule exists is the thing that gets lost.
- Say what you verified and what you didn't.
- Note any new permission, dependency, or network call explicitly.
