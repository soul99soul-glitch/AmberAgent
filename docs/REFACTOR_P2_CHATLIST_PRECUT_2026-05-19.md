# REFACTOR P2 — ChatList.kt pre-cut analysis

## §0 Meta

- **Target**: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- **Lines**: 2293 (header L1-162 imports; body L163-2293)
- **Branch**: `refactor/p2-chatlist` off main `307ee8a5`
- **Test baseline (re-verified 2026-05-19)**: **467 tests, 3 failed** — `GenerativeUiPlannerTest.directDrawingRequestsDisableToolDetours`, `GenerativeUiPlannerTest.toolMediatedVisualRequestsWarnAgainstProgressWidgets`, `DefaultProvidersTest.default providers are curated and deletable`
- **Risk**: 🟡 medium-low — text-extraction kind, no state machine, but ~30 cross-section symbol widenings (constants/data classes/helpers/composables) due to ChatListNormal consuming everything

Follows [REFACTOR_PLAYBOOK.md](./REFACTOR_PLAYBOOK.md). New since P2 stage 4: pre-cut decl grep pattern expanded (see lesson in `project_rikkahub_phase2_target.md`).

---

## §1 Top-level decl table

Generated with the **expanded** grep pattern (per stage-4 lesson — covers `private object` / `private suspend fun` / `private enum class` / `private val = …` which the playbook §2.1 sample grep missed):

```bash
grep -nE "^(@Composable|@OptIn|fun|private fun|internal fun|public fun|suspend fun|private suspend fun|internal suspend fun|class|sealed|data class|private data class|internal data class|object|private object|internal object|enum class|private enum class|internal enum class|const val|private const|internal const|val |private val |internal val )" "$F"
```

