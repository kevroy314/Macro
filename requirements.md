# MacroPad (Working Name) -- Requirements

## Overview

MacroPad is an Android application designed for **fast macro nutrient
accounting** using **home‑screen widgets and programmable macro
shortcuts**.\
Unlike traditional nutrition trackers focused on food logging, MacroPad
focuses on **direct macro budgeting** (protein, carbs, fat) with minimal
friction.

Primary goals:

-   Extremely fast macro entry
-   Home screen widget driven workflow
-   Programmable macro shortcut buttons
-   Offline‑first architecture
-   Simple exportable data
-   Visual feedback via graphs
-   Annotatable timeline of days

The app is intended for power users such as:

-   bodybuilders
-   strength athletes
-   keto / macro tracking enthusiasts
-   quantified‑self users

------------------------------------------------------------------------

# Core Concepts

## Macro Counters

Each day tracks:

  Field       Type
  ----------- ------------------
  date        ISO date
  protein_g   integer
  carbs_g     integer
  fat_g       integer
  calories    optional derived

Calories may be derived using:

    calories = protein*4 + carbs*4 + fat*9

------------------------------------------------------------------------

# Functional Requirements

## 1. Macro Logging

Users must be able to:

-   increment macros
-   decrement macros
-   log preset macro meals
-   manually edit totals

### Example actions

    +60 protein
    +30 carbs
    +10 fat
    -10 fat

These actions must work:

-   inside the app
-   from widgets

------------------------------------------------------------------------

## 2. Preset Macro Shortcuts

Users must be able to create configurable shortcut buttons.

Example:

  Name           Protein   Carbs   Fat
  -------------- --------- ------- -----
  Protein Meal   60        35      5
  Shake          50        5       2
  Rice Portion   0         40      0

Pressing a shortcut should immediately add those macros.

Requirements:

-   unlimited presets
-   editable
-   deletable
-   usable from widgets

------------------------------------------------------------------------

## 3. Widgets

Widgets are a **primary interaction model**.

### 3.1 Macro Status Widget

Displays the current day totals.

Example:

    Protein: 134 / 180
    Carbs:   212 / 250
    Fat:      51 / 70
    Calories: 2150

Optional:

-   progress bars
-   color thresholds

Size targets:

-   2x2
-   4x2

------------------------------------------------------------------------

### 3.2 Increment Widget

Widget containing macro increment buttons.

Example:

    +60P
    +30C
    +10F
    -10F

Pressing a button:

-   logs macros immediately
-   updates all widgets

------------------------------------------------------------------------

### 3.3 Preset Widget

Widget showing user‑configured macro shortcuts.

Example:

    Protein Meal
    Shake
    Rice Portion

Pressing the button applies the preset.

------------------------------------------------------------------------

## 4. Graphing

Users should be able to visualize macro totals over time.

### Graph Types

-   daily protein
-   daily carbs
-   daily fat
-   calories
-   macro ratios (optional)

Time ranges:

-   7 days
-   30 days
-   90 days
-   custom

Graphs should be:

-   smooth
-   zoomable (optional)
-   scrollable

------------------------------------------------------------------------

## 5. Day Annotations

Users can attach notes to specific days.

Examples:

    Went to party
    Goal day
    Travel day
    High carb refeed

Annotations must:

-   attach to a date
-   be editable
-   display on graphs as markers

Graph overlays may appear as:

-   icons
-   vertical markers
-   labels

------------------------------------------------------------------------

## 6. Daily Targets

Users can configure macro goals.

Example:

  Macro     Target
  --------- --------
  protein   180
  carbs     250
  fat       70

Targets should appear in:

-   widgets
-   graphs
-   UI summaries

------------------------------------------------------------------------

# Data Export

Users must be able to export their data.

Supported formats:

    CSV
    JSON

Example CSV format:

    date,protein,carbs,fat,calories,annotation
    2026-03-06,180,240,60,2220,goal day

Exports should include:

-   macros
-   annotations
-   timestamps

------------------------------------------------------------------------

# Optional API (Stretch)

Provide a local HTTP API to support automation.

Example endpoints:

    POST /macros/add
    POST /macros/preset
    GET /macros/today
    GET /macros/history

Example request:

    POST /macros/add
    {
     "protein":60,
     "carbs":30,
     "fat":10
    }

This would enable:

-   Tasker
-   scripts
-   automation tools

------------------------------------------------------------------------

# UI Requirements

## Dashboard

Main screen should show:

    Today's Macros
    Protein
    Carbs
    Fat
    Calories

Plus quick actions:

-   add macros
-   apply presets
-   edit day
-   add annotation

------------------------------------------------------------------------

## History Screen

Displays:

-   macro graph
-   annotations
-   daily summaries

------------------------------------------------------------------------

## Preset Editor

Users can:

-   create presets
-   edit presets
-   delete presets

------------------------------------------------------------------------

# Non‑Functional Requirements

## Performance

-   widget updates must be instant
-   app must function offline
-   minimal battery use

## Data Ownership

User owns their data.

Requirements:

-   local storage
-   export anytime
-   no required account

------------------------------------------------------------------------

# Technical Suggestions

Recommended stack:

    Language: Kotlin
    UI: Jetpack Compose
    Widgets: Glance
    Database: Room (SQLite)
    Graphs: Compose chart library

Architecture:

    offline first
    local database
    reactive UI
    widget broadcast updates

------------------------------------------------------------------------

# Future Ideas

Potential enhancements:

-   voice macro logging
-   OCR nutrition label scanning
-   wearable integration
-   adaptive macro targets
-   ML‑based meal prediction

------------------------------------------------------------------------

# MVP Scope

Minimum viable product:

-   macro counters
-   preset shortcuts
-   widgets
-   graph view
-   annotations
-   CSV export

Everything else can come later.
