package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import app.amber.feature.ui.hooks.ImeLazyListAutoScroller
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.COUNCIL_ROOM_HOST_ID
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.modelcouncil.COUNCIL_ROOM_USER_ID
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilMessageKind
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.modelcouncil.CouncilParticipantStatus
import app.amber.feature.modelcouncil.CouncilPhaseMarker
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.modelcouncil.running
import app.amber.feature.ui.components.richtext.MarkdownBlock
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.chat.LocalChatTheme

/**
 * One entry in the merged timeline (a message or a phase marker), with a stable
 * key for [LazyColumn].
 */
private sealed interface TimelineEntry {
    val key: String
    val sortMs: Long

    data class Message(val msg: CouncilMessage) : TimelineEntry {
        override val key get() = msg.id
        override val sortMs get() = msg.createdAtMs
    }

    data class Phase(val marker: CouncilPhaseMarker) : TimelineEntry {
        override val key get() = marker.id
        override val sortMs get() = marker.createdAtMs
    }
}

/**
 * 群聊时间线 — merges messages + phase markers by timestamp.
 *
 * Alignment mirrors the main chat: user right-aligned, host/guest left-aligned.
 * Host bubbles get an amber avatar; guests get a coloured pixel avatar. Guest-
 * to-guest reference graph (reply / continues / invited-by) renders as small
 * footnotes under the bubble.
 *
 * Streaming auto-follow: while any message is STREAMING and the user has not
 * scrolled away from the tail, keep the timeline pinned to the true content
 * bottom. Content growth and newly appended turns do not disable follow; an
 * actual user scroll away from the bottom does.
 */
