ALPINE_DIR=$PREFIX/local/alpine
ALPINE_ARCHIVE=$PREFIX/files/alpine.tar.gz

[ ! -f "$ALPINE_ARCHIVE" ] && ALPINE_ARCHIVE=$PREFIX/files/alpine.tar

mkdir -p "$ALPINE_DIR"

if [ -z "$(ls -A "$ALPINE_DIR" 2>/dev/null | grep -vE '^(root|tmp)$')" ]; then
    tar -xf "$ALPINE_ARCHIVE" -C "$ALPINE_DIR"
fi

FIPS_COMPAT_FILE="$PREFIX/local/sysctl_crypto_fips_enabled"
[ ! -f "$FIPS_COMPAT_FILE" ] && {
    mkdir -p "$PREFIX/local"
    printf '0\n' > "$FIPS_COMPAT_FILE"
}

if [ -n "$AMBERAGENT_HOST_WORKSPACE" ]; then
    mkdir -p "$AMBERAGENT_HOST_WORKSPACE"
    mkdir -p "$ALPINE_DIR/workspace"
fi

[ ! -e "$PREFIX/local/bin/proot" ] && cp "$PREFIX/files/proot" "$PREFIX/local/bin"

for sofile in "$PREFIX/files/"*.so.2; do
    dest="$PREFIX/local/lib/$(basename "$sofile")"
    [ ! -e "$dest" ] && cp "$sofile" "$dest"
done

ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do
 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $FIPS_COMPAT_FILE:/proc/.sysctl_crypto_fips_enabled"

if [ -n "$AMBERAGENT_HOST_WORKSPACE" ]; then
  ARGS="$ARGS -b $AMBERAGENT_HOST_WORKSPACE:/workspace"
fi

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

ARGS="$ARGS -b /sys"

if [ ! -d "$PREFIX/local/alpine/tmp" ]; then
 mkdir -p "$PREFIX/local/alpine/tmp"
 chmod 1777 "$PREFIX/local/alpine/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/alpine/tmp:/dev/shm"

# --- DNS setup (fixes package download failures in PROOT) ---
RESOLV_CONF="$ALPINE_DIR/etc/resolv.conf"
mkdir -p "$(dirname "$RESOLV_CONF")"
if [ ! -s "$RESOLV_CONF" ]; then
    # Try Android system DNS first, fall back to public resolvers
    DNS1=$(getprop net.dns1 2>/dev/null)
    DNS2=$(getprop net.dns2 2>/dev/null)
    {
        [ -n "$DNS1" ] && echo "nameserver $DNS1"
        [ -n "$DNS2" ] && echo "nameserver $DNS2"
        if [ -z "$DNS1" ] && [ -z "$DNS2" ]; then
            echo "nameserver 8.8.8.8"
            echo "nameserver 8.8.4.4"
        fi
    } > "$RESOLV_CONF"
fi
# ----------------------------------------------------

ARGS="$ARGS -r $PREFIX/local/alpine"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

exec $LINKER $PREFIX/local/bin/proot $ARGS sh $PREFIX/local/bin/init "$@"
