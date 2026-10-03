# P7 novel card reactive update correction

Status: two-card source frozen for independent review and root's same-card device verification. This worker did not build, install or operate the device.

## Confirmed failure and evidence

`NovelGhostwriteCard` passed the job into private builders by value, then passed copied progress counts and expansion flags into further builders. `NovelChapterVerdictCard` passed review/candidate/plan into builders and captured that review in the approval shell content callback. The registered update callbacks therefore read old local values after the component's Prop object was replaced.

This is confirmed by three independent evidence levels:

1. Offline official `arkts-builder` documentation states that the default by-value parameter passing does not refresh builder UI when state changes, whereas a private builder reading component state directly can refresh. Relevant extracted text: `/tmp/p7-builder-doc.txt:194–213` and the private builder example at `:232–274`. No newer API or binding framework was introduced.
2. Actual prior build compiler output in `harmony/entry/build/default/cache/default/default@CompileArkTS/esmodule/release/entry/src/main/ets/components/NovelGhostwriteCard.ts` registered `Row.width(progressPercent(done,total))`, `Text.create(expanded ? ...)` and `Text.onClick` checks against captured `enabled`. `NovelChapterVerdictCard.ts` approval-shell update/content callbacks similarly captured `review`. These are compiler output observations, not a claim that re-invoking an entire builder proves automatic V1 updating.
3. Root's real-device same-mounted-card reproduction showed writing `0/1` after the held writer finished and the actual job reached blocked review/failed. Closing and reopening the control displayed the actual failure and blocking review. Expanding the frozen plan displayed its content while the label still said “查看”. Evidence: [before update](device/p7/ghostwrite-same-card-before-update.jpeg), [frozen same-card reproduction](device/p7/ghostwrite-frozen-update-repro.jpeg), [reopen shows actual state](device/p7/ghostwrite-remount-current.jpeg). Reopening was diagnostic evidence, not the implemented fix. The controlled job was `nv_murhpm3v_1_1aqyle9i`, chapter 5, failed `0/1`, without adoption.

## Narrow correction

- Job body, progress, frozen-plan/candidate detail, action row, verdict body/details and approval-shell projection read current `this.job`, `this.review`, `this.candidate`, `this.frozenPlan` and expansion state directly.
- Static section selectors, labels/colors and callback actions can still be parameters. Start readiness, disabled appearance and existing button callbacks read current plan/model/busy Props instead of copied booleans.
- Finding-row identities include message/location, so a new same-code review can display changed finding text rather than reuse a row bound to an older unobserved finding object.
- Preserved durable job semantics, callback entry points, dimensions/spacing and local expansion behavior. No remount, polling, new recovery behavior, runtime subscription or domain/workspace change.

Only `NovelGhostwriteCard.ets`, `NovelChapterVerdictCard.ets` and the new `novel_card_reactivity.test.cjs` were changed by this task.

## Validation

`harmony/entry/src/test/novel_card_reactivity.test.cjs` executes the actual production methods with ArkUI call stubs. UI expressions are retained as update callbacks and re-evaluated after Prop/State replacement without calling the original builder again; this reproduces old lexical captures instead of masking them with a full render. The three original regressions first failed with stale progress `0/2`, expansion label “查看” and verdict “需重写”. After the correction, **5/5 passed**, covering:

- Same job ID, replaced progress/candidate/frozen-plan/review and progress width.
- Existing expansion and action callbacks after expansion/busy changes.
- Existing verdict shell and expanded details after review/candidate/plan replacement.
- Start readiness and start action after model/plan gate changes.
- Same-code findings with changed message/location.

Logs: `/tmp/p7-novel-card-tests.log`; scoped two-card ArkTS lint **0 defects**, `/tmp/p7-novel-card-lint.log`; `git diff --check` passed.

Independent non-implementing reviewer: `p1_novel_inventory`. Root owns the post-fix build and actual same-mounted-card writing → review → completion verification. Source/static/callback tests do not close that device gate.

Independent review passed with no new findings: reviewer re-read the actual old compiler callbacks and checked current direct Prop/State reads; permanent 5 regressions plus two independent probes **7/7 passed**. Probes cover replacement of plan/model disabled reasons, a retained start callback after busy changes, and stable finding identity for unchanged content versus a changed identity for same-code new message/location. Evidence: `/tmp/p7-novel-card-independent-review.log` and `/tmp/p7-novel-card-independent-review.cjs`. Compiler cache remains the old baseline until root rebuilds; that distinction is intentional.

## Device AX target correction

The independent device read confirmed dynamic state updates and readable layout, then measured the verdict “查看/收起” action at AX bounds `[964,2304][1091,2458]`: 127 × 154px, about 36.3 × 44vp at density 3.5. Height met the target, but width did not. Added only `minWidth: 44` to the existing action constraint; retained its padding, alignment, callback and business behavior. Actual retained-callback tests **5/5 passed**, single-card lint **0 defects**, diff check passed. Logs: `/tmp/p7-novel-verdict-width-tests.log`, `/tmp/p7-novel-verdict-width-lint.log`. Source re-frozen for root's final unified build and device bounds check.
