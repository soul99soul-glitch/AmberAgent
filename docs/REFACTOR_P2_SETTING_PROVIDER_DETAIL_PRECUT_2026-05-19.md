# P2 Pre-cut — SettingProviderDetailPage.kt

> Pre-cut analysis per `docs/REFACTOR_PLAYBOOK.md` §2.
> **Do not strip anything until this doc is reviewed.**

- **Target**: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt` (1705 lines)
- **Branch**: `refactor/p2-settings-provider-detail` (from `github-private/main` = `67e7e442`)
- **Baseline**: **3 failed / 467 unit tests** — `GenerativeUiPlannerTest×2` + `DefaultProvidersTest×1`. *Changed from the prior P2 cut's `6/463` baseline because upstream commits `1d46d09c fix(context)` + `8967a468 fix(chat)` landed in between — `ContextFootprintEstimatorTest×3` now passes, and +4 new tests appeared.* This new baseline is locked-in for this refactor.
- **Risk**: 🟢 LOW — single-purpose setting/edit page, no state machines or business logic outside provided callbacks. Compose state is all `remember`/`rememberSaveable` local to leaf functions. `LaunchedEffect` + `produceState` exist but only inside `ModelList` which we extract as one whole unit.

---

## 1. Top-level decl table

| Lines | Name | Vis | Role |
|---|---|---|---|
| 140–255 | `SettingProviderDetailPage` | `fun` (public) | **Hub**: Scaffold + TopAppBar + bottom NavigationBar + HorizontalPager (2 tabs) |
| 257–279 | `parseContextWindowInput` | `private fun` | Pure helper: parses "100K"/"1M" → Int |
| 281–285 | `Int.formatContextWindowInput` | `private fun` (extension) | Pure helper: Int → "100K"/"1M" |
| 287–418 | `SettingProviderConfigPage` | `private fun` (composable) | Tab 0: provider configuration LazyColumn + delete dialog |
| 420–429 | `SettingProviderModelPage` | `private fun` (composable) | Tab 1 wrapper — delegates to `ModelList` |
| 431–559 | `ModelList` | `private fun` (composable) | Models tab core: `produceState` + reorderable LazyColumn + floating toolbar |
| 561–590 | `ProviderSetting.modelListRequestKey` | `private fun` (extension) | Builds dedup key for `produceState` |
| 592–599 | `ProviderModelListRequestKey` | `private data class` | Dedup-key value type used by `produceState` |
| 601–798 | `ModelSettingsForm` | `private fun` (composable) | 3-tab pager (Basic / Advanced / Built-in Tools) inside add/edit dialogs |
| 800–966 | `AddModelButton` | `private fun` (composable) | "+" button + ModelPicker + Add dialog (wraps `ModelSettingsForm`) |
| 968–1143 | `ModelPicker` | `private fun` (composable) | Modal bottom sheet with filter + checkbox-style picker |
| 1145–1176 | `ModelTypeSelector` | `private fun` (composable) | Segmented button for Chat / Embedding / Image |
| 1178–1251 | `ModelModalitySelector` | `private fun` (composable) | Input + output modality segments |
| 1253–1289 | `ModalAbilitySelector` | **`fun` (public)** | Tool / Reasoning ability segments (typo: "Modal" not "Model") |
| 1291–1479 | `ModelCard` | `private fun` (composable) | OutlinedCard + swipe-to-dismiss + edit dialog (wraps `ModelSettingsForm`) |
| 1481–1559 | `BuiltInToolsSettings` | `private fun` (composable) | Tab 2 of `ModelSettingsForm`: per-tool Switch list |
| 1561–1705 | `ProviderOverrideSettings` | `private fun` (composable) | Inside `ModelSettingsForm` Tab 1 (Advanced): provider override config |

---

## 2. Call graph (internal)

```
SettingProviderDetailPage (hub)
├─ SettingProviderConfigPage           ← Stage 1 sibling
│   └─ (only external Composables: ProviderConfigure, SettingProviderBalanceOption,
│         ProviderBalanceText, ProviderConnectionTester, SiliconFlowPowerByIcon)
└─ SettingProviderModelPage            ← Stage 3 sibling
    └─ ModelList                       ← Stage 3 sibling
        ├─ modelListRequestKey         ← Stage 3 sibling
        │   └─ ProviderModelListRequestKey  ← Stage 3 sibling (data class)
        ├─ ModelCard                   ← Stage 3 sibling
        │   └─ ModelSettingsForm       ← Stage 2 sibling
        └─ AddModelButton              ← Stage 3 sibling
            ├─ ModelPicker             ← Stage 3 sibling
            └─ ModelSettingsForm       ← Stage 2 sibling

