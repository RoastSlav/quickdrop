#!/usr/bin/env python3
"""Lightweight i18n key consistency check for QuickDrop.

Compares locale bundles against messages.properties and reports:
- duplicate keys within a file (last value silently wins — a real bug risk)
- missing keys in locale bundles (key exists in baseline but not in locale file)
- extra keys in locale bundles (key not present in baseline)
- empty values in locale bundles
- untranslated values (value is identical to the base English value)

Duplicate-key and missing-key checks are also run on messages.properties itself.

Default mode is warn-only (exit code 0). Use --strict to fail on structural
findings (missing/extra/duplicate/empty). Use --strict-untranslated to also
fail on untranslated values (or combine both with --strict --strict-untranslated).
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

# Ensure UTF-8 output even on Windows consoles that default to cp1252.
if sys.stdout.encoding and sys.stdout.encoding.lower() not in ("utf-8", "utf-8-sig"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]

# Values that are legitimately identical in all languages (brand names, units,
# pure numbers, single chars, URLs). These are skipped in the untranslated check.
_SKIP_UNTRANSLATED_RE = re.compile(
    r"""
    ^\d+(\.\d+)?$         |  # pure number / decimal
    ^[A-Z]{1,3}$          |  # short unit like GB, MB, KB, B
    ^https?://            |  # URL
    ^\S+@\S+\.\S+$        |  # email address
    ^\{[^}]+\}$              # single placeholder like {0}
    """,
    re.VERBOSE,
)


def _is_skippable(value: str) -> bool:
    """Return True for values that are expected to be identical across locales."""
    v = value.strip()
    if len(v) <= 1:
        return True
    if _SKIP_UNTRANSLATED_RE.match(v):
        return True
    return False


def read_text_with_fallback(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
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
    seen: Dict[str, int] = {}
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
) -> Tuple[List[str], List[str], List[str], List[str]]:
    """Return (missing, extra, empty, untranslated) for *target* relative to *base*.

    - missing     : keys in base that are absent from target
    - extra       : keys in target that are absent from base
    - empty       : keys in target whose value is blank
    - untranslated: keys present in both where target value == base value
                    (excluding values that are legitimately locale-invariant)
    """
    base_keys = set(base.keys())
    target_keys = set(target.keys())
    missing = sorted(base_keys - target_keys)
    extra = sorted(target_keys - base_keys)
    empty = sorted(k for k, v in target.items() if not v.strip())

    untranslated = sorted(
        k for k in base_keys & target_keys
        if target[k] == base[k]
        and base[k].strip()
        and not _is_skippable(base[k])
    )
    return missing, extra, empty, untranslated


def find_locale_files(resources_dir: Path) -> Iterable[Path]:
    for path in sorted(resources_dir.glob("messages_*.properties")):
        yield path


def _section(label: str, items: List[str], summary: List[str]) -> None:
    summary.append(f"- {label} ({len(items)}):")
    summary.extend(f"  - `{item}`" for item in items)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Exit non-zero on structural findings (missing/extra/duplicate/empty).",
    )
    parser.add_argument(
        "--strict-untranslated",
        action="store_true",
        help="Exit non-zero when untranslated values are found.",
    )
    parser.add_argument(
        "--resources-dir",
        default="src/main/resources",
        help="Resources directory containing message bundles.",
    )
    args = parser.parse_args()

    resources_dir = Path(args.resources_dir)
    base_file = resources_dir / "messages.properties"

    base, base_dupes = parse_properties(base_file)

    if not base:
        print(f"::error::Base bundle not found or empty: {base_file}")
        return 1

    structural_findings = 0
    untranslated_findings = 0
    summary_lines: List[str] = []

    if base_dupes:
        summary_lines.append(f"## {base_file.name}  ⚠ baseline issues")
        _section("Duplicate keys", base_dupes, summary_lines)
        structural_findings += len(base_dupes)
    else:
        summary_lines.append(f"## {base_file.name}")
        summary_lines.append("- OK: no duplicate keys in baseline")

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
        missing, extra, empty, untranslated = diff_keys(base, target)

        locale_structural = len(missing) + len(extra) + len(empty) + len(dup_keys)
        structural_findings += locale_structural
        untranslated_findings += len(untranslated)

        total_locale_findings = locale_structural + len(untranslated)
        summary_lines.append(f"## {locale_file.name}")
        if total_locale_findings == 0:
            summary_lines.append("- OK: fully translated and aligned with messages.properties")
            continue

        if dup_keys:
            _section("Duplicate keys", dup_keys, summary_lines)
        if missing:
            _section(f"Missing keys (exist in {base_file.name})", missing, summary_lines)
        if extra:
            _section("Extra keys (not in baseline)", extra, summary_lines)
        if empty:
            _section("Empty values", empty, summary_lines)
        if untranslated:
            _section("Untranslated values (same as base English)", untranslated, summary_lines)

    for line in summary_lines:
        print(line)

    total_findings = structural_findings + untranslated_findings
    if total_findings > 0:
        msg_parts = []
        if structural_findings:
            msg_parts.append(f"{structural_findings} structural issue(s)")
        if untranslated_findings:
            msg_parts.append(f"{untranslated_findings} untranslated value(s)")
        print(f"::warning::i18n check found {', '.join(msg_parts)}.")

        should_fail = (args.strict and structural_findings > 0) or \
                      (args.strict_untranslated and untranslated_findings > 0)
        if should_fail:
            print("::error::Failing due to i18n findings (strict mode).")
            return 2
    else:
        print("i18n key check passed with no findings.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
