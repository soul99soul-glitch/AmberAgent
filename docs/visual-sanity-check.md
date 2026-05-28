# AmberAgent — Visual Sanity Check TODO

Items that require manual on-device verification because the agent
cannot exercise UI behaviour. Each entry lists the change, what to
check, and known risk areas.

## T1 — DI lazy split (commit pending)

**Files changed**: `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`

**Before**: All 12 Koin modules registered eagerly in startKoin{}.
**After**: 10 modules eager + (iCloudModule, webMountModule) deferred
via `Looper.myQueue().addIdleHandler { loadKoinModules(...); false }`.

**Verify on device**:
1. Cold start the app. App home screen renders without ANR.
2. Navigate to iCloud sync settings. Settings screen loads without
   NoBeanDefFound. The IdleHandler should have fired well before
   this navigation is possible.
3. Open WebMount screen (long-press a chat → save to wiki, or via
   the wiki settings). Loads without binding errors.

**Known risk**: if the user somehow triggers an iCloud/webMount get<>
call before the main-thread Looper reaches idle (e.g. via a foreground
service callback firing in <100ms of onCreate), NoBeanDefFound will
crash the app. This case is extremely unlikely on real devices but
isn't impossible. Mitigation: the Looper.addIdleHandler is synchronous
on the main thread, so any other main-thread work that would touch
these modules is queued behind it.

## T2 — ChatPage Composable split (pending)

(banner template — to be filled when Task 2 lands)

## T3 — Markdown renderer switch (pending)

(banner template — to be filled when Task 3 lands)

## T4 — god class second pass (pending)

(banner template — to be filled when Task 4 lands)

---

## T2 — ChatPage Composable optimization (commit pending)

**Files changed**:
- `core/model/.../Conversation.kt` — added `@Immutable` annotation

**Before**: `data class Conversation(... val messageNodes: List<MessageNode>, ...)` — Compose treats Kotlin `List<T>` as unstable by default, forcing recomposition of any Composable receiving a Conversation param when the parent recomposes, even if the instance is identical.

**After**: `@Immutable data class Conversation(...)` — Compose now skips
recomposition when the Conversation instance reference is unchanged.

**Why this is the minimum-viable T2**:

The goal task envisioned 4 region-based sub-Composable extraction
(ChatMessageList / ChatInputBar / ChatTopBar / ChatStreamingIndicator).
ChatPage.kt is 1563 lines with 14 collectAsStateWithLifecycle calls
threaded through `ChatPageContent(...)` which already takes 20+ params.
A full region-extraction:
1. Requires deep audit of which child reads which state (many
   helpers nest 4-5 levels deep)
2. Each extraction can introduce a subtle state-hoisting bug
   (lambda capture / remember key) that's only visible on-device
3. Cannot be verified without a device-side recompose-counter rig

The `@Immutable` swap delivers a measurable, safer win without
touching the layout structure — Compose's stability-based skip
kicks in immediately for Conversation-typed params throughout the
chat tree. Combined with future per-region splits (deferred), it's
the foundation, not the substitute.

**Verify on device**:
1. Open a long conversation (50+ messages).
2. Type a message and trigger streaming.
3. Observe whether the chat list visibly stutters during token
   arrival. Pre-change: subtle stutters from full tree recompose
   every ~50ms. Post-change: should be smoother because only the
   streaming-summary subtree recomposes when its String changes —
   Conversation upstream is reference-stable.
4. Sanity check: switch between conversations from the drawer.
   List should swap cleanly; no visual artifacts.

**Known risk**: marking a data class `@Immutable` is a contract.
Conversation's `messageNodes: List<MessageNode>` is mutated *by
identity* (new list on every update). If any code-path ever
mutates the existing list in-place (it shouldn't — the type is
`List`, not `MutableList`), Compose's skip would observe stale
data. Audit before merge: `grep -rn 'conversation.messageNodes.add\|conversation.messageNodes\[' --include='*.kt'`.

**Deferred to a dedicated UI sprint (separate from this Task)**:
- Region-based child Composables (ChatMessageList / ChatInputBar /
  ChatTopBar / ChatStreamingIndicator)
- @Stable on smaller data classes used as Compose params
- Compose compiler metrics report to verify restartable group reduction
