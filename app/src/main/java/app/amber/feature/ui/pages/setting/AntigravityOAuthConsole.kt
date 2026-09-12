package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.google.AntigravityAuthStore
import app.amber.ai.provider.providers.google.AntigravityOAuthClient
import app.amber.ai.provider.providers.google.AntigravityOAuthTokens
import app.amber.ai.provider.providers.google.defaultAntigravityModels
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderLabeledField
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.ai.provider.fixedBaseUrl
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Antigravity 登录面板与 Code Assist 分离，token/status 绑定 provider UUID。 */
@Composable
internal fun AntigravityOAuthConsole(
    provider: ProviderSetting.Google,
    onCommit: (ProviderSetting.Google) -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val store = koinInject<AntigravityAuthStore>()
    val client = koinInject<AntigravityOAuthClient>()
    var tokens by remember(provider.id) { mutableStateOf<AntigravityOAuthTokens?>(store.get(provider.id)) }
    var busy by remember(provider.id) { mutableStateOf(false) }
    val latestProvider by rememberUpdatedState(provider)

    suspend fun login() {
        if (busy) return
        busy = true
        try {
            val result = client.authorize(context, provider.id)
            val loginGeneration = client.sessionGeneration(provider.id)
            val models = try {
                client.listModels(provider.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            // Logout may happen while the model request is in flight. Do not
            // let that old callback repopulate the account in the panel.
            val current = latestProvider
            if (current.id != provider.id || client.sessionGeneration(provider.id) != loginGeneration) return
            tokens = client.cached(provider.id) ?: return
            onCommit(current.copy(models = if (models.isNotEmpty()) models else if (current.models.isEmpty()) defaultAntigravityModels() else current.models, name = if (current.name == "Google") "Gemini Antigravity" else current.name))
            toaster.show("已登录 Antigravity（${result.email ?: "Google 账号"}）", type = ToastType.Success)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            toaster.show("Antigravity 登录失败：${e.message ?: e}", type = ToastType.Error)
        } finally { busy = false }
    }

    LaunchedEffect(provider.id) {
        if (autoStartOAuth && provider.authMode == GoogleAuthMode.ANTIGRAVITY_OAUTH && tokens == null && !busy) {
            onAutoStartConsumed(); login()
        }
    }
    ProviderSectionLabel("会话")
    ProviderCard(modifier = Modifier.fillMaxWidth()) {
        ProviderLabeledField("Antigravity OAuth") {
            ProviderMonoNote(
                tokens?.email?.let { "已登录：$it" } ?: "尚未登录 Antigravity",
            )
        }
    }
    ProviderSectionLabel("端点")
    ProviderCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            ProviderLabeledField("API Base URL") {
                ProviderTextField(value = checkNotNull(GoogleAuthMode.ANTIGRAVITY_OAUTH.fixedBaseUrl()), onValueChange = {}, mono = true, readOnly = true)
            }
            ProviderCommandButton(text = if (tokens == null) "用 Google 账号登录 Antigravity" else "重新登录 Antigravity", accent = tokens == null, onClick = { scope.launch { login() } }, modifier = Modifier.fillMaxWidth())
            if (tokens != null) {
                ProviderCommandButton(text = "退出 Antigravity", onClick = { client.logout(provider.id); tokens = null; toaster.show("已退出 Antigravity", type = ToastType.Success) }, modifier = Modifier.fillMaxWidth())
            }
            androidx.compose.material3.Text(
                "登录后会自动刷新模型列表并保存到该服务商。",
                style = app.amber.feature.ui.theme.LocalAmberType.current.secondary,
                color = app.amber.feature.ui.theme.LocalAmberTokens.current.ink3,
            )
        }
    }
}