| Line | Decl | Visibility | Role |
|---|---|---|---|
| 164 | `TAG = "ChatList"` | private const | **DEAD** — no in-file caller; not exported |
| 168 | `SCROLL_TAG = "ChatScroll"` | private const | diagnostic log tag, ChatListNormal only |
| 169 | `LoadingIndicatorKey` | private const | LazyColumn key, ChatListNormal only |
| 170 | `HistoryLoadingItemKey` | private const | LazyColumn key, ChatListNormal only |
| 171 | `ScrollBottomKey` | private const | LazyColumn key, ChatListNormal only |
| 172 | `TimelineHorizontalPadding = 16.dp` | private val | layout const, ChatListNormal only |
| 173 | `SendTransitionSlideDistance = 8.dp` | private val | layout const, ChatListNormal only |
| 179 | `COMPACT_SUMMARY_WHITESPACE_RE` | private val | regex, used by **summaryPreviewOf + ContextCompactInProgressMarker** |
| 181 | `TimelineHistoryLoadSignal` | private data class | snapshotFlow signal, ChatListNormal only |
| 189 | `TimelineScrollAnchor` | private data class | scroll anchor record, ChatListNormal only |
| 202 | `summaryPreviewOf` | private fun | helper, ChatListNormal callsites×3 |
| 210-214 | `Timeline{Top,Bottom,Item,Inner,Sel}Padding` | private val (×5) | layout consts, ChatListNormal only |
| 215-217 | `MarkdownPrewarm{Before,After,Max}` | private const (×3) | prewarm consts, **tail helper only** |
| 218 | `ActionOptionLineRegex` | private val | regex, **tail helper only** |
| 221 | `ActionOptionCuePhrases` | private val (List) | phrase list, **tail helper only** |
| 244 | `TimelineFollowMode` | private enum class | follow-state machine, ChatListNormal only |
| 250 | `Modifier.dashedRoundedBorder` | private fun (ext) | helper, **PendingUserMessageBubble only** |
| 265 | `TimelineHistoryLoadingIndicator` | @Composable private fun | indicator |
| 303 | `AgentWorkingIndicator` | @Composable private fun | indicator |
| 344 | `ContextCompactInProgressMarker` | @Composable private fun | marker (uses COMPACT_SUMMARY_WHITESPACE_RE) |
| 478 | `ContextCompactMarker` | @Composable private fun | marker |
| 540 | `PendingUserMessageBubble` | @Composable private fun | marker (uses dashedRoundedBorder) |
| 622 | `ChatList` | @Composable **public** fun | **entry**, called by ChatPage.kt:638 |
| 717 | `ChatListNormal` | @Composable private fun | the 1023-line beast |
| 1741 | `extractMatchingSnippet` | private fun | tail helper, **ChatSuggestionsRow only** |
| 1765 | `LazyListState.isNearListEnd` | internal fun (ext) | scroll predicate, ChatListNormal only |
| 1772 | `LazyListState.isAtTimelineBottom` | internal fun (ext) | scroll predicate, ChatListNormal only |
| 1781 | `LazyListState.markdownPrewarmTexts` | private fun (ext) | prewarm helper, ChatListNormal only |
| 1808 | `buildLazyItemMessageIndexMap` | internal fun | index map, ChatListNormal + **ChatPage.kt** |
| 1832 | `List<Int?>.firstLazyIndexForMessage` | internal fun (ext) | index lookup, **ChatPage.kt only** (no same-file caller) |
| 1836 | `ChatMessageVirtualItem.isAdjacentMarkdownChild` | private fun (ext) | spacing predicate, ChatListNormal only |
| 1843 | `TimelineSelectableMessageItem` | @Composable private fun | row item, ChatListNormal only |
| 1876 | `MessageNode.markdownPrewarmTexts` | private fun (ext) | nested helper of L1781 |
| 1896 | `Conversation.actionSuggestionTexts` | private fun (ext) | called by ChatListNormal L779 |
| 1911 | `String.extractAssistantActionOptions` | private fun (ext) | intra-tail (called by L1903) |
| 1935 | `String.isActionOptionCueLine` | private fun (ext) | intra-tail (called by L1916) |
| 1949 | `String.normalizedActionSuggestionOrNull` | private fun (ext) | intra-tail (called by L1906/1924) |
| 1969 | `String.compactSuggestionKey` | private fun (ext) | intra-tail (called by L1907) |
| 1973 | `buildHighlightedText` | private fun | called by ChatSuggestionsRow L2103 |
| 2012 | `ChatListPreview` | @Composable private fun | preview mode, called by ChatList entry L667 |
| 2125 | `ChatSuggestionsRow` | @Composable private fun | suggestion strip, ChatListNormal L1725 |
| 2169 | `BoxScope.MessageJumper` | @Composable private fun | jump-to overlay, ChatListNormal L1713 |

---

## §2 Section boundaries

| Section | Range | Lines | Description |
|---|---|---|---|
| **A — Imports + header** | L1-162 | 162 | package + 158 imports |
| **B — Constants + helpers** | L164-262 | 99 | top-level consts, regexes, data classes, enum, summaryPreviewOf, dashedRoundedBorder |
| **C — Indicator/marker composables** | L264-619 | 356 | 5 composables |
| **D — ChatList entry** | L621-714 | 94 | public entry, dispatches preview vs normal |
| **E — ChatListNormal** | L716-1740 | 1025 | the big composable |
| **F — Tail helpers + extensions** | L1741-2010 | 270 | scroll/index/prewarm/action-suggestion/highlight |
| **G — Sub-composables** | L2011-2293 | 283 | ChatListPreview, ChatSuggestionsRow, BoxScope.MessageJumper |

---

## §3 Call graph (same-file cross-section)

Result of `grep -nE "\bSymbol\b"` per decl. Only listing **cross-section** edges (intra-section calls suppressed). E→F means a symbol declared in section E is called from section F.

### B → B (intra-section)
- `COMPACT_SUMMARY_WHITESPACE_RE` (B/L179) → `summaryPreviewOf` body L206 ✓
- `summaryPreviewOf` → no intra-B caller (called from C and E)

