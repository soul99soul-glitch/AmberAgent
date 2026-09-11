#!/usr/bin/env python3
"""Read-only Android localization checks.

The check intentionally covers only the resource trees that are part of the
Android localization audit.  It never writes files.  A non-zero exit means at
least one check found a problem.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOTS = (
    ROOT / "app/src/main/res",
    ROOT / "app/src/graphite/res",
    ROOT / "search/src/main/res",
)
APP_VARIANT_SOURCE_ROOTS = (
    ROOT / "app/src/debug",
    ROOT / "app/src/graphite",
)

HAN_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

# Android printf placeholders plus the prompt variables used by the app.
PLACEHOLDER_RE = re.compile(
    r"%(?:\d+\$)?[-+#0,(]*\d*(?:\.\d+)?[a-zA-Z]|\{[A-Za-z][A-Za-z0-9_]*\}"
)

# Keep this list exact and preview-only.  Path + full text is deliberate: it
# survives harmless line movement while still failing if the preview copy or
# its location changes.  Add a path/text pair only for reviewed Preview copy;
# production UI and business/prompt data must remain visible to the check.
PREVIEW_TEXT_ALLOWLIST: set[tuple[str, str]] = {
    (
        "app/src/main/java/app/amber/feature/ui/components/ds/CoreScreenPreview.kt",
        "Graphite 重构",
    ),
    (
        "app/src/main/java/app/amber/feature/ui/components/ds/CoreScreenPreview.kt",
        "帮我把界面改成石墨灰",
    ),
    (
        "app/src/main/java/app/amber/feature/ui/components/ds/CoreScreenPreview.kt",
        "已套用 Terminal × Modern：暖石墨底、赤陶强调、机器事实用等宽。",
    ),
    (
        "app/src/main/java/app/amber/feature/ui/components/ds/CoreScreenPreview.kt",
        "输入消息…",
    ),
    (
        "app/src/main/java/app/amber/feature/ui/components/ds/AmberPreview.kt",
        "人读内容用无衬线，机器事实用等宽——这就是品牌。",
    ),
}

COMPOSE_TEXT_RE = re.compile(
    r'\bText\s*\(\s*(?:text\s*=\s*)?"((?:\\.|[^"\\\n])*)"'
)

VISIBLE_PARAMETER_RE = re.compile(
    r'\b(contentDescription|title|label|description|placeholder)\s*=\s*'
    r'"((?:\\.|[^"\\\n])*)"'
)

XML_COMMENT_RE = re.compile(r"<!--[\s\S]*?-->")


def parse_strings(path: Path) -> dict[str, str]:
    """Parse direct <string> children and preserve XML parse failures."""

    tree = ET.parse(path)
    return {
        element.attrib["name"]: "".join(element.itertext())
        for element in tree.getroot().findall("string")
        if "name" in element.attrib
    }


def placeholder_tokens(value: str) -> Counter[str]:
    return Counter(PLACEHOLDER_RE.findall(value))


def is_preview_allowlisted(relative: str, text: str) -> bool:
    return "Preview" in Path(relative).name and (relative, text) in PREVIEW_TEXT_ALLOWLIST


def sample(values: set[str], limit: int = 5) -> str:
    names = sorted(values)
    if len(names) <= limit:
        return ", ".join(names)
    return ", ".join(names[:limit]) + f", ... (+{len(names) - limit})"


def kotlin_source_paths() -> list[Path]:
    paths = set(ROOT.rglob("src/main/**/*.kt"))
    for source_root in APP_VARIANT_SOURCE_ROOTS:
        paths.update(source_root.rglob("*.kt"))
    return sorted(paths)


def check_resources(failures: list[str]) -> None:
    for resource_root in RESOURCE_ROOTS:
        default_path = resource_root / "values/strings.xml"
        label = default_path.relative_to(ROOT).as_posix()
        if not default_path.is_file():
            failures.append(f"{label}: missing default strings.xml")
            continue

        try:
            default = parse_strings(default_path)
        except (OSError, ET.ParseError) as error:
            failures.append(f"{label}: XML parse failed: {error}")
            continue

        default_han = [
            (key, value)
            for key, value in default.items()
            if HAN_RE.search(key) or HAN_RE.search(value)
        ]
        if default_han:
            failures.append(
                f"{label}: default values contain Han text in "
                f"{len(default_han)} key(s): {sample({key for key, _ in default_han})}"
            )
        else:
            print(f"PASS default-no-Han: {label} ({len(default)} strings)")

        locale_paths = sorted(resource_root.glob("values-*/strings.xml"))
        for locale_path in locale_paths:
            locale_label = locale_path.relative_to(ROOT).as_posix()
            try:
                locale = parse_strings(locale_path)
            except (OSError, ET.ParseError) as error:
                failures.append(f"{locale_label}: XML parse failed: {error}")
                continue

            missing = set(default) - set(locale)
            extra = set(locale) - set(default)
            if missing or extra:
                failures.append(
                    f"{locale_label}: key parity missing={len(missing)} "
                    f"extra={len(extra)}"
                    + (f"; missing sample: {sample(missing)}" if missing else "")
                    + (f"; extra sample: {sample(extra)}" if extra else "")
                )
            else:
                print(f"PASS key-parity: {locale_label} ({len(locale)} strings)")

            placeholder_mismatches = []
            for key in sorted(set(default) & set(locale)):
                expected = placeholder_tokens(default[key])
                actual = placeholder_tokens(locale[key])
                if expected != actual:
                    placeholder_mismatches.append((key, expected, actual))
            if placeholder_mismatches:
                failures.append(
                    f"{locale_label}: placeholder mismatch in "
                    f"{len(placeholder_mismatches)} key(s): "
                    f"{sample({key for key, _, _ in placeholder_mismatches})}"
                )
            else:
                print(f"PASS placeholders: {locale_label}")


def mask_kotlin_comments(source: str) -> str:
    """Mask Kotlin //, /* */, and KDoc comments while preserving line numbers.

    Comment markers inside regular, character, or triple-quoted strings are
    copied verbatim so string contents remain eligible for the literal checks.
    """

    NORMAL, LINE, BLOCK, STRING, TRIPLE, CHAR = range(6)
    output: list[str] = []
    state = NORMAL
    block_depth = 0
    index = 0

    def mask(character: str) -> None:
        output.append("\n" if character == "\n" else " ")

    while index < len(source):
        character = source[index]

        if state == NORMAL:
            if source.startswith("//", index):
                mask("/")
                mask("/")
                index += 2
                state = LINE
            elif source.startswith("/*", index):
                mask("/")
                mask("*")
                index += 2
                state = BLOCK
                block_depth = 1
            elif source.startswith('"""', index):
                output.append('"""')
                index += 3
                state = TRIPLE
            elif character == '"':
                output.append(character)
                index += 1
                state = STRING
            elif character == "'":
                output.append(character)
                index += 1
                state = CHAR
            else:
                output.append(character)
                index += 1
        elif state == LINE:
            if character == "\n":
                output.append(character)
                index += 1
                state = NORMAL
            else:
                mask(character)
                index += 1
        elif state == BLOCK:
            if source.startswith("/*", index):
                mask("/")
                mask("*")
                index += 2
                block_depth += 1
            elif source.startswith("*/", index):
                mask("*")
                mask("/")
                index += 2
                block_depth -= 1
                if block_depth == 0:
                    state = NORMAL
            else:
                mask(character)
                index += 1
        elif state == STRING:
            output.append(character)
            index += 1
            if character == "\\" and index < len(source):
                output.append(source[index])
                index += 1
            elif character == '"':
                state = NORMAL
        elif state == TRIPLE:
            if source.startswith('"""', index):
                output.append('"""')
                index += 3
                state = NORMAL
            else:
                output.append(character)
                index += 1
        else:  # CHAR
            output.append(character)
            index += 1
            if character == "\\" and index < len(source):
                output.append(source[index])
                index += 1
            elif character == "'":
                state = NORMAL

    return "".join(output)


