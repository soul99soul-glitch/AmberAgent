package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.novel.workspace.NovelWorkspaceCollectTarget
import app.amber.feature.novel.workspace.NovelWorkspaceWriteEntry
import app.amber.feature.novel.workspace.NovelWorkspaceWriteProposal
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

private enum class DraftCollectMode { New, Append, Replace }

@Composable
internal fun MarkdownDraftReviewSheet(
    draft: NovelMarkdownDraftUi,
    chapters: List<NovelMarkdownChapterUi>,
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    readBody: suspend (String) -> String?,
    onSave: (String, String, () -> Unit) -> Unit,
    onCollect: (NovelWorkspaceCollectTarget, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val loaded by produceState<Result<String?>?>(null, draft.path) {
        value = null
        value = runCatching { readBody(draft.path) }
    }
    val initialBody = loaded?.getOrNull()
    var body by remember(draft.path, initialBody) { mutableStateOf(initialBody.orEmpty()) }
    var savedBody by remember(draft.path, initialBody) { mutableStateOf(initialBody.orEmpty()) }
    var mode by remember(draft.path) { mutableStateOf(DraftCollectMode.New) }
    var selectedPath by remember(draft.path) { mutableStateOf(chapters.lastOrNull()?.path) }
    val editable = !busy && !writeLocked && initialBody != null
    val dirty = body != savedBody
    NovelReviewSheetFrame(draft.title, onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(draft.title, style = LocalAmberType.current.body, color = workspaceColors().ink)
            NovelReviewLockNotice(writeLocked)
            when {
                loaded == null -> Text(stringResource(R.string.novel_review_reading))
                initialBody == null -> NovelReviewError(stringResource(R.string.novel_review_load_error))
                else -> NovelReviewTextEditor(
                    label = stringResource(R.string.novel_draft_body),
                    value = body,
                    enabled = editable,
                    onValueChange = { body = it },
                    tag = "novel-draft-body",
                )
            }
            Text(stringResource(R.string.novel_collect_target), style = LocalAmberType.current.body)
            DraftCollectMode.entries.forEach { item ->
                if (item == DraftCollectMode.New || chapters.isNotEmpty()) {
                    TextButton(
                        onClick = { mode = item }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        RadioButton(selected = mode == item, onClick = null)
                        Text(stringResource(when (item) {
                            DraftCollectMode.New -> R.string.novel_collect_as_new_chapter
                            DraftCollectMode.Append -> R.string.novel_append_to_chapter
                            DraftCollectMode.Replace -> R.string.novel_replace_chapter
                        }), modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            }
            if (mode != DraftCollectMode.New) {
                var open by remember { mutableStateOf(false) }
                Box {
                    NovelGhostButton(
                        text = chapters.find { it.path == selectedPath }?.let {
                            stringResource(R.string.novel_chapter_heading, it.ordinal, it.title)
                        } ?: stringResource(R.string.novel_select_chapter),
                        onClick = { open = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        chapters.forEach { chapter ->
                            DropdownMenuItem(text = { Text(stringResource(
                                R.string.novel_chapter_heading, chapter.ordinal, chapter.title)) },
                                onClick = { selectedPath = chapter.path; open = false })
                        }
                    }
                }
            }
            if (dirty) Text(stringResource(R.string.novel_review_unsaved),
                style = LocalAmberType.current.meta, color = workspaceColors().muted)
        }
        NovelReviewError(errorMessage)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NovelGhostButton(stringResource(R.string.novel_save_draft),
                onClick = { onSave(draft.path, body) { savedBody = body } },
                enabled = editable && dirty, modifier = Modifier.weight(1f))
            NovelPrimaryButton(stringResource(R.string.novel_collect_selected),
                onClick = {
                    val target = when (mode) {
                        DraftCollectMode.New -> NovelWorkspaceCollectTarget.NewChapter
                        DraftCollectMode.Append -> NovelWorkspaceCollectTarget.AppendToChapter(selectedPath!!)
                        DraftCollectMode.Replace -> NovelWorkspaceCollectTarget.ReplaceChapter(selectedPath!!)
                    }
                    if (dirty) onSave(draft.path, body) { savedBody = body; onCollect(target, onDismiss) }
                    else onCollect(target, onDismiss)
                },
                enabled = editable && body.isNotBlank() &&
                    (mode == DraftCollectMode.New || chapters.any { it.path == selectedPath }),
                accent = true, compact = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
internal fun MarkdownProposalReviewSheet(
    proposal: NovelWorkspaceWriteProposal,
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    readRaw: suspend (String) -> String?,
    onSave: (List<NovelWorkspaceWriteEntry>, () -> Unit) -> Unit,
    onApprove: (() -> Unit) -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    var bodyEdits by remember(proposal.id) { mutableStateOf(emptyMap<String, String>()) }
    var selectedIndex by remember(proposal.id) { mutableStateOf(0) }
    val entries = proposal.entries
    val entry = entries.getOrNull(selectedIndex)
    val current by produceState<Result<String?>?>(null, proposal.id, entry?.path) {
        value = null
        value = entry?.let { runCatching { readRaw(it.path) } }
    }
    val editable = !busy && !writeLocked && entry != null && current?.isSuccess == true
    val dirty = entries.any { item -> bodyEdits[item.path]?.let {
        it != NovelWorkspaceMarkdown.parseFile(item.content).body
    } == true }
    fun editedEntries(): List<NovelWorkspaceWriteEntry> = entries.map { item ->
        val body = bodyEdits[item.path] ?: return@map item
        item.copy(content = NovelWorkspaceMarkdown.withBody(item.content, body))
    }
    NovelReviewSheetFrame(stringResource(R.string.novel_write_proposal_title), onDismiss) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NovelReviewLockNotice(writeLocked)
            var open by remember { mutableStateOf(false) }
            Box {
                NovelGhostButton(
                    stringResource(R.string.novel_review_file_index, selectedIndex + 1, entries.size),
                    onClick = { open = true }, modifier = Modifier.fillMaxWidth())
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    entries.forEachIndexed { index, item ->
                        DropdownMenuItem(text = { Text(item.path) },
                            onClick = { selectedIndex = index; open = false })
                    }
                }
            }
            Text(entry?.path.orEmpty(), style = LocalAmberType.current.meta, color = workspaceColors().muted)
            val existing = current?.getOrNull()?.let { NovelWorkspaceMarkdown.parseFile(it) }
            val proposed = entry?.let { NovelWorkspaceMarkdown.parseFile(it.content) }
            val title = if (current?.getOrNull() != null) existing?.fields?.get("title")
                else proposed?.fields?.get("title")
            title?.let { Text(it, style = LocalAmberType.current.body, color = workspaceColors().ink) }
            entry?.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = LocalAmberType.current.secondary, color = workspaceColors().muted)
            }
            NovelReviewReadOnlyText(stringResource(R.string.novel_current_content), when {
                current == null -> stringResource(R.string.novel_review_reading)
                current?.isFailure == true -> stringResource(R.string.novel_review_load_error)
                current?.getOrNull() == null -> stringResource(R.string.novel_file_missing)
                else -> existing?.body.orEmpty()
            })
            if (entry != null) NovelReviewTextEditor(
                stringResource(R.string.novel_proposed_content), bodyEdits[entry.path] ?: proposed?.body.orEmpty(), editable,
                onValueChange = { text -> bodyEdits = bodyEdits + (entry.path to text) },
                tag = "novel-proposal-body")
            if (dirty) Text(stringResource(R.string.novel_review_unsaved),
                style = LocalAmberType.current.meta, color = workspaceColors().muted)
        }
        NovelReviewError(errorMessage)
        if (dirty) NovelGhostButton(stringResource(R.string.novel_save_proposal),
            onClick = { onSave(editedEntries()) { bodyEdits = emptyMap() } },
            enabled = editable, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NovelGhostButton(stringResource(R.string.novel_reject),
                onClick = { onReject(); onDismiss() }, enabled = !busy && !writeLocked,
                modifier = Modifier.weight(1f))
            NovelPrimaryButton(stringResource(R.string.novel_confirm_write),
                onClick = {
                    if (dirty) onSave(editedEntries()) { bodyEdits = emptyMap(); onApprove(onDismiss) }
                    else onApprove(onDismiss)
                }, enabled = editable, accent = true, compact = true,
                modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReviewSheetFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = workspaceColors().canvas) {
        Column(Modifier.fillMaxWidth().heightIn(max = 760.dp).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                    color = workspaceColors().ink, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            }
            content()
        }
    }
}

@Composable
internal fun NovelReviewTextEditor(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    tag: String,
) {
    val colors = workspaceColors()
    Text(label, style = LocalAmberType.current.meta, color = colors.muted)
    Surface(color = colors.paper, border = BorderStroke(1.dp, colors.hairline)) {
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled,
            textStyle = LocalAmberType.current.body.copy(color = colors.ink),
            cursorBrush = SolidColor(LocalAmberTokens.current.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(12.dp).testTag(tag))
    }
}

@Composable
internal fun NovelReviewReadOnlyText(label: String, value: String) {
    Text(label, style = LocalAmberType.current.meta, color = workspaceColors().muted)
    Surface(color = workspaceColors().paper, border = BorderStroke(1.dp, workspaceColors().hairline)) {
        SelectionContainer {
            Text(value, style = LocalAmberType.current.body, color = workspaceColors().ink,
                modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
    }
}

@Composable
internal fun NovelReviewLockNotice(locked: Boolean) {
    if (locked) Text(stringResource(R.string.novel_review_locked),
        style = LocalAmberType.current.meta, color = workspaceColors().muted)
}

@Composable
internal fun NovelReviewError(message: String?) {
    if (!message.isNullOrBlank()) Text(message, style = LocalAmberType.current.meta,
        color = workspaceColors().red, modifier = Modifier.fillMaxWidth())
}