@Composable
fun CouncilTimelineTab(
    room: CouncilRoom,
    vm: CouncilRoomVM?,
    modifier: Modifier = Modifier,
) {
    val entries = remember(room.messages, room.phaseMarkers) {
        buildList {
            room.messages.forEach { add(TimelineEntry.Message(it)) }
            room.phaseMarkers.forEach { add(TimelineEntry.Phase(it)) }
        }.sortedBy { it.sortMs }
    }
    val isStreaming = remember(room.messages) {
        room.messages.any { it.status.running }
    }
    // A token that changes whenever the streaming content actually changes
    // (message count OR the running message's text length). Used as a
    // LaunchedEffect key to *request* a follow — but the request goes through a
    // conflate'd SharedFlow (one scroll per frame max), NOT a direct scroll,
    // which is what stops the per-chunk jitter the old `streamingTail`-keyed
    // effect caused.
    val streamingChangeToken = remember(room.messages) {
        val running = room.messages.lastOrNull { it.status.running }
        "${room.messages.size}#${running?.text?.length ?: 0}"
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── Three-state follow mode (mirrors chat's TimelineFollowMode) ─────────
    // Idle: not streaming, no follow needed.
    // FollowingBottom: stick to the bottom; content growth scrolls to keep tail pinned.
    // PausedForUser: the user dragged away; stay put until they scroll back to bottom.
    // The sticky PausedForUser state is the key fix: the old bool `followStreaming`
    // couldn't tell "programmatic scroll in progress" from "user dragged away", so
    // isScrollInProgress races flipped it off mid-stream → the jitter the user saw.
    var followMode by remember { mutableStateOf(CouncilFollowMode.Idle) }

    // Token counter so overlapping programmatic scrolls don't clear the flag
    // out from under each other (mirrors chat's programmaticScrollToken). An
    // older `endProgrammaticScroll` whose token no longer matches is a no-op,
    // so a fast follow-up follow can't prematurely mark "done scrolling".
    var programmaticScrollToken by remember { mutableIntStateOf(0) }
    var programmaticScrollInProgress by remember { mutableStateOf(false) }

    // Ground-truth user gesture detection. `isScrollInProgress` fires for BOTH
    // user drags and our own scrollBy; only a real pointer-down in the list
    // proves the user initiated it. Mirrors chat's pointerInput/awaitFirstDown.
    var userScrollInTimeline by remember { mutableStateOf(false) }
    var userDragInTimeline by remember { mutableStateOf(false) }

    // Token captured by the IME auto-scroller's start so its end callback can
    // release the programmatic-scroll flag via endProgrammaticScroll(token).
    // Stored (not discarded) so a cancelled/replaced scroll still pairs cleanly
    // — if we discarded it (the old buggy approach) and the start's launch got
    // cancelled, programmaticScrollInProgress would stick at true and the
    // PausedForUser → FollowingBottom recovery would never re-arm.
    var imeScrollToken by remember { mutableIntStateOf(0) }

    // One-shot event flow: a streaming chunk requests "please follow", and the
    // collector drains at most one per frame (conflate + DROP_OLDEST). This
    // replaces the old per-chunk LaunchedEffect restart, which fired scrollBy
    // once per chunk with no coalescing — the direct cause of the jitter.
    val followEvents = remember(room.conversationId) {
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    // Match the main chat: about one CJK text line. The explicit follow state
    // keeps streaming growth from disabling follow, so the buffer can stay tight.
    val bottomFollowBufferPx = with(LocalDensity.current) { 24.dp.toPx().toInt() }

    // "Is the viewport at the bottom?" — the single source of truth for follow.
    // Two-stage check, matching the main chat's ChatListSupport.isAtTimelineBottom:
    //   1) index check: the last VISIBLE item must be the LAST item (guards against
    //      "the tail got pushed below the fold" — a pure pixel check can't see that
    //      and falsely reports bottom during fast scrolls).
    //   2) pixel check with a small buffer: the last item's bottom edge is within
    //      bufferPx of the viewport's bottom.
    fun isAtBottom(bufferPx: Int): Boolean {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return true
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
        if (lastVisible.index < total - 1) return false
        val contentBottom = lastVisible.offset + lastVisible.size + info.afterContentPadding
        return contentBottom <= info.viewportEndOffset + bufferPx
    }

    /**
     * Loose "near bottom" check based on item COUNT, not pixels — mirrors the
     * chat page's `isNearListEnd(bufferItems=2)`. True if the last visible item
     * is within [bufferItems] of the true last item.
     *
     * This is the key to reliable follow re-arm: a pixel-based isAtBottom (even
     * with a 24dp buffer) fails the moment streaming growth pushes content a
     * few px below the fold, so the user can scroll to what *looks* like the
     * bottom yet never trip the "resume follow" condition. An index-based check
     * is immune to per-frame pixel jitter — if the last couple items are
     * visible, we're "near enough" and should follow.
     */
    fun isNearBottom(bufferItems: Int = 2): Boolean {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return true
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
        return lastVisible.index >= total - 1 - bufferItems
    }

    /**
     * Pixels from the current viewport to the true content bottom — null when the
     * last item isn't measured yet (off-screen). Same approach as the main chat's
     * distanceToTimelineBottomPx: we scrollBy this exact distance, NOT scrollToItem,
     * because scrollToItem(last) pins to the item's TOP edge — and when a streaming
     * message grows taller than one screen, that leaves the newest text scrolling
     * off below the fold (the "doesn't follow" symptom).
     */
    fun distanceToBottomPx(): Int? {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return 0
        val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == total - 1 }
            ?: return null
        val contentBottom = bottomItem.offset + bottomItem.size + info.afterContentPadding
        return (contentBottom - info.viewportEndOffset).coerceAtLeast(0)
    }

    fun beginProgrammaticScroll(): Int {
        val token = programmaticScrollToken + 1
        programmaticScrollToken = token
        programmaticScrollInProgress = true
        return token
    }

    fun endProgrammaticScroll(token: Int) {
        scope.launch {
            // One frame is enough for the isScrollInProgress flicker right after
            // scrollBy settles to clear; matching the chat's M0.3 reasoning. The
            // token check prevents an older follow's end from clobbering a newer
            // one's "still scrolling" flag.
            withFrameNanos { }
            if (programmaticScrollToken == token) {
                programmaticScrollInProgress = false
            }
        }
    }

    suspend fun scrollToBottom() {
        val token = beginProgrammaticScroll()
        try {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) return
            // Single-frame jump to the bottom. Used for one-shot events (new
            // content arrival, generation-end settle). CONTINUOUS follow during
            // streaming is handled by the frame-pinning effect below, NOT by
            // re-calling this every chunk — re-measuring distance every frame
            // and scrollBy-ing it is what caused the jitter (content grows
            // between measure and scroll, so the scroll overshoots/undershoots
            // every frame). The pin effect compensates the *increment* of
            // growth instead, which is jitter-free.
            val distance = distanceToBottomPx()
            if (distance == null) {
                listState.scrollToItem(total - 1)
                withFrameNanos { }
                val settleDistance = distanceToBottomPx()
                if (settleDistance != null && settleDistance > 0) {
                    listState.scrollBy(settleDistance.toFloat())
                }
            } else if (distance > 0) {
                listState.scrollBy(distance.toFloat())
            }
        } finally {
            endProgrammaticScroll(token)
        }
    }

    fun bottomFollowAllowed(): Boolean =
        followMode == CouncilFollowMode.FollowingBottom && !userScrollInTimeline

    // ── Streaming bottom-follow (mirrors chat's follow state machine) ───────
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            // Streaming started: arm follow if we're already near the bottom.
            val atBottom = isAtBottom(bottomFollowBufferPx)
            android.util.Log.i("CouncilFollow", "arm check: isStreaming=true atBottom=$atBottom total=${listState.layoutInfo.totalItemsCount}")
            if (atBottom) {
                followMode = CouncilFollowMode.FollowingBottom
                android.util.Log.i("CouncilFollow", "armed FollowingBottom")
            } else {
                android.util.Log.i("CouncilFollow", "NOT armed (not at bottom)")
            }
        } else {
            // Streaming just ended. Mirror the chat page's "generation-end"
            // settle: if we were following, fire one last bottom-follow so the
            // final chunk's tail (often left 1-2 lines below the fold by the
            // last incremental scrollBy) gets pulled fully into view.
            //
            // CRITICAL: do NOT flip to Idle here. tryEmit only enqueues the
            // event; the collector consumes it asynchronously on a later
            // frame. If we set Idle now, the collector would see
            // bottomFollowAllowed()==false and drop the generation-end scroll
            // — leaving the last lines hidden, the exact symptom this fixes.
            // Instead we keep FollowingBottom and let the collector flip to
            // Idle after it has serviced the generation-end scroll (see the
            // collector below). This matches the chat page, which does NOT
            // enter Idle on generation-end either.
            if (followMode == CouncilFollowMode.FollowingBottom) {
                followEvents.tryEmit("generation-end")
            }
        }
    }

    // The single scroll executor: one collector, conflate'd, so a burst of
    // follow requests in the same frame coalesces into one scrollBy. This is
    // the core anti-jitter fix — the old code re-launched a new scroll per chunk.
    LaunchedEffect(followEvents, room.conversationId) {
        followEvents.conflate().collect { reason ->
            val allowed = bottomFollowAllowed()
            android.util.Log.i("CouncilFollow", "collector: reason=$reason allowed=$allowed mode=$followMode")
            if (!allowed) return@collect
            withFrameNanos { }
            val stillAllowed = bottomFollowAllowed()
            // One-shot jumps only. Continuous per-chunk follow is handled by the
            // frame-pinning effect above, which is jitter-free; having the
            // collector ALSO scrollBy every chunk would fight the pin and
            // re-introduce the jitter. So:
            //  - "new-content": jump to bottom (first arm / big content arrival)
            //  - "generation-end": final settle, then flip to Idle
            //  - "chunk" / "visible" / "visual-active": no-op here (pin handles them)
            if (stillAllowed && (reason == "new-content" || reason == "generation-end")) {
                scrollToBottom()
            }
            if (reason == "generation-end") {
                followMode = CouncilFollowMode.Idle
            }
        }
    }

    // ── Frame-pinning follow (the anti-jitter core) ────────────────────────
    // While FollowingBottom, run a continuous per-frame loop that PINS the last
    // item's bottom edge to a fixed on-screen Y, instead of chasing the content
    // bottom with repeated "scrollBy distance-to-bottom" (which jitters because
    // content grows between measure and scroll). Here we compensate only the
    // *increment*: if the last item's bottom moved down by N px this frame (the
    // content just grew), we scrollBy(N). The bubble's lower edge therefore
    // stays glued to the same screen position — no oscillation, because we never
    // overshoot (we move exactly what the content moved). Negative deltas
    // (content shrinking) are ignored so the list never scrolls back up on its
    // own.
    LaunchedEffect(followMode, room.conversationId) {
        if (followMode != CouncilFollowMode.FollowingBottom) return@LaunchedEffect
        var pinnedBottomY: Int? = null
        while (followMode == CouncilFollowMode.FollowingBottom) {
            withFrameNanos { }
            if (followMode != CouncilFollowMode.FollowingBottom) break
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) {
                pinnedBottomY = null
                continue
            }
            val bottomItem = info.visibleItemsInfo.firstOrNull { it.index == total - 1 }
            if (bottomItem == null) {
                // Last item scrolled out of view (e.g. just appended) — re-pin
                // with a one-shot jump so the pin loop resumes from bottom.
                if (bottomFollowAllowed()) {
                    scrollToBottom()
                    withFrameNanos { }
                }
                pinnedBottomY = null
                continue
            }
            val currentBottomY = bottomItem.offset + bottomItem.size + info.afterContentPadding
            val prev = pinnedBottomY
            pinnedBottomY = currentBottomY
            if (prev != null) {
                val delta = currentBottomY - prev
                if (delta > 1) {
                    // Content grew this frame — compensate exactly the growth
                    // so the bubble's bottom edge stays at the same screen Y.
                    val token = beginProgrammaticScroll()
                    try {
                        listState.scrollBy(delta.toFloat())
                    } finally {
                        endProgrammaticScroll(token)
                    }
                }
            }
        }
    }

    // Force-arm follow when content arrives and we're not PausedForUser.
    // This mirrors the chat page's "new USER message / new message row lands →
    // resumeBottomFollow()" safety net (ChatListNormalSection.kt:606-619). Without
    // it, arm relied SOLELY on LaunchedEffect(isStreaming), which requires
    // isAtBottom() at the exact moment streaming starts — if the viewport is a
    // few px above bottom (very common right after sending a topic, due to the
    // composer + contentPadding), arm fails and follow stays Idle for the entire
    // host opening + first guest turn, only recovering by accident later.
    // Keyed on message count so every new turn (host opening, each guest, phase
    // markers) re-evaluates; PausedForUser is sticky so a real user scroll-up
    // still wins.
    val lastMessageId = room.messages.lastOrNull()?.id
    LaunchedEffect(room.messages.size, lastMessageId, isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        if (followMode == CouncilFollowMode.PausedForUser) return@LaunchedEffect
        // Arm (or re-arm) follow when new content arrives — but ONLY if we're
        // near the bottom (don't yank the user away while reading history).
        // Use the index-based isNearBottom (not pixel isAtBottom) so streaming
        // growth that pushes content a few px below the fold doesn't prevent
        // re-arm — the user scrolled to "looks like bottom" and that should count.
        if (followMode == CouncilFollowMode.Idle && !isNearBottom()) {
            return@LaunchedEffect
        }
        if (followMode != CouncilFollowMode.FollowingBottom) {
            followMode = CouncilFollowMode.FollowingBottom
            android.util.Log.i("CouncilFollow", "force-armed FollowingBottom (msgCount=${room.messages.size} lastMsg=$lastMessageId nearBottom=${isNearBottom()})")
        }
        followEvents.tryEmit("new-content")
    }

    // Content-changed → request a follow (emit, not scroll). The collector above
    // drains and coalesces. Keyed on streamingChangeToken so it only fires when
    // the streaming tail actually grew, not on every unrelated recomposition.
    LaunchedEffect(streamingChangeToken, room.conversationId, isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        val allowed = bottomFollowAllowed()
        android.util.Log.i("CouncilFollow", "chunk emit: token=$streamingChangeToken allowed=$allowed mode=$followMode userScroll=$userScrollInTimeline")
        if (allowed) {
            followEvents.tryEmit("chunk")
        }
    }

    // User-gesture vs programmatic-scroll disambiguation. This is the other half
    // of the jitter fix: `isScrollInProgress` alone can't tell our scrollBy from a
    // user drag, so we tag real pointer-downs here and gate the pause on that.
    // Mirrors chat's LaunchedEffect(isScrollInProgress, userDragInTimeline).
    LaunchedEffect(listState.isScrollInProgress, userDragInTimeline) {
        if (listState.isScrollInProgress) {
            val userDriven = userScrollInTimeline &&
                (!programmaticScrollInProgress || userDragInTimeline)
            if (userDriven && followMode == CouncilFollowMode.FollowingBottom) {
                followMode = CouncilFollowMode.PausedForUser
            }
        } else {
            // M0.3-style gate: only decide "user released at bottom → resume follow"
            // when we were actually PausedForUser. A programmatic scroll that just
            // ended (followMode still FollowingBottom) must NOT be misread as a
            // user release — that was the old code's race and the root of the jitter.
            if (!programmaticScrollInProgress &&
                followMode == CouncilFollowMode.PausedForUser
            ) {
                // Resume follow when the user has scrolled back to "near bottom".
                // Use isNearBottom (index-based, immune to pixel jitter) so that
                // streaming growth doesn't make "looks like bottom" fail the
                // strict pixel isAtBottom check — which was why follow wouldn't
                // re-arm even after the user scrolled down.
                if (isNearBottom()) {
                    followMode = CouncilFollowMode.FollowingBottom
                }
                // else: user released away from bottom → stay PausedForUser.
            }
        }
    }

    // The first user message is the room's "topic"; it gets the 议题 · 发起人 label.
    val topicMessageId = remember(room.messages) {
        room.messages.firstOrNull { it.authorId == COUNCIL_ROOM_USER_ID }?.id
    }

    // Whether an ask_user card is still awaiting input. The host can pause the
    // council with a question embedded in the timeline (not the bottom composer),
    // so when the soft keyboard rises for that card we must scroll the list to
    // keep it visible — otherwise the TextField is hidden behind the keyboard.
    val hasPendingAskUser = remember(room.messages) {
        room.messages.any { msg ->
            msg.kind == CouncilMessageKind.ASK_USER &&
                room.messages.none {
                    it.authorId == COUNCIL_ROOM_USER_ID &&
                        (it.replyToMessageId == msg.id || it.createdAtMs > msg.createdAtMs)
                }
        }
    }
    ImeLazyListAutoScroller(
        lazyListState = listState,
        shouldScroll = {
            // Scroll on IME rise while following the bottom, OR whenever an
            // ask_user card needs to be kept on screen — the latter applies even
            // when the user has scrolled up (PausedForUser), because the question
            // is the only actionable control at that moment.
            followMode == CouncilFollowMode.FollowingBottom || hasPendingAskUser ||
                isAtBottom(bottomFollowBufferPx)
        },
        onProgrammaticScrollStart = {
            // Capture the token so end can release the flag via the same
            // endProgrammaticScroll(token) path the streaming follow uses.
            // Mirrors chat's imeProgrammaticScrollToken pairing.
            imeScrollToken = beginProgrammaticScroll()
        },
        onProgrammaticScrollEnd = {
            endProgrammaticScroll(imeScrollToken)
            imeScrollToken = 0
        },
    )
    // Who is mid-turn — drives the live "正在发言" strip above the composer.
    val speaking = remember(room.participants, streamingChangeToken) {
        room.participants.firstOrNull { it.status == CouncilParticipantStatus.SPEAKING }
    }

    // Timeline keys whose entrance "pop" has already played. Pre-seeded with the
    // entries present at first composition, so opening a room with history does
    // NOT replay the pop for every past message — only turns that arrive after
    // open pop, and each only once (scrolling a popped item back into view, which
    // re-creates its composition, finds the key here and skips re-animating).
    val poppedKeys = remember { entries.mapTo(mutableSetOf<String>()) { it.key } }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                // Tag real user pointer-downs BEFORE the scroll-progress effect
                // runs, so isScrollInProgress can't be misread as user-driven
                // during our own follow scrollBy. Initial pass = earliest point
                // we can observe the gesture. Mirrors the chat page's pointerInput.
                .pointerInput(room.conversationId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        userScrollInTimeline = true
                        userDragInTimeline = false
                        try {
                            do {
                                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                if (event.changes.any { it.positionChange().getDistance() > 0.5f }) {
                                    userDragInTimeline = true
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            userScrollInTimeline = false
                            userDragInTimeline = false
                        }
                    }
                },
            // Extra bottom headroom so the streaming tail (and a freshly appended
            // line) stays comfortably above the composer instead of hugging the edge.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 64.dp),
            // Per-item spacing (not spacedBy): the chat page uses spacedBy(0) +
            // per-item padding because spacedBy's uniform spacing is managed by
            // the LazyList and, combined with a streaming item whose height
            // changes every frame, can make the "last item bottom offset"
            // measurement jitter — which feeds back into isAtBottom / distance
            // and amplifies the scroll phase difference. Per-item padding fixes
            // the gap into each item's own layout slot, decoupling it from the
            // list-level arrangement.
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    CouncilTimelineEmpty(room)
                }
            }
            items(items = entries, key = { it.key }) { entry ->
                // A user message that arrives after the room is open gets a one-shot
                // scale "pop" from its trailing edge — the same send feedback as the
                // main chat. firstAppearance is false for history (pre-seeded) and
                // for items scrolled back into view, so neither replays the pop.
                //
                // We deliberately do NOT use Modifier.animateItem() here. The chat
                // page avoids it too: animateItem's placement animation chases the
                // per-frame auto-scroll during streaming and the two fight, which
                // reads as flicker/jitter at the anchor point. The pop is done with
                // a pure graphicsLayer scale (no layout slot change), so it doesn't
                // interfere with scroll placement at all.
                val isUserMsg = entry is TimelineEntry.Message &&
                    entry.msg.authorId == COUNCIL_ROOM_USER_ID
                val firstAppearance = remember(entry.key) { entry.key !in poppedKeys }
                val popIn = isUserMsg && firstAppearance
                val popScale = remember(entry.key) { Animatable(if (popIn) 0.8f else 1f) }
                LaunchedEffect(entry.key) {
                    if (popIn) {
                        poppedKeys += entry.key
                        popScale.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Per-item bottom gap (replaces the old spacedBy(14.dp) —
                        // see the LazyColumn verticalArrangement comment above).
                        .padding(bottom = 14.dp)
                        .graphicsLayer {
                            scaleX = popScale.value
                            scaleY = popScale.value
                            // User bubble is right-aligned; pop from its trailing edge.
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        },
                ) {
                    when (entry) {
                        is TimelineEntry.Phase -> PhaseDivider(entry.marker)
                        is TimelineEntry.Message -> {
                            if (entry.msg.kind == CouncilMessageKind.ASK_USER) {
                                val answered = room.messages.any { msg ->
                                    val isAnswer = msg.replyToMessageId == entry.msg.id ||
                                        msg.createdAtMs > entry.msg.createdAtMs
                                    msg.authorId == COUNCIL_ROOM_USER_ID && isAnswer
                                }
                                CouncilAskUserCard(
                                    msg = entry.msg,
                                    answered = answered,
                                    onAnswer = { answer -> vm?.resumeAfterUserAnswer(answer) },
                                )
                            } else {
                                // Only the actively-streaming turn drives bottom-follow,
                                // and it does so off the renderer's frame-release callback
                                // (onStreamingVisibleFrame) so scroll stays in phase with
                                // the characters actually appearing on screen, not the raw
                                // text length. The lambdas close over followEvents /
                                // followMode, which live in this composable's scope, so
                                // they must be built here and threaded down to MessageBubble.
                                val isStreamingTurn = entry.msg.status == CouncilMessageStatus.STREAMING
                                TimelineMessageRow(
                                    msg = entry.msg,
                                    room = room,
                                    isTopic = entry.msg.id == topicMessageId,
                                    onStreamingVisibleFrame = if (isStreamingTurn) {
                                        { followEvents.tryEmit("visible") }
                                    } else null,
                                    onStreamingVisualActiveChange = if (isStreamingTurn) {
                                        { active ->
                                            if (active && followMode == CouncilFollowMode.FollowingBottom) {
                                                followEvents.tryEmit("visual-active")
                                            }
                                        }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Live speaking strip — slides/fades in while a guest turn streams.
        AnimatedVisibility(
            visible = isStreaming && speaking != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            speaking?.let { p ->
                CouncilSpeakingStrip(
                    participant = p,
                    onClick = {
                        val idx = entries.indexOfLast {
                            it is TimelineEntry.Message && it.msg.authorId == p.id
                        }
                        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                    },
                )
            }
        }
        // Composer only while the room is actively running. INTERRUPTED keeps vm
        // for the ask-user card but should not expose the normal composer.
        if (vm != null && room.status.running) {
            CouncilRoomComposer(room = room, vm = vm)
        }
    }
}

/** Live "X 正在发言…" strip with a breathing signal dot + model label. */
@Composable
private fun CouncilSpeakingStrip(participant: CouncilParticipant, onClick: () -> Unit) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = chatTheme.surface,
        border = BorderStroke(1.dp, chatTheme.surfaceEdge),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CouncilBreathingDot(color = workspace.green)
            Text(
                text = "${participant.name.ifBlank { participant.id }} 正在发言…",
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )
            val modelLabel = participant.modelLabel()
            if (modelLabel.isNotBlank()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.faint,
                    )
                }
            }
        }
    }
}

/** Pulsing signal dot — a fading, expanding ring around a solid core (design `.dot::after`). */
@Composable
private fun CouncilBreathingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "council-dot")
    val ringScale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 2.1f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "council-dot-scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(2200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "council-dot-alpha",
    )
    Box(modifier = Modifier.size(15.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                    alpha = ringAlpha
                }
                .background(color, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
    }
}

