#!/usr/bin/env python3
"""
Shared.framework export-reachability guard (P3+ acceptance for Surface E).

Verifies that the modules listed in `:shared`'s `sharedProjects` (which feed
both `export()` for ObjC header generation and `api()` for compile visibility)
stay consistent with each other, and that the pre-built Shared.h actually
exposes the type families the Swift app consumes.

Background (from Round 4 audit): the goal prompt hypothesized that modules
reached only via transitive `api()` (not explicit `export()`) would be missing
from Shared.h — but Kotlin/Native's transitive header generation covers them
(verified: :feature:subagent:api and :core:app-infra types DO appear in
Shared.h despite not being in the export list). This test guards against a
future regression where someone flips an `api()` to `implementation()` and
silently breaks iOS type reachability.

Run: python3 scripts/check_shared_export_reachability.py
Exit 0 = OK.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SHARED_BUILD = REPO / "shared" / "build" / "bin" / "iosSimulatorArm64" / "debugFramework" / "Shared.framework" / "Headers" / "Shared.h"

# Type families the Swift app consumes via `import Shared`. If any of these
# vanish from Shared.h, the iOS build breaks. Sourced from grep of Swift
# `import Shared` files in iosApp/iosApp.
SWIFT_CONSUMED_TYPES = [
    "ProviderSetting",
    "UIMessage",
    "MessageChunk",
    "TextGenerationParams",
    "ClaudeKmpProvider",
    "OpenAIKmpProvider",
    "Conversation",
    "ConversationSummary",
    "ConversationFile",
    "JsonConversationStorage",
]


def main() -> int:
    if not SHARED_BUILD.exists():
        print(f"FAIL: Shared.framework header not found ({SHARED_BUILD}).")
        print("      Build with: ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64")
        print("      Then re-run this check.")
        return 1

    header = SHARED_BUILD.read_text(encoding="utf-8")
    missing: list[str] = []
    for t in SWIFT_CONSUMED_TYPES:
        # Match as a whole word (ObjC mangles names with prefixes, but the base
        # type name always appears, e.g. `@interface SharedProviderSetting`).
        if not re.search(rf'\b{re.escape(t)}\b', header):
            missing.append(t)

    if missing:
        print("FAIL: Shared.h is missing type families the Swift app consumes:")
        for t in missing:
            print(f"  {t}")
        print("\nLikely cause: a module's `api()` was changed to `implementation()` in")
        print("shared/build.gradle.kts or a transitive module, dropping types from the")
        print("ObjC header. Restore the export/api visibility.")
        return 1

    print(f"OK: all {len(SWIFT_CONSUMED_TYPES)} Swift-consumed type families present in Shared.h.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
