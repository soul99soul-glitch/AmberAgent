# HarmonyOS Gate 0 Toolchain Readiness Report

> Generated: 2026-06-22T04:28:29.121Z
> Source commit: `be6f2ba37`
> Gate status: **ready**

Gate 0 must be ready before any HarmonyOS spike result is marked `pass`, `limited`, or `fail` in `docs/harmony-capability-matrix.md`.

Toolchain probe IDs use `G0-Txx` to avoid colliding with conceptual Gate 0 IDs in the capability matrix. Matrix mapping: `G0-01` ← `G0-T01` or `G0-T02`; `G0-02` ← SDK/tool availability around `G0-T03..G0-T08`; `G0-03` requires signing setup outside this script; `G0-04` ← `G0-T08..G0-T09`; `G0-05` ← `G0-T11..G0-T13` plus HarmonyOS native SDK.

## Checks

| ID | Item | Status | Detail |
|---|---|---|---|
| G0-T01 | DevEco Studio app | missing | /Applications/DevEco-Studio.app ; /Applications/DevEco Studio.app ; /Users/mi/Applications/DevEco-Studio.app ; /Users/mi/Applications/DevEco Studio.app |
| G0-T02 | Command Line Tools directory | found | /Users/mi/Library/Huawei/commandline/command-line-tools |
| G0-T03 | OHOS_SDK_HOME | missing | not set |
| G0-T04 | HARMONYOS_HOME | missing | not set |
| G0-T05 | DEVECO_SDK_HOME | missing | not set |
| G0-T06 | hvigor/hvigorw | found | /Users/mi/Library/Huawei/commandline/command-line-tools/bin/hvigorw (6.26.1) |
| G0-T07 | ohpm | found | /Users/mi/Library/Huawei/commandline/command-line-tools/bin/ohpm (26.0.0.410) |
| G0-T08 | hdc | found | /Users/mi/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony/toolchains/hdc (Ver: 3.2.0e) |
| G0-T09 | hdc targets | found | [Empty] |
| G0-T10 | node | found | /opt/homebrew/bin/node (v25.4.0) |
| G0-T11 | cmake | missing | /Users/mi/command-line-tools/bin/cmake ; /Users/mi/Library/Huawei/commandline/command-line-tools/bin/cmake |
| G0-T12 | rustc | found | /opt/homebrew/opt/rustup/bin/rustc (rustc 1.93.0 (254b59607 2026-01-19)) |
| G0-T13 | cargo | found | /opt/homebrew/opt/rustup/bin/cargo (cargo 1.93.0 (083ac5135 2025-12-15)) |

## Blocking Missing Items

- None detected by this script.

## Notes

- This script checks local toolchain availability only; it does not prove API-level feature support.
- Missing CMake/Rust is not a Chat blocker by itself; native/NAPI work is an optimization track while ArkTS fallbacks exist.
- DevEco Studio GUI is useful for project creation, SDK management, signing, and debugging, but Gate 0 can also be unlocked by Command Line Tools plus SDK plus hdc target.
- Missing SDK env vars are informational when tools can discover SDKs internally, but missing hvigor/ohpm/hdc/device target blocks verified device spikes.
- DevEco-generated project files should still be produced by DevEco Studio or verified templates, not hand-written by AI.
- If Gate 0 is blocked, continue only source-only prep: fixtures, inventories, contracts, and documentation.
- This script probes PATH, zsh interactive PATH, common DevEco app bundles, common SDK locations, and common Command Line Tools locations.

## End-to-end verification (2026-06-22)

After Gate 0 went `ready`, an end-to-end toolchain exercise was performed in `/tmp/hello-harmony` (a minimal ArkTS "Hello HarmonyOS" HAP):

| Step | Result | Notes |
|---|---|---|
| `ohpm install` | ✅ pass | Required `~/.npmrc` with `registry=https://repo.harmonyos.com/npm/` (default npmjs.org fails for `@ohos/*` packages). |
| `hvigorw assembleHap` | ✅ BUILD SUCCESSFUL (8s) | Required: hvigor-config `modelVersion: "5.0.0"` aligned with root `oh-package.json5` `modelVersion`; `@ohos/hvigor-ohos-plugin` version must match `hvigorVersion` (6.26.1); project `build-profile.json5` `runtimeOS: "HarmonyOS"` needs string SDK versions like `"26.0.0"` (OpenHarmony branch accepts integer API level); `app.json5` `apiReleaseType` for API 26 must be `Canary1`/`Beta1`/`Release1` (not bare `Release`); `module.json5` uses `entities` (plural), not `entity`. |
| Phone emulator image download | ✅ pass | `Emulator -install -deviceType phone -osVersion 6.1.0.125` → HarmonyOS 6.1.1(24), API 24 Release. Only `6.1.0.125` / `6.1.1(24)` publicly available; no API 26 image yet. |
| Emulator instance create + start | ✅ pass | `Emulator -create phoneEmulator -deviceType phone -osVersion "HarmonyOS 6.1.1(24)"`; cold boot ~1-2 min. |
| `hdc list targets` | ✅ pass | `127.0.0.1:5555` after boot. |
| `hdc install *.hap` | ❌ FAIL | "older sdk version, please use latest sdk version" — HAP compiled against API 26 (Beta1) cannot install on API 24 (Release) emulator. |

### Known blocker: SDK / emulator image version mismatch

- Installed Command Line Tools 26.0.0.461 ships **API 26 Beta1 SDK only**.
- Public emulator images top out at **API 24 Release** (HarmonyOS 6.1.1).
- Command Line Tools has **no SDK Manager** (`ohsdkmgr` is OpenHarmony command-line-tools only); installing API 24 SDK requires DevEco Studio GUI's SDK Manager, or manual component download from Huawei CDN.
- Resolution options (pick before Phase 1 implementation):
  1. Install DevEco Studio → SDK Manager → add API 24 SDK components. Also unlocks signing/preview/debug GUI needed later.
  2. Manually download API 24 ets/js/native/toolchains components and drop into `sdk/default/openharmony/`.
  3. Wait for public API 26 emulator image and keep API 26 SDK.
- Impact: **all other toolchain capabilities verified working** (compile, ohpm, emulator boot, hdc). Only HAP install on emulator is blocked by this version gap. Not a design blocker.
