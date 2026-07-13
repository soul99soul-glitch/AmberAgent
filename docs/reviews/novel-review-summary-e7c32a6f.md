# Novel Android Review Summary (e7c32a6f)

- **Mode**: local (novel-scoped)
- **Reviewers**: 2 independent read-only subagents
  - Domain / data closed-loop
  - Android production call-path
- **Full notes**:
  - `docs/reviews/novel-domain-review-e7c32a6f.md`
  - `docs/reviews/novel-callpath-review-e7c32a6f.md`

## Verdict

Not complete product. Nav + DI + Phase 1 create/rename/delete durable paths mostly continuous.
Multiple **closed-loop breaks** and **fake-looking UI** paths remain.

## Top P0/P1 (agreed by both reviewers)

1. [bug] Send button silent no-op — never calls `start()`
2. [bug] Empty project list can stick on “加载中…” (StateFlow equality + loading flag)
3. [bug] Create does not navigate/open new project
4. [bug] SwitchBranch returns durable `BranchRenamed` without rename/persist
5. [bug] Branch select ignores domain failure, still updates UI
6. [bug] List scan drops corrupt primary; `isDegraded` never true; degraded access not in snapshot
7. [bug] Delete not atomic (previous can resurrect)
8. [bug] Idempotent replay + commit incompatible (revision must +1)

## Call-path closed-loop (condensed)

| Path | Status |
|------|--------|
| Drawer → NovelProjects | closed |
| List CRUD (rename/delete) | closed |
| Create → list refresh | partial (no open) |
| Open workspace load | closed (happy path) |
| Materials read-only | closed |
| Composer send | broken/stub |
| Route interrupt | partial (called, empty body) |
| DI singleton | closed |

