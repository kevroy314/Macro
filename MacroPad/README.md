# MacroPad

Fast macro nutrient tracking Android app with home screen widgets.

## Features

- **Quick Macro Tracking**: Add protein, carbs, and fat with a single tap
- **Home Screen Widgets**:
  - **Macro Status Widget**: Displays current daily totals vs targets
  - **Increment Widget**: Quick +/- buttons for each macro
  - **Preset Widget**: Apply saved meal presets instantly
- **Daily Targets**: Set and track protein, carbs, and fat goals
- **Presets**: Save frequently eaten meals for quick logging
- **History**: View past days and add annotations
- **Day Reset Time**: Configure when your tracking day rolls over (e.g., 5am for night owls)
- **Dropbox Sync**: Backup and restore data across devices
- **Export/Import**: CSV and JSON export for data portability

## Version History

### v2.0 (Current Stable)
This is the first stable release with all core features working reliably.

**Key fixes in v2.0:**
- **Widget Update Fix**: Widgets now reliably update when macros are added via increment or preset widgets
  - Implemented `PreferencesGlanceStateDefinition` pattern to force Glance to recognize state changes
  - Widget data is written to preferences state before triggering update, ensuring Glance recomposes with fresh data
- **Day Reset Time Setting**: Now visible as a dedicated card in Settings (was previously hidden in a dialog)
- **Day Reset Hour**: All date calculations now properly respect the configured day reset hour

**Architecture Notes (for future reference):**
- The widget update issue was caused by Glance not re-running `provideGlance()` when `update()` was called
- Solution: Use `updateAppWidgetState()` to write data INTO the widget's `PreferencesGlanceStateDefinition`, then call `update()`. Glance detects the preferences change and triggers a recomposition.
- Key files: `MacroStatusWidget.kt`, `IncrementWidget.kt`, `PresetWidget.kt`

### Previous Versions
- v1.x: Development versions with various bug fixes and feature additions

## Tech Stack

- **UI**: Jetpack Compose
- **Widgets**: Jetpack Glance
- **Database**: Room
- **Cloud Sync**: Dropbox SDK
- **Background Work**: WorkManager

## Building

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0 (API 26) or higher
- Java 17 for building
