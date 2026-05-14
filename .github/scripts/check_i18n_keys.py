#!/usr/bin/env python3
"""Lightweight i18n key consistency check for QuickDrop.

Compares locale bundles against messages.properties and reports:
- duplicate keys within a file (last value silently wins — a real bug risk)
- missing keys in locale bundles (key exists in baseline but not in locale file)
- extra keys in locale bundles (key not present in baseline)
- empty values in locale bundles

Duplicate-key and missing-key checks are also run on messages.properties itself.

Default mode is warn-only (exit code 0). Use --strict to fail on findings.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

# Ensure UTF-8 output even on Windows consoles that default to cp1252.
if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf-8-sig"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]



def read_text_with_fallback(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    # Last resort keeps the check running even on malformed files.
    return raw.decode("utf-8", errors="replace")


def parse_properties(path: Path) -> Tuple[Dict[str, str], List[str]]:
    """Parse a .properties file.

    Returns:
        A tuple of (key→value dict, list of duplicate key names).
        When a key appears more than once the last value is kept in the dict,
        matching Java's runtime behaviour, so callers can still use the dict
        for value lookups.
    """
    data: Dict[str, str] = {}
    seen: Dict[str, int] = {}   # key → first-seen line number (1-based)
    duplicates: List[str] = []

    if not path.exists():
        return data, duplicates

    text = read_text_with_fallback(path)
    for lineno, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue

        key, value = _split_property(line)
        if not key:
            continue

        if key in seen:
            if key not in duplicates:
                duplicates.append(key)
        else:
            seen[key] = lineno

        data[key] = value

    return data, sorted(duplicates)


def _split_property(line: str) -> Tuple[str, str]:
    """Split a single non-comment properties line into (key, value)."""
    # Java properties separators: '=', ':' or first whitespace.
    escaped = False
    for idx, ch in enumerate(line):
        if escaped:
            escaped = False
            continue
        if ch == "\\":
            escaped = True
            continue
        if ch in ("=", ":"):
            return line[:idx].strip(), line[idx + 1:].strip()
        if ch.isspace():
            return line[:idx].strip(), line[idx + 1:].strip()
    return line.strip(), ""


def diff_keys(
    base: Dict[str, str], target: Dict[str, str]
) -> Tuple[List[str], List[str], List[str]]:
    """Return (missing, extra, empty) key lists for *target* relative to *base*."""
    base_keys = set(base.keys())
    target_keys = set(target.keys())
    missing = sorted(base_keys - target_keys)
    extra = sorted(target_keys - base_keys)
    empty = sorted(k for k, v in target.items() if not v.strip())
    return missing, extra, empty



def find_locale_files(resources_dir: Path) -> Iterable[Path]:
    for path in sorted(resources_dir.glob("messages_*.properties")):
        yield path


def _section(label: str, items: List[str], summary: List[str]) -> None:
    summary.append(f"- {label} ({len(items)}):")
    summary.extend(f"  - `{item}`" for item in items)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="Exit non-zero when findings exist.")
    parser.add_argument(
        "--resources-dir",
        default="src/main/resources",
        help="Resources directory containing message bundles.",
    )
    args = parser.parse_args()

    resources_dir = Path(args.resources_dir)
    base_file = resources_dir / "messages.properties"

    # ── Check the baseline file itself ──────────────────────────────────────
    base, base_dupes = parse_properties(base_file)

    if not base:
        print(f"::error::Base bundle not found or empty: {base_file}")
        return 1

    findings = 0
    summary_lines: List[str] = []

    if base_dupes:
        summary_lines.append(f"## {base_file.name}  ⚠ baseline issues")
        _section("Duplicate keys", base_dupes, summary_lines)
        findings += len(base_dupes)
    else:
        summary_lines.append(f"## {base_file.name}")
        summary_lines.append("- OK: no duplicate keys in baseline")

    # ── Check each locale bundle ─────────────────────────────────────────────
    locale_files = list(find_locale_files(resources_dir))
    if not locale_files:
        print("::warning::No locale bundles found (messages_*.properties).")
        return 0

    for locale_file in locale_files:
        try:
            locale_file.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            print(
                f"::warning file={locale_file}::Bundle is not UTF-8 encoded. "
                "Consider converting to UTF-8."
            )

        target, dup_keys = parse_properties(locale_file)
        missing, extra, empty = diff_keys(base, target)

        locale_findings = len(missing) + len(extra) + len(empty) + len(dup_keys)
        findings += locale_findings

        summary_lines.append(f"## {locale_file.name}")
        if locale_findings == 0:
            summary_lines.append("- OK: key set is aligned with messages.properties")
            continue

        if dup_keys:
            _section("Duplicate keys", dup_keys, summary_lines)
        if missing:
            _section(f"Missing keys (exist in {base_file.name})", missing, summary_lines)
        if extra:
            _section("Extra keys (not in baseline)", extra, summary_lines)
        if empty:
            _section("Empty values", empty, summary_lines)

    for line in summary_lines:
        print(line)

    if findings > 0:
        print(f"::warning::i18n key check found {findings} issue(s).")
        if args.strict:
            print("::error::Strict mode enabled. Failing due to i18n findings.")
            return 2
    else:
        print("i18n key check passed with no findings.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())