def check_inline_compose_text(failures: list[str]) -> None:
    hits: list[tuple[str, int, str]] = []
    for path in kotlin_source_paths():
        relative = path.relative_to(ROOT).as_posix()
        try:
            source = path.read_text(encoding="utf-8")
        except OSError as error:
            failures.append(f"{relative}: read failed: {error}")
            continue

        for line_number, line in enumerate(mask_kotlin_comments(source).splitlines(), start=1):
            for match in COMPOSE_TEXT_RE.finditer(line):
                text = match.group(1)
                if HAN_RE.search(text) and not is_preview_allowlisted(relative, text):
                    hits.append((relative, line_number, text))

    if hits:
        failures.append(
            f"inline Compose Text Chinese literals: {len(hits)} hit(s) "
            f"({len(PREVIEW_TEXT_ALLOWLIST)} exact allowlisted preview entries); "
            f"first: {relative_hit(hits[0])}"
        )
        for hit in hits[:10]:
            print(f"  inline-hit {relative_hit(hit)}")
        if len(hits) > 10:
            print(f"  ... {len(hits) - 10} more inline-hit(s)")
    else:
        print("PASS inline Compose Text Chinese literals: 0")


def is_ui_kotlin(relative: str, source: str) -> bool:
    """Limit named-argument checks to UI-ish Kotlin, not prompt/business data."""

    return "/ui/" in f"/{relative}" or "@Composable" in source or "androidx.compose" in source


