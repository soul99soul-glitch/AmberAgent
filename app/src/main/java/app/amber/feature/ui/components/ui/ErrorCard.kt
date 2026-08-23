package app.amber.feature.ui.components.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import app.amber.agent.R
import app.amber.core.service.ChatError
import kotlin.uuid.Uuid

@Composable
fun ErrorCardsDisplay(
    errors: List<ChatError>,
    globalErrors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleErrors = errors + globalErrors
    AnimatedVisibility(
        visible = visibleErrors.isNotEmpty(),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // 清除全部按钮（当有多个错误时显示）
            if (visibleErrors.size > 1) {
                Surface(
                    onClick = onClearAllErrors,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = stringResource(R.string.chat_page_clear_all_errors),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // P8-07: 全局错误（conversationId == null）与消息错误分区展示——
            // 全局 banner 用 tertiaryContainer 区分，不伪装成当前消息错误。
            globalErrors.forEach { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { onDismissError(error.id) },
                    isGlobal = true,
                )
            }

            // 错误卡片列表（当前会话的消息错误）
            errors.forEach { error ->
                ErrorCard(
                    error = error,
                    onDismiss = { onDismissError(error.id) },
                )
            }
        }
    }
}

@Composable
fun ErrorCard(
    error: ChatError,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isGlobal: Boolean = false,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // 2026-05-15: removed the 5s auto-dismiss. Generation errors are often the only
    // signal a user gets that something went wrong (e.g. context compaction failure,
    // network drop mid-stream), and a 5-second window is far too short — users
    // notice 30+ seconds later that "AI 没回复" and have no idea why. The card now
    // stays put until explicitly dismissed via the close button, matching how
    // every other in-app status (tool failure, queue exhaustion) behaves.

    // P8-07: 全局错误 banner 用 tertiary 色系与消息错误（errorContainer）区分。
    val containerColor = if (isGlobal) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (isGlobal) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isGlobal) {
                        Text(
                            text = "全局",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                        )
                    }
                    if (error.title != null) {
                        Text(
                            text = error.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = error.error.message ?: "Unknown error",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                clipData = ClipData.newPlainText("Error", error.error.message ?: "Unknown error")
                            )
                        )
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = "Copy error message",
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.chat_page_dismiss_error),
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
