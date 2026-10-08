package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun MarkdownChapterEditor(
    chapter: NovelMarkdownChapterUi,
    initialBody: String,
    busy: Boolean,
    writeLocked: Boolean,
    onSave: (title: String, body: String, onSaved: () -> Unit) -> Unit,
    onCancel: () -> Unit,
    onHistory: (() -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    var title by remember(chapter.path) { mutableStateOf(chapter.title) }
    var body by remember(chapter.path) { mutableStateOf(initialBody) }
    var saved by remember(chapter.path) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                NovelQuietButton(
                    text = stringResource(R.string.cancel),
                    onClick = onCancel,
                    enabled = !busy,
                )
            }
            Text(
                stringResource(R.string.novel_edit_chapter),
                style = type.screenTitle.copy(fontSize = 19.sp),
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                NovelEditorSaveButton(
                    text = if (busy) {
                        stringResource(R.string.novel_saving)
                    } else {
                        stringResource(R.string.chat_page_save)
                    },
                    enabled = !busy && !writeLocked,
                    onClick = {
                        saved = false
                        onSave(title, body) {
                            saved = true
                        }
                    },
                )
            }
        }
        onHistory?.let { openHistory ->
            TextButton(
                onClick = openHistory,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.novel_chapter_history)) }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NovelWorkspaceSectionLabel(text = "章节")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = workspace.paper,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Column {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "章节标题",
                            style = type.meta,
                            color = workspace.muted,
                        )
                        BasicTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                saved = false
                            },
                            enabled = !busy && !writeLocked,
                            singleLine = true,
                            textStyle = type.body.copy(color = workspace.ink, fontWeight = FontWeight.SemiBold),
                            cursorBrush = SolidColor(tokens.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(workspace.hairline))
                    BasicTextField(
                        value = body,
                        onValueChange = {
                            body = it
                            saved = false
                        },
                        enabled = !busy && !writeLocked,
                        textStyle = type.body.copy(color = workspace.ink),
                        cursorBrush = SolidColor(tokens.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 320.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(workspace.hairline))
                    Row(
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.accent))
                        Text(
                            stringResource(R.string.novel_character_count, body.length),
                            style = type.meta,
                            color = workspace.muted,
                        )
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = CircleShape,
                            color = workspace.row,
                            border = BorderStroke(1.dp, workspace.hairline),
                        ) {
                            Text(
                                if (saved) stringResource(R.string.novel_saved) else "草稿",
                                style = type.meta,
                                color = workspace.muted,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(48.dp))
        }
    }
}

