package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
internal fun ChatConfigurationHint(issue: ChatConfigurationIssue, onConfigure: () -> Unit) {
    val colors = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(issue.messageRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        TextButton(onClick = onConfigure) {
            Text(stringResource(if (issue == ChatConfigurationIssue.MissingModel) R.string.chat_setup_choose_model else R.string.chat_setup_configure))
        }
    }
}
