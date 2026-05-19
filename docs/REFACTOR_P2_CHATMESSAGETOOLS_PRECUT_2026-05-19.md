# Pre-cut: ChatMessageTools.kt (P2 第四刀)

> Pre-cut analysis per `docs/REFACTOR_PLAYBOOK.md` §2.1. Frozen historical record after user GO; subsequent corrections go in commit bodies, not edits to this doc.

## 0. Meta

- **Target**: `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- **Parent commit (refactor baseline)**: `ae597a0d` (= current `main`)
- **Branch**: `refactor/p2-chatmessagetools`
- **Refactor date**: 2026-05-19
- **Risk**: 🟡 medium (cross-block helper dependencies; no exotic Compose primitives)
- **Unit test baseline**: 467 tests / 3 failed (`GenerativeUiPlannerTest×2`, `DefaultProvidersTest×1`)

## 1. Top-level decl table

`grep -nE "^(@Composable|fun|private fun|class|data class|private data class|object|const val|private const|val|private val)"` →

| L | Decl | Visibility | Role |
|---|---|---|---|
| 198 | `getToolIcon(toolName, action)` | private | helper: pick ImageVector by tool name |
| 263 | `getToolKind(toolName)` | private | helper: classify tool into AgentToolKind |
| 315 | `JsonElement?.getStringContent(key)` | private | helper: parse string from json |
| 318 | `String.compactToolPreview(maxLength)` | private | helper: truncate preview text |
| 323 | `toolHasFailure(content, output)` | private | helper: detect tool failure |
| 341 | `toolStatusFromMessagePart(...)` | private | helper: derive AgentToolStatus |
| 355 | `toolStatusLabel(status)` | private | helper: localized status label |
| 363 | `toolStatusTone(status)` | private | helper: pick WorkspaceTone |
| 372 | `toolKindLabel(kind, toolName)` | private | helper: localized kind label |
| 387 | `ToolStatusPill(status, modifier)` | private | UI primitive: small pill |
| 399 | `AgentToolCallCapsule(...)` | private | UI primitive: clickable capsule |
| 482 | `toolDisplayTitle(toolName, arguments, memoryAction)` | private | helper: human-readable title |
| 556 | `ChainOfThoughtScope.ChatMessageToolStep(...)` | **public** | hub Composable |
| 904 | `ToolCallPreviewSheet(...)` | private | sheet Composable |
| 957 | `SearchWebPreview(...)` | private | sheet sub-Composable |
| 1104 | `ScrapeWebPreview(content)` | private | sheet sub-Composable |
| 1148 | `GenericToolPreview(...)` | private | sheet sub-Composable |
| 1241 | `ChainOfThoughtScope.AskUserToolStep(...)` | private | Composable: AskUser step |
| 1541 | `AskOptionChip(...)` | private | UI primitive: option chip |
| 1593 | `AskUserQuestion(...)` | private data class | model |
| 1601 | `ToolDenyReasonDialog(...)` | private | dialog Composable |
| 1646 | `SubAgentTaskStepView(...)` | **public** | Composable: SubAgent step |
| 1724 | `PHASE_LABEL_INTERVAL_MS` | private const | const: 5s |
| 1730 | `parseLatestSubAgentStatus(tools)` | private | helper |
| 1746 | `extractLatestSubAgentName(tools)` | private | helper |
| 1759 | `SubAgentRunStatus.toAgentToolStatus()` | private ext | helper |
| 1778 | `SubAgentRunSheet(...)` | private | sheet Composable |
| 1958 | `CouncilTaskStepView(...)` | **public** | Composable: Council step |
| 2020 | `parseLatestCouncilStatus(tools)` | private | helper |
| 2034 | `ModelCouncilCardStatus.toAgentToolStatus()` | private ext | helper |
| 2050 | `ModelCouncilRunSheet(...)` | private | sheet Composable |
| 2252 | `CouncilTabEntry` | private data class | model |
| 2254 | `CouncilRoundSection` | private data class | model |
| 2260 | `CouncilRoundContent(...)` | private | sub-Composable |
| 2284 | `CouncilRoundDivider(label)` | private | sub-Composable |
| 2312 | `CouncilObjectiveCard(objective)` | private | sub-Composable |
| 2368 | `String.shouldCollapseCouncilObjective()` | private ext | helper |
| 2373 | `String.toCouncilRoundSections()` | private ext | helper |
| 2403 | `COUNCIL_OBJECTIVE_COLLAPSED_LINES` | private const | const |
| 2404 | `COUNCIL_OBJECTIVE_COLLAPSE_LINES` | private const | const |
| 2405 | `COUNCIL_OBJECTIVE_COLLAPSE_CHARS` | private const | const |
| 2406 | `COUNCIL_ROUND_MARKER_TEXT` | private const | const |
| 2407 | `COUNCIL_ROUND_MARKER` | private val | const-like |
| 2410 | `extractFinalCouncilSynthesisText(tools)` | private | helper |
| 2427 | `extractCouncilModelLabel(tools, seatId)` | private | helper |
| 2454 | `extractCouncilSeatEntries(tools)` | private | helper |
| 2486 | `extractFinalCouncilSeatText(tools, seatId)` | private | helper |
| 2521 | `JsonObject.payloadObject(name)` | private ext | json helper |
| 2529 | `JsonObject.payloadArray(name)` | private ext | json helper |
| 2547 | `extractFinalSubAgentText(tools)` | private | helper |
| 2594 | `String.cleanCouncilLineBreaks()` | private ext | helper |

**Public funs (cross-file callers — must stay public byte-equal)**:
- `ChatMessageToolStep` ← `ChatMessage.kt:1313`
- `SubAgentTaskStepView` ← `ChatMessage.kt:671, 1325, 1618`
- `CouncilTaskStepView` ← `ChatMessage.kt:680, 1338, 1625`

## 2. Section boundaries

| Section | Line range | Approx |
|---|---|---|
| imports + package | L1-L197 | 197 |
| **Helper cluster + UI primitives** | L198-L554 | 357 |
| **ChatMessageToolStep** (hub) | L555-L901 | 347 |
| **Tool preview sheets** | L903-L1238 | 336 |
| **AskUser system** | L1240-L1644 | 405 |
| **SubAgent system** | L1645-L1955 | 311 |
| **Council system** | L1957-L2605 | 649 |
| **Total** | L1-L2605 | 2605 |

After all 4 stages, **coordinator = L1-L901 ≈ 899 lines** (helpers/primitives + hub).

## 3. Call graph (cross-section)

### 3.1 Helpers in L198-L554 used by which downstream section

| Helper | L | Only used in coordinator (L555-L901)? | Used elsewhere? |
|---|---|---|---|
| `getToolIcon` | 198 | YES (L617) | — |
| `getToolKind` | 263 | YES (L618) | — |
| `getStringContent` | 315 | YES (L325, L496-L548, L576, L596-L820+) | ⚠ **ToolPreview L914,L963,L1028-1030,L1117; SubAgent L1653,L1657,L1753** |
| `compactToolPreview` | 318 | YES (toolDisplayTitle body, L459-L549) | — |
| `toolHasFailure` | 323 | YES (only inside `toolStatusFromMessagePart`) | — |
| `toolStatusFromMessagePart` | 341 | YES (L590) | — |
| `toolStatusLabel` | 355 | indirect (only inside ToolStatusPill body) | — |
| `toolStatusTone` | 363 | indirect (only inside pill/capsule body) | — |
| `toolKindLabel` | 372 | indirect (only inside capsule body) | — |
| `ToolStatusPill` | 387 | YES (L470 inside capsule, L628 inside hub) | — |
| `AgentToolCallCapsule` | 399 | YES (L614) | ⚠ **SubAgent L1701; Council L1995** |
| `toolDisplayTitle` | 482 | YES (L589) | — |

### 3.2 Helpers OUTSIDE L198-L554 that are also cross-section

| Helper | Decl L | Decl block | Used at | Used by block |
|---|---|---|---|---|
| `PHASE_LABEL_INTERVAL_MS` | 1724 | SubAgent | L1676, L1971 | SubAgent + **Council** |
| `payloadObject` | 2521 | Council | L1656, L2417 | **SubAgent** + Council |
| `payloadArray` | 2529 | Council | L2438, L2461, L2493 | Council only |
| `extractFinalSubAgentText` | 2547 | Council | L1817, L2547 | **SubAgent** + Council |

⚠ **SubAgent ↔ Council circular reference** — drives stage order (Council before SubAgent, see §6).

### 3.3 Section-local decls called from elsewhere

| Decl | Decl block | Used at | Used by block |
|---|---|---|---|
| `ToolCallPreviewSheet` | ToolPreview L904 | L893 | hub (coordinator) |
| `AskUserToolStep` | AskUser L1241 | L566 | hub (coordinator) |
| `ToolDenyReasonDialog` | AskUser L1601 | L883 | hub (coordinator) |
| All others (SearchWebPreview, ScrapeWebPreview, GenericToolPreview, AskOptionChip, AskUserQuestion, SubAgentRunSheet, Council {Tab,Round,Sheet,...}) | (their own block) | within-block only | — |

## 4. ⚠ Same-file cross-stage caller scan (P2 stage 4 lesson)

For every PRIVATE helper that **stays in coordinator** after stage N, scan future-extracted blocks for refs:

| Helper kept in coordinator | Called from extracted? | Action |
|---|---|---|
| `getStringContent` (L315) | ToolPreview L914,963,1028-30,1117; SubAgent L1653,1657,1753 | **widen p→i at Stage 1** |
| `AgentToolCallCapsule` (L399) | SubAgent L1701; Council L1995 | **widen p→i at Stage 3** (Council stage) |
| `getToolIcon` / `getToolKind` / `compactToolPreview` / `toolHasFailure` / `toolStatusFromMessagePart` / `toolStatusLabel` / `toolStatusTone` / `toolKindLabel` / `ToolStatusPill` / `toolDisplayTitle` | only inside hub L555-L901 | no widening |

For every PRIVATE helper that **moves to a sibling** but is called from another future-extracted block or from coordinator:

| Sibling-local decl | Cross-section caller | Action |
|---|---|---|
| `ToolCallPreviewSheet` (Stage 1) | hub L893 | **widen p→i in sibling** |
| `AskUserToolStep` (Stage 2) | hub L566 | **widen p→i in sibling** |
| `ToolDenyReasonDialog` (Stage 2) | hub L883 | **widen p→i in sibling** |
| `payloadObject` (Stage 3 Council sibling) | SubAgent block (still coord at stage 3) at L1656 | **widen p→i in sibling** |
| `extractFinalSubAgentText` (Stage 3 Council sibling) | SubAgent block at L1817 | **widen p→i in sibling** |
| `PHASE_LABEL_INTERVAL_MS` (Stage 4 SubAgent sibling) | Council sibling at L1971 — at extraction time Council is already a sibling → cross-sibling reference | **widen p→i at Stage 3** in coordinator (so Council sibling can see it), then preserve internal when SubAgent moves it to its sibling at Stage 4 |
| All others (SearchWebPreview, ScrapeWebPreview, GenericToolPreview, AskOptionChip, AskUserQuestion, parse/extract SubAgent helpers, SubAgentRunSheet, Council intra-block decls, payloadArray, etc.) | within-block only | stays private |

**Widening tally**:
- Stage 1: 1 widening in coordinator (`getStringContent`), 1 widening in sibling (`ToolCallPreviewSheet`)
- Stage 2: 2 widenings in sibling (`AskUserToolStep`, `ToolDenyReasonDialog`)
- Stage 3 (Council): 2 widenings in coordinator (`AgentToolCallCapsule`, `PHASE_LABEL_INTERVAL_MS`), 2 widenings in sibling (`payloadObject`, `extractFinalSubAgentText`)
- Stage 4 (SubAgent): 0 net new widenings — but `PHASE_LABEL_INTERVAL_MS` retains `internal` visibility when moved (was widened in stage 3)

Total = **8 widenings** across 4 stages. All documented in their respective commit bodies.

## 5. Cross-file caller scan

```bash
grep -rn --include="*.kt" "\bChatMessageToolStep\b\|\bSubAgentTaskStepView\b\|\bCouncilTaskStepView\b" app/src/main/java/
```

Result:
- `ChatMessage.kt:1313` calls `ChatMessageToolStep`
- `ChatMessage.kt:671, 1325, 1618` call `SubAgentTaskStepView`
- `ChatMessage.kt:680, 1338, 1625` call `CouncilTaskStepView`
- (no other callers anywhere in the app module)

All 3 public funs **stay public byte-equal**. No tightening (no opportunity — they're consumed cross-package from `ChatMessage.kt` same package, but other potential callers cannot be excluded without a module-wide grep; staying public is the safe + byte-equal choice).

## 6. Stage plan (recommended: 4 stages, Council BEFORE SubAgent)

| Stage | Extract range | Sibling file | Approx lines | Widenings |
|---|---|---|---|---|
| 1 | L903-L1238 (ToolPreview sheets) | `ChatMessageToolPreviewSheet.kt` | 336 + imports | coord: `getStringContent` p→i; sibling: `ToolCallPreviewSheet` p→i |
| 2 | L1240-L1644 (AskUser system) | `ChatMessageAskUserStep.kt` | 405 + imports | sibling: `AskUserToolStep` p→i, `ToolDenyReasonDialog` p→i |
| 3 | L1957-L2605 (Council system) | `ChatMessageCouncilStep.kt` | 649 + imports | coord: `AgentToolCallCapsule` p→i, `PHASE_LABEL_INTERVAL_MS` p→i; sibling: `payloadObject` p→i, `extractFinalSubAgentText` p→i |
| 4 | L1645-L1955 (SubAgent system) | `ChatMessageSubAgentStep.kt` | 311 + imports | none new — `PHASE_LABEL_INTERVAL_MS` stays internal when relocated |

### 6.1 Why Council BEFORE SubAgent

`SubAgent block (L1645-1955)` calls `payloadObject` (L1656 → L2521 in Council block) and `extractFinalSubAgentText` (L1817 → L2547 in Council block). If we extract SubAgent first, those Council-block-resident helpers would need to be widened by reaching into Council block while it's still in coordinator — feasible but awkward.

Cleaner order:
1. Extract **Council** first; Council sibling owns `payloadObject` + `extractFinalSubAgentText` (widened internal).
2. Extract **SubAgent** next; SubAgent sibling references those helpers cross-sibling (same package, internal visibility — no import line edit needed).

### 6.2 Final coordinator state

After all 4 stages: `ChatMessageTools.kt` = L1-L901 of the parent commit (≈ 899 lines, minus dead-import cleanup). Contains:
- Imports (cleaned)
- L198-L554 helper cluster + UI primitives (2 widenings noted above)
- L555-L901 `ChatMessageToolStep` (the hub composable, fully self-contained)

### 6.3 Strip order per stage

Per playbook §3 step 6: highest-line range first, OR single combined awk filter. All 4 stages here have a SINGLE contiguous range, so simple `awk 'NR<START || NR>END'` works.

## 7. Risk assessment

**🟡 medium-low** overall. Below baseline P1 ChatInput because:

- ✅ No WebView, no graphicsLayer, no DisposableEffect, no produceState, no rememberSaveable
- ✅ No `utils.plus` operator
- ✅ No Sandbox-style bitmap thumbnail / state-mutation traps
- ⚠ 10 LaunchedEffect, 15 `by` delegation — each is local to its containing composable, no cross-LE coordination. Need `getValue` + `setValue` imports per sibling that uses `by`.
- ⚠ Cross-section helper sharing (esp. SubAgent ↔ Council circular reference) — addressed by stage ordering + 8 documented widenings.
- ✅ 56 top-level decls (manageable) — not 70 as earlier-estimated.
- ✅ All 3 public functions have just 7 known call sites, all in `ChatMessage.kt`. Stay public byte-equal.

## 8. Decisions for user

### 8.1 Stage count: 4 stages vs combined SubAgent+Council in 1 stage

| Option | Stages | Pros | Cons |
|---|---|---|---|
| **A (recommended)** | 4 stages: ToolPreview / AskUser / Council / SubAgent | per-stage atomicity (each commit is ~300-650 lines), smaller blast radius per review | cross-sibling import dependency (SubAgent → Council) |
| B | 3 stages: ToolPreview / AskUser / SubAgent+Council combined | no cross-sibling imports — both move together in one commit | one stage extracts ~960 lines + creates 2 new files; review surface bigger |

**Recommendation**: **A (4 stages)**. Cross-sibling internal imports are clean (same package, no import line edits). Smaller per-stage commits make sub-agent + Codex review easier.

### 8.2 Import sort policy

**Strict byte-equal** (no alphabetical re-sort). Consistent with P1 ChatInput + P2 prior cuts. Only deletions of this-refactor orphan imports.

### 8.3 PR strategy

**Single PR with stage-by-stage commits**, merged with `--merge` (NEVER squash/rebase, per playbook §8.2). Audit trail per stage preserved.

### 8.4 Pre-existing dead imports

Will be enumerated per-stage. Verify against baseline `ae597a0d` with:
```bash
git show ae597a0d:app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt | grep -cE "\bSYM\b"
```
If = 1 (import line only) → pre-existing dead → DO NOT delete (CLAUDE.md §3).

## 9. Per-stage gates (reminder)

Every stage:
1. `:app:compileDebugKotlin --offline` clean
2. `:app:testDebugUnitTest --offline` → **467 / 3 failed** (baseline match)
3. Sub-agent Round 1 review = APPROVE
4. EOF clean (`tail -c 5 | xxd` shows trailing `0a`, no double newline)

Final stage (Stage 4 SubAgent) additionally:
5. `:app:assembleDebug :app:assembleNotion :app:assembleRefactortest --offline` all BUILD SUCCESSFUL

Then Codex independent review (playbook §7), then PR with `--merge`.

---

**Status**: Pre-cut ready. Awaiting user **GO** to begin Stage 1.
