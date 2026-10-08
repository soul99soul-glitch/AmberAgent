package app.amber.feature.ui.pages.novel

import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import java.time.Instant
import app.amber.feature.novelworkspace.NovelWorkspaceCommit

/** One chronological UI stream without inventing a source message for imported drafts. */
internal sealed interface NovelMarkdownTimelineRow {
    val key: String
    val createdAt: Instant

    data class Message(val message: NovelMarkdownMessageUi) : NovelMarkdownTimelineRow {
        override val key: String get() = "message-${message.id}"
        override val createdAt: Instant get() = message.createdAt
    }

    data class Draft(val draft: NovelMarkdownDraftUi) : NovelMarkdownTimelineRow {
        override val key: String get() = "draft-${draft.path}"
        override val createdAt: Instant get() = draft.createdAt
    }

    data class Proposal(val proposal: NovelWorkspaceWriteProposal) : NovelMarkdownTimelineRow {
        override val key: String get() = "proposal-${proposal.id}"
        override val createdAt: Instant get() = proposal.createdAt
    }
}

/** Newest first for reverse layout; equal timestamps retain the stored session order on screen. */
internal fun novelMarkdownTimeline(state: NovelMarkdownWorkspaceUiState): List<NovelMarkdownTimelineRow> =
    buildList {
        addAll(state.messages.map(NovelMarkdownTimelineRow::Message))
        addAll(state.drafts.map(NovelMarkdownTimelineRow::Draft))
        addAll(state.proposals.map(NovelMarkdownTimelineRow::Proposal))
    }.sortedBy { it.createdAt }.asReversed()

/** A collected/deleted path starts a new draft lifetime when it is written again. */
internal fun novelMarkdownDraftCreatedAt(
    ancestry: List<NovelWorkspaceCommit>,
    path: String,
    fileModifiedAt: Instant,
): Instant = ancestry.asReversed().takeWhile { path in it.files }.lastOrNull()?.createdAt ?: fileModifiedAt
