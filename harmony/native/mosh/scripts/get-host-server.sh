#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
archive="$root/archives/mosh-1.4.0.pkg"
if [ ! -f "$archive" ]; then curl -fL --retry 2 https://github.com/mobile-shell/mosh/releases/download/mosh-1.4.0/mosh-1.4.0.pkg -o "$archive.part"; mv "$archive.part" "$archive"; fi
(cd "$root/archives" && shasum -a 256 -c "$root/HOST_SERVER_SHA256SUMS")
if [ ! -d "$root/build/host/official-package" ]; then pkgutil --expand-full "$archive" "$root/build/host/official-package"; fi
"$root/build/host/official-package/edu.mit.mosh.mosh.pkg/Payload/local/bin/mosh-server" --version
