#!/usr/bin/env python3
"""Manually reproduce the dated Amber/RikkaHub chat-kernel audit snapshot.

This migration-evidence tool is not part of the Android runtime or a standing
CI/release gate. Keep it only while the archived audit remains reproducible.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from difflib import SequenceMatcher
from pathlib import Path


def git_head(root: Path) -> str:
    return subprocess.check_output(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        text=True,
    ).strip()


def strip_comments(text: str) -> str:
    text = re.sub(
        r"/\*.*?\*/",
        lambda match: "\n" * match.group(0).count("\n"),
        text,
        flags=re.S,
    )
    return re.sub(r"//.*", "", text)


def normalize(text: str, namespace_mappings: list[dict[str, str]]) -> str:
    text = strip_comments(text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', " STRING ", text)
    text = re.sub(r"'(?:\\.|[^'\\])*'", " CHAR ", text)
    for mapping in namespace_mappings:
        text = text.replace(mapping["from"], mapping["to"])
    return text


def normalized_lines(
    text: str,
    namespace_mappings: list[dict[str, str]],
) -> list[tuple[int, str]]:
    normalized = normalize(text, namespace_mappings)
    result: list[tuple[int, str]] = []
    for number, line in enumerate(normalized.splitlines(), start=1):
        value = re.sub(r"\s+", " ", line).strip()
        if value:
            result.append((number, value))
    return result


def tokens(text: str, namespace_mappings: list[dict[str, str]]) -> list[str]:
    normalized = normalize(text, namespace_mappings)
    return re.findall(r"[A-Za-z_][A-Za-z_0-9]*|\d+|[^\s\w]", normalized)


def shingles(values: list[str], size: int) -> set[tuple[str, ...]]:
    return {
        tuple(values[index : index + size])
        for index in range(max(0, len(values) - size + 1))
    }


def percent(part: int, total: int) -> float:
    return round((part / total * 100.0) if total else 0.0, 1)


def longest_code_block(
    amber_lines: list[tuple[int, str]],
    rikka_lines: list[tuple[int, str]],
) -> dict[str, int] | None:
    matcher = SequenceMatcher(
        None,
        [line for _, line in amber_lines],
        [line for _, line in rikka_lines],
        autojunk=False,
    )
    candidates: list[tuple[int, int, int]] = []
    for block in matcher.get_matching_blocks():
        segment = amber_lines[block.a : block.a + block.size]
        code_line_count = sum(
            not value.startswith(("package ", "import ")) for _, value in segment
        )
        if code_line_count >= 3:
            candidates.append((block.size, block.a, block.b))
    if not candidates:
        return None
    size, amber_index, rikka_index = max(candidates)
    return {
        "normalized_line_count": size,
        "amber_start": amber_lines[amber_index][0],
        "amber_end": amber_lines[amber_index + size - 1][0],
        "rikka_start": rikka_lines[rikka_index][0],
        "rikka_end": rikka_lines[rikka_index + size - 1][0],
    }


def compare_pair(
    pair: dict[str, str],
    amber_root: Path,
    rikka_root: Path,
    namespace_mappings: list[dict[str, str]],
    shingle_size: int,
) -> dict[str, object]:
    amber_path = amber_root / pair["amber"]
    rikka_path = rikka_root / pair["rikka"]
    amber_text = amber_path.read_text()
    rikka_text = rikka_path.read_text()
    amber_lines = normalized_lines(amber_text, namespace_mappings)
    rikka_lines = normalized_lines(rikka_text, namespace_mappings)
    amber_tokens = tokens(amber_text, namespace_mappings)
    rikka_tokens = tokens(rikka_text, namespace_mappings)

    amber_line_set = {value for _, value in amber_lines}
    rikka_line_set = {value for _, value in rikka_lines}
    shared_lines = amber_line_set & rikka_line_set
    amber_shingles = shingles(amber_tokens, shingle_size)
    rikka_shingles = shingles(rikka_tokens, shingle_size)
    shared_shingles = amber_shingles & rikka_shingles

    return {
        "id": pair["id"],
        "amber": pair["amber"],
        "rikka": pair["rikka"],
        "amber_source_lines": len(amber_text.splitlines()),
        "rikka_source_lines": len(rikka_text.splitlines()),
        "ordered_token_similarity_pct": round(
            SequenceMatcher(
                None,
                amber_tokens,
                rikka_tokens,
                autojunk=False,
            ).ratio()
            * 100.0,
            1,
        ),
        "normalized_line_shared_amber_pct": percent(
            len(shared_lines), len(amber_line_set)
        ),
        "normalized_line_rikka_retained_pct": percent(
            len(shared_lines), len(rikka_line_set)
        ),
        "token_shingle_shared_amber_pct": percent(
            len(shared_shingles), len(amber_shingles)
        ),
        "token_shingle_rikka_retained_pct": percent(
            len(shared_shingles), len(rikka_shingles)
        ),
        "longest_normalized_code_block": longest_code_block(
            amber_lines,
            rikka_lines,
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--amber-root", type=Path, default=Path.cwd())
    parser.add_argument("--rikka-root", required=True, type=Path)
    parser.add_argument("--allow-amber-head-mismatch", action="store_true")
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text())
    amber_root = args.amber_root.resolve()
    rikka_root = args.rikka_root.resolve()
    actual_amber_head = git_head(amber_root)
    actual_rikka_head = git_head(rikka_root)
    expected_amber_head = manifest["baseline"]["amber_head"]
    expected_rikka_head = manifest["baseline"]["rikka_head"]

    if actual_amber_head != expected_amber_head and not args.allow_amber_head_mismatch:
        raise SystemExit(
            f"Amber HEAD mismatch: expected {expected_amber_head}, got {actual_amber_head}"
        )
    if actual_rikka_head != expected_rikka_head:
        raise SystemExit(
            f"Rikka HEAD mismatch: expected {expected_rikka_head}, got {actual_rikka_head}"
        )

    normalization = manifest["normalization"]
    excluded_wip = set(manifest.get("excluded_dirty_wip", []))
    compared_amber_paths = {pair["amber"] for pair in manifest["pairs"]}
    excluded_comparison_overlap = sorted(excluded_wip & compared_amber_paths)
    if excluded_comparison_overlap:
        raise SystemExit(
            "Dirty WIP paths must not enter the comparison set: "
            + ", ".join(excluded_comparison_overlap)
        )
    comparisons = [
        compare_pair(
            pair=pair,
            amber_root=amber_root,
            rikka_root=rikka_root,
            namespace_mappings=normalization["namespace_mappings"],
            shingle_size=normalization["token_shingle_size"],
        )
        for pair in manifest["pairs"]
    ]
    for comparison in comparisons:
        review_reasons: list[str] = []
        if (
            comparison["token_shingle_shared_amber_pct"]
            >= normalization["review_amber_shingle_pct"]
        ):
            review_reasons.append("amber_token_shingle_threshold")
        longest_block = comparison["longest_normalized_code_block"]
        if (
            longest_block is not None
            and longest_block["normalized_line_count"]
            >= normalization["review_block_min_normalized_lines"]
        ):
            review_reasons.append("normalized_block_threshold")
        comparison["review_required"] = bool(review_reasons)
        comparison["review_reasons"] = review_reasons
    result = {
        "schema_version": 1,
        "amber_head": actual_amber_head,
        "rikka_head": actual_rikka_head,
        "token_shingle_size": normalization["token_shingle_size"],
        "review_amber_shingle_pct": normalization["review_amber_shingle_pct"],
        "review_block_min_normalized_lines": normalization[
            "review_block_min_normalized_lines"
        ],
        "excluded_dirty_wip_count": len(excluded_wip),
        "pairs": comparisons,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
