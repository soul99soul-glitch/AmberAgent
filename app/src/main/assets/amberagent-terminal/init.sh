export HOME=/root
export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin
export LANG=C.UTF-8
export TERM=${TERM:-xterm-256color}

if [ -d /workspace ]; then
  cd /workspace || cd /
fi

exec "$@"
