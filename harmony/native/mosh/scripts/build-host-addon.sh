#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cpp="$root/../../entry/src/main/cpp"
node_home=${AMBER_MOSH_NODE_HOME:-/opt/homebrew/Cellar/node/26.8.1}
if [ ! -f "$node_home/include/node/node_api.h" ]; then echo 'Set AMBER_MOSH_NODE_HOME to a Node distribution with headers' >&2; exit 2; fi
"$root/scripts/build-deps.sh" host
mkdir -p "$root/build/host/napi-include/napi"
ln -sf "$node_home/include/node/node_api.h" "$root/build/host/napi-include/napi/native_api.h"
prefix="$root/build/host/install"
crypto="$root/../ssh/build/host/install"
/usr/bin/clang++ -std=c++17 -Wall -Wextra -Werror -Wno-deprecated-declarations -fPIC -shared -undefined dynamic_lookup \
  -DAMBER_MOSH_EMBEDDED=1 -DNDEBUG -I"$cpp" -I"$root/build/host/napi-include" -I"$node_home/include/node" \
  -isystem "$prefix/include" -isystem "$crypto/include" \
  -isystem "$prefix/include/mosh/crypto" -isystem "$prefix/include/mosh/network" -isystem "$prefix/include/mosh/statesync" \
  -isystem "$prefix/include/mosh/terminal" -isystem "$prefix/include/mosh/util" \
  "$cpp/mosh_core.cpp" "$cpp/mosh_napi.cpp" "$root/tests/host_addon.cpp" \
  "$prefix/lib/libamber_mosh.a" "$prefix/lib/libprotobuf-lite.a" "$crypto/lib/libcrypto.a" -lz \
  -o "$root/build/host/amber_mosh_host.node"
