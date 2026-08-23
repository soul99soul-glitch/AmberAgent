#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <ios|android> <target-path>" >&2
  exit 2
fi

platform="$1"
target_path="$2"

case "$platform" in
  ios|android) ;;
  *)
    echo "platform must be ios or android" >&2
    exit 2
    ;;
esac

repo_root=$(git rev-parse --show-toplevel)

if [ -e "$target_path" ]; then
  echo "target already exists: $target_path" >&2
  exit 1
fi

git -C "$repo_root" worktree add --detach "$target_path" HEAD
git -C "$target_path" sparse-checkout init --cone
git -C "$target_path" sparse-checkout set "apps/$platform" core docs/current scripts

echo "created $platform workspace at $target_path"
echo "open $target_path/apps/$platform for platform work"