/** A guest's display model id, e.g. "gpt-5.1" / "deepseek-v3.2". */
private fun CouncilParticipant.modelLabel(): String =
    modelName.ifBlank { externalModel }.ifBlank { providerName }

@Composable
private fun CouncilTimelineEmpty(room: CouncilRoom) {
    val chatTheme = LocalChatTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "amber council",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = chatTheme.ink,
        )
        Text(
            text = room.objective.ifBlank { "把问题交给多模型一起讨论。" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = chatTheme.ink,
        )
        Text(
            text = "发送第一条消息，或邀请成员开始发言。",
            style = MaterialTheme.typography.bodyMedium,
            color = chatTheme.inkFaint,
        )
    }
}

@Composable
private fun PhaseDivider(marker: CouncilPhaseMarker) {
    val chatTheme = LocalChatTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.surfaceEdge),
        )
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = chatTheme.surface,
            border = BorderStroke(1.dp, chatTheme.surfaceEdge),
        ) {
            Text(
                text = marker.label,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = chatTheme.inkFaint,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(chatTheme.surfaceEdge),
        )
    }
}

@Composable
private fun TimelineMessageRow(
    msg: CouncilMessage,
    room: CouncilRoom,
    isTopic: Boolean,
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onStreamingVisualActiveChange: ((Boolean) -> Unit)? = null,
) {
    val isUser = msg.authorId == COUNCIL_ROOM_USER_ID
    val isHost = msg.authorId == COUNCIL_ROOM_HOST_ID
    val chatTheme = LocalChatTheme.current

    // Withdrawn host review → render a compact "主持人撤回发言" capsule instead
    // of a normal bubble. The host started a review (user saw it streaming) but
    // decided this round needs no commentary; showing a withdrawn notice keeps
    // the timeline continuous (no vanishing-jump) and explains the silence.
    if (msg.kind == CouncilMessageKind.WITHDRAWN) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = chatTheme.surface,
                border = BorderStroke(1.dp, chatTheme.surfaceEdge),
            ) {
                Text(
                    text = "主持人撤回发言",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = chatTheme.inkFaint,
                )
            }
        }
        return
    }

    if (isUser) {
        // Mirror the main chat user bubble: solid userBubble fill, light userBubbleInk
        // text, asymmetric 16/16/5/16 corners, RIGHT-aligned, wraps content up to 82%
        // of the row (fillWidth=false on the markdown is what makes it adaptive).
        val userBubbleShape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomEnd = 5.dp,
            bottomStart = 16.dp,
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopEnd,
        ) {
            val userBubbleMaxWidth = maxWidth * 0.82f
            Surface(
                modifier = Modifier
                    .wrapContentWidth(Alignment.End)
                    .widthIn(max = userBubbleMaxWidth)
                    .clip(userBubbleShape),
                shape = userBubbleShape,
                color = chatTheme.userBubble,
                contentColor = chatTheme.userBubbleInk,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    if (isTopic) {
                        Text(
                            text = "议题 · 发起人",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = chatTheme.userBubbleInk.copy(alpha = 0.55f),
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    if (msg.attachments.isNotEmpty()) {
                        CouncilBubbleAttachments(
                            attachments = msg.attachments,
                            modifier = Modifier.padding(bottom = if (msg.text.isNotBlank()) 8.dp else 0.dp),
                        )
                    }
                    if (msg.text.isNotBlank()) {
                        MarkdownBlock(
                            content = msg.text,
                            fillWidth = false,
                        )
                    }
                }
            }
        }
    } else {
        // The host's final synthesis is surfaced inline as a host message
        // (mode == SYNTHESIZE). Give it its own look — a neutral surface card with
        // an accent frame + "综合结论" kicker — so it reads as the conclusion and
        // doesn't get confused with the green topic ("命题") or ordinary host turns.
        val isSynthesis = isHost && msg.mode == CouncilRoomMode.SYNTHESIZE
        val modelLabel = remember(room.participants, msg.authorId) {
            room.participantById(msg.authorId)?.modelLabel().orEmpty()
        }
        // Member / host turn: header (avatar + identity + model) on top, then a
        // FULL-WIDTH bubble flush to the content margins (left margin = right
        // margin). The bubble's top-left corner is squared, mirroring the user
        // bubble's squared bottom-right — each notch points back at its sender.
        val bubbleShape = RoundedCornerShape(
            topStart = 5.dp,
            topEnd = 16.dp,
            bottomEnd = 16.dp,
            bottomStart = 16.dp,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                SubAgentAvatar(id = msg.authorId, name = msg.authorName, avatarSize = 34.dp)
                AuthorLabel(msg, isHost, modelLabel)
            }
            if (isSynthesis) {
                Text(
                    text = "主持人总结",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = chatTheme.accent,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            MessageBubble(
                msg = msg,
                container = when {
                    isSynthesis -> chatTheme.surface
                    isHost -> chatTheme.accentSoft
                    else -> chatTheme.surface
                },
                content = when {
                    isSynthesis -> chatTheme.ink
                    isHost -> chatTheme.accentDeep
                    else -> chatTheme.ink
                },
                borderColor = when {
                    isSynthesis -> chatTheme.accent
                    isHost -> chatTheme.accentTint
                    else -> chatTheme.surfaceEdge
                },
                shape = bubbleShape,
                // Forwarded from CouncilTimelineTab: only the actively-streaming
                // turn supplies non-null callbacks, so the renderer's frame
                // release drives bottom-follow in phase with visible characters.
                onStreamingVisibleFrame = onStreamingVisibleFrame,
                onStreamingVisualActiveChange = onStreamingVisualActiveChange,
            )
            ReferenceFootnotes(msg, room, alignEnd = false)
        }
    }
}

