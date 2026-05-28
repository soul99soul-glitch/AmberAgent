# AmberAgent — Visual Sanity Check

Changes that require manual on-device verification because the agent
cannot exercise UI behaviour. **All performance-layer flag flips listed
below are default-off and dead-code-eliminated by R8 when disabled.**
Flipping a flag is a single `const val` edit + rebuild; reverting a
broken flag-on path takes one of:

- flip the flag back to `false` in `app/src/main/java/me/rerere/rikkahub/PerfFlags.kt`
- `git revert <commit-sha>` for full removal

---

## Suggested verification order

Flip flags **one at a time**, exercise the listed verification steps,
then either keep or revert before moving to the next. This isolates
which change caused any regression.

1. **T1 DI lazy split** — already landed (no flag, default-on). Run
   cold start + open iCloud sync + WebMount once to confirm
   `Looper.IdleHandler` fired before navigation.
2. **T-A ChatPage scaffold** (`USE_SPLIT_CHATPAGE_COMPOSABLES`) — open
   any conversation. Should see the debug scaffold instead of the
   normal chat UI. If yes → wiring works; flip back to false (the
   scaffold is not user-shippable yet). If no → revert
   `62c8c9e0`.
3. **T-B Markdown Rust renderer** (`USE_RUST_MARKDOWN_RENDERER`) —
   open a chat with markdown messages. Should render normally (JVM
   AST consumed); logcat should show a per-message native-parse
   validation. If decode fails repeatedly → revert `48a9f33e`.
4. **T-C god class scaffolds** — three independent flags. Flip one,
   open the corresponding screen, confirm scaffold debug screen
   shows. Flip back. If wiring broken → revert `8bdd0038`.

---

## T1 — DI lazy split (LANDED, default-on)

**Files changed**: `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`
**Commit**: `f7e01c6c`
**Flag**: none — always-on. iCloud/webMount modules defer via
`Looper.myQueue().addIdleHandler { loadKoinModules(...); false }`.

**Revert**:
```
git revert f7e01c6c
```

**Verify on device**:
1. Cold start the app. App home screen renders without ANR.
2. Navigate to iCloud sync settings. Settings screen loads without
   `NoBeanDefFound`.
3. Open WebMount screen (long-press a chat → save to wiki). Loads
   without binding errors.

**Known risk**: extremely unlikely race where a main-thread callback
fires `get<X>()` against an iCloud/webMount binding in <100ms of
onCreate. Mitigated because the IdleHandler is synchronous on the
main Looper.

---

## T2 — Conversation `@Immutable` (LANDED, default-on)

**Files changed**: `core/model/src/main/kotlin/app/amber/core/model/Conversation.kt`
**Commit**: `834f2000`
**Flag**: none — always-on annotation.

**Revert**:
```
git revert 834f2000
```

**Verify on device**:
1. Open a long conversation (50+ messages). Type a message and
   trigger streaming.
2. Observe chat list smoothness during token arrival. Should NOT
   stutter from full tree recompose.
3. Switch between conversations from the drawer. List swaps cleanly.

**Known risk**: `@Immutable` is a contract. Conversation
`messageNodes: List<MessageNode>` must never be mutated in-place.
Audit grep finds zero mutation sites; if any future code does
`conversation.messageNodes.add(...)`, Compose would observe stale
data and skip a needed recompose.

---

## T-A — ChatPage region-split scaffold (LANDED, default OFF)

**Files added**:
- `app/src/main/java/me/rerere/rikkahub/PerfFlags.kt`
- `app/src/main/java/app/amber/feature/ui/pages/chat/ChatPageSplit.kt`
**Files changed**: `app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt`
  (dispatcher prefix added)
**Commit**: `62c8c9e0`
**Flag**: `PerfFlags.USE_SPLIT_CHATPAGE_COMPOSABLES` (default `false`)

**Revert**:
```
# disable only
PerfFlags.kt: USE_SPLIT_CHATPAGE_COMPOSABLES = false  (already default)

# remove entirely
git revert 62c8c9e0
```

**Verify on device** (flip flag to `true` first):
1. Open any conversation. Should see **"T2 ChatPage scaffold active"**
   debug screen instead of the normal chat UI. This confirms the
   dispatcher path is wired correctly.
2. Profile the four region Composables (Layout Inspector → Composition
   counts) — each should recompose ONLY when its own StateFlow emits,
   not on every parent re-render. Streaming-indicator subtree should
   recompose per-token while top bar / input bar stay still.
3. Flip the flag back to `false` to restore the legacy chat UI.