### B → C
- `COMPACT_SUMMARY_WHITESPACE_RE` (B/L179) → `ContextCompactInProgressMarker` body L452 ⚠
- `dashedRoundedBorder` (B/L250) → `PendingUserMessageBubble` body L573 ⚠

### B → E (most common)
- `SCROLL_TAG` → L815 (inside ChatListNormal)
- `SendTransitionSlideDistance` → L775
- `LoadingIndicatorKey` → L1585
- `HistoryLoadingItemKey` → L1142, L1155, L1297
- `ScrollBottomKey` → L1597
- `TimelineHorizontalPadding` → L1260, L1262
- `TimelineTopPadding` → L1261
- `TimelineBottomSafetyPadding` → L1263
- `TimelineItemSpacing` → L1304, L1328, L1339, L1345, L1433, L1455, L1461
- `TimelineMessageInnerSpacing` → L1435
- `TimelineSelectionToolbarOffset` → L1638
- `TimelineHistoryLoadSignal` → L1141
- `TimelineScrollAnchor` → L1156
- `TimelineFollowMode` → L759, L854, L859, L863, L999, L1001, L1029
- `summaryPreviewOf` → L1340, L1456, L1552

### B → F
- `MarkdownPrewarmBeforeItems/After/MaxTexts` → L1794, L1796, L1803, L1805 (inside `LazyListState.markdownPrewarmTexts`)
- `ActionOptionLineRegex` → L1921, L1951 (inside action suggestion helpers)
- `ActionOptionCuePhrases` → L1936 (inside `isActionOptionCueLine`)

### C → E
- `TimelineHistoryLoadingIndicator` → L1300
- `AgentWorkingIndicator` → L1588, L1614
- `ContextCompactInProgressMarker` → L1344, L1460, L1561
- `ContextCompactMarker` → L1338, L1454, L1550
- `PendingUserMessageBubble` → L1574