ModelSettingsForm                      ← Stage 2 sibling
├─ parseContextWindowInput             ← Stage 2 sibling (helper)
├─ Int.formatContextWindowInput        ← Stage 2 sibling (helper)
├─ ModelTypeSelector                   ← Stage 2 sibling
├─ ModelModalitySelector               ← Stage 2 sibling
├─ ModalAbilitySelector                ← Stage 2 sibling  (currently public; cross-file scan = 0 callers, byte-equal preserve)
├─ ProviderOverrideSettings            ← Stage 2 sibling
└─ BuiltInToolsSettings                ← Stage 2 sibling
```

Each provider-specific Composable / data class / helper is called from a single up-stream caller within this file.

---

## 3. Cross-file caller scan (within `app/`)

`grep -rn --include="*.kt" "\\bSYM\\b" app/src/main/java/` for every top-level decl, excluding `SettingProviderDetailPage.kt`:

| Symbol | External hit | Real? | Action |
|---|---|---|---|
| `SettingProviderDetailPage` | `RouteActivity.kt:133` (import) + `:484` (call) | ✅ navigation entry | **Keep public, byte-equal.** |
| `ModelList` | `ui/components/ai/ModelList.kt:243, 325` | ❌ that file has its OWN unrelated `private fun ColumnScope.ModelList(...)` (different package) | safe |
| `ModelList` mention in `ProviderConfigure.kt:727` | text inside a comment ("modelList in ModelList composable") | ❌ comment only | safe |
| `ModalAbilitySelector` | no hits | — | byte-equal preserve `public` per playbook §4 (don't tighten without explicit cleanup PR) |
| All other 14 decls | no hits | — | private/internal as determined by §4 |

The Stage 2 + Stage 3 siblings will live in the **same `me.rerere.rikkahub.ui.pages.setting` package** so import lines in `RouteActivity.kt` are unaffected.

---

## 4. ⚠ Same-file cross-stage caller scan (P2 stage-4 lesson — playbook §2.1 #4)

**Stage 1 = extract `SettingProviderConfigPage`** (one composable to `SettingProviderConfigPage.kt`):
- Hub → moved? **Yes**, hub calls `SettingProviderConfigPage(...)` at L231 → widen `private` → `internal`.
- Moved → hub? **No**, `SettingProviderConfigPage` body only references external Composables (ProviderConfigure, ProviderConnectionTester, etc.) and standard Compose primitives.
- Total Stage 1 widenings: **1**.

**Stage 2 = extract `ModelSettingsForm` + its 6 internal helpers** (form + selectors + 2 pure helpers to `SettingProviderModelSettingsForm.kt`):
- Trimmed coordinator after Stage 1 still contains `ModelList`, `ModelCard`, `AddModelButton`, `ModelPicker`. Of these, **`ModelCard` calls `ModelSettingsForm(...)` at L1351 and `AddModelButton` calls `ModelSettingsForm(...)` at L933**. So `ModelSettingsForm` MUST widen `private` → `internal` at Stage 2 so the coordinator-still-in-place ModelCard/AddModelButton can call it across files.
- The 6 sibling-internal helpers (`parseContextWindowInput`, `formatContextWindowInput`, `ModelTypeSelector`, `ModelModalitySelector`, `ModalAbilitySelector`, `ProviderOverrideSettings`, `BuiltInToolsSettings`) are only called from within `ModelSettingsForm` — they stay `private` in the new sibling (except `ModalAbilitySelector` which is byte-equal-preserved as public).
- Moved → hub? **No** — `ModelSettingsForm` only calls its 6 sibling helpers + external Composables (`CustomHeaders`, `CustomBodies`).
- Total Stage 2 widenings: **1**.

**Stage 3 = extract `SettingProviderModelPage` + `ModelList` + helper + key + `ModelCard` + `AddModelButton` + `ModelPicker`** (Models tab to `SettingProviderModelTabPage.kt`):
- Hub → moved? **Yes**, hub calls `SettingProviderModelPage(...)` at L247 → widen `private` → `internal`.
- Moved → hub? **No** — `SettingProviderModelPage` only calls `ModelList`; `ModelList` only calls `ModelCard`/`AddModelButton`/`modelListRequestKey`; `AddModelButton` calls `ModelPicker` + `ModelSettingsForm` (Stage 2 sibling, already `internal`); `ModelCard` calls `ModelSettingsForm` (already `internal`). All cross-call paths reach either same-sibling symbols or already-widened Stage 2 sibling.
- Total Stage 3 widenings: **1**.

**Conclusion**: 3 widenings total across 3 stages (one each, all hub/coordinator-to-sibling cross-file calls). **No "extracted block calls back to coordinator" surprises** — confirmed by grep.

---

## 5. Import gotchas to watch (playbook §5.3)

| Gotcha | Sites in this file |
|---|---|
| `by` delegation needs `getValue` + `setValue` | 10 sites — L142 (hub val), L293 + L294 (Config var×2), L438 (ModelList val), L465 (ModelList var rememberSaveable), L977 + L983 (ModelPicker var×2), L1567 + L1568 (ProviderOverride var×2), L1653 (ProviderOverride sheet var). Each sibling needs both `getValue` + `setValue`. |
| `utils.plus` operator (`PaddingValues + PaddingValues`) | L480 in `ModelList` (`PaddingValues(16.dp) + PaddingValues(bottom = 128.dp)`). Stage 3 sibling needs `me.rerere.rikkahub.utils.plus`. |
| `ColumnScope.fillParentMaxHeight` | L490 in `ModelList`'s LazyColumn — implicit receiver scope, no extra import needed. Verified at compile. |
| `Modifier.longPressDraggableHandle()` | L523 in `ModelList` — from `sh.calvin.reorderable.ReorderableItem` scope. Imported via `ReorderableItem`. |
| `graphicsLayer { scaleX/scaleY }` | L524 in `ModelList` drag-scale — `androidx.compose.ui.graphics.graphicsLayer`. Stage 3 needs it. |
| `fastFilter` | L985 in `ModelPicker` — `androidx.compose.ui.util.fastFilter`. Stage 3 needs it. |
| `produceState` / `LaunchedEffect` | L438, L449 in `ModelList`. Stage 3 needs both `androidx.compose.runtime.{produceState, LaunchedEffect}`. |
| `rememberSaveable` | L465 in `ModelList`. Stage 3 needs `androidx.compose.runtime.saveable.rememberSaveable`. |

---

## 6. Visibility plan

| Symbol | Before | After Stage 1 | After Stage 2 | After Stage 3 |
|---|---|---|---|---|
| `SettingProviderDetailPage` | `public` (no mod) | unchanged | unchanged | unchanged |
| `SettingProviderConfigPage` | `private` | **`internal`** (widened) | unchanged | unchanged |
| `parseContextWindowInput` | `private` | unchanged | `private` in sibling | unchanged |
| `Int.formatContextWindowInput` | `private` | unchanged | `private` in sibling | unchanged |
| `ModelSettingsForm` | `private` | unchanged | **`internal`** (widened) | unchanged |
| `ModelTypeSelector` | `private` | unchanged | `private` in sibling | unchanged |
| `ModelModalitySelector` | `private` | unchanged | `private` in sibling | unchanged |
| `ModalAbilitySelector` | `public` (no mod) | unchanged | unchanged (`public`, byte-equal) | unchanged |
| `ProviderOverrideSettings` | `private` | unchanged | `private` in sibling | unchanged |
| `BuiltInToolsSettings` | `private` | unchanged | `private` in sibling | unchanged |
| `SettingProviderModelPage` | `private` | unchanged | unchanged | **`internal`** (widened) |
| `ModelList` | `private` | unchanged | unchanged | `private` in sibling |
| `ProviderSetting.modelListRequestKey` | `private` | unchanged | unchanged | `private` in sibling |
| `ProviderModelListRequestKey` | `private data class` | unchanged | unchanged | `private` in sibling |
| `ModelCard` | `private` | unchanged | unchanged | `private` in sibling |
| `AddModelButton` | `private` | unchanged | unchanged | `private` in sibling |
| `ModelPicker` | `private` | unchanged | unchanged | `private` in sibling |

Total visibility deltas: **3 widenings** (one per stage, each hub/coord ↔ sibling cross-file call). All justified by §4 same-file scan.

---

## 7. Proposed stage plan

### Stage 1 — extract Config tab
- **New sibling**: `SettingProviderConfigPage.kt` (~150 lines)
- **Contents** (byte-equal body): `SettingProviderConfigPage` (only)
- **Source range**: L287–418 (132 lines)
- **Coordinator strip**: 1705 → ~1565 (-140 with import cleanup)
- **Imports likely trimmed**: `AlertDialog`, `Button`, `Spacer`, `SiliconFlowPowerByIcon`, `SettingProviderBalanceOption`, `ProviderBalanceText`, `ProviderConfigure`, `ProviderConnectionTester`, `Refresh03`, `Delete01` (the ones only used by SettingProviderConfigPage). Each verified ≥1 baseline body ref + 0 trimmed coord ref before deletion.

### Stage 2 — extract `ModelSettingsForm` bundle (form + 6 internal helpers)
- **New sibling**: `SettingProviderModelSettingsForm.kt` (~720 lines)
- **Contents** (in original order, byte-equal): `parseContextWindowInput`, `Int.formatContextWindowInput`, `ModelSettingsForm`, `ModelTypeSelector`, `ModelModalitySelector`, `ModalAbilitySelector`, `BuiltInToolsSettings`, `ProviderOverrideSettings`.
- **Source ranges** (after Stage 1 strip — recompute at Stage 2 time): the helpers at original L257–285 + the form + selectors + tool/override blocks. Note `ModelSettingsForm` (L601) and below; helpers up top. Two non-contiguous ranges since `SettingProviderConfigPage` (Stage 1) sits between them.
  - Range A: helpers `parseContextWindowInput` + `formatContextWindowInput` (originally L257–285)
  - Range B: `ModelSettingsForm` + all 5 sub-helpers + `BuiltInToolsSettings` + `ProviderOverrideSettings` (originally L601–798 + L1145–1289 + L1481–1559 + L1561–1705)
- **Visibility**: `ModelSettingsForm` widens to `internal` (called from coordinator-still-in-place ModelCard + AddModelButton).
- **Coordinator strip**: ~1565 → ~840.

### Stage 3 — extract Models tab + cards
- **New sibling**: `SettingProviderModelTabPage.kt` (~750 lines)
- **Contents** (in original order, byte-equal): `SettingProviderModelPage`, `ModelList`, `ProviderSetting.modelListRequestKey`, `ProviderModelListRequestKey`, `AddModelButton`, `ModelPicker`, `ModelCard`.
- **Source ranges** (after Stage 2 strip): originally L420–599 (SettingProviderModelPage + ModelList + key + data class) + L800–1143 (AddModelButton + ModelPicker) + L1291–1479 (ModelCard). Recompute at Stage 3 time.
- **Visibility**: `SettingProviderModelPage` widens to `internal` (hub calls it cross-file).
- **Coordinator strip**: ~840 → ~150.

### Final coordinator
- Just `SettingProviderDetailPage` (the hub) + imports.
- Target: ~150 lines (-91% from 1705).

---

## 8. Per-stage success criteria

Each stage must:
- Compile: `./gradlew :app:compileDebugKotlin --offline` clean.
- Tests: `./gradlew :app:testDebugUnitTest --offline` → **exactly 3 failed / 467** (current baseline).
- Sub-agent Round 1 review: APPROVE (or NITS only).
- New sibling EOF: single `\n`.
- One commit per stage, no `--amend`, no `--no-verify`.

After **Stage 3 (final)**:
- 3-variant assemble: `./gradlew :app:assembleDebug :app:assembleNotion :app:assembleRefactortest --offline` → BUILD SUCCESSFUL.
- Codex independent review prompt → APPROVE → open PR with `--merge` per playbook §8.

---

## 9. Decisions for you

| # | Question | Recommendation |
|---|---|---|
| 1 | **Stage count: 2 or 3?** Alternative is 2 stages — combine Stage 2 + Stage 3 into one "Models everything" sibling (~1470 lines). Drawback: single huge sibling, harder review. | **3 stages**. Each sibling ends ≤720 lines (smaller than P2 first-cut OfficePro 793). Stage 1 small/easy, Stage 2 medium, Stage 3 medium. Each stage independently reviewable. |
| 2 | **Stage order**: Config first (small, easy win) vs Models first | **Config first** (Stage 1). Smallest blast radius for the first commit + warmup before the Form+Models extractions. |
| 3 | **Sibling naming**: `SettingProvider{Config,ModelSettingsForm,ModelTabPage}Page.kt` vs shorter names | **Long-but-explicit names** matching the P2 first cut style (`SettingExperimental{SubAgent,ModelCouncil,...}Page.kt`). Consistent. |
| 4 | **`ModalAbilitySelector`** is `public` but has 0 cross-file callers + name is also typo'd ("Modal" vs likely-intended "Model"). Tighten to `private`/`internal`? Fix typo? | **No — byte-equal preserve `public`, no rename.** Both are cleanup beyond the refactor's scope; defer to a follow-up PR. |
| 5 | **PR strategy**: single PR with 3 stage commits vs separate PRs | **Single PR**, 3 stage commits. Per playbook §8 merge-commit preserves history. |
| 6 | **Baseline change**: previous P2 baseline was 6/463; this branch reads 3/467 because `1d46d09c fix(context)` + `8967a468 fix(chat)` landed on main between cuts. | **Lock new baseline 3/467 for this refactor.** Each stage must preserve exactly this number. |

---

## 10. Open items / non-goals

- **Not changing behavior**. No reorder, no styling tweak, no copy edit.
- **Not touching `RouteActivity.kt`** beyond confirming import line continues to resolve (same package).
- **Not cleaning** `ModalAbilitySelector` typo or its uselessly-public visibility (defer).
- **Not extracting** to per-Composable-per-file siblings inside a stage — selectors / helpers ride along with their parent `ModelSettingsForm`.

---

## 11. Ready-to-go checklist (post-approval)

- [ ] User says GO on this doc.
- [ ] Commit this doc as `docs(p2): pre-cut analysis for SettingProviderDetailPage`.
- [ ] Stage 1 (Config) extract → sub-agent Round 1 → push.
- [ ] Stage 2 (ModelSettingsForm + helpers) extract → sub-agent Round 1 → push.
- [ ] Stage 3 (Models tab + cards) extract → sub-agent Round 1 → push.
- [ ] 3-variant assemble.
- [ ] Codex review prompt.
- [ ] PR + `--merge`.
- [ ] Cleanup remote/local branch; update memory.