/** Renders a user message's image thumbnails + document chips inside its bubble. */
@Composable
private fun CouncilBubbleAttachments(attachments: List<UIMessagePart>, modifier: Modifier = Modifier) {
    val chatTheme = LocalChatTheme.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.filterIsInstance<UIMessagePart.Image>().forEach { img ->
            AsyncImage(
                model = img.url,
                contentDescription = "图片附件",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        attachments.filterIsInstance<UIMessagePart.Document>().forEach { doc ->
            Text(
                text = "文件 · ${doc.fileName}",
                style = MaterialTheme.typography.labelMedium,
                color = chatTheme.userBubbleInk,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(chatTheme.userBubbleInk.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun AuthorLabel(msg: CouncilMessage, isHost: Boolean, modelLabel: String) {
    val workspace = workspaceColors()
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = msg.authorName.ifBlank { msg.authorId },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isHost) workspace.amber else workspace.ink,
            )
            val displayRole = if (isHost) "主持人" else msg.role
            if (displayRole.isNotBlank() && displayRole != msg.authorName) {
                CouncilRolePill(text = displayRole, isHost = isHost)
            }
        }
        if (modelLabel.isNotBlank()) {
            Text(
                text = modelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.faint,
            )
        }
    }
}

@Composable
internal fun CouncilRolePill(text: String, isHost: Boolean) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isHost) chatTheme.accentTint else chatTheme.surfaceEdge),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isHost) chatTheme.accent else workspace.muted,
        )
    }
}

