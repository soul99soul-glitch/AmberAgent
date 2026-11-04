package app.amber.feature.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.isEmptyUIMessage
import app.amber.agent.R
import app.amber.core.model.Avatar
import app.amber.feature.prompts.AgentSoulStore
import app.amber.feature.ui.components.ai.AgentSoulSheet
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.compose.koinInject

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
            Text(
                text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                color = LocalContentColor.current.copy(alpha = 0.85f),
            )
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
    val showName = settings.displaySetting.showModelName
    val agentSoulStore: AgentSoulStore = koinInject()
    val soulState by agentSoulStore.state.collectAsStateWithLifecycle()
    var showSoulSheet by remember { mutableStateOf(false) }
    if (message.role == MessageRole.ASSISTANT && model != null && showName) {
        val displayLabel = if (soulState.isDefault) {
            "amber"
        } else {
            soulState.displayName.ifBlank { "amber" }
        }
        Text(
            text = displayLabel,
            modifier = modifier.clickable { showSoulSheet = true },
            style = LocalAmberType.current.meta.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
            color = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent,
            maxLines = 1,
        )
    }
    if (showSoulSheet) {
        AgentSoulSheet(onDismiss = { showSoulSheet = false })
    }
}