def check_inline_visible_parameters(failures: list[str]) -> None:
    hits: list[tuple[str, int, str]] = []
    for path in kotlin_source_paths():
        relative = path.relative_to(ROOT).as_posix()
        try:
            source = path.read_text(encoding="utf-8")
        except OSError as error:
            failures.append(f"{relative}: read failed: {error}")
            continue
        if not is_ui_kotlin(relative, source):
            continue

        masked_source = mask_kotlin_comments(source)
        for line_number, (original_line, line) in enumerate(
            zip(source.splitlines(), masked_source.splitlines()), start=1
        ):
            if original_line.lstrip().startswith("//"):
                continue
            for match in VISIBLE_PARAMETER_RE.finditer(line):
                parameter, text = match.groups()
                if HAN_RE.search(text) and not is_preview_allowlisted(relative, text):
                    hits.append((relative, line_number, f"{parameter}={text}"))

    if hits:
        failures.append(
            f"inline visible Kotlin parameters with Chinese literals: {len(hits)} hit(s); "
            f"first: {relative_hit(hits[0])}"
        )
        for hit in hits[:10]:
            print(f"  visible-parameter-hit {relative_hit(hit)}")
        if len(hits) > 10:
            print(f"  ... {len(hits) - 10} more visible-parameter-hit(s)")
    else:
        print("PASS inline visible Kotlin parameters with Chinese literals: 0")


def mask_xml_comments(source: str) -> str:
    """Remove comments while preserving line numbers for direct XML literals."""

    def replacement(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    return XML_COMMENT_RE.sub(replacement, source)


def check_xml_literals(failures: list[str]) -> None:
    hits: list[tuple[str, int, str]] = []
    paths = set(ROOT.rglob("src/main/AndroidManifest.xml"))
    paths.update(ROOT.rglob("src/main/res/xml/*.xml"))
    for source_root in APP_VARIANT_SOURCE_ROOTS:
        manifest = source_root / "AndroidManifest.xml"
        if manifest.is_file():
            paths.add(manifest)
        paths.update((source_root / "res/xml").glob("*.xml"))
    for path in sorted(paths):
        relative = path.relative_to(ROOT).as_posix()
        try:
            source = path.read_text(encoding="utf-8")
            ET.parse(path)
        except (OSError, ET.ParseError) as error:
            failures.append(f"{relative}: XML parse failed: {error}")
            continue

        for line_number, line in enumerate(mask_xml_comments(source).splitlines(), start=1):
            if HAN_RE.search(line):
                hits.append((relative, line_number, line.strip()))

    if hits:
        failures.append(
            f"Manifest/res/xml direct Chinese literals: {len(hits)} hit(s); "
            f"first: {relative_hit(hits[0])}"
        )
        for hit in hits[:10]:
            print(f"  xml-hit {relative_hit(hit)}")
        if len(hits) > 10:
            print(f"  ... {len(hits) - 10} more xml-hit(s)")
    else:
        print("PASS Manifest/res/xml direct Chinese literals: 0")


def relative_hit(hit: tuple[str, int, str]) -> str:
    path, line_number, text = hit
    return f"{path}:{line_number}: {text}"


def main() -> int:
    failures: list[str] = []
    check_resources(failures)
    check_inline_compose_text(failures)
    check_inline_visible_parameters(failures)
    check_xml_literals(failures)

    if failures:
        print(f"FAIL android-localization-check: {len(failures)} check(s)")
        for failure in failures:
            print(f"  {failure}")
        return 1

    print("PASS android-localization-check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