@Composable
private fun MessageBubble(
    msg: CouncilMessage,
    container: Color,
    content: Color,
    borderColor: Color,
    shape: Shape = RoundedCornerShape(18.dp),
    onStreamingVisibleFrame: (() -> Unit)? = null,
    onStreamingVisualActiveChange: ((Boolean) -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val streaming = msg.status == CouncilMessageStatus.STREAMING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (msg.text.isNotBlank()) {
                MarkdownBlock(
                    content = msg.text,
                    streaming = streaming,
                    deferStreamingParse = streaming,
                    style = TextStyle(color = content),
                    // Forward the streaming frame callbacks so the timeline's
                    // bottom-follow stays in lockstep with what the renderer
                    // actually shows (each released frame → follow), instead of
                    // chasing raw text length which leads the display buffer.
                    onStreamingVisibleFrame = onStreamingVisibleFrame,
                    onStreamingVisualActiveChange = onStreamingVisualActiveChange,
                )
            } else if (streaming) {
                StreamingPlaceholder()
            }
            if (msg.error.isNotBlank()) {
                Text(
                    text = msg.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.red,
                )
            }
            if (msg.warnings.isNotEmpty()) {
                Text(
                    text = msg.warnings.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.amber,
                )
            }
        }
    }
}

@Composable
private fun StreamingPlaceholder() {
    val transition = rememberInfiniteTransition(label = "council-streaming")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "council-streaming-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(workspaceColors().muted, RoundedCornerShape(50)),
    )
}

