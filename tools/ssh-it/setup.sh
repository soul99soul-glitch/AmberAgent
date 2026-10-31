#!/bin/sh
set -eu
cd "$(dirname "$0")"
umask 077
mkdir -p fixtures/authorized_keys
if [ ! -f fixtures/client ]; then ssh-keygen -q -t ed25519 -N '' -C amber-ssh-it -f fixtures/client; fi
cp fixtures/client.pub fixtures/authorized_keys/jumper
cp fixtures/client.pub fixtures/authorized_keys/deploy
