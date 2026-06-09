# God-class Refactor Playbook

> Stable playbook for splitting large files into thin coordinators + sibling files.
> Distilled from **P1** (LocalTools / SystemAccessTools / WebMountPrimitiveTools / ChatInput) and **P2 first cut** (SettingExperimentalPage).
>
> Update this file as the playbook evolves; do **not** create dated `_2026-XX-XX.md` copies. Pre-cut analysis docs per refactor are dated and live alongside this.

---

## 0. When to use this playbook

Files in the `app/` module > ~1500 lines where the structure is "one coordinator function + N independent sub-blocks that can each become a sibling file":
- **Compose pages**: each `@Composable` sub-page + its private helpers is a candidate sibling
- **Tool factories**: each `*Tools.kt` capability + its private helpers is a candidate sibling
- **Service classes**: harder — splitting needs explicit state-machine boundaries. Treat as a separate playbook concern.

**Out of scope here**: cross-module refactors, behavior-changing rewrites, "make code prettier" cleanup. Strict scope = mechanical extraction with **byte-equal bodies**.

---

## 1. Hard rules (non-negotiable)

| # | Rule | Why |
|---|---|---|
| 1 | **Worktree isolation**: only work in `rikkahub-refactor-godclass/`. `rikkahub/` is Codex's worktree and a禁区. | P1 cross-branch commit accident (2026-05-17) — see [[feedback_rikkahub_worktree_isolation]] in memory. |
| 2 | **No `--amend`, no `--no-verify`, no force-push** | Each stage's commit is an audit artifact; amending destroys history. |
| 3 | **Byte-equal bodies**: extracted code's function bodies, signatures, FQN forms, and indentation are bit-identical to the parent commit. Only visibility modifier (`public/internal/private`) may change. | Reviewer should be able to `diff` and see zero deltas in the moved code. |
| 4 | **Don't touch pre-existing dead imports** (CLAUDE.md §3) | "Pre-existing dead" = was already 0 body refs in the pre-refactor baseline commit. Cleaning unrelated stuff inflates blast radius. |
| 5 | **Each stage = one commit** | Stage-level granularity for review + revert. |
| 6 | **Each stage gets a sub-agent Round 1 review** before push. Round 2 only if Round 1 flagged REQUEST CHANGES. | Independent verification of byte-equal claim. |
| 7 | **Compile + test gate per stage**: `:app:compileDebugKotlin --offline` clean, `:app:testDebugUnitTest --offline` = exact known-failed count (baseline preserved). | Stage doesn't ship if it breaks compile or changes test count. |
| 8 | **Final stage adds 3-variant assemble**: `:app:assembleDebug :app:assembleNotion :app:assembleRefactortest --offline` BUILD SUCCESSFUL | Notion / Refactortest variants can fail on resource or manifest issues that Debug doesn't catch. |

---

## 2. Pre-cut analysis (mandatory, before any strip)

Write a `docs/REFACTOR_<id>_PRECUT_<date>.md` document. **Don't strip anything until the doc is reviewed by the user**.

### 2.1 What to include

1. **Top-level decl table** (line range, name, current visibility, role) of every `@Composable`, `fun`, `class`, `const val` in the target file. Use `grep -nE "^(@Composable|fun|private fun|internal fun|class|object|private const|internal const)"`.
2. **Call graph**: which symbols call which? `grep -nE "\bSymbolName\b"` for each candidate sibling-section helper to verify it's only called inside its own section.
3. **Cross-file caller scan**: for each function being moved, `grep -rn --include="*.kt" "\\bFunctionName\\b" app/src/main/java/` to find external callers. Drives the `public / internal / private` visibility decision (see §4).
4. **⚠ Same-file cross-stage caller scan** *(P2 stage 4 lesson)*: for every `private fun` that **stays** in the coordinator after extraction, grep against the planned extracted ranges. If something in the coordinator is called by an extracted block, that helper must be widened to `internal` (or moved to the sibling). **Pre-cut MUST catch this** — discovering it at compile-gate is a last-minute scramble.
5. **Risk assessment**: 🟢 low / 🟡 medium / 🔴 high based on:
   - Coupling between sub-sections (more coupling → higher risk)
   - Whether the file owns side effects (`LaunchedEffect` / `DisposableEffect` / state machines)
   - Whether the file has subtle invariants (composite state, key ordering, etc.)