/**
 * Reference-graph footnotes (reply / continues / invited-by). Only rendered for
 * guest messages with a non-null reference; resolved against the room's message
 * list to show the target author's name.
 */
@Composable
private fun ReferenceFootnotes(msg: CouncilMessage, room: CouncilRoom, alignEnd: Boolean) {
    val workspace = workspaceColors()
    val notes = buildList {
        msg.replyToMessageId?.let { id ->
            val name = room.messages.firstOrNull { it.id == id }?.authorName ?: "已删除"
            add("↳ 回复 $name")
        }
        msg.continuesFromMessageId?.let { id ->
            val name = room.messages.firstOrNull { it.id == id }?.authorName ?: "已删除"
            add("↳ 延续 $name 的论点")
        }
        // (Removed the "由 Host 邀请" note — in auto-orchestration every member is
        // host-invited, so it was on every bubble and carried no signal.)
    }
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .padding(start = 4.dp, top = 2.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.faint,
            )
        }
    }
}

/**
 * Answer card for a host ask_user question. Renders the host's question with an
 * accent border + a capsule text input + submit button. On submit, calls
 * [onAnswer] which drives [CouncilRoomVM.resumeAfterUserAnswer], appending the
 * user's answer and un-suspending the council.
 *
 * After the user answers, the room gains a normal USER message right after this
 * card (so the card is historical context) and the council resumes. The card
 * itself stays read-only (it already served its purpose).
 */
