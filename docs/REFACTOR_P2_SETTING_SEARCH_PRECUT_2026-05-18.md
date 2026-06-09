# P2 Pre-cut — SettingSearchPage.kt

> Pre-cut analysis per `docs/REFACTOR_PLAYBOOK.md` §2.
> **Do not strip anything until this doc is reviewed.**

- **Target**: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt` (1571 lines)
- **Branch**: `refactor/p2-settings-search` (from `github-private/main` = `53604a03`)
- **Baseline**: 6 failed / 463 unit tests — GenerativeUiPlannerTest×2 / ContextFootprintEstimatorTest×3 / DefaultProvidersTest×1 (matches expected)
- **Risk**: 🟢 LOW — setting-page pattern, no side effects in extracted bodies, no state machines, no Compose subtle traps. One pre-existing `public` cross-package caller (handled byte-equal).

---

## 1. Top-level decl table

| Lines | Name | Vis | Role |
|---|---|---|---|
| 83–352 | `SettingSearchPage` | `fun` (public) | **Hub** Composable: Scaffold + LazyColumn + edit-service sheet |
| 354–378 | `SearchRecommendationCard` | `private` | Card item (recommendation copy) |
| 380–455 | `BuiltinFreeSearchCard` | `private` | Card item (built-in toggles) |
| 457–488 | `BuiltinSourceRow` | `private` | Helper row for built-in card |
| 490–493 | `SearchServiceEditorTarget` | `private data class` | Editor sheet state |
| 495–504 | `resolveSelectedSearchIndex` | `private fun` | Pure helper for selection reconcile |
| 506–554 | `AgentSearchEnableCard` | `private` | Card item (global toggle) |
| 556–645 | `SearchProviderCard` | `private` | Card item (per service row) |
| 647–741 | `SearchServiceEditorSheet` | `private` | ModalBottomSheet for service edit |
| 743–815 | `SearchServiceOptionsEditor` | `private` | `when`-dispatcher → 16 provider-specific editors |
| 817–840 | `SearchAbilityTagLine` | `fun` (**public**) | Tag row used in sheet AND in `SearchPicker.kt` |
| 842–891 | `TavilyOptions` | `private` | Provider options editor |
| 893–915 | `ExaOptions` | `private` | Provider options editor |
| 918–940 | `ZhipuOptions` | `fun` (**public**, but only dispatcher-called) | Provider options editor |
| 942–984 | `CommonOptions` | `private` | Card item (common search options) |
| 986–1080 | `SearXNGOptions` | `private` | Provider options editor |
| 1082–1131 | `SearchLinkUpOptions` | `private` | Provider options editor |
| 1133–1155 | `BraveOptions` | `private` | Provider options editor |
| 1157–1175 | `SerperOptions` | `private` | Provider options editor |
| 1177–1195 | `SerpApiOptions` | `private` | Provider options editor |
| 1198–1220 | `MetasoOptions` | `private` | Provider options editor |
| 1222–1244 | `OllamaOptions` | `private` | Provider options editor |
| 1246–1306 | `PerplexityOptions` | `private` | Provider options editor |
| 1308–1330 | `FirecrawlOptions` | `private` | Provider options editor |
| 1332–1396 | `JinaOptions` | `private` | Provider options editor |
| 1398–1441 | `BochaOptions` | `private` | Provider options editor |
| 1443–1492 | `RikkaHubOptions` | `private` | Provider options editor |
| 1494–1571 | `GrokOptions` | `private` | Provider options editor |

Note: `CommonOptions` (L942–984) is **interleaved between provider options**, not grouped with the other list cards. Pre-existing layout quirk; we'll handle by stripping non-contiguous ranges or by leaving CommonOptions in hub during Stage 1.

---

## 2. Call graph (internal)

```
SettingSearchPage (hub)
├─ AgentSearchEnableCard
├─ BuiltinFreeSearchCard
│   └─ BuiltinSourceRow      (only caller)
├─ SearchRecommendationCard
├─ SearchProviderCard
├─ SearchServiceEditorTarget (data class — constructor + property reads)
├─ resolveSelectedSearchIndex
├─ CommonOptions
└─ SearchServiceEditorSheet
    ├─ SearchAbilityTagLine       (also called externally — see §3)
    └─ SearchServiceOptionsEditor (dispatcher)
        ├─ TavilyOptions
        ├─ ExaOptions
        ├─ ZhipuOptions
        ├─ SearXNGOptions
        ├─ SearchLinkUpOptions
        ├─ BraveOptions
        ├─ SerperOptions
        ├─ SerpApiOptions
        ├─ MetasoOptions
        ├─ OllamaOptions
        ├─ PerplexityOptions
        ├─ FirecrawlOptions
        ├─ JinaOptions
        ├─ BochaOptions
        ├─ RikkaHubOptions
        └─ GrokOptions
