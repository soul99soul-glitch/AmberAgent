package app.amber.feature.ui.pages.novel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.CancellationException

/** A full-screen manuscript reader; all writes remain with the workspace owner. */
@Composable
internal fun NovelMarkdownChapterReader(
    chapters: List<NovelMarkdownChapterUi>,
    chapterPath: String,
    active: Boolean,
    busy: Boolean,
    writeLocked: Boolean,
    errorMessage: String?,
    refreshVersion: Int = 0,
    scrollState: ScrollState,
    readBody: suspend (String) -> String?,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onSave: (NovelMarkdownChapterUi, String, String, () -> Unit) -> Unit,
    onRewrite: (NovelMarkdownChapterUi) -> Boolean,
    onHistory: (NovelMarkdownChapterUi) -> Unit,
    onDiscard: (NovelMarkdownChapterUi) -> Unit,
    onClearError: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val orderedChapters = chapters.sortedBy { it.ordinal }
    val currentIndex = orderedChapters.indexOfFirst { it.path == chapterPath }
    val previous = orderedChapters.getOrNull(currentIndex - 1)
    val next = if (currentIndex >= 0) orderedChapters.getOrNull(currentIndex + 1) else null
    var editing by remember(chapterPath) { mutableStateOf(false) }
    var contentTick by remember(chapterPath) { mutableStateOf(0) }
    var rewritingPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshVersion) { editing = false }
    LaunchedEffect(busy) { if (!busy) rewritingPath = null }
    val leaveEditor = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        editing = false
    }
    BackHandler(enabled = active) {
        if (editing) {
            if (!busy) leaveEditor()
        } else {
            onBack()
        }
    }

    val chapterTransition = updateTransition(chapterPath, label = "novelReaderChapter")
    val chapterOffset = with(LocalDensity.current) { 32.dp.roundToPx() }
    Column(
        Modifier.fillMaxSize().background(workspace.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
    ) {
        chapterTransition.AnimatedContent(
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = {
                val from = orderedChapters.indexOfFirst { it.path == initialState }
                val to = orderedChapters.indexOfFirst { it.path == targetState }
                workspacePageMotion(forward = to >= from, offsetPx = chapterOffset)
                    .using(SizeTransform(clip = false) { _, _ -> tween(NovelMotion.MediumMs) })
            },
        ) { visiblePath ->
            val visibleChapter = chapters.firstOrNull { it.path == visiblePath }
            // Keep an outgoing chapter on its own scroll position during the short overlap.
            val retainedScroll = remember(visiblePath) { mutableStateOf(scrollState) }
            val visibleScroll = if (visiblePath == chapterPath) scrollState else retainedScroll.value
            SideEffect {
                if (visiblePath == chapterPath) retainedScroll.value = scrollState
            }
            val visibleActive = active && visiblePath == chapterPath
            val loaded by produceState<Result<String?>?>(null, visiblePath, refreshVersion, contentTick) {
                value = null
                value = try {
                    Result.success(readBody(visiblePath))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            val body = loaded?.getOrNull()
            val showingEditor = editing && visiblePath == chapterPath && body != null && visibleChapter != null
            Column(Modifier.fillMaxSize().workspaceTransitionInput(active = visibleActive)) {
                if (!showingEditor) {
                    ReaderHeader(
                        chapter = visibleChapter,
                        onBack = onBack,
                        busy = busy,
                        writeLocked = writeLocked,
                        editAvailable = body != null && visibleChapter != null,
                        rewriting = rewritingPath == visiblePath,
                        onEdit = { editing = true },
                        onRewrite = {
                            visibleChapter?.let { if (onRewrite(it)) rewritingPath = it.path }
                        },
                        onHistory = { visibleChapter?.let(onHistory) },
                        onDiscard = { visibleChapter?.let(onDiscard) },
                    )
                }
                errorMessage?.let { message ->
                    Text(message, style = type.meta, color = workspace.red,
                        modifier = Modifier.fillMaxWidth().novelPressable(onClick = onClearError)
                            .padding(horizontal = 20.dp, vertical = 8.dp))
                }
                when {
                    loaded == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = workspace.ink, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    body == null || visibleChapter == null -> {
                        Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.novel_reader_load_error), style = type.secondary, color = workspace.muted)
                            NovelGhostButton(stringResource(R.string.retry), onClick = { contentTick++ })
                        }
                    }
                    editing && visiblePath == chapterPath -> MarkdownChapterEditor(
                        chapter = visibleChapter,
                        initialBody = body,
                        busy = busy,
                        writeLocked = writeLocked,
                        onSave = { title, text, onSaved ->
                            onSave(visibleChapter, title, text) {
                                onSaved()
                                contentTick++
                                leaveEditor()
                            }
                        },
                        onCancel = leaveEditor,
                        onHistory = { onHistory(visibleChapter) },
                    )
                    else -> Column(
                        Modifier.fillMaxWidth().weight(1f)
                            .testTag("novel-chapter-reader-scroll")
                            .verticalScroll(visibleScroll)
                            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
                    ) {
                        if (body.isBlank()) {
                            Text(stringResource(R.string.novel_empty_chapter), style = type.body, color = workspace.muted)
                        } else {
                            MarkdownBlock(body, modifier = Modifier.fillMaxWidth(),
                                style = type.body.copy(fontSize = 17.sp, lineHeight = 26.sp, color = workspace.ink))
                        }
                    }
                }
            }
        }
        if (!editing) {
            Row(
                Modifier.fillMaxWidth().testTag("novel-chapter-reader-navigation")
                    .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    ReaderChapterButton(stringResource(R.string.novel_previous_chapter), Lucide.ChevronLeft,
                        iconAfter = false, enabled = active && previous != null,
                        onClick = { previous?.let { onOpenChapter(it.path) } })
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    ReaderChapterButton(stringResource(R.string.novel_next_chapter), Lucide.ChevronRight,
                        iconAfter = true, enabled = active && next != null,
                        onClick = { next?.let { onOpenChapter(it.path) } })
                }
            }
        }
    }
}

