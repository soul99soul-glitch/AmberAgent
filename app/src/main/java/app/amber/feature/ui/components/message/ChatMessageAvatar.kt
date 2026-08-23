package app.amber.feature.ui.components.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.isEmptyUIMessage
import app.amber.agent.R
import app.amber.core.model.Assistant
import app.amber.core.model.Avatar
import app.amber.feature.ui.components.ui.AutoAIIcon
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.formatNumber
import app.amber.core.utils.toLocalString

/** 6dp 圆点 background helper（避免在 inline block 里链 background+CircleShape）。 */
private fun Modifier.androidxBackgroundCircle(color: Color): Modifier =
    background(color = color, shape = CircleShape)

@Composable
fun ChatMessageUserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && settings.displaySetting.showUserAvatar) {
        Row(
            modifier = modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    color = LocalContentColor.current.copy(alpha = 0.85f),
                )
                if (settings.displaySetting.showDateBelowName) {
                    Text(
                        text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
            UIAvatar(
                name = nickname,
                modifier = Modifier.size(36.dp),
                value = avatar,
                loading = false,
            )
        }
    }
}

@Composable
fun ChatMessageAssistantAvatar(
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val showIcon = settings.displaySetting.showModelIcon
    val useAssistantAvatar = assistant?.useAssistantAvatar == true
    if (message.role == MessageRole.ASSISTANT && (model != null || useAssistantAvatar)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            if (useAssistantAvatar) {
                if (showIcon) {
                    UIAvatar(
                        name = assistant.name,
                        modifier = Modifier.size(32.dp),
                        value = assistant.avatar,
                        loading = loading,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if(settings.displaySetting.showModelName) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 1,
                        )
                        if (settings.displaySetting.showDateBelowName) {
                            Text(
                                text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                                style = MaterialTheme.typography.titleSmall,
                                color = LocalContentColor.current.copy(alpha = 0.8f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            } else if (model != null) {
                // V3 Whisper: model 名 + 6px 绿点状态（取代 provider 图标）。
                // chat1.md L463：'纯黑 DeepSeek V4 Pro，去掉 on 胶囊；改用 6px 绿色状态点做轻量信号'。
                if (showIcon) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(6.dp)
                            .androidxBackgroundCircle(
                                app.amber.feature.ui.pages.chat.LocalChatTheme.current.modelStatusDot
                            ),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    if(settings.displaySetting.showModelName) {
                        Text(
                            // Graphite §6.2 "Assistant turn": the brand label is the mono
                            // `amber` wordmark in accent (mono = machine-fact, the mono/sans
                            // split is the signature). Still the Agent group identity, not the
                            // underlying model id — switch concrete model from the TopBar.
                            text = "amber",
                            // .meta = JetBrains Mono machine-fact style; wordmark is 700 + accent.
                            style = LocalAmberType.current.meta.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            ),
                            color = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent,
                            maxLines = 1,
                        )
                        if (settings.displaySetting.showDateBelowName) {
                            Text(
                                text = message.createdAt.toJavaLocalDateTime().toLocalString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = app.amber.feature.ui.pages.chat.LocalChatTheme.current.inkFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}
