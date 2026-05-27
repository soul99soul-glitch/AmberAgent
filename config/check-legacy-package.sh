#!/usr/bin/env bash
# check-legacy-package.sh
# Blocks new Kotlin files from being added to me.rerere.* packages
# unless they're in the allowlist.
#
# Usage:
#   ./config/check-legacy-package.sh              # check against git HEAD
#   ./config/check-legacy-package.sh <base-ref>   # check against a base ref (for CI)
#
# Exit code: 0 if clean, 1 if violations found

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ALLOWLIST="$SCRIPT_DIR/legacy-package-allowlist.txt"

if [ ! -f "$ALLOWLIST" ]; then
  echo "ERROR: allowlist not found at $ALLOWLIST"
  exit 1
fi

BASE_REF="${1:-HEAD}"

# Get newly added files (A = added) relative to base ref
if [ "$BASE_REF" = "HEAD" ]; then
  # Local mode: check untracked + staged new files
  NEW_FILES=$(git -C "$REPO_ROOT" diff --cached --name-only --diff-filter=A 2>/dev/null || true)
  UNTRACKED=$(git -C "$REPO_ROOT" ls-files --others --exclude-standard 2>/dev/null || true)
  NEW_FILES=$(printf '%s\n%s' "$NEW_FILES" "$UNTRACKED" | sort -u | grep -v '^$' || true)
else
  NEW_FILES=$(git -C "$REPO_ROOT" diff --name-only --diff-filter=A "$BASE_REF"...HEAD 2>/dev/null || true)
fi

# Filter to only Kotlin files in legacy package paths
LEGACY_PATTERN="^(app|ai|common|highlight|search|tts|document|web)/src/.*/java/me/rerere/"
VIOLATIONS=()

while IFS= read -r file; do
  [ -z "$file" ] && continue
  if echo "$file" | grep -qE "$LEGACY_PATTERN"; then
    ALLOWED=false
    while IFS= read -r entry; do
      [ -z "$entry" ] && continue
      [[ "$entry" == \#* ]] && continue
      if [[ "$entry" == *"**"* ]]; then
        PREFIX="${entry%%\*\*}"
        if [[ "$file" == "$PREFIX"* ]]; then
          ALLOWED=true
          break
        fi
      elif [ "$file" = "$entry" ]; then
        ALLOWED=true
        break
      fi
    done < "$ALLOWLIST"

    if [ "$ALLOWED" = false ]; then
      VIOLATIONS+=("$file")
    fi
  fi
done <<< "$NEW_FILES"

if [ ${#VIOLATIONS[@]} -gt 0 ]; then
  echo "::error::Legacy package guard: new files in me.rerere.* must move to app.amber.*"
  echo ""
  echo "Violations:"
  for v in "${VIOLATIONS[@]}"; do
    echo "  - $v"
  done
  echo ""
  echo "If a file MUST stay in me.rerere.*, add it to config/legacy-package-allowlist.txt"
  exit 1
fi

echo "Legacy package guard: OK (no new files in me.rerere.*)"
exit 0
