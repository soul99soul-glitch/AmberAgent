# ADR-0006: Brand rebrand complete (scorched earth)

**Status**: Accepted
**Date**: 2026-05-29
**Supersedes**: ADR-0001 (Legacy frozen surfaces) — deleted in this rebrand.

## Context

The codebase historically carried two brand identities side-by-side:

- `me.rerere.rikkahub.*` — the original fork name from rikkahub (Apache 2.0).
- `app.amber.agent.*` (in progress) — the AmberAgent rebrand.

ADR-0001 froze 81 files in `me.rerere.rikkahub.*` to protect Room schema FQNs,
JNI symbols, manifest references, broadcast actions, and DataStore migration
chains for **existing-user data continuity**.

This app is single-developer, self-use only. There is no installed user
base whose data must survive a brand rename. The dual identity, frozen
file list, allowlist guard, and migration chains were paying ongoing
maintenance cost (split greps, partial refactors, "this file is in the
allowlist" carve-outs) to protect data continuity nobody needed.

## Decision

Scorched-earth rebrand to a single identity:

- `applicationId` and `namespace`: `app.amber.agent`
- Kotlin packages: every `me.rerere.X` → `app.amber.X` (with `rikkahub` → `agent`).
- Rust JNI: `Java_me_rerere_*` → `Java_app_amber_*`.
- DB file, DataStore stores, backup paths, JWT realms, SharedPreferences names,
  reveal-tag magic, sealed-class @SerialName values — all renamed.
- All Room migrations + autoMigrations + schema dir deleted. DB version reset to 1.
- All DataStore `PreferenceStoreV*Migration` deleted (no version-key bump path).
- `SettingsJsonMigrator` deleted; backup-import callers pass JSON through verbatim.
- Class identifiers: `RikkaHubApp` → `AmberAgentApp`, `RikkahubTheme` → `AmberAgentTheme`,
  `SearchServiceOptions.RikkaHubOptions` → `AmberAgentSearchOptions`, etc.
- Guardrails removed: ADR-0001, `config/legacy-package-allowlist.txt`,
  `config/check-legacy-package.sh`, `.github/workflows/legacy-package-guard.yml`,
  Invariant 3 of `config/check-refactor-state.sh`.

## Out-of-scope exceptions

The following `rikkahub` references remain (each rationale):

- `gradle/libs.versions.toml` — 4 external Maven coords
  (`com.github.rikkahub:jlatexmath-android`, `markdown`, `hugeicons-compose`,
  `sqlite-android`). These are upstream-fork repos under the user's GitHub
  org; renaming requires re-publishing the forks under a new org, out of
  this rebrand's scope.
- `.github/FUNDING.yml: patreon: rikkahub` — user's personal Patreon handle,
  not the app codename.
- `LICENSE` — required Apache 2.0 attribution to the original `rikkahub`
  fork.
- `SettingAboutPage.kt` — 2 in-app links to
  `github.com/rikkahub/rikkahub` (forked-project attribution, akin to
  LICENSE).
- `app/build.gradle.kts` — single comment referencing
  `github.com/rikkahub/jlatexmath-android` (matches the
  `libs.versions.toml` external coord).
- `git log` / commit messages — immutable history.
- `docs/refactor-progress.md`, `docs/scorched-earth-audit.md`, and other
  historical docs — chronological log retains pre-rebrand entries.

## Consequences

**Breaking**: applicationId change means a fresh install under
`app.amber.agent[.debug/.notion]`. Existing `me.rerere.amberagent[.debug/.notion]`
installs are NOT touched (different package id, different data sandbox).
Old install can be uninstalled separately; no data carried over.

**Maintenance simplification**: single brand identity; no allowlist; no
"is this file frozen?" review per change; no migration chain to keep
backward-compatible.

**Future**: if external fork repos under `github.com/rikkahub/*` are
republished under an `app-amber` org, drop the 4 toml entries + 1
build.gradle comment + 2 SettingAboutPage links + `.github/FUNDING.yml`
patreon as a follow-up cleanup. Not blocking.
