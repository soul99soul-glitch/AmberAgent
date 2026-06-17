# HarmonyOS Gate 0 Toolchain Readiness Report

> Generated: 2026-06-16T05:15:00.136Z
> Source commit: `cdad564d7`
> Gate status: **blocked**

Gate 0 must be ready before any HarmonyOS spike result is marked `pass`, `limited`, or `fail` in `docs/harmony-capability-matrix.md`.

Toolchain probe IDs use `G0-Txx` to avoid colliding with conceptual Gate 0 IDs in the capability matrix. Matrix mapping: `G0-01` ← `G0-T01` or `G0-T02`; `G0-02` ← SDK/tool availability around `G0-T03..G0-T08`; `G0-03` requires signing setup outside this script; `G0-04` ← `G0-T08..G0-T09`; `G0-05` ← `G0-T11..G0-T13` plus HarmonyOS native SDK.

## Checks

| ID | Item | Status | Detail |
|---|---|---|---|
| G0-T01 | DevEco Studio app | missing | /Applications/DevEco-Studio.app ; /Applications/DevEco Studio.app ; /Users/mi/Applications/DevEco-Studio.app ; /Users/mi/Applications/DevEco Studio.app |
| G0-T02 | Command Line Tools directory | missing | /Users/mi/command-line-tools ; /Users/mi/Library/Huawei/commandline/command-line-tools |
| G0-T03 | OHOS_SDK_HOME | missing | not set |
| G0-T04 | HARMONYOS_HOME | missing | not set |
| G0-T05 | DEVECO_SDK_HOME | missing | not set |
| G0-T06 | hvigor/hvigorw | missing | /Users/mi/command-line-tools/bin/hvigor ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/hvigor ; /Users/mi/command-line-tools/bin/hvigorw ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/hvigorw |
| G0-T07 | ohpm | missing | /Users/mi/command-line-tools/bin/ohpm ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/ohpm |
| G0-T08 | hdc | missing | /Users/mi/command-line-tools/bin/hdc ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/hdc |
| G0-T09 | hdc targets | missing | hdc unavailable or no targets: hdc executable not found |
| G0-T10 | node | found | /opt/homebrew/bin/node (v25.4.0) |
| G0-T11 | cmake | missing | /Users/mi/command-line-tools/bin/cmake ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/cmake |
| G0-T12 | rustc | found | /opt/homebrew/opt/rustup/bin/rustc (rustc 1.93.0 (254b59607 2026-01-19)) |
| G0-T13 | cargo | found | /opt/homebrew/opt/rustup/bin/cargo (cargo 1.93.0 (083ac5135 2025-12-15)) |

## Blocking Missing Items

- G0-TOOLS DevEco app or Command Line Tools source: Install DevEco Studio or Huawei Command Line Tools
- G0-T06 hvigor/hvigorw: /Users/mi/command-line-tools/bin/hvigor ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/hvigor ; /Users/mi/command-line-tools/bin/hvigorw ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/hvigorw
- G0-T07 ohpm: /Users/mi/command-line-tools/bin/ohpm ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/ohpm
- G0-T08 hdc: /Users/mi/command-line-tools/bin/hdc ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/hdc
- G0-T09 hdc targets: hdc unavailable or no targets: hdc executable not found

## Notes

- This script checks local toolchain availability only; it does not prove API-level feature support.
- Missing CMake/Rust is not a Chat blocker by itself; native/NAPI work is an optimization track while ArkTS fallbacks exist.
- DevEco Studio GUI is useful for project creation, SDK management, signing, and debugging, but Gate 0 can also be unlocked by Command Line Tools plus SDK plus hdc target.
- Missing SDK env vars are informational when tools can discover SDKs internally, but missing hvigor/ohpm/hdc/device target blocks verified device spikes.
- DevEco-generated project files should still be produced by DevEco Studio or verified templates, not hand-written by AI.
- If Gate 0 is blocked, continue only source-only prep: fixtures, inventories, contracts, and documentation.
- This script probes PATH, zsh interactive PATH, common DevEco app bundles, common SDK locations, and common Command Line Tools locations.
