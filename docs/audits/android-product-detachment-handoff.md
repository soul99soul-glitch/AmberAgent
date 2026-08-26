# Android product detachment residuals handoff

- Sender: mini-codex
- Recipient: main-codex
- Worktree: `/Users/arquiel/Downloads/AI/amberagent-monorepo/apps/android`
- Frozen HEAD: `c44302af7f6ae31e2259fb03b769379fc712a8af` (`main`)
- Plan status: completed
- Engineering detachment gate: GO
- Strict release gate: NO-GO because the private `app.amber.agent` Firebase client is unavailable and the full 16KB/visual release matrix is not complete

## Completed scope

- Replaced all six Rikka Maven coordinates with JetBrains Markdown 0.7.8, Compose Icons Lucide 1.1.0, noties JLaTeXMath 0.2.0 modules, and fixed requery sqlite-android commit `0bbaa7a8...`.
- Migrated 112 Kotlin files / 153 HugeIcons mappings directly to 137 Lucide targets; source imports/usages and resolved HugeIcons dependency are zero.
- Removed Rikka search production service/endpoint and closed legacy `amber_agent` DataStore, backup restore, secret cleanup, and startup routing paths without key reassignment.
- Removed Rikka icon alias and `rikkahub.svg`; About links now point to AmberAgent canonical repository/LICENSE.
- Added NOTICE, affected-scope SBOM, packaged Lucide/Feather/Compose Icons and JLaTeXMath/font license texts, derived font provenance and hashes.
- Preserved RikkaHub historical fork provenance as out-of-scope `NOASSERTION`; no legal-clearance claim.
- Post-completion retirement cleanup removed two zero-consumer approval wrappers, consolidated 13 legacy wrapper tests into three unique resolver-policy matrices, removed stale non-Novel owner wording, and kept migration/CJK/data/license compatibility boundaries intact.
- Landed the exact 180-file product-detachment delta into the current Android `main` checkout while preserving unrelated WIP; fixed one missing serialization import, one non-structural test assertion, and one concurrent test-fake list without changing production behavior.
- Closed the detachment-attributable static UI pass with precise Workspace breadcrumb semantics and 48dp minimum touch targets; ancestor navigation and file/folder callbacks remain unchanged.
- Committed the complete 444-file Android product/runtime migration as `c44302af7`, excluding all iOS WIP and build outputs.
- Fixed and verified the Novel stale-plot action, adaptive-width user bubbles, Provider list-height regression, settings/database compatibility, and main-thread lifecycle observer registration.

## Final evidence

- Plan: `docs/plans/2026-08-25-android-product-detachment-residuals-plan.md`
- Final evidence: `docs/audits/android-product-detachment-final-evidence.json`
- Current manifest: `docs/audits/android-product-detachment-current.json`
- Migration ledger: `docs/audits/android-product-detachment-migration-ledger.json`
- SBOM: `docs/audits/android-product-detachment-sbom.json`
- NOTICE: `NOTICE`

## Verified

- Debug and release dependency insight: no `com.github.rikkahub` match; both Gradle tasks BUILD SUCCESSFUL.
- `:core:agent-utils:test`, `:core:settings:testDebugUnitTest`, app Debug compile, and app Debug unit-test compile: BUILD SUCCESSFUL.
- Focused chat/runtime suite: 27/27 passed across production/runtime canaries, permission decisions, effect ledger and SubAgent thread graph.
- `:app:assembleDebug` and `:app:assembleGraphite`: BUILD SUCCESSFUL. The manifest scanner first found two stale pre-detachment Graphite APKs; rebuilding them from current source removed the old endpoint/SVG. All four current Debug/Graphite APK outputs now have zero hard-token hits.
- After the final runtime fixes, Graphite was rebuilt with cargo-ndk: its APK outputs share SHA-256 `d0a6684f…`, package the fresh `liboffice_parsers.so` SHA-256 `4a650eb…`, and retain zero hard-token hits.
- Release merged-assets built and passed the same zero-hit scan. A configured Release APK/AAB was not produced because the existing build guard correctly rejects a checkout without the private Firebase client.
- Source/catalog/asset/About/NOTICE/SBOM gates, JSON parse, 3 LicenseRef sets, 7 packaged-license hashes, 6 derived-asset hashes, Lucide debug/release variant hashes, JLaTeX cmap `E000=Omega` / `E001=harpoonleftright`, and `git diff --check`: passed.
- D6 independent Luna Max review: engineering GO, Critical 0 / High 0 / Medium 0 / Low 1. The two comment-only HugeIcons words were closed in the post-completion cleanup.
- Post-completion Luna Max review: engineering GO, Critical 0 / High 0 / Medium 0 / Low 1 accepted. The remaining Low is limited to two old-owner comments inside protected Novel dirty WIP; there is no production symbol or runtime effect.
- Current-delta Luna Max UI review and targeted rereview: static GO, no attributable Critical/High/Medium issue. Root/current breadcrumbs expose no no-op click target, ancestor navigation remains wired, and Workspace rows/segments keep a 48dp minimum target.
- USB device `987a53f3`: overlay installation preserved all compared database/WAL/SHM hashes; Provider cards and scrolling were visible with an approximately 48dp filter; cold launch showed no lifecycle-observer, Koin-construction, SQLite/migration or fatal exception.

## Unverified strict release gates

- Configured Release APK/AAB and signing/Firebase integration.
- Emulator/physical-device Markdown, JLaTeXMath, Lucide, About/license UI.
- Destructive database-upgrade scenarios and 16KB device behavior beyond the non-destructive overlay/cold-launch check.
- Search backup restore runtime and real provider smoke.
- Real-provider network calls and the remaining full-device visual matrix.

Android product code was committed as `c44302af7`; no push, reset, clean, rebase or stash was performed. iOS WIP was excluded and preserved.