### D → C
- (none — ChatList entry doesn't call indicators directly)

### D → E
- `ChatListNormal` (E/L717) → ChatList entry L676

### D → G
- `ChatListPreview` (G/L2012) → ChatList entry L667

### F → E
- `LazyListState.isNearListEnd` → L1003, L1041, L1046
- `LazyListState.isAtTimelineBottom` → L1002, L1040, L1046, L1052, L1119
- `LazyListState.markdownPrewarmTexts` → L1180
- `buildLazyItemMessageIndexMap` → L985
- `ChatMessageVirtualItem.isAdjacentMarkdownChild` → L1434
- `TimelineSelectableMessageItem` → L1469
- `Conversation.actionSuggestionTexts` → L779

### F → G
- `extractMatchingSnippet` → ChatSuggestionsRow L2099
- `buildHighlightedText` → ChatSuggestionsRow L2103

### G → E
- `ChatSuggestionsRow` → L1725
- `BoxScope.MessageJumper` → L1713

**Summary**: dependency graph is largely fan-in to E (ChatListNormal). C/F/G all serve E. D (entry) dispatches to E and G. B is pure declarations + 2 helpers consumed by C and E.

---

## §4 Same-file cross-stage caller scan + widening tally

Stage extraction order (helper-physical-owner first, then consumers — playbook lesson from P2 stage 4):

```
Stage 1 (F) → Stage 2 (C) → Stage 3 (G) → Stage 4 (E)
```

### Stage 1 — extract F (tail helpers, L1741-2010) → `ChatListSupport.kt`

**What stays in F (carried to sibling)**:
- All extracted decls keep their physical body byte-equal.
- Intra-F private helpers (`String.extract.../isAction.../normalized.../compact...`, `MessageNode.markdownPrewarmTexts`) keep `private` — same-sibling visibility OK.

**Visibility widenings — sibling side** (private → internal, so coord/other-siblings can call):
1. `extractMatchingSnippet` (F/L1741) — called from G L2099 (sub-composable)
2. `LazyListState.markdownPrewarmTexts` (F/L1781) — called from E L1180 (ChatListNormal, still in coord at this stage)
3. `ChatMessageVirtualItem.isAdjacentMarkdownChild` (F/L1836) — called from E L1434
4. `TimelineSelectableMessageItem` (F/L1843) — called from E L1469
5. `Conversation.actionSuggestionTexts` (F/L1896) — called from E L779
6. `buildHighlightedText` (F/L1973) — called from G L2103

(2 already-internal extensions `isNearListEnd`, `isAtTimelineBottom`, `buildLazyItemMessageIndexMap`, `firstLazyIndexForMessage` stay `internal` — no change.)

**Visibility widenings — coord side** (siblings need to see consts/regexes still in coord):
7. `MarkdownPrewarmBeforeItems` (B/L215) — used by F L1794
8. `MarkdownPrewarmAfterItems` (B/L216) — used by F L1796
9. `MarkdownPrewarmMaxTexts` (B/L217) — used by F L1803, L1805
10. `ActionOptionLineRegex` (B/L218) — used by F L1921, L1951
11. `ActionOptionCuePhrases` (B/L221) — used by F L1936

**Stage 1 widening total**: 6 sibling + 5 coord = **11**.

### Stage 2 — extract C (indicators, L264-619) → `ChatListIndicators.kt`

**Sibling-side widenings**:
1. `TimelineHistoryLoadingIndicator` — called from E L1300
2. `AgentWorkingIndicator` — called from E L1588, L1614
3. `ContextCompactInProgressMarker` — called from E L1344, L1460, L1561
4. `ContextCompactMarker` — called from E L1338, L1454, L1550
5. `PendingUserMessageBubble` — called from E L1574

**Coord-side widenings** (C body reaches back to B):
6. `COMPACT_SUMMARY_WHITESPACE_RE` (B/L179) — used by C/L452. **Already widened? Check Stage 1.** No — Stage 1 only widened MarkdownPrewarm + ActionOption consts. Widen now.
7. `dashedRoundedBorder` (B/L250) — used by C/L573

**Stage 2 widening total**: 5 sibling + 2 coord = **7**.

### Stage 3 — extract G (sub-composables, L2011-end) → `ChatListSubViews.kt`

**Sibling-side widenings**:
1. `ChatListPreview` (G/L2012) — called from D L667 (still in coord)
2. `ChatSuggestionsRow` (G/L2125) — called from E L1725
3. `BoxScope.MessageJumper` (G/L2169) — called from E L1713

**Coord-side widenings**: none direct. (G calls F symbols which were already internal-widened in Stage 1.)

⚠ G body uses many `data/model/Conversation.kt` types, `LocalInspectionMode`, etc. — all already imported.

⚠ G also uses some constants? Re-verify: G calls `extractMatchingSnippet` (Stage 1, already widened), `buildHighlightedText` (Stage 1, already widened). No B constants. ✓

**Stage 3 widening total**: 3 sibling + 0 coord = **3**.

### Stage 4 — extract E (ChatListNormal, L716-1740) → `ChatListNormalSection.kt`

**Sibling-side widenings**:
1. `ChatListNormal` (E/L717) — called from D L676 (coord)

**Coord-side widenings** — E body references EVERYTHING. Pre-cut tally:
2. `SCROLL_TAG` (B/L168)
3. `SendTransitionSlideDistance` (B/L173)
4. `LoadingIndicatorKey` (B/L169)
5. `HistoryLoadingItemKey` (B/L170)
6. `ScrollBottomKey` (B/L171)
7. `TimelineHorizontalPadding` (B/L172)
8. `TimelineTopPadding` (B/L210)
9. `TimelineBottomSafetyPadding` (B/L211)
10. `TimelineItemSpacing` (B/L212)
11. `TimelineMessageInnerSpacing` (B/L213)
12. `TimelineSelectionToolbarOffset` (B/L214)
13. `TimelineHistoryLoadSignal` data class (B/L181)
14. `TimelineScrollAnchor` data class (B/L189)
15. `TimelineFollowMode` enum class (B/L244)
16. `summaryPreviewOf` (B/L202)

**Stage 4 widening total**: 1 sibling + 15 coord = **16**.

### Cumulative widening tally

| Stage | Sibling | Coord | Subtotal |
|---|---|---|---|
| 1 — Support | 6 | 5 | 11 |
| 2 — Indicators | 5 | 2 | 7 |
| 3 — SubViews | 3 | 0 | 3 |
| 4 — ChatListNormal | 1 | 15 | 16 |
| **Total** | **15** | **22** | **37** |

**`TAG` (B/L164) is DEAD** — flagged, not deleted (CLAUDE.md §3, dead-code rule).

---

## §5 Cross-file caller scan

```
for sym in <each candidate>; do grep -rln "\bsym\b" app/src/main/java/ | grep -v ChatList.kt; done
```

| Symbol | External callers | Action |
|---|---|---|
| `ChatList` (public) | ChatPage.kt:638 | stays public ✓ |
| `LazyListState.isNearListEnd` | none | stays internal (no narrowing — byte-equal rule) |
| `LazyListState.isAtTimelineBottom` | none | stays internal |
| `buildLazyItemMessageIndexMap` | ChatPage.kt | stays internal ✓ |
| `List<Int?>.firstLazyIndexForMessage` | ChatPage.kt | stays internal ✓ |
| `ContextCompactInProgressMarker` | ConversationContextEngine.kt L95 — **comment-only string match**, not real call | stays private (widened to internal in Stage 2 for cross-sibling) |
| All other private decls | none | per stage table above |

---

## §6 Stage plan

**4 stages**, single PR multi-commit, `--merge` mode.

### Stage 1 — `ChatListSupport.kt` (target ~270 lines)

**Range**: L1741-2010 (section F)
**Contents**:
- `extractMatchingSnippet`
- 4 `LazyListState` extensions (`isNearListEnd`, `isAtTimelineBottom`, `markdownPrewarmTexts`, `buildLazyItemMessageIndexMap`)
- `List<Int?>.firstLazyIndexForMessage`
- `ChatMessageVirtualItem.isAdjacentMarkdownChild`
- `TimelineSelectableMessageItem` composable
- `MessageNode.markdownPrewarmTexts`
- Action suggestion chain (5 `String`/`Conversation` extensions)
- `buildHighlightedText`

**Coord post-strip**: 2293 - 270 = ~2023 lines + 5 coord widenings.

### Stage 2 — `ChatListIndicators.kt` (target ~360 lines)

**Range**: L264-619 (section C)
**Contents**: 5 indicator/marker composables.

**Coord post-strip**: ~1667 lines + cumulative widenings.

### Stage 3 — `ChatListSubViews.kt` (target ~283 lines)

**Range**: L2011-2293 (section G)
**Contents**: `ChatListPreview`, `ChatSuggestionsRow`, `BoxScope.MessageJumper`.

**Coord post-strip**: ~1384 lines.

### Stage 4 — `ChatListNormalSection.kt` (target ~1025 lines)

**Range**: L716-1740 (section E)
**Contents**: `ChatListNormal` (the beast).

**Coord post-strip**: ~360 lines (Imports + B-section consts/helpers + ChatList entry D).

**Final coord** (`ChatList.kt`):
- Imports
- TAG (dead, flagged)
- All other B-section consts/data classes/enum/helpers (now `internal`)
- `ChatList` public entry (unchanged)

### Stage ordering rationale (playbook lesson)

**Order**: F (Support) → C (Indicators) → G (SubViews) → E (ChatListNormal).

- F first: ChatListNormal heavy-consumes F symbols; pulling F out first means E (still in coord) reaches F via internal-widened symbols. When E goes in Stage 4, those widenings already cover cross-sibling visibility.
- C before G: independent of each other; doing C earlier prevents accidental Stage 3 widening collisions with Stage 2 callers.
- E last: largest stage; consumes everything from B/C/F/G; pre-stage widenings reduce final-stage widening surface.

---

## §7 Risk assessment — 🟡 medium-low

**Positives**:
- No state machine in this file (ChatService owns chat state). Local state via `remember`/`mutableStateOf` only.
- All cross-section dependencies resolved by visibility-widening; no body modification needed.
- No suspending pipelines, no DI graphs, no Koin re-wiring.
- Test baseline is stable (3/467, all pre-existing).

**Watchpoints**:
- **Stage 4 widening volume (16)** — 15 of which are coord-side data/const visibility flips. High mechanical surface area; **must double-check each widening is `private → internal`, not `private → public`**.
- **Stage 4 size (~1025 lines)** — single composable. If body has subtle compile errors after extraction, debug surface is large. Recommend running `:app:compileDebugKotlin` immediately after strip, before any other commit work.
- **ChatList entry's `AnimatedContent` block** — calls both `ChatListPreview` and `ChatListNormal` cross-sibling. After Stages 3+4, both call sites in coord need recompile verification (visibility correct, signature byte-equal).
- **Sub-composable `BoxScope.MessageJumper`** — `BoxScope` receiver across sibling boundaries: no special concern (BoxScope is from compose-foundation, public).

**No suspected in-progress migrations** in this file.

---

## §8 Decisions for user

| Question | Recommendation | Alternatives |
|---|---|---|
| Stage count | **4 stages** as above | 5 (split E in half) — only if Stage 4 body has subtle internal cluster |
| Import sort policy | **Strict byte-equal** (no alphabetical sort) — preserves [[feedback_rikkahub_worktree_isolation]] dead-import rule | Loose (alphabetical) — rejected, breaks dead-import preservation |
| Coord-side data class handling | **Leave + widen to internal** | Move with E into Stage 4 sibling — rejected as non-byte-equal structural change |
| Dead `TAG` const | **Flag, do not delete** (CLAUDE.md §3) | Delete — requires explicit user authorization |
| PR strategy | **Single PR, 4 commits + pre-cut commit** | Per-stage PRs — overkill for this risk level |
| Merge mode | **`--merge`** (playbook §8.2 hard rule) | Squash/rebase — **forbidden** |

---

## §9 Per-stage gates

Each stage **must pass** before pushing the stage commit:

1. **Compile gate** — `./gradlew :app:compileDebugKotlin` returns BUILD SUCCESSFUL
2. **Test gate** — `./gradlew :app:testDebugUnitTest` shows exactly **3 failed / 467 tests** (baseline)
3. **Diff sanity** — `git diff HEAD~1 --stat` shows only:
   - New sibling file added
   - Coord file lines removed in extracted range
   - Coord file visibility modifiers flipped (private → internal) on the symbols planned in §4
   - No body bytes changed in extracted symbols (use `git diff -w` for whitespace-blind verification)
4. **Sub-agent Round 1 review** — per playbook §6 template:
   - Byte-equal extraction (only visibility flips allowed)
   - Pre-existing dead imports preserved verbatim (verified against baseline `307ee8a5` grep)
   - No accidental imports added/removed beyond what the strip/extract requires
5. **Stage 4 additional**: 3-variant assemble (`:app:assembleDebug :app:assembleNotion :app:assembleRefactortest`) all BUILD SUCCESSFUL

**Failure recovery**: per playbook §10 — fix in a new commit on the stage branch; never `--amend` after compile/test gate.

---

## §10 Open questions for user

None — all six §8 decisions are pre-committed recommendations. Awaiting **GO for Stage 1**.