@Composable
private fun CouncilAskUserCard(
    msg: CouncilMessage,
    answered: Boolean,
    onAnswer: (String) -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    val workspace = workspaceColors()
    var answer by remember(msg.id) { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = chatTheme.surface,
        contentColor = chatTheme.ink,
        border = BorderStroke(1.dp, chatTheme.accent),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = chatTheme.accentSoft,
                ) {
                    Text(
                        text = "主持人提问",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = chatTheme.accent,
                    )
                }
            }
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = chatTheme.ink,
            )
            if (!answered) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.TextField(
                        value = answer,
                        onValueChange = { answer = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("回答主持人…", color = workspace.faint) },
                        shape = RoundedCornerShape(999.dp),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = chatTheme.surface,
                            unfocusedContainerColor = chatTheme.surface,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedTextColor = chatTheme.ink,
                            unfocusedTextColor = chatTheme.ink,
                        ),
                        maxLines = 4,
                    )
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        color = if (answer.isNotBlank()) chatTheme.accent else chatTheme.surface,
                        contentColor = if (answer.isNotBlank()) chatTheme.surface else workspace.faint,
                        border = BorderStroke(1.dp, if (answer.isNotBlank()) chatTheme.accent else chatTheme.surfaceEdge),
                        enabled = answer.isNotBlank(),
                        onClick = {
                            val text = answer.trim()
                            if (text.isNotEmpty()) {
                                onAnswer(text)
                                // Clear only after submit; if VM rejects, user can
                                // re-type — recovery path now accepts late answers.
                                answer = ""
                            }
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("→", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text(
                    text = "已回答",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.faint,
                )
            }
        }
    }
}

/**
 * Three-state bottom-follow mode for the council timeline, mirroring the chat
 * page's TimelineFollowMode. The sticky [PausedForUser] state is what lets us
 * tell a programmatic follow-scroll from a real user drag — the core fix for
 * the old bool-flag jitter.
 */
private enum class CouncilFollowMode {
    /** Not streaming; no auto-follow needed. */
    Idle,

    /** Stick to the bottom; streaming growth scrolls to keep the tail pinned. */
    FollowingBottom,

    /**
     * The user dragged away from the bottom. Stay put (don't yank back) until
     * they scroll back within the bottom buffer, which re-arms [FollowingBottom].
     */
    PausedForUser,
}
