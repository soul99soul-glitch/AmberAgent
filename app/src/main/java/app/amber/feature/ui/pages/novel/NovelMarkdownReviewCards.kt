package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.amber.agent.R
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.CancellationException

/** Draft summaries keep the full manuscript in the dedicated review sheet. */
@Composable
internal fun MarkdownDraftCard(draft: NovelMarkdownDraftUi, onReview: () -> Unit) {
    val colors = workspaceColors()
    val type = LocalAmberType.current
    AmberCard(Modifier.fillMaxWidth(), containerColor = colors.paper, borderColor = colors.hairline) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.novel_draft_pending), style = type.meta, color = colors.muted)
            Text(draft.title, style = type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(draft.excerpt, style = type.secondary, color = colors.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(
                onClick = onReview,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.novel_review_draft), style = type.secondary, color = colors.ink) }
        }
    }
}

@Composable
internal fun MarkdownProposalCard(
    proposal: NovelWorkspaceWriteProposal,
    busy: Boolean,
    onReview: () -> Unit,
    onReject: () -> Unit,
    readRaw: suspend (String) -> String?,
    onApprove: () -> Unit,
) {
    val colors = workspaceColors()
    val type = LocalAmberType.current
    AmberCard(Modifier.fillMaxWidth(), containerColor = colors.paper, borderColor = colors.hairline) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val singleEntry = proposal.entries.singleOrNull()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.novel_write_proposal_title),
                    style = type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (singleEntry != null) TextButton(onClick = onReview,
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 140.dp)) {
                    Text(stringResource(R.string.novel_review_proposal), style = type.meta, color = colors.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (singleEntry != null) {
                val proposed = NovelWorkspaceMarkdown.parseFile(singleEntry.content)
                val original by produceState<Result<String?>?>(null, proposal.id, singleEntry.path, singleEntry.content) {
                    value = try {
                        Result.success(readRaw(singleEntry.path))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
                val originalRaw = original?.getOrNull()
                val originalFile = originalRaw?.let(NovelWorkspaceMarkdown::parseFile)
                val ordinal = originalFile?.fields?.get("ordinal")?.toIntOrNull()
                val target = when {
                    singleEntry.path.endsWith("/plot/current.md") -> stringResource(R.string.novel_catalog_current_plot)
                    originalFile?.fields?.get("kind") == "chapter" && ordinal != null ->
                        stringResource(R.string.novel_chapter_heading, ordinal,
                            originalFile.fields["title"] ?: singleEntry.path.substringAfterLast('/').removeSuffix(".md"))
                    else -> singleEntry.path
                }
                val proposedTitle = proposed.fields["title"] ?: singleEntry.path.substringAfterLast('/').removeSuffix(".md")
                val sameChapterTitle = originalFile?.fields?.get("kind") == "chapter" && ordinal != null &&
                    proposedTitle == originalFile.fields["title"]
                if (!sameChapterTitle) Text(proposedTitle,
                    style = type.body.copy(fontWeight = FontWeight.Medium), color = colors.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(target, style = if (sameChapterTitle) type.body.copy(fontWeight = FontWeight.Medium) else type.meta,
                    color = if (sameChapterTitle) colors.ink else colors.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                singleEntry.reason?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = type.secondary, color = colors.muted)
                }
                ProposalTextPreview(stringResource(R.string.novel_original_text), when {
                    original == null -> stringResource(R.string.novel_review_reading)
                    original?.isFailure == true -> stringResource(R.string.novel_review_load_error)
                    originalRaw == null -> stringResource(R.string.novel_file_missing)
                    else -> originalFile?.body.orEmpty()
                })
                ProposalTextPreview(stringResource(R.string.novel_revised_text), proposed.body)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovelGhostButton(stringResource(R.string.novel_reject), onReject,
                        enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    NovelPrimaryButton(stringResource(R.string.novel_confirm_write), onApprove,
                        enabled = !busy && original?.isSuccess == true, accent = true, compact = true,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            } else {
                proposal.entries.forEach { entry ->
                    Text(entry.path, style = type.meta, color = colors.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovelGhostButton(stringResource(R.string.novel_reject), onReject,
                        enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    NovelPrimaryButton(stringResource(R.string.novel_review_proposal), onReview,
                        accent = true, compact = true, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun ProposalTextPreview(label: String, content: String) {
    val colors = workspaceColors()
    val type = LocalAmberType.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(colors.canvas).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = type.meta.copy(fontWeight = FontWeight.SemiBold), color = colors.muted)
        Box(Modifier.fillMaxWidth().heightIn(max = 110.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(content, style = type.secondary, color = colors.ink) }
        }
    }
}
