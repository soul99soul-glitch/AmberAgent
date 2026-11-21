#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cpp="$root/../../entry/src/main/cpp"
node_home=${AMBER_PYTHON_NODE_HOME:-$(node -p 'require("node:path").resolve(process.execPath, "../..")')}
if [ ! -f "$node_home/include/node/node_api.h" ]; then echo 'Node headers missing; set AMBER_PYTHON_NODE_HOME' >&2; exit 2; fi
case "$(uname -s)" in
  Darwin)
    compiler=${CXX:-/usr/bin/clang++}
    set -- -undefined dynamic_lookup -framework CoreFoundation ;;
  Linux)
    compiler=${CXX:-clang++}
    set -- -pthread -ldl -lm -lutil ;;
  *)
    echo 'Unsupported CPython addon host' >&2
    exit 2 ;;
esac
"$root/scripts/build-deps.sh" host
mkdir -p "$root/build/host/napi-include/napi"
ln -sf "$node_home/include/node/node_api.h" "$root/build/host/napi-include/napi/native_api.h"
"$compiler" -std=c++17 -Wall -Wextra -Werror -fPIC -shared \
  -I"$cpp" -I"$root/build/host/napi-include" -I"$node_home/include/node" \
  -I"$root/build/host/install/include/python3.14" "$cpp/python_core.cpp" "$cpp/python_napi.cpp" \
  "$root/tests/host_addon.cpp" "$root/build/host/install/lib/libpython3.14.a" \
  "$@" -o "$root/build/host/amber_python_host.node"
