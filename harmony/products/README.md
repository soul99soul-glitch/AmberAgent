# Standalone application icons

Each product contains `app_icon.png`, the 1024 × 1024 icon packaged by `scripts/harmony-standalone/prepare.mjs`. As of 2026-10-05, these files are byte-for-byte copies of each standalone iOS app's standard AppIcon asset:

- Novel: `../amberagent-ios/iosApp/NovelApp/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png` — the cinnabar writing seal.
- Deep Read: `../amberagent-ios/iosApp/DeepReadApp/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png` — the cinnabar book symbol.

The paths above are relative to this repository root. Both iOS projects select the `AppIcon` asset catalog for Debug and Release. Novel's separate iOS dark/tinted variants are preserved in its iOS source catalog; Harmony currently packages the standard icon without automatic appearance switching. The launcher applies its own mask to the full-square canvas. Source identities, dimensions, hashes and verification are recorded in `docs/reviews/2026-10-05-deepread-ios-replica/ios-icons-reuse.md`.

The standalone preparation script generates a separate `${product}_start_icon.svg` for the system start window. It embeds the same PNG bytes in a 256 × 256 viewport with a 56px corner clip, so the start window presents rounded artwork without changing the iOS source asset. Both products use this resource for `startWindowIcon`; their application icons continue to use the original PNG.

The previous generated artwork remains in `app_icon.master.png`; it is not the currently packaged icon. Its sculptural-paper prompts and verification remain in `docs/reviews/2026-10-03-standalone-app-icons-paper-ribbon/`. Earlier outlined-character explorations remain in `docs/reviews/2026-10-03-standalone-app-icons-redesign/`.
