package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.google.AntigravityAuthStatus
import app.amber.ai.provider.providers.google.AntigravityAuthStatusCode
import app.amber.ai.provider.providers.google.AntigravityAuthStore
import app.amber.ai.provider.providers.google.AntigravityOAuthClient
import app.amber.ai.provider.providers.google.AntigravityOAuthTokens
import app.amber.ai.provider.providers.google.defaultAntigravityModels
import app.amber.ai.provider.fixedBaseUrl
import app.amber.agent.R
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
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
    var tokens by remember(provider.id) {
        mutableStateOf<AntigravityOAuthTokens?>(store.get(provider.id))
    }
    var busy by remember(provider.id) { mutableStateOf(false) }
    val latestProvider by rememberUpdatedState(provider)
    val status = remember(tokens, provider.id) {
        client.authStatus(provider.id)
    }
    val signedIn = tokens != null

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
            // Logout may happen while model discovery is in flight. Keep the
            // durable generation guard before committing the provider snapshot.
            val current = latestProvider
            if (current.id != provider.id || client.sessionGeneration(provider.id) != loginGeneration) {
                return
            }
            val cached = client.cached(provider.id) ?: return
            tokens = cached
            onCommit(
                current.copy(
                    models = when {
                        models.isNotEmpty() -> models
                        current.models.isEmpty() -> defaultAntigravityModels()
                        else -> current.models
                    },
                    name = if (current.name == "Google") "Gemini Antigravity" else current.name,
                )
            )
            toaster.show(
                "已登录 Antigravity（${result.email ?: "Google 账号"}）",
                type = ToastType.Success,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            toaster.show(
                "Antigravity 登录失败：${error.message ?: error}",
                type = ToastType.Error,
            )
        } finally {
            busy = false
        }
    }

    val latestAutoStart by rememberUpdatedState(autoStartOAuth)
    val latestAutoStartConsumed by rememberUpdatedState(onAutoStartConsumed)
    LaunchedEffect(provider.id, provider.authMode) {
        if (
            latestAutoStart &&
            provider.authMode == GoogleAuthMode.ANTIGRAVITY_OAUTH &&
            tokens == null &&
            !busy
        ) {
            latestAutoStartConsumed()
            login()
        }
    }

    ProviderSectionLabel("会话")
    ProviderCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderMonogram(
                text = provider.name.toProviderMonogram(),
                size = 32.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val t = LocalAmberTokens.current
                val type = LocalAmberType.current
                Text(
                    text = "Antigravity OAuth",
                    style = type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink,
                    maxLines = 1,
                )
                AntigravityStatusPill(
                    status = status,
                    email = tokens?.email,
                )
            }
        }
    }

    ProviderSectionLabel(stringResource(R.string.setting_provider_page_endpoint))
    ProviderCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AntigravityLabeledField("API Base URL") {
                ProviderTextField(
                    value = checkNotNull(GoogleAuthMode.ANTIGRAVITY_OAUTH.fixedBaseUrl()),
                    onValueChange = {},
                    mono = true,
                    readOnly = true,
                )
            }
            ProviderCommandButton(
                text = if (busy) "登录中…" else if (signedIn) "重新登录 Antigravity" else "用 Google 账号登录 Antigravity",
                accent = !signedIn,
                onClick = { scope.launch { login() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            AntigravityDangerButton(
                text = "退出 Antigravity",
                enabled = signedIn && !busy,
                onClick = {
                    client.logout(provider.id)
                    tokens = null
                    toaster.show("已退出 Antigravity", type = ToastType.Success)
                },
            )
            Text(
                text = "登录后会自动刷新模型列表并保存到该服务商。",
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink2,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun AntigravityStatusPill(
    status: AntigravityAuthStatus,
    email: String?,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val connected = status.usable
    val statusColor = if (connected) t.signal else t.ink3
    val copy = when {
        connected && !email.isNullOrBlank() -> "已登录：$email"
        connected -> "已登录"
        status.code == AntigravityAuthStatusCode.NOT_SIGNED_IN -> "未登录"
        status.code == AntigravityAuthStatusCode.CLIENT_UNAVAILABLE -> "不可用"
        else -> "需要重新登录"
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (connected) t.signal.copy(alpha = 0.14f) else t.surface2)
            .border(1.dp, if (connected) t.signal.copy(alpha = 0.32f) else t.line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(statusColor, CircleShape),
            )
            Text(
                text = copy,
                style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AntigravityLabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(modifier = modifier.padding(top = 12.dp)) {
        Text(
            text = label,
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = t.ink,
            modifier = Modifier.padding(start = 2.dp, bottom = 7.dp),
            maxLines = 1,
        )
        Box(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun AntigravityDangerButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(15.dp))
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) MaterialTheme.colorScheme.error else t.ink4,
            maxLines = 1,
        )
    }
}