6. **Proposed stage plan**: how many stages, what each stage extracts, line ranges, expected new sibling file names, estimated new line counts.
7. **Decisions for user**: stage count (fine-grained vs combined), import-sort policy (strict byte-equal vs allow alphabetical sort), PR strategy (single PR vs per-stage PRs).

### 2.2 Reference docs

Past pre-cut analyses serve as templates:
- `docs/SESSION_5_HANDOFF.md` (P1 ChatInput Sandbox)
- `docs/chatinput-composers-plan.md` (P1 Composers)
- `docs/REFACTOR_P2_SETTING_EXP_PRECUT_2026-05-18.md` (P2 first cut)

---

## 3. Per-stage flow (14 steps)

```
1.  Read stage range; grep block boundaries (already in pre-cut doc)
2.  Extract stage range to /tmp/stageN.kt via awk 'NR>=START && NR<=END'
3.  Filter required imports: from coordinator's import block, keep only those whose short
    name (last segment) appears as \bWORD\b inside the extracted body.
    ⚠ Watch for non-grep-able imports:
      - getValue / setValue (used implicitly by `by` delegation — check for `by `)
      - operator imports like utils.plus (used as `+`, not `plus`)
4.  Write new sibling file = `package ...\n\nimports...\n\n<extracted body>`
5.  Trim trailing blank: Python or `truncate` so file ends with single `\n`
6.  awk strip extracted range from coordinator: `awk 'NR<START || NR>END' file > tmp && mv tmp file`
    ⚠ For multiple ranges, strip from highest to lowest line number to avoid offset bugs,
    OR use a single combined awk filter (e.g. `NR<A || (NR>B && NR<C) || NR>D`)
7.  Compile gate: `./gradlew :app:compileDebugKotlin --offline`
    If fails: check imports list and visibility (see §4) before adding extra code
8.  Dead-import cleanup in coordinator:
    For each "stage-specific" import (anything from the coordinator's import block whose
    short name now has 0 body refs in the trimmed coordinator):
      a. Verify it had ≥1 body ref in the parent commit's extracted range
         (i.e., it became orphan BECAUSE of this extraction, not pre-existing dead)
      b. If pre-existing dead → SKIP (don't touch). Verify by:
         `git show <pre-refactor-baseline>:<path> | grep -nE "\\bSYM\\b"`
         Only the import line should appear (no body refs).
      c. Otherwise sed -i '' '/^import .*\\.SYM$/d' coordinator
9.  Compile gate again
10. Unit test: `./gradlew :app:testDebugUnitTest --offline`. Must match baseline failed count
    (currently 6 / 463: GenerativeUiPlannerTest×2, ContextFootprintEstimatorTest×3,
    DefaultProvidersTest×1)
11. git add + commit (message format below)
12. Sub-agent Round 1 review (see §6)
13. If Round 1 flags REQUEST CHANGES: fix and commit FIX-UP (don't amend). Optional Round 2.
14. git push to fork remote
```

After the FINAL stage:
```
15. 3-variant assemble (Debug, Notion, Refactortest)
16. Provide user the Codex review prompt (see §7)
17. After Codex APPROVE: open PR with `gh pr create` (see §8)
```

### 3.1 Commit message format

```
refactor(p2): extract <Section> into sibling page (stage N of M)

<one paragraph summarizing what moved and from where>

Byte-equal: bodies and signatures unchanged.
Visibility unchanged. [OR: NAME widened private → internal — REASON]

Coordinator strip: <before> -> <after> (-<delta> with import cleanup).
Imports trimmed: <N> orphans.

[List notable specific items: cross-stage catches, pre-existing dead preserved, etc.]

Baseline 6/463 unit tests preserved.
```

---

## 4. Visibility decision table

| Caller location for the moved symbol | Use |
|---|---|
| Only inside its own new sibling file | `private` |
| Cross-file, same module (e.g., from RouteActivity, from another sibling in same package) | `internal` |
| Cross-module (rare for UI code) | `public` |

