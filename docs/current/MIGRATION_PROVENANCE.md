# AmberAgent Migration Provenance

The active Monorepo started from a clean, independent Git root. The Android and iOS histories were not joined with an unrelated-history merge.

## Source baselines

- Android source branch: `main`
- Android source commit: `8d8f33db1af1e72a54bf620338eb6f88a016a251`
- iOS source branch: `feat/ios-provider-parity-claude`
- iOS source commit: `1fbe173fe420fab51ab3a321c1d803de8f4bbada`
- Monorepo bootstrap root: `4e50121b576a77e28e7ad35af198358f6498c38f`

The bootstrap snapshot includes the recorded Android and iOS working-tree changes. Recovery patches and untracked-file archives remain outside Git under `.migration/snapshots/`.

## Canonical recovery refs

The canonical GitHub repository preserves the pre-cutover histories through:

- branch `legacy/android-main`
- tag `legacy/android-main-2026-08-23`
- tag `legacy/ios-pr13-2026-08-23`

The old histories are recovery and audit sources. New product development uses the Monorepo `main` branch and short-lived task branches.
