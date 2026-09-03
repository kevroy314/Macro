"""The contract between the agent and the daemon.

The agent is constrained by `--json-schema` (via ClaudeAgentOptions.output_format),
so the happy path needs no parsing at all. Everything here still re-validates,
because a schema guarantees shape, not sense: it cannot stop the model returning
negative fat or a follow-up question worth 3 calories.
"""

from __future__ import annotations

import json
import re
from typing import Any

CONFIDENCE_VALUES = ("high", "medium", "low")

RESULT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["items", "total", "preset_name", "summary", "sources", "follow_up_questions"],
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": [
                    "name",
                    "qty",
                    "protein_g",
                    "carbs_g",
                    "fat_g",
                    "confidence",
                    "assumptions",
                ],
                "properties": {
                    "name": {"type": "string", "description": "The food, as a person would name it"},
                    "qty": {"type": "string", "description": "Portion, e.g. '1 bowl', '2 slices', '340 g'"},
                    "protein_g": {"type": "number"},
                    "carbs_g": {"type": "number"},
                    "fat_g": {"type": "number"},
                    "confidence": {"type": "string", "enum": list(CONFIDENCE_VALUES)},
                    "assumptions": {
                        "type": "string",
                        "description": "What you had to assume for this item. Empty string if nothing.",
                    },
                },
            },
        },
        "total": {
            "type": "object",
            "additionalProperties": False,
            "required": ["protein_g", "carbs_g", "fat_g", "calories"],
            "properties": {
                "protein_g": {"type": "number"},
                "carbs_g": {"type": "number"},
                "fat_g": {"type": "number"},
                "calories": {"type": "number"},
            },
        },
        "preset_name": {
            "type": "string",
            "description": "Short reusable name for the whole entry, e.g. 'Chipotle chicken bowl + chips'. Max ~40 chars.",
        },
        "summary": {
            "type": "string",
            "description": "One or two sentences on how the estimate was reached.",
        },
        "sources": {
            "type": "array",
            "items": {"type": "string"},
            "description": "URLs or data sources actually used. Empty if estimated from general knowledge.",
        },
        "follow_up_questions": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["id", "question", "why", "est_calorie_swing"],
                "properties": {
                    "id": {"type": "string"},
                    "question": {
                        "type": "string",
                        "description": "A single plain-text question answerable in a few words.",
                    },
                    "why": {"type": "string", "description": "What the answer would change."},
                    "est_calorie_swing": {
                        "type": "number",
                        "description": "Calories the total could move by, depending on the answer.",
                    },
                },
            },
        },
    },
}

OUTPUT_FORMAT = {"type": "json_schema", "schema": RESULT_SCHEMA}


class ResultError(ValueError):
    """The agent's output could not be made into a usable result."""


def extract_json(text: str) -> dict[str, Any] | None:
    """Best-effort recovery when structured output is unavailable.

    Tries the whole string, then a fenced block, then the outermost brace pair.
    """
    if not text:
        return None
    candidates: list[str] = [text.strip()]

    fenced = re.findall(r"```(?:json)?\s*(.+?)```", text, re.DOTALL)
    candidates.extend(f.strip() for f in fenced)

    start, end = text.find("{"), text.rfind("}")
    if start != -1 and end > start:
        candidates.append(text[start : end + 1])

    for candidate in candidates:
        try:
            parsed = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            continue
        if isinstance(parsed, dict):
            return parsed
    return None


def _num(value: Any, default: float = 0.0) -> float:
    try:
        out = float(value)
    except (TypeError, ValueError):
        return default
    if out != out or out in (float("inf"), float("-inf")):  # NaN / inf
        return default
    return out


def _round_macro(value: float) -> int:
    return max(0, int(round(value)))


def calories_from_macros(protein: float, carbs: float, fat: float) -> int:
    """Same 4/4/9 arithmetic the Android app uses, so totals never disagree."""
    return int(round(protein * 4 + carbs * 4 + fat * 9))


def normalise(raw: Any) -> dict[str, Any]:
    """Coerce agent output into the canonical result shape.

    Raises ResultError when there is nothing salvageable.
    """
    if not isinstance(raw, dict):
        raise ResultError("agent returned no JSON object")

    items_out: list[dict[str, Any]] = []
    for item in raw.get("items") or []:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        if not name:
            continue
        confidence = str(item.get("confidence") or "medium").lower()
        items_out.append(
            {
                "name": name[:120],
                "qty": str(item.get("qty") or "").strip()[:80],
                "protein_g": _round_macro(_num(item.get("protein_g"))),
                "carbs_g": _round_macro(_num(item.get("carbs_g"))),
                "fat_g": _round_macro(_num(item.get("fat_g"))),
                "confidence": confidence if confidence in CONFIDENCE_VALUES else "medium",
                "assumptions": str(item.get("assumptions") or "").strip()[:500],
            }
        )

    raw_total = raw.get("total") if isinstance(raw.get("total"), dict) else {}
    if items_out:
        # Item sums are authoritative — a stated total that disagrees with its own
        # itemisation is the more likely arithmetic slip.
        protein = sum(i["protein_g"] for i in items_out)
        carbs = sum(i["carbs_g"] for i in items_out)
        fat = sum(i["fat_g"] for i in items_out)
    else:
        protein = _round_macro(_num(raw_total.get("protein_g")))
        carbs = _round_macro(_num(raw_total.get("carbs_g")))
        fat = _round_macro(_num(raw_total.get("fat_g")))

    if not items_out and protein == carbs == fat == 0:
        raise ResultError("agent returned no items and a zero total")

    calories = calories_from_macros(protein, carbs, fat)

    questions: list[dict[str, Any]] = []
    for index, question in enumerate(raw.get("follow_up_questions") or []):
        if not isinstance(question, dict):
            continue
        text = str(question.get("question") or "").strip()
        if not text:
            continue
        questions.append(
            {
                "id": str(question.get("id") or f"q{index + 1}"),
                "question": text[:300],
                "why": str(question.get("why") or "").strip()[:300],
                "est_calorie_swing": abs(_num(question.get("est_calorie_swing"))),
            }
        )

    sources = [
        str(s).strip()[:500]
        for s in (raw.get("sources") or [])
        if isinstance(s, (str, int, float)) and str(s).strip()
    ]

    preset_name = str(raw.get("preset_name") or "").strip()
    if not preset_name:
        preset_name = items_out[0]["name"] if items_out else "AI entry"

    return {
        "items": items_out,
        "total": {
            "protein_g": protein,
            "carbs_g": carbs,
            "fat_g": fat,
            "calories": calories,
        },
        "preset_name": preset_name[:60],
        "summary": str(raw.get("summary") or "").strip()[:1000],
        "sources": sources[:20],
        "follow_up_questions": questions,
    }