**Known risk**: the scaffold is intentionally a debug screen — does
NOT reproduce the full chat UI (drawer, message-list interactions,
input attachments, suggestions, sandbox timeline). Reproducing those
~1000 LOC of private Composables faithfully needs device-by-device
QA and is the **next sprint** scope.

---

## T-B — Markdown renderer Rust-path flag (LANDED, default OFF)

**Files changed**: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt`
**Commit**: `48a9f33e`
**Flag**: `PerfFlags.USE_RUST_MARKDOWN_RENDERER` (default `false`)

**Revert**:
```
# disable only
PerfFlags.kt: USE_RUST_MARKDOWN_RENDERER = false  (already default)

# remove entirely
git revert 48a9f33e
```

**Verify on device** (flip flag to `true` first):
1. Open a chat with markdown content (code blocks, lists, headings).
   Renders identically to flag-off (the renderer body still consumes
   the JVM ASTNode).
2. `adb logcat -s Markdown` — should see `RUST_MARKDOWN_RENDERER` warnings
   only if the native decode fails. Steady decode success = expected.
3. Test 30+ markdown samples (Chinese / English / code blocks / GFM
   tables / KaTeX inline + block / nested blockquotes / mixed inline).
   Each should render identically to flag-off.

**Known risk**: this flag does NOT swap the renderer's ASTNode consumer
yet — it only upgrades the native correctness signal from
sample-rate-shadow to every-parse hard-validation. The full renderer
ASTNode→PackedAstNode swap stays deferred per
`docs/td-rust-1a-feasibility.md` (multi-day on-device QA work).

---

## T-C — god class scaffolds (3 LANDED flags, all default OFF)

**Files added**:
- `app/src/main/java/app/amber/feature/ui/pages/board/DeepReadScreenSplit.kt`
- `app/src/main/java/app/amber/feature/ui/components/richtext/MarkdownSplit.kt`
- `app/src/main/java/app/amber/feature/ui/components/message/GenerativeWidgetCardSplit.kt`
**Files changed**: DeepReadScreen.kt, Markdown.kt, GenerativeWidgetCard.kt
  (dispatcher prefix added to each entry)
**Commit**: `8bdd0038`

### T-C.1 — DeepReadScreen split

**Flag**: `PerfFlags.USE_SPLIT_DEEPREAD_SCREEN` (default `false`)

**Verify on device** (flip flag to `true` first):
1. Open any DeepRead article. Should see **"T4 DeepReadScreen scaffold
   active"** debug screen.
2. Flip back to `false` — legacy DeepReadScreen returns.

### T-C.2 — MarkdownBlock split

**Flag**: `PerfFlags.USE_SPLIT_MARKDOWN` (default `false`)

**Verify on device** (flip flag to `true` first):
1. Any chat message with markdown should render as a **scaffold card**
   with the literal source text + "T4 Markdown scaffold active" label.
2. This flag composes with `USE_RUST_MARKDOWN_RENDERER` — turning both
   on is supported (Rust validation runs THEN scaffold renders).

### T-C.3 — GenerativeWidgetCard split

**Flag**: `PerfFlags.USE_SPLIT_GENERATIVE_WIDGET_CARD` (default `false`)

**Verify on device** (flip flag to `true` first):
1. Generate a widget (e.g. `/draw a bar chart of …`). Should see
   scaffold card with `widgetCodeChars=...` label instead of the
   rendered widget.
2. Flip back — widget renders normally.

**Revert all three**:
```
# disable individual flags (already default)
PerfFlags.kt: USE_SPLIT_<NAME> = false

# remove all three scaffolds + dispatchers
git revert 8bdd0038
```

**Known risk for T-C suite**: scaffolds intentionally do NOT reproduce
the legacy UIs. Flipping a flag in a user-facing build would degrade
the corresponding screen to a debug placeholder. **All three flags are
dev-only**; do not enable in a release build until the next sprint
populates the scaffold bodies with parity UI + device-side QA passes.

---

## Suggested next sprint (after device access)

For each scaffolded T-A / T-C path:
1. Replace the scaffold body with the parity-equivalent UI region by
   region.
2. Run side-by-side comparison on the listed verification flow.
3. Capture Compose compiler metrics: restartable groups / skippable
   functions reductions for the flag-on path vs. legacy.
4. When parity is verified and metrics show improvement, flip the
   `const val` default to `true`. Ship as default-on with the flag
   retained as a one-flip rollback insurance.

The patterns established by T-A through T-C give the next sprint a
zero-exploration starting point — just fill in the bodies.
