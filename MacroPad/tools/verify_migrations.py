#!/usr/bin/python3
"""Check that Room migrations produce the schema Room expects.

The database is built with `fallbackToDestructiveMigration()`, so a migration that
throws — or that quietly produces a column Room disagrees about — wipes real data
instead of failing loudly. This replays every migration in order against a synthetic
old database, then diffs the result against the schema Room generated from the
entities.

Run after any schema change:

    /usr/bin/python3 tools/verify_migrations.py

(/usr/bin/python3 specifically — the brew python on this machine has a broken
sqlite3 module.)

Needs the KSP output, so run a build first if MacroPadDatabase_Impl.java is stale.
"""

from __future__ import annotations

import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB_KT = ROOT / "app/src/main/java/com/macropad/app/data/MacroPadDatabase.kt"
IMPL_CANDIDATES = [
    ROOT / "app/build/generated/ksp/release/java/com/macropad/app/data/MacroPadDatabase_Impl.java",
    ROOT / "app/build/generated/ksp/debug/java/com/macropad/app/data/MacroPadDatabase_Impl.java",
]

# The schema as it shipped in 2.0.7 (version 4), which is what an upgrading install
# actually starts from.
BASELINE_V4 = """
CREATE TABLE daily_macros (date TEXT NOT NULL PRIMARY KEY, proteinG INTEGER NOT NULL,
    carbsG INTEGER NOT NULL, fatG INTEGER NOT NULL, annotation TEXT, updatedAt INTEGER NOT NULL);
CREATE TABLE macro_presets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL,
    proteinG INTEGER NOT NULL, carbsG INTEGER NOT NULL, fatG INTEGER NOT NULL, createdAt INTEGER NOT NULL);
CREATE TABLE macro_targets (id INTEGER NOT NULL PRIMARY KEY, proteinG INTEGER NOT NULL,
    carbsG INTEGER NOT NULL, fatG INTEGER NOT NULL);
CREATE TABLE widget_settings (id INTEGER NOT NULL PRIMARY KEY, proteinIncrement INTEGER NOT NULL DEFAULT 5,
    carbsIncrement INTEGER NOT NULL DEFAULT 5, fatIncrement INTEGER NOT NULL DEFAULT 5,
    proteinDecrement INTEGER NOT NULL DEFAULT 1, carbsDecrement INTEGER NOT NULL DEFAULT 1,
    fatDecrement INTEGER NOT NULL DEFAULT 1, dayResetHour INTEGER NOT NULL DEFAULT 0);
CREATE TABLE macro_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL,
    timestamp INTEGER NOT NULL, proteinG INTEGER NOT NULL DEFAULT 0, carbsG INTEGER NOT NULL DEFAULT 0,
    fatG INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL DEFAULT 'manual');
CREATE INDEX index_macro_entries_date ON macro_entries(date);
CREATE TABLE sync_settings (id INTEGER NOT NULL PRIMARY KEY,
    conflictResolution TEXT NOT NULL DEFAULT 'LOCAL_WINS', autoSyncEnabled INTEGER NOT NULL DEFAULT 0,
    lastSyncTimestamp INTEGER NOT NULL DEFAULT 0);
"""

BASELINE_VERSION = 4

# A little real data, so a migration that drops rows is caught too.
SEED = [
    "INSERT INTO macro_presets (name,proteinG,carbsG,fatG,createdAt) VALUES ('Almonds',6,6,14,0)",
    "INSERT INTO macro_presets (name,proteinG,carbsG,fatG,createdAt) VALUES ('banana',1,27,0,0)",
    "INSERT INTO macro_presets (name,proteinG,carbsG,fatG,createdAt) VALUES ('Chicken breast',43,0,5,0)",
    "INSERT INTO daily_macros VALUES ('2026-08-10',150,200,60,NULL,0)",
    "INSERT INTO macro_entries (date,timestamp,proteinG,carbsG,fatG,source) "
    "VALUES ('2026-08-10',0,43,0,5,'preset:Chicken breast')",
]

EXEC_SQL = re.compile(
    r'db\.execSQL\(\s*"""(?P<triple>.*?)"""\.trimIndent\(\)\)'
    r'|db\.execSQL\(\s*"(?P<single>(?:[^"\\]|\\.)*)"\s*\)'
    r'|db\.execSQL\(\s*"(?P<concat_a>(?:[^"\\]|\\.)*)"\s*\+\s*"(?P<concat_b>(?:[^"\\]|\\.)*)"\s*\)',
    re.DOTALL,
)

CREATE_TABLE = re.compile(r'"(CREATE TABLE IF NOT EXISTS `(?P<name>\w+)`[^"]*)"')
CREATE_INDEX = re.compile(r'"(CREATE (?:UNIQUE )?INDEX IF NOT EXISTS `(?P<name>\w+)`[^"]*)"')


