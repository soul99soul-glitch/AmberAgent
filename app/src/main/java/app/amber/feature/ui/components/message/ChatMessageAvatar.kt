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
    model: Model?,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val showIcon = settings.displaySetting.showModelIcon
    val showName = settings.displaySetting.showModelName
    if (message.role == MessageRole.ASSISTANT && model != null && (showIcon || showName)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            // V3 Whisper: model 名 + 6px 绿点状态（取代 provider 图标）。
            if (showIcon) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(6.dp)
                        .androidxBackgroundCircle(
                            app.amber.feature.ui.pages.chat.LocalChatTheme.current.modelStatusDot
                        ),
                )
            }
            if (showName) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "amber",
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