```

Each provider-options Composable is **only** called from `SearchServiceOptionsEditor`. None calls back to anything in the hub.

---

## 3. Cross-file caller scan (within `app/`)

`grep -rn --include="*.kt" "\\bSYM\\b" app/src/main/java/` for every top-level decl, excluding `SettingSearchPage.kt`:

| Symbol | External callers | Action |
|---|---|---|
| `SearchAbilityTagLine` | **`ui/components/ai/SearchPicker.kt:55` (import) + `:280` (call)** | Must remain `internal`-or-`public` after move. **Byte-equal: stays `public`.** |
| All others (cards, options, dispatcher, sheet, helper, data class) | No real callers (grep hits on `SearchServiceOptions.XxxOptions` are matches against the unrelated data-class FQN — false positives) | Visibility depends on which side of the cut they end up on (see §6) |

`SearchPicker.kt` is in `me.rerere.rikkahub.ui.components.ai` package; the import already names the FQN `me.rerere.rikkahub.ui.pages.setting.SearchAbilityTagLine`. The sibling will live in the **same `me.rerere.rikkahub.ui.pages.setting` package**, so the import line in `SearchPicker.kt` does NOT change.

---

## 4. ⚠ Same-file cross-stage caller scan (P2 stage-4 lesson — playbook §2.1 #4)

For every symbol that **stays in the hub** after extraction, check whether anything in the extracted ranges calls it; for every symbol that **moves**, check whether the hub still calls it.

**Stage 1 = extract sheet bundle** (`SearchServiceEditorSheet`, `SearchServiceOptionsEditor`, `SearchAbilityTagLine`, all 16 provider Options editors):

- Hub → moved? **Yes**: hub calls `SearchServiceEditorSheet(...)` at L299.
  → `SearchServiceEditorSheet` must widen `private` → `internal`.
- Moved → hub? **No** — each moved symbol's body only references other moved symbols + external libs (`Tag`, `Text`, `FormItem`, `SearchService.getService`, etc.).
- `SearchAbilityTagLine` already `public` → byte-equal, leave as-is.
- `ZhipuOptions` already `public` (no caller outside dispatcher) → byte-equal, leave as-is. *(Tightening to `private` would also work but violates byte-equal discipline; defer.)*
- `BuiltinSourceRow` stays in hub at Stage 1 (it moves with BuiltinFreeSearchCard in Stage 2); no Stage-1 dep.

**Stage 2 = extract list cards** (`AgentSearchEnableCard`, `BuiltinFreeSearchCard`, `BuiltinSourceRow`, `SearchRecommendationCard`, `SearchProviderCard`, `CommonOptions`, `SearchServiceEditorTarget`, `resolveSelectedSearchIndex`):

- Hub → moved? **Yes**, hub calls all of them:
  - `AgentSearchEnableCard` — L158
  - `BuiltinFreeSearchCard` — L176
  - `SearchRecommendationCard` — L205
  - `SearchProviderCard` — L215
  - `CommonOptions` — L284
  - `SearchServiceEditorTarget` — L103, L227 (constructor), L298 (`target.index`, `target.service`)
  - `resolveSelectedSearchIndex` — L142, L240
  → All 7 widen `private` → `internal`. (`BuiltinSourceRow` is called only by `BuiltinFreeSearchCard` — both move; stays `private`.)
- Moved → hub? **No** — list-card bodies don't reference each other across the cut, and they don't call sheet-bundle symbols (sheet is opened by setting hub-local `editingService` state, not by direct call from the cards).

**Conclusion**: 1 widening at Stage 1 (`SearchServiceEditorSheet`), 7 widenings at Stage 2. **No "extracted block calls back to coordinator" surprises** — this is the trap P2 Stage 4 ran into and we've eliminated it here.

---

## 5. Import gotchas to watch (playbook §5.3)

| Gotcha | Where in this file |
|---|---|
| `by` delegation needs `getValue` + `setValue` | 4 sites: L85 (hub), L89 (hub), L656 (sheet — Stage 1), L947 (CommonOptions — Stage 2). Hub keeps both. Stage 1 sibling needs both. Stage 2 sibling needs both. |
| `utils.plus` operator (`PaddingValues + PaddingValues`) | L153 in hub (`it + PaddingValues(16.dp)`). Hub keeps `me.rerere.rikkahub.utils.plus`. Siblings don't use it. |
| List concat `+` (kotlin.collections) | L220, L318. No special import. |
| Extension functions on `ReorderableItemScope` (e.g. `longPressDraggableHandle`) | Called inside hub's lambda passed to `SearchProviderCard.dragHandle`, not inside the card body — hub keeps the import. |

---

## 6. Visibility plan

| Symbol | Before | After Stage 1 | After Stage 2 |
|---|---|---|---|
| `SettingSearchPage` | `public` (no mod) | unchanged | unchanged |
| `SearchServiceEditorSheet` | `private` | **`internal`** (widened) | unchanged |
| `SearchServiceOptionsEditor` | `private` | `private` in sibling | unchanged |
| `SearchAbilityTagLine` | `public` (no mod) | unchanged (`public`) | unchanged |
| `ZhipuOptions` | `public` (no mod) | unchanged (`public`) | unchanged |
| 15 other provider Options | `private` | `private` in sibling | unchanged |
| `AgentSearchEnableCard` | `private` | unchanged | **`internal`** |
| `BuiltinFreeSearchCard` | `private` | unchanged | **`internal`** |
| `BuiltinSourceRow` | `private` | unchanged | `private` in sibling |
| `SearchRecommendationCard` | `private` | unchanged | **`internal`** |
| `SearchProviderCard` | `private` | unchanged | **`internal`** |
| `CommonOptions` | `private` | unchanged | **`internal`** |
| `SearchServiceEditorTarget` (data class) | `private` | unchanged | **`internal`** |
| `resolveSelectedSearchIndex` | `private` | unchanged | **`internal`** |

Total visibility deltas: **1 widening at Stage 1**, **7 widenings at Stage 2**. All justified by §4 scan (callers across cut boundary).

---

## 7. Proposed stage plan

### Stage 1 — extract editor sheet bundle
- **New sibling**: `SettingSearchServiceEditorSheet.kt` (~900 lines)
- **Contents** (in order, byte-equal bodies): `SearchServiceEditorSheet`, `SearchServiceOptionsEditor`, `SearchAbilityTagLine`, then 16 provider Options Composables in the original order.
- **Source ranges** to extract (two non-contiguous):
  - `L647–940` (sheet + dispatcher + tag-line + Tavily + Exa + Zhipu)
  - `L986–1571` (SearXNG → Grok)
  - `CommonOptions` (L942–984) stays in hub for Stage 1; moves with Stage 2.
- **Strip order**: high → low (`L986–1571` first, then `L647–940`) to preserve line numbers in the second strip.
- **Coordinator delta**: 1571 → ~656 (Stage 1 only) — minus the ~915 lines extracted, plus the `internal` modifier on `SearchServiceEditorSheet`.
- **Imports trimmed**: Provider-options-only imports (`OutlinedTextField`, `SegmentedButton`, `KeyboardOptions`, `KeyboardType`, `BottomSheetDefaults`, `ModalBottomSheet`, `ProvideTextStyle`, `rememberModalBottomSheetState`, `rememberScrollState`, `verticalScroll`, `fillMaxHeight`, `Select`, `Tag`, `TagType`, `kotlin.reflect.full.primaryConstructor`, `SearchService`) — verify each has 0 refs in trimmed coordinator before deleting.

### Stage 2 — extract list cards
- **New sibling**: `SettingSearchListCards.kt` (~340 lines)
- **Contents** (in original order, byte-equal): `SearchRecommendationCard`, `BuiltinFreeSearchCard`, `BuiltinSourceRow`, `SearchServiceEditorTarget`, `resolveSelectedSearchIndex`, `AgentSearchEnableCard`, `SearchProviderCard`, `CommonOptions`.
- **Source ranges** (after Stage 1 strip — line numbers will shift; use post-Stage-1 file):
  - L354–488 (recommendation + builtin + helper)
  - L490–504 (data class + helper fn)
  - L506–645 (toggle + provider card)
  - CommonOptions block (was L942–984; after Stage 1 strip it shifts to a new location — recompute at Stage 2 time)
- **Coordinator delta**: ~656 → ~270 (hub fn + imports only).
- **Imports trimmed**: `Card`, `CardDefaults`, `Surface`, `IconButton`, `Switch`, `AutoAIIcon`, `FormItem`, `OutlinedNumberInput`, `SearchCommonOptions`, `HugeIcons.{DragDropHorizontal,Add01,Edit01,Delete01}`, `Settings` (if unused in hub after card move), `kotlin.uuid.Uuid` if only used by `SearchServiceEditorTarget`/`resolveSelectedSearchIndex`. Each verified with 0 body refs before deletion.

### Final coordinator
- Just `SettingSearchPage` (the hub Composable) + imports needed by hub: `R`, `BackButton`, `CustomColors`, `koinViewModel`, `SettingVM`, `Settings` (state type), `SearchServiceOptions`, `utils.plus`, `Uuid`, `ReorderableItem`, `rememberReorderableLazyListState`, `LocalHapticFeedback`, `HapticFeedbackType`, all relevant Compose foundation/material3/runtime imports for the hub body.
- Target: ~270 lines (-83%).

---

## 8. Per-stage success criteria

Each stage must:
- Compile: `./gradlew :app:compileDebugKotlin --offline` clean.
- Tests: `./gradlew :app:testDebugUnitTest --offline` → exactly 6 failed / 463 (baseline).
- Sub-agent Round 1 review: APPROVE (or NITS only).
- New sibling EOF: single `\n`.
- One commit per stage, no `--amend`, no `--no-verify`.

After **Stage 2 (final)**:
- 3-variant assemble: `./gradlew :app:assembleDebug :app:assembleNotion :app:assembleRefactortest --offline` → BUILD SUCCESSFUL.
- Codex independent review prompt → wait for APPROVE → open PR with `--merge` mode per playbook §8.

---

## 9. Decisions for you

| # | Question | Recommendation |
|---|---|---|
| 1 | **Stage count: 2 or 3?** 3-stage variant = split provider Options into a Stage 1a (~700 lines, 16 Composables) and Stage 1b (sheet + dispatcher + tag-line, ~200 lines). Cleaner units but creates an intermediate state with 16 internal-but-unused Composables until Stage 1b lands, plus an extra commit. | **2 stages** (Stage 1 bundled). Sheet + dispatcher + provider Options are conceptually one feature ("the editor sheet"); splitting them is artificial. Biggest sibling will be ~900 lines, in line with P2 OfficePro (~793). |
| 2 | **Import order policy**: strict byte-equal (preserve existing order in coordinator after trim) vs alphabetical sort | **Strict byte-equal** — same as P1/P2. Reduces diff noise and reviewer cognitive load. |
| 3 | **PR strategy**: single PR with both stage commits vs two PRs | **Single PR** with both stages as commits inside (per playbook §8 — merge mode preserves stage history). Low-risk + cohesive change. |
| 4 | **`ZhipuOptions` is uselessly `public`.** Tighten to `private` in sibling? | **No, keep `public`** (byte-equal). Defer cleanup; not in this refactor's scope. |
| 5 | **`SearchAbilityTagLine` is `public` but only one cross-package caller.** Tighten to `internal`? | **No, keep `public`** (byte-equal). Same reasoning as #4. |

---

## 10. Open items / non-goals

- **Not changing behavior**. No reorder, no styling tweak, no copy edit.
- **Not touching `SearchPicker.kt`** beyond confirming its import line still resolves (same package).
- **Not cleaning `BingLocalOptions`**'s `Unit` branch (L793) or other pre-existing oddities.
- **Not extracting nested helpers**: `BuiltinSourceRow` (private inside the same module) stays in sibling 2 as `private`, doesn't need its own file.
- **No fancy refactor of `SearchServiceOptionsEditor`**'s `when` dispatch into a registry pattern — that's a behavior-adjacent change and belongs to a separate PR.

---

## 11. Ready-to-go checklist (post-approval)

- [ ] User says GO on this doc.
- [ ] Commit this doc as `docs(p2): pre-cut analysis for SettingSearchPage`.
- [ ] Stage 1 extract → sub-agent Round 1 → push.
- [ ] Stage 2 extract → sub-agent Round 1 → push.
- [ ] 3-variant assemble.
- [ ] Codex review prompt.
- [ ] PR + `--merge`.
- [ ] Cleanup remote/local branch; update memory.