def filter_questions(
    result: dict[str, Any], threshold_mode: str, threshold_value: float
) -> dict[str, Any]:
    """Drop follow-ups whose answer wouldn't move the estimate enough to matter.

    The agent is told the threshold, but it is enforced here too — a threshold the
    user set should hold regardless of how the model interpreted it.
    """
    calories = max(1.0, float(result["total"]["calories"]))
    if threshold_mode == "absolute":
        cutoff = max(0.0, float(threshold_value))
    else:
        cutoff = calories * max(0.0, float(threshold_value)) / 100.0

    kept = [
        q for q in result["follow_up_questions"] if q["est_calorie_swing"] >= cutoff
    ]
    # Two questions is the most anyone will answer from a notification.
    kept.sort(key=lambda q: q["est_calorie_swing"], reverse=True)
    result = dict(result)
    result["follow_up_questions"] = kept[:2]
    result["question_cutoff_calories"] = round(cutoff)
    return result


# ---------------------------------------------------------------- planning threads

PLANNING_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["reply", "title", "proposed_entries"],
    "properties": {
        "reply": {
            "type": "string",
            "description": (
                "Your answer to the user, in plain prose. This is the whole message "
                "they see, so put the number they asked for in the first sentence."
            ),
        },
        "title": {
            "type": "string",
            "description": (
                "Three to five words naming what this conversation is about, e.g. "
                "'Goldfish within calorie budget'. Keep it stable across turns."
            ),
        },
        "proposed_entries": {
            "type": "array",
            "description": (
                "Food the user could log right now as a result of this exchange. "
                "Empty unless they are clearly about to eat something specific, or "
                "asked you to log it."
            ),
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["name", "qty", "protein_g", "carbs_g", "fat_g", "note"],
                "properties": {
                    "name": {"type": "string"},
                    "qty": {"type": "string"},
                    "protein_g": {"type": "number"},
                    "carbs_g": {"type": "number"},
                    "fat_g": {"type": "number"},
                    "note": {
                        "type": "string",
                        "description": "One line on what this covers. Empty if obvious.",
                    },
                },
            },
        },
    },
}

PLANNING_OUTPUT_FORMAT = {"type": "json_schema", "schema": PLANNING_SCHEMA}


def normalise_planning(raw: Any) -> dict[str, Any]:
    """Coerce a planning turn into the canonical shape."""
    if not isinstance(raw, dict):
        raise ResultError("agent returned no JSON object")

    reply = str(raw.get("reply") or "").strip()
    if not reply:
        raise ResultError("agent returned an empty reply")

    entries: list[dict[str, Any]] = []
    for item in raw.get("proposed_entries") or []:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip()
        if not name:
            continue
        protein = _round_macro(_num(item.get("protein_g")))
        carbs = _round_macro(_num(item.get("carbs_g")))
        fat = _round_macro(_num(item.get("fat_g")))
        if protein == 0 and carbs == 0 and fat == 0:
            continue
        entries.append(
            {
                "name": name[:60],
                "qty": str(item.get("qty") or "").strip()[:80],
                "protein_g": protein,
                "carbs_g": carbs,
                "fat_g": fat,
                "calories": calories_from_macros(protein, carbs, fat),
                "note": str(item.get("note") or "").strip()[:300],
            }
        )

    return {
        "reply": reply[:8000],
        "title": str(raw.get("title") or "").strip()[:60],
        "proposed_entries": entries[:6],
    }


# ------------------------------------------------------------------- preset tags

TAG_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["tags"],
    "properties": {
        "tags": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["index", "keywords"],
                "properties": {
                    "index": {"type": "integer"},
                    "keywords": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Lowercase single words or short phrases.",
                    },
                },
            },
        }
    },
}

TAG_OUTPUT_FORMAT = {"type": "json_schema", "schema": TAG_SCHEMA}


def normalise_tags(raw: Any, count: int) -> dict[int, list[str]]:
    """Map index -> keyword list, dropping anything out of range or empty."""
    if not isinstance(raw, dict):
        raise ResultError("agent returned no JSON object")
    out: dict[int, list[str]] = {}
    for entry in raw.get("tags") or []:
        if not isinstance(entry, dict):
            continue
        try:
            index = int(entry.get("index"))
        except (TypeError, ValueError):
            continue
        if not 0 <= index < count:
            continue
        keywords = [
            str(k).strip().lower()[:30]
            for k in (entry.get("keywords") or [])
            if str(k).strip()
        ]
        if keywords:
            out[index] = keywords[:12]
    return out
