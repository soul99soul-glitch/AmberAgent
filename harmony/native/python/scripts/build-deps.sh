#!/bin/sh
set -eu
# SDK toolchains/diff is a device binary and can falsely report equality on this host.
PATH="/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
export PATH
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mode=${1:-ohos}
case "$mode" in host|ohos) ;; *) echo 'Usage: build-deps.sh [host|ohos]' >&2; exit 2 ;; esac
mkdir -p "$root/build/sources" "$root/build/$mode/Modules"
if [ ! -f "$root/build/Python-3.14.7.tar.xz" ]; then
  curl -fL https://www.python.org/ftp/python/3.14.7/Python-3.14.7.tar.xz -o "$root/build/Python-3.14.7.tar.xz.part"
  mv "$root/build/Python-3.14.7.tar.xz.part" "$root/build/Python-3.14.7.tar.xz"
fi
(cd "$root/build" && shasum -a 256 -c "$root/SHA256SUMS")
source="$root/build/sources/Python-3.14.7"
if [ ! -d "$source" ]; then tar -xf "$root/build/Python-3.14.7.tar.xz" -C "$root/build/sources"; fi
(cd "$root" && shasum -a 256 -c PATCH_SHA256SUMS)
if ! grep -q "API12 libintl.h exists" "$source/Modules/_localemodule.c"; then
  (cd "$source" && patch -p1 < "$root/patches/cpython-3.14.7-ohos12-gettext.patch")
fi
(cd "$root" && shasum -a 256 -c PATCHED_SOURCE_SHA256SUMS)
# Only data-processing modules are compiled into the archive. No loadable extensions.
cat > "$root/build/$mode/Modules/Setup.local" <<'SETUP'
*static*
array arraymodule.c
_bisect _bisectmodule.c
_heapq _heapqmodule.c
_json _json.c
_random _randommodule.c
_struct _struct.c
math mathmodule.c
cmath cmathmodule.c
_statistics _statisticsmodule.c
binascii binascii.c -I.
unicodedata unicodedata.c
_datetime _datetimemodule.c
*disabled*
_asyncio _lsprof _pickle _queue _remote_debugging _interpreters _interpchannels _interpqueues
_zoneinfo _decimal _bz2 _lzma _zstd zlib _dbm _gdbm readline _md5 _sha1 _sha2 _sha3 _blake2 _hmac
pyexpat _elementtree _codecs_cn _codecs_hk _codecs_iso2022 _codecs_jp _codecs_kr _codecs_tw _multibytecodec
fcntl grp mmap _posixsubprocess resource select _socket syslog termios _posixshmem _multiprocessing
_ctypes _curses _curses_panel _sqlite3 _ssl _hashlib _tkinter _uuid _scproxy _crypt _ctypes_test
_testcapi _testinternalcapi _testbuffer _testimportmultiple _testmultiphase _testsinglephase xxsubtype _xxtestfuzz
SETUP
if [ ! -f "$root/build/$mode/Makefile" ] || [ ! -f "$root/build/$mode/pyconfig.h" ]; then
  if [ "$mode" = host ]; then
    (cd "$root/build/host" && "$source/configure" --prefix="$root/build/host/install" \
      --disable-shared --without-ensurepip --disable-test-modules \
      CFLAGS='-O2 -fPIC')
  else
    "$root/scripts/build-deps.sh" host
    sdk="${OHOS_BASE_SDK_HOME:-$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony}/native"
    # Configure uses musl classification; all compilation/linking uses OHOS API12, never a Linux compiler.
    (cd "$root/build/ohos" && "$source/configure" \
      --build="$($source/config.guess)" --host=aarch64-unknown-linux-musl \
      --with-build-python="$root/build/host/python.exe" --prefix="$root/build/ohos/install" \
      --disable-shared --without-ensurepip --disable-test-modules --without-c-locale-coercion --disable-ipv6 \
      CC="$root/scripts/ohos-clang.sh" AR="$sdk/llvm/bin/llvm-ar" RANLIB="$sdk/llvm/bin/llvm-ranlib" \
      CFLAGS='-O2 -fPIC' ac_cv_file__dev_ptmx=no ac_cv_file__dev_ptc=no)
  fi
fi
(cd "$root/build/$mode" && make -j "${AMBER_PYTHON_BUILD_JOBS:-4}" libpython3.14.a)
if [ "$mode" = host ]; then (cd "$root/build/host" && make -j "${AMBER_PYTHON_BUILD_JOBS:-4}" python.exe); fi
mkdir -p "$root/build/$mode/install/lib" "$root/build/$mode/install/include/python3.14"
cp "$root/build/$mode/libpython3.14.a" "$root/build/$mode/install/lib/"
cp -R "$source/Include/" "$root/build/$mode/install/include/python3.14/"
cp "$root/build/$mode/pyconfig.h" "$root/build/$mode/install/include/python3.14/"
