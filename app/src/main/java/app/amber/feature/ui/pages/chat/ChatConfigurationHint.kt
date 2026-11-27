package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.amber.agent.R
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStatus
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.google.AntigravityAuthStatus
import app.amber.ai.provider.providers.google.AntigravityAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStatus
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.core.settings.findProvider
import app.amber.feature.ui.components.ui.workspaceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
internal fun rememberChatConfigurationIssue(model: Model?, providers: List<ProviderSetting>): ChatConfigurationIssue? {
    val provider = model?.findProvider(providers)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val codexStore = koinInject<OpenAICodexAuthStore>()
    val siwcStore = koinInject<OpenAIChatGptSiwcAuthStore>()
    val googleStore = koinInject<GoogleGeminiAuthStore>()
    val grokStore = koinInject<GrokAuthStore>()
    val antigravityStore = koinInject<AntigravityAuthStore>()
    val oauthReady by produceState<Boolean?>(null, provider, lifecycle) {
        value = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = withContext(Dispatchers.IO) {
                when {
                    provider is ProviderSetting.OpenAI && provider.authMode == OpenAIAuthMode.CODEX_OAUTH -> {
                        val tokens = codexStore.get(provider.id)
                        tokens != null && (tokens.refreshToken.isNotBlank() ||
                            (tokens.accessToken.isNotBlank() && tokens.expiresAtMillis > System.currentTimeMillis()))
                    }
                    provider is ProviderSetting.OpenAI && provider.authMode == OpenAIAuthMode.CHATGPT_SIWC ->
                        siwcStore.get(provider.id)?.isUsable() == true
                    provider is ProviderSetting.Google && provider.authMode == GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH ->
                        GoogleGeminiAuthStatus.from(googleStore.get(provider.id), System.currentTimeMillis()).usable
                    provider is ProviderSetting.OpenAI && provider.authMode == OpenAIAuthMode.GROK_OAUTH ->
                        GrokAuthStatus.from(grokStore.get(provider.id), System.currentTimeMillis()).usable
                    provider is ProviderSetting.Google && provider.authMode == GoogleAuthMode.ANTIGRAVITY_OAUTH ->
                        AntigravityAuthStatus.from(antigravityStore.get(provider.id), System.currentTimeMillis()).usable
                    else -> true
                }
            }
            awaitCancellation()
        }
    }
    return chatConfigurationIssue(model, providers, oauthReady)
}

@Composable
internal fun ChatConfigurationHint(
    issue: ChatConfigurationIssue,
    compactPill: Boolean = false,
    onConfigure: () -> Unit,
) {
    val actionLabel = stringResource(
        if (issue == ChatConfigurationIssue.MissingModel) R.string.chat_setup_choose_model else R.string.chat_setup_configure,
    )
    val actionTestTag = if (issue == ChatConfigurationIssue.MissingModel) {
        "chat_setup_choose_model"
    } else {
        "chat_setup_configure"
    }
    val hintTag = Modifier.testTag("chat-configuration-hint")
    if (compactPill) {
        val tokens = app.amber.feature.ui.theme.LocalAmberTokens.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = hintTag
                    .clip(CircleShape)
                    .clickable(onClick = onConfigure),
                shape = CircleShape,
                color = tokens.surface,
                border = BorderStroke(0.5.dp, tokens.floatingOutline),
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(issue.messageRes),
                        color = tokens.ink3,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = actionLabel,
                        color = tokens.accentLabel,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.testTag(actionTestTag),
                    )
                }
            }
        }
        return
    }
    val colors = workspaceColors()
    Row(
        modifier = hintTag.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(issue.messageRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        TextButton(
            onClick = onConfigure,
            modifier = Modifier.testTag(actionTestTag),
        ) {
            Text(actionLabel)
        }
    }
}