def migration_blocks(source: str) -> list[tuple[int, int, str]]:
    """Every `object : Migration(a, b)` body, in ascending version order."""
    starts = [
        (int(m.group(1)), int(m.group(2)), m.end())
        for m in re.finditer(r"object : Migration\((\d+),\s*(\d+)\)", source)
    ]
    blocks = []
    for index, (frm, to, start) in enumerate(starts):
        end = starts[index + 1][2] if index + 1 < len(starts) else len(source)
        blocks.append((frm, to, source[start:end]))
    return sorted(blocks, key=lambda b: b[0])


def statements(block: str) -> list[str]:
    out = []
    for match in EXEC_SQL.finditer(block):
        if match.group("triple") is not None:
            sql = match.group("triple")
        elif match.group("single") is not None:
            sql = match.group("single")
        else:
            sql = match.group("concat_a") + match.group("concat_b")
        out.append(sql.replace('\\"', '"').strip())
    return out


def room_expected(impl: str) -> tuple[dict[str, str], set[str]]:
    tables = {m.group("name"): m.group(1) for m in CREATE_TABLE.finditer(impl)}
    indices = {m.group("name") for m in CREATE_INDEX.finditer(impl)}
    return tables, indices


def columns_of(create_sql: str) -> dict[str, tuple[str, bool]]:
    """name -> (type, not_null), read out of a CREATE TABLE statement."""
    body = create_sql[create_sql.index("(") + 1 : create_sql.rindex(")")]
    cols: dict[str, tuple[str, bool]] = {}
    depth = 0
    current = ""
    for char in body + ",":
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        if char == "," and depth == 0:
            part = current.strip()
            current = ""
            if not part or part.upper().startswith(("PRIMARY KEY", "FOREIGN KEY", "UNIQUE", "CHECK")):
                continue
            tokens = part.replace("`", "").split()
            if len(tokens) >= 2:
                cols[tokens[0]] = (tokens[1].upper(), "NOT NULL" in part.upper())
        else:
            current += char
    return cols


def main() -> int:
    source = DB_KT.read_text()
    impl_path = next((p for p in IMPL_CANDIDATES if p.exists()), None)
    if impl_path is None:
        print("Room's generated schema not found — run a build first.", file=sys.stderr)
        return 2

    target_version = int(re.search(r"version = (\d+)", source).group(1))
    db = sqlite3.connect(":memory:")
    db.executescript(BASELINE_V4)
    for row in SEED:
        db.execute(row)
    db.commit()
    seeded_presets = db.execute("SELECT COUNT(*) FROM macro_presets").fetchone()[0]

    applied = 0
    for frm, to, block in migration_blocks(source):
        if frm < BASELINE_VERSION:
            continue
        sqls = statements(block)
        if not sqls:
            print(f"  ! {frm}->{to}: no statements parsed (check the extractor)")
            return 1
        for sql in sqls:
            try:
                db.execute(sql)
            except Exception as exc:
                print(f"  ✗ {frm}->{to} FAILED: {sql[:80]}...\n      {exc}")
                return 1
        print(f"  ✓ {frm}->{to} ({len(sqls)} statements)")
        applied = to
    db.commit()

    if applied != target_version:
        print(f"  ✗ migrations end at v{applied} but the database declares v{target_version}")
        return 1

    tables, indices = room_expected(impl_path.read_text())
    problems = []

    for name, create_sql in tables.items():
        actual = {
            r[1]: (r[2].upper(), bool(r[3]))
            for r in db.execute(f"PRAGMA table_info({name})")
        }
        if not actual:
            problems.append(f"table `{name}` missing after migration")
            continue
        expected = columns_of(create_sql)
        for column, (col_type, not_null) in expected.items():
            if column not in actual:
                problems.append(f"{name}.{column} missing")
            elif actual[column][0] != col_type:
                problems.append(
                    f"{name}.{column} type {actual[column][0]}, Room expects {col_type}"
                )
            elif actual[column][1] != not_null:
                problems.append(
                    f"{name}.{column} nullability differs (Room expects "
                    f"{'NOT NULL' if not_null else 'nullable'})"
                )
        for column in actual.keys() - expected.keys():
            problems.append(f"{name}.{column} exists but Room doesn't know about it")

    actual_indices = {
        r[0] for r in db.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'"
        )
    }
    for index in indices - actual_indices:
        problems.append(f"index `{index}` missing after migration")

    kept = db.execute("SELECT COUNT(*) FROM macro_presets").fetchone()[0]
    if kept != seeded_presets:
        problems.append(f"lost rows: {seeded_presets} presets seeded, {kept} survived")

    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  ✗ {problem}")
        return 1

    print(
        f"\nOK — v{BASELINE_VERSION} to v{target_version}: "
        f"{len(tables)} tables and {len(indices)} indices match Room, data preserved."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
