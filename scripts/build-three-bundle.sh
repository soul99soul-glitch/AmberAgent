#!/usr/bin/env bash
# Rebuilds app/src/main/assets/generative-libs/guizang/three.min.js: three core + OrbitControls
# as one classic script exposing window.THREE (used by full_html widgets and MiniApps).
# Keep VERSION in sync with amberagent-ios/iosApp/scripts/build-three-bundle.sh.
set -euo pipefail
VERSION="${1:-0.186.1}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/generative-libs/guizang/three.min.js"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
npm init -y >/dev/null
npm i "three@$VERSION" esbuild --silent
cat > entry.js <<'JS'
import * as THREE_CORE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
window.THREE = Object.assign({}, THREE_CORE, { OrbitControls });
JS
npx esbuild entry.js --bundle --minify --format=iife --target=safari16 --legal-comments=none --outfile=bundle.js
{ printf '/*! three.js %s + OrbitControls | MIT License | https://github.com/mrdoob/three.js | exposes window.THREE */\n' "$VERSION"; cat bundle.js; } > "$OUT"
echo "wrote $OUT"
