#!/usr/bin/env bash
# check-refactor-state.sh
# Asserts the refactor invariants — these are the load-bearing properties
# that prevent regression of the Phase 0-E achievements.
#
# Exit code: 0 if all invariants hold, 1 if any violated.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

fail=0

# Invariant 1: minimum physical Gradle module count
modules=$(find core feature -maxdepth 4 -name "build.gradle.kts" 2>/dev/null | wc -l | tr -d ' ')
MIN_MODULES=15
if [ "$modules" -lt "$MIN_MODULES" ]; then
  echo "::error::Physical Gradle modules dropped below $MIN_MODULES (got $modules)"
  fail=1
else
  echo "OK: $modules physical Gradle modules (>= $MIN_MODULES)"
fi

# Invariant 2: app.amber.* file count should not regress
amber_files=$(find app/src/main/java/app/amber -name "*.kt" -not -path "*/build/*" 2>/dev/null | wc -l | tr -d ' ')
MIN_AMBER_FILES=200
if [ "$amber_files" -lt "$MIN_AMBER_FILES" ]; then
  echo "::error::app.amber.* file count dropped below $MIN_AMBER_FILES (got $amber_files)"
  fail=1
else
  echo "OK: $amber_files files in app.amber.* (>= $MIN_AMBER_FILES)"
fi

# Invariant 3: every me.rerere.* file must be in the allowlist
allowlist="$SCRIPT_DIR/legacy-package-allowlist.txt"
unaccounted=$(
  find app/src/main/java/me/rerere -name "*.kt" -not -path "*/build/*" 2>/dev/null | while read f; do
    matched=false
    while IFS= read -r entry; do
      [ -z "$entry" ] && continue
      [[ "$entry" == \#* ]] && continue
      if [[ "$entry" == *"**"* ]]; then
        prefix=${entry%%\*\*}
        if [[ "$f" == "$prefix"* ]]; then matched=true; break; fi
      elif [[ "$f" == "$entry" ]]; then
        matched=true; break
      fi
    done < "$allowlist"
    if [ "$matched" = false ]; then echo "$f"; fi
  done
)
if [ -n "$unaccounted" ]; then
  echo "::error::Unaccounted legacy files (not in allowlist):"
  echo "$unaccounted"
  fail=1
else
  echo "OK: all me.rerere.* files allowlisted per ADR-0001"
fi

# Invariant 4: ChatTurnAgent + ChatEventProjector + AgentRunner all present
required_kernel=(
  "app/src/main/java/app/amber/feature/chat/impl/ChatTurnAgent.kt"
  "app/src/main/java/app/amber/feature/chat/impl/ChatEventProjector.kt"
  "core/agent-runtime-impl/src/main/kotlin/app/amber/core/agent/runtime/impl/InProcessAgentRunner.kt"
  "core/agent-runtime/src/main/kotlin/app/amber/core/agent/runtime/Agent.kt"
)
for f in "${required_kernel[@]}"; do
  if [ ! -f "$f" ]; then
    echo "::error::Kernel file missing: $f"
    fail=1
  fi
done
echo "OK: kernel files present"

if [ "$fail" -eq 0 ]; then
  echo ""
  echo "All refactor invariants hold."
fi
exit $fail
