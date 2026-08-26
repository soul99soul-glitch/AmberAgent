#!/usr/bin/env python3
"""Manually reproduce the dated Android product-detachment audit evidence.

This migration-evidence tool is not part of the Android runtime or a standing
CI/release gate. Keep it only while the archived manifests remain reproducible.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RIKKA_TOKENS = (
    b"api.rikka-ai.com",
    b"com.github.rikkahub",
    b"me.rerere.hugeicons",
    b"rikkahub.svg",
)
TEXT_EXTENSIONS = {
    ".gradle", ".java", ".json", ".kts", ".kt", ".properties", ".toml", ".xml", ".yaml", ".yml"
}


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, text=True, capture_output=True
    ).stdout.rstrip("\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_files() -> list[Path]:
    ignored = {"build", ".gradle", ".git"}
    return sorted(
        path
        for path in ROOT.rglob("*.kt")
        if not ignored.intersection(path.relative_to(ROOT).parts)
    )


def production_text_files() -> list[Path]:
    files: set[Path] = set()
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix not in TEXT_EXTENSIONS:
            continue
        relative = path.relative_to(ROOT)
        if {"build", ".gradle", ".git", "docs"}.intersection(relative.parts):
            continue
        if "src" in relative.parts and "main" in relative.parts:
            files.add(path)
        elif relative.as_posix() in {"app/build.gradle.kts", "gradle/libs.versions.toml"}:
            files.add(path)
    return sorted(files)


def line_matches(path: Path, pattern: re.Pattern[str]) -> list[dict[str, object]]:
    matches: list[dict[str, object]] = []
    for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
        if pattern.search(line):
            matches.append({"line": number, "text": line.strip()})
    return matches


def scan_zip(path: Path) -> dict[str, object]:
    hits: dict[str, list[str]] = {token.decode(): [] for token in RIKKA_TOKENS}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        for token in RIKKA_TOKENS:
            label = token.decode()
            hits[label].extend(name for name in names if label.lower() in name.lower())
        for info in archive.infolist():
            try:
                with archive.open(info) as stream:
                    tail = b""
                    found: set[bytes] = set()
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        payload = tail + chunk
                        found.update(token for token in RIKKA_TOKENS if token in payload)
                        tail = payload[-max(map(len, RIKKA_TOKENS)) :]
            except (KeyError, RuntimeError):
                continue
            for token in found:
                hits[token.decode()].append(info.filename)
        hits = {label: sorted(set(paths)) for label, paths in hits.items()}
    return {
        "path": str(path.relative_to(ROOT)),
        "sha256": sha256(path),
        "size_bytes": path.stat().st_size,
        "rikka_hits": hits,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", required=True)
    parser.add_argument("--resolved-dependencies", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    kotlin_files = source_files()
    import_pattern = re.compile(r"^import me\.rerere\.hugeicons(?:\.stroke)?\.([A-Za-z0-9_]+)$")
    huge_files: list[dict[str, object]] = []
    concrete_icons: set[str] = set()
    for path in kotlin_files:
        icons: list[str] = []
        has_root = False
        for line in path.read_text(errors="replace").splitlines():
            if line == "import me.rerere.hugeicons.HugeIcons":
                has_root = True
                continue
            match = import_pattern.match(line)
            if match:
                icons.append(match.group(1))
                concrete_icons.add(match.group(1))
        if has_root or icons:
            huge_files.append(
                {
                    "path": str(path.relative_to(ROOT)),
                    "imports_root": has_root,
                    "icons": sorted(set(icons)),
                }
            )

    endpoint_pattern = re.compile(r"api\.rikka-ai\.com")
    namespace_pattern = re.compile(r"(?:com\.github\.rikkahub|me\.rerere\.hugeicons|RikkaHub|rikkahub)")
    endpoint_hits = []
    namespace_hits = []
    for path in production_text_files():
        endpoint = line_matches(path, endpoint_pattern)
        if endpoint:
            endpoint_hits.append({"path": str(path.relative_to(ROOT)), "matches": endpoint})
        namespace = line_matches(path, namespace_pattern)
        if namespace:
            namespace_hits.append({"path": str(path.relative_to(ROOT)), "matches": namespace})

    catalog = ROOT / "gradle/libs.versions.toml"
    coordinate_hits = line_matches(catalog, re.compile(r"com\.github\.rikkahub"))

    asset_hits = []
    assets_root = ROOT / "app/src/main/assets"
    if assets_root.exists():
        for path in sorted(p for p in assets_root.rglob("*") if p.is_file()):
            name_hit = "rikka" in path.name.lower()
            payload_hit = b"rikka" in path.read_bytes().lower()
            if name_hit or payload_hit:
                asset_hits.append(
                    {
                        "path": str(path.relative_to(ROOT)),
                        "sha256": sha256(path),
                        "name_hit": name_hit,
                        "payload_hit": payload_hit,
                    }
                )

    artifacts = []
    outputs = ROOT / "app/build/outputs"
    if outputs.exists():
        for path in sorted(list(outputs.rglob("*.apk")) + list(outputs.rglob("*.aab"))):
            artifacts.append(scan_zip(path))

    notice_files = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file() or {
            "build",
            ".gradle",
            ".git",
            ".venv",
            "venv",
            "__pycache__",
        }.intersection(path.relative_to(ROOT).parts):
            continue
        lower_name = path.name.lower()
        if (
            lower_name in {"notice", "notice.md", "notice.txt", "sbom.json", "licenses.json"}
            or "acknowledg" in lower_name
            or "sbom" in lower_name
        ):
            text = path.read_text(errors="replace")
            notice_files.append(
                {
                    "path": str(path.relative_to(ROOT)),
                    "sha256": sha256(path),
                    "unknown_occurrences": len(re.findall(r"\bunknown\b", text, re.IGNORECASE)),
                    "rikka_occurrences": len(re.findall(r"rikka", text, re.IGNORECASE)),
                }
            )

    status_lines = git("status", "--short").splitlines()
    if not args.resolved_dependencies.exists():
        parser.error(f"resolved dependency report not found: {args.resolved_dependencies}")
    resolved_pattern = re.compile(r"(com\.github\.rikkahub[^:\s]*:[^:\s]+:[^\s(]+)")
    resolved = sorted(
        {
            match.group(1)
            for line in args.resolved_dependencies.read_text().splitlines()
            if (match := resolved_pattern.search(line))
        }
    )

    call_chain_patterns = {
        "registry_and_serial_tag": (
            ROOT / "search/src/main/java/app/amber/search/SearchService.kt",
            re.compile(r"AmberAgentSearch(?:Options|Service)|amber_agent"),
        ),
        "endpoint_owner": (
            ROOT / "search/src/main/java/app/amber/search/AmberAgentSearchService.kt",
            re.compile(r"api\.rikka-ai\.com|AmberAgentSearchOptions"),
        ),
        "settings_editor": (
            ROOT / "app/src/main/java/app/amber/feature/ui/pages/setting/SettingSearchServiceEditorSheet.kt",
            re.compile(r"AmberAgentSearchOptions"),
        ),
        "settings_persistence": (
            ROOT / "core/settings/src/main/kotlin/app/amber/core/settings/prefs/SearchPrefs.kt",
            re.compile(r"SEARCH_SERVICES|searchServices"),
        ),
        "raw_legacy_decoder": (
            ROOT / "core/settings/src/main/kotlin/app/amber/core/settings/prefs/PrefsDecode.kt",
            re.compile(r"amber_agent|decodeSettingsDroppingLegacySearchService|enableWebSearch"),
        ),
        "cached_backup_rescue": (
            ROOT / "core/settings/src/main/kotlin/app/amber/core/settings/prefs/SettingsProviderRescue.kt",
            re.compile(r"decodeSettingsDroppingLegacySearchService"),
        ),
        "webdav_backup_restore": (
            ROOT / "app/src/main/java/app/amber/core/sync/webdav/WebDavSync.kt",
            re.compile(r"decodeSettingsDroppingLegacySearchService"),
        ),
        "s3_backup_restore": (
            ROOT / "app/src/main/java/app/amber/core/sync/S3Sync.kt",
            re.compile(r"decodeSettingsDroppingLegacySearchService"),
        ),
        "sync_archive_restore": (
            ROOT / "app/src/main/java/app/amber/core/sync/core/SyncRedactor.kt",
            re.compile(r"decodeSettingsDroppingLegacySearchService"),
        ),
        "secret_redaction": (
            ROOT / "core/settings/src/main/kotlin/app/amber/core/settings/secret/SecretRedactor.kt",
            re.compile(r"AmberAgentSearchOptions"),
        ),
        "secret_migration": (
            ROOT / "core/settings/src/main/kotlin/app/amber/core/settings/secret/SettingsSecretMigrator.kt",
            re.compile(r"SearchServiceOptions|searchServices"),
        ),
        "settings_normalization": (
            ROOT / "core/settings/src/main/kotlin/app/amber/core/settings/prefs/SettingsAggregator.kt",
            re.compile(r"searchServices|searchEnabledServiceIds"),
        ),
        "tool_selector_alias": (
            ROOT / "app/src/main/java/app/amber/core/ai/tools/SearchAggregator.kt",
            re.compile(r"AmberAgentSearchOptions|amber_agent|amberagent"),
        ),
        "tool_availability": (
            ROOT / "app/src/main/java/app/amber/core/ai/tools/SearchOrchestrator.kt",
            re.compile(r"AmberAgentSearchOptions"),
        ),
        "deepread_priority": (
            ROOT / "app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSourcePrefetcher.kt",
            re.compile(r"AmberAgentSearchOptions"),
        ),
    }
    search_call_chain = {
        name: {
            "path": str(path.relative_to(ROOT)),
            "matches": line_matches(path, pattern) if path.exists() else [],
        }
        for name, (path, pattern) in call_chain_patterns.items()
    }

    about_path = ROOT / "app/src/main/java/app/amber/feature/ui/pages/setting/SettingAboutPage.kt"
    matcher_path = ROOT / "app/src/main/java/app/amber/core/utils/AIIconMatcher.kt"
    icon_loader_path = ROOT / "app/src/main/java/app/amber/feature/ui/components/ui/AIIcon.kt"

    other_platform_wip_present = any(
        "apps/ios/" in line or line[3:].startswith("../ios/") for line in status_lines
    )
    document = {
        "schema_version": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "phase": args.phase,
        "scope": "AmberAgent Android product detachment residuals",
        "git": {
            "head": git("rev-parse", "HEAD"),
            "branch": git("branch", "--show-current") or None,
            "detached": not bool(git("branch", "--show-current")),
            "dirty_entry_count": len(status_lines),
            "android_dirty_entry_count": len(status_lines) - sum(
                "apps/ios/" in line or line[3:].startswith("../ios/") for line in status_lines
            ),
            "other_platform_wip_present": other_platform_wip_present,
            "status_sha256": hashlib.sha256("\n".join(status_lines).encode()).hexdigest(),
            "android_dirty_entries": [
                line for line in status_lines
                if "apps/ios/" not in line and not line[3:].startswith("../ios/")
            ],
            "other_platform_wip_entry_count": sum(
                "apps/ios/" in line or line[3:].startswith("../ios/") for line in status_lines
            ),
        },
        "dependencies": {
            "catalog_rikka_coordinates": coordinate_hits,
            "resolved_rikka_coordinates": resolved,
            "resolved_rikka_count": len(resolved),
            "resolved_report": {
                "command": "./gradlew :app:dependencies --configuration debugRuntimeClasspath | rg 'com\\.github\\.rikkahub'",
                "sha256": sha256(args.resolved_dependencies),
            },
        },
        "hugeicons": {
            "file_count": len(huge_files),
            "concrete_icon_count": len(concrete_icons),
            "concrete_icons": sorted(concrete_icons),
            "files": huge_files,
        },
        "source": {
            "rikka_endpoint_hits": endpoint_hits,
            "rikka_endpoint_file_count": len(endpoint_hits),
            "rikka_namespace_or_brand_hits": namespace_hits,
            "search_legacy_route": search_call_chain,
            "about_current_product_links": {
                "path": str(about_path.relative_to(ROOT)),
                "matches": line_matches(about_path, re.compile(r"github\.com/rikkahub")),
                "canonical_repository_matches": line_matches(
                    about_path,
                    re.compile(r"github\.com/soul99soul-glitch/AmberAgent\""),
                ),
                "canonical_license_matches": line_matches(
                    about_path,
                    re.compile(r"github\.com/soul99soul-glitch/AmberAgent/blob/main/LICENSE"),
                ),
            },
            "dynamic_asset_route": {
                "matcher": line_matches(matcher_path, re.compile(r"PATTERN_AMBERAGENT|rikka")),
                "loader": line_matches(icon_loader_path, re.compile(r"android_asset/icons|computeAIIconByName")),
            },
        },
        "assets": asset_hits,
        "artifacts": artifacts,
        "notice_sbom": {
            "status": "present" if notice_files else "missing",
            "files": notice_files,
            "file_count": len(notice_files),
            "unknown_occurrences": (
                sum(item["unknown_occurrences"] for item in notice_files) if notice_files else None
            ),
            "root_license": {
                "path": "LICENSE",
                "present": (ROOT / "LICENSE").exists(),
                "sha256": sha256(ROOT / "LICENSE") if (ROOT / "LICENSE").exists() else None,
            },
        },
        "device_evidence": {
            "simulator_ui": "not verified by this manifest",
            "physical_device_ui": "not verified by this manifest",
            "database_upgrade": "not verified by this manifest",
            "kill_relaunch": "not verified by this manifest",
        },
    }
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