**Tightening (`public` → `internal`)**: only do this if you've grep'd ALL cross-package callers within the module and confirmed none escape the module. If the function was `public` in the parent and you tighten it, call it out explicitly in the commit message; reviewer will check.

**Widening (`private` → `internal`)**: required when a helper that stays in the coordinator is also called from an extracted sibling. **This is the ONLY allowed body-level edit to the coordinator beyond imports.** Document in the commit message.

---

## 5. Import discipline

### 5.1 Three categories

| Category | Action |
|---|---|
| **This-refactor orphan**: import was alive (≥1 body ref) in parent commit's extracted range, now 0 refs in trimmed coordinator | DELETE |
| **Pre-existing dead**: import was already 0 body refs in the pre-refactor baseline commit | LEAVE ALONE |
| **Still alive elsewhere**: import has ≥1 body ref in the trimmed coordinator (some other stage / coordinator's remaining code uses it) | KEEP |

### 5.2 How to verify "pre-existing dead"

For the current refactor, identify the **pre-refactor baseline commit** (the last commit on `main` before this refactor's first stage). For SettingExperimentalPage in P2, that was `cdbdf159`.

```bash
git show <baseline>:<path> | grep -cE "\\bSYM\\b"
```

If output is `1` (only the import line) → pre-existing dead → DO NOT touch.

### 5.3 Common import gotchas (from past stages)

| Symbol | Why grep misses it | Fix |
|---|---|---|
| `getValue` / `setValue` | Used implicitly by `by` keyword | Check for `by ` in extracted body |
| `utils.plus` | Used as `+` operator, not function name | Check for `+ PaddingValues` or similar operator overloads |
| `findModelById` | Extension function, used as method call on receiver | Grep for the literal name still works, but easy to miss in a hasty filter |
| `Model` (bare) | Often "used" inside comments or string literals; not a real reference | Disambiguate by checking grep matches' context |

---

## 6. Sub-agent Round 1 review template

Spawn a general-purpose agent per stage commit. Prompt template:

```
Round 1 review of god-class refactor extraction stage <N>.
**Verify everything with grep/diff/Read.**

Repo: <abs path>
Branch: <branch>
Stage commit: <hash> (HEAD)
Parent: <hash>

What stage <N> did: <one paragraph>

Hard rules to verify:
1. Byte-equal: parent commit's L<start>-L<end> must match new sibling's body
   (after package + imports). The only allowed delta is visibility modifier changes
   in the COORDINATOR (extracted body should be 100% identical).
2. New file imports complete: every symbol used has an import. Watch for
   getValue/setValue (by delegation), utils.plus (PaddingValues +).
3. Coordinator strip clean: deletions match exactly the stage range. No edits to
   non-stage lines (except documented visibility widening).
4. Coordinator orphan-import discipline: deleted imports had 0 body refs in HEAD
   AND ≥1 ref in parent's stage range. Pre-existing dead imports preserved.
5. EOF clean: new sibling ends with single `\n` (verify with `tail -c 5 | xxd`).
6. No collateral files: only the 2 expected files in the commit.
7. No accidental visibility changes (other than documented ones).

How to verify (suggested):
  <bash snippet with diff + grep + xxd>

Output format:
ROUND 1 STAGE <N> — <APPROVE | NITS | REQUEST CHANGES>
<line-by-line PASS/FAIL>
<findings as CHANGES / NITS>
<overall recommendation>

Cap at 250 words. Terse.
```

---

## 7. Codex independent review prompt

After the final stage + 3-variant assemble, hand the user this prompt to paste into Codex (which runs in `rikkahub/` worktree, NOT this one):

```
对 rikkahub fork 上的分支 `refactor/<branch>` 做独立的 P<N> 重构 review，
决定能否合并到 main。

## 背景
<one-paragraph context: which god-class, what stages, baseline commit>

## 提交序列
<list of stage commits with hashes>

## 拆分结果
<before/after line counts table>

## 你需要验证的核心问题
1. Byte-equal 严格性: 每个 stage 抽出的 body 是否与 parent commit 对应行范围完全字节相等?
2. Visibility 改动合理性: <list any visibility changes and ask Codex to verify justified>
3. Dead-import 处理: pre-existing dead vs this-refactor orphan 区分对吗?
4. 3 个 build variant 都过吗? (注意 refactortest 需要本地正确的 google-services.json)
5. 单测 baseline 6/463 保持?
6. EOF 卫生?
7. 行为不变 smoke check (静态导航 + 编译层即可)

## 输出格式
P<N> REVIEW — <APPROVE / NITS / REQUEST CHANGES>
<line-by-line PASS/FAIL with stage breakdown>
<findings + severity + suggestions>
<能否合 main: YES / NO + 原因>
```

If Codex flags REQUEST CHANGES, fix and ask Codex to re-review (Round 2). If Codex APPROVE → proceed to PR.

---

## 8. PR + merge mode

### 8.1 Open PR

```bash
gh pr create \
  --repo soul99soul-glitch/AmberAgent \
  --base main \
  --head refactor/<branch> \
  --title "refactor(<phase>): split <ClassName> (<before> → <after>, <N> siblings)" \
  --body "$(cat <<'EOF'
## Summary
<one paragraph: what was split, where, key constraints>

## Stages
<list of stage commits with one-line description each>

## Verification
- Compile: <result>
- Unit tests: <result>
- 3-variant assemble: <result>
- Sub-agent Round 1 per stage: <N × APPROVE>
- Codex independent review: <APPROVE / with caveats>

## Test plan
- [x] <items done>
- [ ] <items deferred to manual UI smoke>

## Merge mode
Per playbook §8: **"Create a merge commit"** (NOT squash/rebase) — preserves the stage-by-stage audit trail.
EOF
)"
```

### 8.2 Merge mode: ALWAYS `--merge` (create merge commit)

```bash
gh pr merge <N> --repo soul99soul-glitch/AmberAgent --merge
```

**Never** squash or rebase: the per-stage commits are audit artifacts that future archaeology relies on.

### 8.3 Post-merge cleanup

```bash
# delete remote branch (already merged)
git push <remote> --delete refactor/<branch>

# detach HEAD locally (so the merged branch can be deleted)
git switch --detach

# delete local branch
git branch -d refactor/<branch>
```

---

## 9. Worktree + branch hygiene

- **One refactor = one branch from main**. Branch name: `refactor/p<phase>-<target-short>`. Example: `refactor/p2-settings-experimental`.
- **Documentation-only changes** (like this playbook) go in their own branch: `docs/<topic>`. Tiny PRs, fast merge.
- **Never** mix code refactor + non-trivial doc churn in one PR (kickoff/precut docs for THE refactor are fine, those ride with the refactor).
- After every push, run `git status` to confirm clean tree. Stash if you see unexpected changes — **never** `git checkout --` to "make it go away" without understanding what those changes were.

---

## 10. Failure recovery

| Symptom | Action |
|---|---|
| Compile fails after strip | Re-add the missing import (most common: a `by` delegation or operator import). DO NOT add code; the parent compiled, so the issue is missing imports or visibility. |
| Test count diverges from baseline | Stop. Don't push. Diff against baseline; if unrelated test changed, this branch shouldn't touch it — investigate origin (probably an `imports` cleanup accidentally broke a `by` delegation; the test sees a compile-time fallback). |
| Sub-agent flags REQUEST CHANGES | Fix in a new commit (no amend). Re-run sub-agent Round 2 with link to the fix commit. |
| Codex flags REQUEST CHANGES | Same; new commit, re-Codex. Don't open PR yet. |
| Merge to main but PR build fails | `git revert <merge-commit>` on a new branch + PR, then investigate. Don't force-push main. |
| Stage rolled back mid-extraction | If not committed: `git checkout -- <files>` to discard worktree changes. If committed: `git reset --hard <previous-stage-commit>` then `git push --force-with-lease <remote> <branch>` — **only on the refactor branch, never on main**. |

---

## 11. Playbook maintenance

This file is the source of truth. When a new lesson is learned (like "P2 stage 4 ExperimentBooleanPill" gotcha), edit this file directly in a small `docs/` PR — don't accumulate scattered "lessons learned" notes in pre-cut docs and assume the next session will read all of them.

If a rule in this playbook becomes wrong or obsolete, fix it here in a `docs/` PR. The pre-cut docs of past refactors are frozen historical records and stay as-is.
