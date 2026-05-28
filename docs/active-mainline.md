# Active Mainline Baseline

Date: 2026-05-29

## Decision

`main` is now the active AmberAgent development line. New work should branch from `github-private/main`, not from the pre-rebrand RikkaHub mainline.

## Baseline

| ref | commit | purpose |
|---|---:|---|
| `github-private/main` | `b44ddc80` | AmberAgent rebrand + architecture mainline before this baseline note |
| `refactor/agent-kernel-surfaces` | `5d0435e7` | Architecture-complete base used before scorched-earth rebrand |
| `github-private/archive/main-pre-rebrand-2026-05-29` | `93c7e669` | Frozen copy of the old mainline |

After this note is committed, the baseline tag is:

```bash
baseline/amber-main-2026-05-29
```

## Rules Of Thumb

- Treat `github-private/main` as the source of truth for future AmberAgent work.
- Treat `github-private/archive/main-pre-rebrand-2026-05-29` as read-only historical reference unless a deliberate cherry-pick is needed.
- Do not merge the archived old mainline back into active `main`; compare or cherry-pick narrowly instead.
- If a new branch needs the completed modular architecture, start from `main`; do not restart from `refactor/agent-kernel-surfaces`.

## Verification Target

The first baseline acceptance pass should run:

```bash
./gradlew assembleNotion test
```

If this passes, the baseline tag marks the first post-rebrand mainline checkpoint.
