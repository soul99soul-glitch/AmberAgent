# AmberAgent Migration Provenance

The active Monorepo started from a clean, independent Git root. The Android and iOS histories were not joined with an unrelated-history merge.

## Source baselines

- Android source branch: `main`
- Android source commit: `8d8f33db1af1e72a54bf620338eb6f88a016a251`
- iOS source branch: `feat/ios-provider-parity-claude`
- iOS source commit: `1fbe173fe420fab51ab3a321c1d803de8f4bbada`

The public `main` branch is a sanitized source snapshot. It includes the recorded Android and iOS working-tree changes without joining either platform's legacy history.

## Legacy recovery

Legacy histories are not published as long-lived branches or tags in the canonical repository. Authorized maintainers keep any required recovery material outside public Git.

New product development uses the Monorepo `main` branch and short-lived task branches.
