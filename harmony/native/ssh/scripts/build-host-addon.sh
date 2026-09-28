#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cpp="$root/../../entry/src/main/cpp"
node_home=${AMBER_SSH_NODE_HOME:-/opt/homebrew/Cellar/node/26.8.1}
if [ ! -f "$node_home/include/node/node_api.h" ]; then
  echo 'Set AMBER_SSH_NODE_HOME to an installed Node distribution with headers' >&2
  exit 2
fi
"$root/scripts/build-deps.sh" host
mkdir -p "$root/build/host/napi-include/napi"
ln -sf "$node_home/include/node/node_api.h" "$root/build/host/napi-include/napi/native_api.h"
/usr/bin/clang++ -std=c++17 -Wall -Wextra -Werror -fPIC -shared -undefined dynamic_lookup \
  -I"$cpp" -I"$root/build/host/napi-include" -I"$node_home/include/node" \
  -I"$root/build/host/install/include" "$cpp/ssh_core.cpp" "$cpp/ssh_napi.cpp" \
  "$root/tests/host_addon.cpp" "$root/build/host/install/lib/libssh2.a" \
  "$root/build/host/install/lib/libcrypto.a" -o "$root/build/host/amber_ssh_host.node"
