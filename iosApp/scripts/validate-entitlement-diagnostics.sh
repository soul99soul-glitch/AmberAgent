#!/bin/sh
set -eu

entitlements_path="${1:?missing entitlements path}"
info_plist_path="${2:?missing Info.plist path}"

/usr/bin/python3 - "$entitlements_path" "$info_plist_path" <<'PY'
import plistlib
import sys

entitlements_path, info_plist_path = sys.argv[1], sys.argv[2]

with open(entitlements_path, "rb") as handle:
    entitlements = plistlib.load(handle)

with open(info_plist_path, "rb") as handle:
    info_plist = plistlib.load(handle)

configured = info_plist.get("AmberAgentConfiguredEntitlements")
if configured is None:
    print("AmberAgentConfiguredEntitlements is missing from Info.plist", file=sys.stderr)
    sys.exit(1)

if not isinstance(configured, list) or any(not isinstance(item, str) for item in configured):
    print("AmberAgentConfiguredEntitlements must be an array of strings", file=sys.stderr)
    sys.exit(1)

declared = sorted(entitlements.keys())
configured = sorted(configured)

if declared != configured:
    print("AmberAgentConfiguredEntitlements must match AmberAgent.entitlements exactly.", file=sys.stderr)
    print(f"Declared in entitlements: {declared}", file=sys.stderr)
    print(f"Configured in Info.plist: {configured}", file=sys.stderr)
    sys.exit(1)
PY
