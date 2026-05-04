#!/usr/bin/env python3
"""Lightweight i18n key consistency check for QuickDrop.

Compares locale bundles against messages.properties and reports:
- missing keys in locale bundles
- extra keys that do not exist in English baseline
- empty values in locale bundles

Default mode is warn-only (exit code 0). Use --strict to fail on findings.
"""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Dict, Iterable, List, Tuple


def read_text_with_fallback(path: Path) -> str:
    raw = path.read_bytes()
    for encoding in ("utf-8", "utf-8-sig", "cp1252", "latin-1"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    # Last resort keeps the check running even on malformed files.
    return raw.decode("utf-8", errors="replace")


def parse_properties(path: Path) -> Dict[str, str]:
    data: Dict[str, str] = {}
    if not path.exists():
        return data

    text = read_text_with_fallback(path)
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue

        key, value = split_property(line)
        if key:
            data[key] = value

    return data


def split_property(line: str) -> Tuple[str, str]:
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
            return line[:idx].strip(), line[idx + 1 :].strip()
        if ch.isspace():
            return line[:idx].strip(), line[idx + 1 :].strip()
    return line.strip(), ""


def diff_keys(base: Dict[str, str], target: Dict[str, str]) -> Tuple[List[str], List[str], List[str]]:
    base_keys = set(base.keys())
    target_keys = set(target.keys())
    missing = sorted(base_keys - target_keys)
    extra = sorted(target_keys - base_keys)
    empty = sorted([k for k, v in target.items() if not v.strip()])
    return missing, extra, empty


def find_locale_files(resources_dir: Path) -> Iterable[Path]:
    for path in sorted(resources_dir.glob("messages_*.properties")):
        yield path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--strict", action="store_true", help="Exit non-zero when findings exist.")
    parser.add_argument(
        "--resources-dir",
        default="src/main/resources",
        help="Resources directory containing message bundles.",
    )
    args = parser.parse_args()

    resources_dir = Path(args.resources_dir)
    base_file = resources_dir / "messages.properties"
    base = parse_properties(base_file)

    if not base:
        print(f"::error::Base bundle not found or empty: {base_file}")
        return 1

    locale_files = list(find_locale_files(resources_dir))
    if not locale_files:
        print("::warning::No locale bundles found (messages_*.properties).")
        return 0

    findings = 0
    summary_lines: List[str] = []

    for locale_file in locale_files:
        try:
            locale_file.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            print(
                f"::warning file={locale_file}::Bundle is not UTF-8 encoded. Consider converting to UTF-8."
            )

        target = parse_properties(locale_file)
        missing, extra, empty = diff_keys(base, target)

        locale_findings = len(missing) + len(extra) + len(empty)
        findings += locale_findings

        summary_lines.append(f"## {locale_file.name}")
        if locale_findings == 0:
            summary_lines.append("- OK: key set is aligned with messages.properties")
            continue

        if missing:
            summary_lines.append(f"- Missing keys ({len(missing)}):")
            summary_lines.extend([f"  - `{k}`" for k in missing])
        if extra:
            summary_lines.append(f"- Extra keys ({len(extra)}):")
            summary_lines.extend([f"  - `{k}`" for k in extra])
        if empty:
            summary_lines.append(f"- Empty values ({len(empty)}):")
            summary_lines.extend([f"  - `{k}`" for k in empty])

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