@Composable
private fun ReaderHeader(
    chapter: NovelMarkdownChapterUi?,
    onBack: () -> Unit,
    busy: Boolean,
    writeLocked: Boolean,
    editAvailable: Boolean,
    rewriting: Boolean,
    onEdit: () -> Unit,
    onRewrite: () -> Unit,
    onHistory: () -> Unit,
    onDiscard: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    var menuOpen by remember(chapter?.path) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NovelWorkspaceNavButton(Lucide.ArrowLeft, stringResource(R.string.novel_return_to_directory), onClick = onBack)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            chapter?.let {
                Text(stringResource(R.string.novel_reader_meta, it.ordinal, it.charCount),
                    style = type.meta, color = workspace.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(chapter?.title ?: stringResource(R.string.novel_file_missing),
                style = type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = workspace.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            NovelWorkspaceNavButton(Lucide.Ellipsis, stringResource(R.string.novel_more_actions),
                onClick = { menuOpen = true }, enabled = chapter != null)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, enabled = editAvailable && !busy && !writeLocked,
                    onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(if (rewriting) R.string.novel_rewriting_chapter else R.string.novel_rewrite_chapter)) },
                    enabled = !busy && !writeLocked, onClick = { menuOpen = false; onRewrite() })
                DropdownMenuItem(text = { Text(stringResource(R.string.novel_chapter_history)) },
                    onClick = { menuOpen = false; onHistory() })
                DropdownMenuItem(text = { Text(stringResource(R.string.novel_discard_chapter), color = workspace.red) },
                    enabled = !busy && !writeLocked, onClick = { menuOpen = false; onDiscard() })
            }
        }
    }
}

@Composable
private fun ReaderChapterButton(
    text: String,
    icon: ImageVector,
    iconAfter: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tint = if (enabled) workspace.ink else workspace.faint
    Box(Modifier.heightIn(min = 48.dp).novelPressable(onClick = onClick, enabled = enabled), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 38.dp).clip(CircleShape).background(workspace.paper)
            .border(1.dp, workspace.hairline, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!iconAfter) Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Text(text, style = type.meta.copy(fontWeight = FontWeight.SemiBold), color = tint,
                modifier = Modifier.weight(1f, fill = false), maxLines = 2)
            if (iconAfter) Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}
