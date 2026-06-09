package app.amber.feature.ui.pages.setting

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.availableAuthModes
import app.amber.ai.provider.fixedBaseUrl
import app.amber.ai.provider.hasUsableAuth
import app.amber.ai.provider.providers.defaultCodexOAuthModelList
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.google.GoogleGeminiAuthTokens
import app.amber.ai.provider.providers.google.GoogleGeminiOAuthClient
import app.amber.ai.provider.providers.google.OBSOLETE_GEMINI_OAUTH_MODEL_IDS
import app.amber.ai.provider.providers.google.defaultGeminiOAuthModelList
import app.amber.ai.provider.providers.isCodexOAuthReviewModel
import app.amber.ai.provider.providers.openai.OPENAI_CODEX_BACKEND_BASE_URL
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexOAuthClient
import app.amber.agent.R
import app.amber.core.utils.openUrl
import app.amber.core.utils.writeClipboardText
import app.amber.feature.ui.components.ai.ProviderBalanceText
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.ui.SiliconFlowPowerByIcon
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderLabeledField
import app.amber.feature.ui.pages.setting.components.ProviderLiveDot
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderPillSeg
import app.amber.feature.ui.pages.setting.components.ProviderSecretField
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.pages.setting.components.ProviderToggle
import app.amber.feature.ui.pages.setting.components.ProviderConnectionTester
import app.amber.feature.ui.pages.setting.components.convertTo
import app.amber.feature.ui.pages.setting.components.isUsingDefaultBaseUrl
import app.amber.feature.ui.pages.setting.components.providerAuthLabel
import app.amber.feature.ui.pages.setting.components.resetBaseUrlToDefault
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PowerService
import me.rerere.hugeicons.stroke.Refresh03
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlin.reflect.KClass

@Composable
internal fun SettingProviderConfigPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onDelete: () -> Unit,
) {
    var internalProvider by remember(provider) { mutableStateOf(provider) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    ProviderConsole(
        provider = internalProvider,
        onEdit = { internalProvider = it },
        onCommit = {
            internalProvider = it
            onEdit(it)
        },
    ) { currentProvider ->
        ProviderConsoleActions(
            provider = currentProvider,
            onDelete = { showDeleteDialog = true },
            onReset = { internalProvider = internalProvider.resetBaseUrlToDefault() },
            onSave = { onEdit(internalProvider) },
        )
    }

    if (showDeleteDialog) {
        ProviderDeleteDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
        )
    }
}

@Composable
private fun ProviderDeleteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Dialog(onDismissRequest = onDismiss) {
        ProviderCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.confirm_delete),
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = t.ink,
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_delete_dialog_text),
                    style = type.secondary,
                    color = t.ink3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ProviderActionButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                    )
                    ProviderActionButton(
                        text = stringResource(R.string.delete),
                        onClick = onConfirm,
                        accent = true,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProviderConsole(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    autoStartOAuth: Boolean = false,
    onAutoStartConsumed: () -> Unit = {},
    onCommit: (ProviderSetting) -> Unit = onEdit,
    actionContent: (@Composable (ProviderSetting) -> Unit)? = null,
) {
    val t = LocalAmberTokens.current
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(t.bg),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("identity") {
            ProviderConsoleIdentity(provider = provider)
        }
        item("status") {
            ProviderStatusSection(
                provider = provider,
                onEdit = onEdit,
            )
        }
        item("protocol") {
            ProviderProtocolSection(
                provider = provider,
                onEdit = onEdit,
            )
        }
        item("auth") {
            ProviderAuthSection(
                provider = provider,
                onEdit = onEdit,
                onCommit = onCommit,
                autoStartOAuth = autoStartOAuth,
                onAutoStartConsumed = onAutoStartConsumed,
            )
        }
        item("endpoint") {
            ProviderEndpointSection(
                provider = provider,
                onEdit = onEdit,
            )
        }
        if (provider is ProviderSetting.OpenAI) {
            item("account") {
                ProviderAccountSection(
                    provider = provider,
                    onEdit = onEdit,
                )
            }
        }
        if (actionContent != null) {
            item("actions") {
                actionContent(provider)
            }
        }
        val siliconFlowProvider = provider as? ProviderSetting.OpenAI
        if (siliconFlowProvider?.baseUrl?.contains("siliconflow.cn") == true) {
            item("siliconflow") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SiliconFlowPowerByIcon()
                }
            }
        }
    }
}

@Composable
private fun ProviderConsoleIdentity(provider: ProviderSetting) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    ProviderCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderMonogram(
                text = provider.name.toProviderMonogram(),
                size = 44.dp,
                enabled = provider.enabled,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = provider.name,
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ProviderLiveDot(enabled = provider.enabled && provider.hasUsableAuth())
                    Text(
                        text = "${if (provider.enabled && provider.hasUsableAuth()) "已连接" else "未连接"} · ${provider.providerAuthLabel()} · ${provider.models.size} 模型",
                        style = type.meta.copy(fontSize = 11.sp),
                        color = t.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderStatusSection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    ProviderSectionLabel("状态")
    ProviderCard {
        ProviderSwitchRow(
            icon = HugeIcons.PowerService,
            title = "是否启用",
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
        )
    }
}

@Composable
private fun ProviderProtocolSection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    ProviderSectionLabel("协议")
    ProviderCard {
        Column(Modifier.padding(12.dp)) {
            val options = ProviderSetting.Types.map { type ->
                ProviderSegOption(type, type.simpleName ?: "")
            }
            ProviderPillSeg(
                options = options,
                selected = provider::class,
                onSelected = { type -> onEdit(provider.convertTo(type)) },
                mono = true,
            )
        }
    }
}

@Composable
private fun ProviderAuthSection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onCommit: (ProviderSetting) -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    ProviderSectionLabel("鉴权")
    ProviderCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (provider) {
                is ProviderSetting.OpenAI -> OpenAIAuthConsole(
                    provider = provider,
                    onEdit = onEdit,
                    onCommit = onCommit,
                    autoStartOAuth = autoStartOAuth,
                    onAutoStartConsumed = onAutoStartConsumed,
                )
                is ProviderSetting.Google -> GoogleAuthConsole(
                    provider = provider,
                    onEdit = onEdit,
                    onCommit = onCommit,
                    autoStartOAuth = autoStartOAuth,
                    onAutoStartConsumed = onAutoStartConsumed,
                )
                is ProviderSetting.Claude -> ClaudeAuthConsole(
                    provider = provider,
                    onEdit = onEdit,
                )
            }
        }
    }
}

@Composable
private fun OpenAIAuthConsole(
    provider: ProviderSetting.OpenAI,
    onEdit: (ProviderSetting.OpenAI) -> Unit,
    onCommit: (ProviderSetting.OpenAI) -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    val availableModes = provider.brand.availableAuthModes()
    if (availableModes.size > 1) {
        ProviderPillSeg(
            options = availableModes.map { ProviderSegOption(it, it.openAIAuthLabel()) },
            selected = provider.authMode,
            onSelected = { mode ->
                if (provider.authMode != mode) {
                    onEdit(provider.switchOpenAIAuthMode(mode))
                }
            },
            mono = true,
        )
    }
    if (provider.authMode !in availableModes) {
        LaunchedEffect(provider.id, provider.brand) {
            onEdit(provider.copy(authMode = OpenAIAuthMode.API_KEY))
        }
    }

    val isCodingPlan = provider.authMode.isCodingPlan()
    when {
        provider.authMode == OpenAIAuthMode.CODEX_OAUTH -> {
            CodexOAuthConsole(
                provider = provider,
                onCommit = onCommit,
                autoStartOAuth = autoStartOAuth,
                onAutoStartConsumed = onAutoStartConsumed,
            )
        }
        provider.authMode == OpenAIAuthMode.API_KEY || isCodingPlan -> {
            ProviderLabeledField("API Key") {
                ProviderSecretField(
                    value = provider.apiKey,
                    onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
                    placeholder = "sk-...",
                )
            }
        }
    }
}

@Composable
private fun GoogleAuthConsole(
    provider: ProviderSetting.Google,
    onEdit: (ProviderSetting.Google) -> Unit,
    onCommit: (ProviderSetting.Google) -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    ProviderPillSeg(
        options = listOf(
            ProviderSegOption(GoogleAuthMode.API_KEY, "API Key"),
            ProviderSegOption(GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH, "OAuth"),
        ),
        selected = provider.authMode,
        onSelected = { mode -> onEdit(provider.switchGoogleAuthMode(mode)) },
        mono = true,
    )

    when (provider.authMode) {
        GoogleAuthMode.API_KEY -> {
            if (!(provider.vertexAI && provider.useServiceAccount)) {
                ProviderLabeledField("API Key") {
                    ProviderSecretField(
                        value = provider.apiKey,
                        onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
                        placeholder = "AIza...",
                    )
                }
            }
            ProviderSwitchRow(
                title = stringResource(R.string.setting_provider_page_vertex_ai),
                checked = provider.vertexAI,
                onCheckedChange = { onEdit(provider.copy(vertexAI = it)) },
            )
            if (provider.vertexAI) {
                ProviderSwitchRow(
                    title = stringResource(R.string.setting_provider_page_use_service_account),
                    checked = provider.useServiceAccount,
                    onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) },
                )
            }
        }
        GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH -> {
            GeminiOAuthConsole(
                provider = provider,
                onCommit = onCommit,
                autoStartOAuth = autoStartOAuth,
                onAutoStartConsumed = onAutoStartConsumed,
            )
        }
    }
}

@Composable
private fun ClaudeAuthConsole(
    provider: ProviderSetting.Claude,
    onEdit: (ProviderSetting.Claude) -> Unit,
) {
    ProviderLabeledField("API Key") {
        ProviderSecretField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
            placeholder = "sk-ant-...",
        )
    }
}

@Composable
private fun ProviderEndpointSection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    ProviderSectionLabel("端点")
    ProviderCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ProviderLabeledField("显示名称") {
                ProviderTextField(
                    value = provider.name,
                    onValueChange = { onEdit(provider.copyProvider(name = it.trim())) },
                    placeholder = "Provider",
                )
            }
            when (provider) {
                is ProviderSetting.OpenAI -> OpenAIEndpointFields(provider, onEdit)
                is ProviderSetting.Google -> GoogleEndpointFields(provider, onEdit)
                is ProviderSetting.Claude -> ClaudeEndpointFields(provider, onEdit)
            }
        }
    }
}

@Composable
private fun OpenAIEndpointFields(
    provider: ProviderSetting.OpenAI,
    onEdit: (ProviderSetting.OpenAI) -> Unit,
) {
    val fixed = provider.authMode == OpenAIAuthMode.CODEX_OAUTH
    ProviderLabeledField("API Base URL") {
        ProviderTextField(
            value = if (fixed) OPENAI_CODEX_BACKEND_BASE_URL else provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            mono = true,
            readOnly = fixed,
        )
    }
    if (!provider.authMode.isCodingPlan() && provider.authMode != OpenAIAuthMode.CODEX_OAUTH) {
        if (!provider.useResponseApi) {
            ProviderLabeledField("API Path") {
                ProviderTextField(
                    value = provider.chatCompletionsPath,
                    onValueChange = { onEdit(provider.copy(chatCompletionsPath = it.trim())) },
                    mono = true,
                    readOnly = provider.builtIn,
                )
            }
        }
        ProviderSwitchRow(
            title = stringResource(R.string.setting_provider_page_response_api),
            checked = provider.useResponseApi,
            onCheckedChange = { onEdit(provider.copy(useResponseApi = it)) },
        )
    }
}

@Composable
private fun GoogleEndpointFields(
    provider: ProviderSetting.Google,
    onEdit: (ProviderSetting.Google) -> Unit,
) {
    val serviceAccountJsonLauncher = rememberGoogleServiceAccountImport(provider, onEdit)

    if (provider.authMode == GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH) {
        ProviderLabeledField("API Base URL") {
            ProviderTextField(
                value = checkNotNull(GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH.fixedBaseUrl()),
                onValueChange = {},
                mono = true,
                readOnly = true,
            )
        }
        return
    }

    if (!provider.vertexAI) {
        ProviderLabeledField("API Base URL") {
            ProviderTextField(
                value = provider.baseUrl,
                onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
                mono = true,
            )
        }
    } else if (provider.useServiceAccount) {
        ProviderActionButton(
            text = stringResource(R.string.setting_provider_page_import_service_account_json),
            onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
        )
        ProviderLabeledField("Service Account Email") {
            ProviderTextField(
                value = provider.serviceAccountEmail,
                onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
                mono = true,
            )
        }
        ProviderLabeledField("Private Key") {
            ProviderSecretField(
                value = provider.privateKey,
                onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
            )
        }
        ProviderLabeledField("Location") {
            ProviderTextField(
                value = provider.location,
                onValueChange = { onEdit(provider.copy(location = it.trim())) },
                mono = true,
            )
        }
        ProviderLabeledField("Project ID") {
            ProviderTextField(
                value = provider.projectId,
                onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
                mono = true,
            )
        }
    }
}

@Composable
private fun ClaudeEndpointFields(
    provider: ProviderSetting.Claude,
    onEdit: (ProviderSetting.Claude) -> Unit,
) {
    ProviderLabeledField("API Base URL") {
        ProviderTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            mono = true,
        )
    }
    ProviderSwitchRow(
        title = stringResource(R.string.setting_provider_page_claude_prompt_caching),
        checked = provider.promptCaching,
        onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
    )
}

@Composable
private fun ProviderAccountSection(
    provider: ProviderSetting.OpenAI,
    onEdit: (ProviderSetting.OpenAI) -> Unit,
) {
    val type = LocalAmberType.current
    ProviderSectionLabel("账户")
    ProviderCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderSwitchRow(
                icon = HugeIcons.Refresh03,
                title = "获取账户余额",
                checked = provider.balanceOption.enabled,
                onCheckedChange = {
                    onEdit(provider.copy(balanceOption = provider.balanceOption.copy(enabled = it)))
                },
            )
            if (provider.balanceOption.enabled) {
                ProviderBalanceText(providerSetting = provider, style = type.meta)
                ProviderLabeledField("API Path") {
                    ProviderTextField(
                        value = provider.balanceOption.apiPath,
                        onValueChange = {
                            onEdit(provider.copy(balanceOption = provider.balanceOption.copy(apiPath = it.trim())))
                        },
                        mono = true,
                    )
                }
                ProviderLabeledField("Result Path") {
                    ProviderTextField(
                        value = provider.balanceOption.resultPath,
                        onValueChange = {
                            onEdit(provider.copy(balanceOption = provider.balanceOption.copy(resultPath = it.trim())))
                        },
                        mono = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderConsoleActions(
    provider: ProviderSetting,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderConnectionTester(internalProvider = provider)
        Spacer(Modifier.weight(1f))
        ProviderIconButton(
            imageVector = HugeIcons.Delete01,
            contentDescription = null,
            tint = LocalAmberTokens.current.ink3,
            onClick = onDelete,
        )
        ProviderIconButton(
            imageVector = HugeIcons.Refresh03,
            contentDescription = null,
            tint = if (provider.isUsingDefaultBaseUrl()) LocalAmberTokens.current.ink4 else LocalAmberTokens.current.ink3,
            onClick = { if (!provider.isUsingDefaultBaseUrl()) onReset() },
        )
        ProviderActionButton(
            text = stringResource(R.string.setting_provider_page_save),
            accent = true,
            onClick = onSave,
        )
    }
}

@Composable
private fun ProviderSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = t.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = type.body,
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProviderToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProviderActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) t.accent else t.surface2)
            .border(1.dp, if (accent) Color.Transparent else t.line, RoundedCornerShape(10.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
            color = if (accent) t.accentInk else t.ink2,
            maxLines = 1,
        )
    }
}

@Composable
private fun CodexOAuthConsole(
    provider: ProviderSetting.OpenAI,
    onCommit: (ProviderSetting.OpenAI) -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val httpClient = koinInject<OkHttpClient>()
    val providerManager = koinInject<ProviderManager>()
    val authStore = remember(context) { OpenAICodexAuthStore(context) }
    val oauthClient = remember(httpClient, authStore) { OpenAICodexOAuthClient(httpClient, authStore) }
    var oauthTokens by remember(provider.id) { mutableStateOf(authStore.get(provider.id)) }
    var oauthBusy by remember(provider.id) { mutableStateOf(false) }
    var oauthDeviceCode by remember(provider.id) { mutableStateOf<String?>(null) }
    var oauthVerificationUrl by remember(provider.id) { mutableStateOf<String?>(null) }

    suspend fun runCodexLogin() {
        if (oauthBusy) return
        oauthBusy = true
        try {
            val authorization = oauthClient.requestDeviceCode()
            oauthDeviceCode = authorization.userCode
            oauthVerificationUrl = authorization.verificationUrl
            context.writeClipboardText(authorization.userCode)
            context.openUrl(authorization.verificationUrl)
            toaster.show(
                context.getString(R.string.setting_provider_page_codex_oauth_code_copied, authorization.userCode),
                type = ToastType.Info,
            )
            oauthTokens = oauthClient.pollDeviceCode(provider.id, authorization)
            oauthDeviceCode = null
            oauthVerificationUrl = null
            val fetchedModels = runCatching {
                providerManager.getProviderByType(provider)
                    .listModels(provider.codexOAuthReadyCopy())
                    .sortedBy { it.modelId }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaultCodexOAuthModelList()
            val newSelection = if (provider.models.withoutCodexReviewModels().isEmpty()) {
                listOfNotNull(fetchedModels.firstOrNull())
            } else {
                provider.models.withoutCodexReviewModels()
            }
            onCommit(provider.copy(models = newSelection))
            toaster.show(
                context.getString(R.string.setting_provider_page_codex_oauth_login_success_with_models, fetchedModels.size),
                type = ToastType.Success,
            )
        } catch (e: Exception) {
            toaster.show(
                context.getString(R.string.setting_provider_page_codex_oauth_login_failed, e.message ?: e.toString()),
                type = ToastType.Error,
            )
        } finally {
            oauthBusy = false
        }
    }

    val latestAutoStart by rememberUpdatedState(autoStartOAuth)
    val latestOnAutoStartConsumed by rememberUpdatedState(onAutoStartConsumed)
    LaunchedEffect(provider.id) {
        if (
            latestAutoStart &&
            provider.authMode == OpenAIAuthMode.CODEX_OAUTH &&
            oauthTokens == null &&
            !oauthBusy
        ) {
            latestOnAutoStartConsumed()
            runCodexLogin()
        }
    }

    ProviderLabeledField("API Base URL") {
        ProviderTextField(
            value = OPENAI_CODEX_BACKEND_BASE_URL,
            onValueChange = {},
            mono = true,
            readOnly = true,
        )
    }
    ProviderMonoNote(
        oauthTokens?.let { tokens ->
            val account = listOfNotNull(tokens.email, tokens.planType).joinToString(" · ")
            if (account.isBlank()) {
                stringResource(R.string.setting_provider_page_codex_oauth_signed_in)
            } else {
                stringResource(R.string.setting_provider_page_codex_oauth_signed_in_as, account)
            }
        } ?: stringResource(R.string.setting_provider_page_codex_oauth_not_signed_in)
    )
    if (oauthDeviceCode != null) {
        ProviderLabeledField(stringResource(R.string.setting_provider_page_codex_oauth_device_code)) {
            ProviderTextField(
                value = oauthDeviceCode.orEmpty(),
                onValueChange = {},
                mono = true,
                readOnly = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderActionButton(
                text = stringResource(R.string.setting_provider_page_codex_oauth_copy_code),
                onClick = {
                    context.writeClipboardText(oauthDeviceCode.orEmpty())
                    toaster.show(
                        context.getString(R.string.setting_provider_page_codex_oauth_code_recopied),
                        type = ToastType.Success,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            ProviderActionButton(
                text = stringResource(R.string.setting_provider_page_codex_oauth_open_page),
                onClick = { oauthVerificationUrl?.let(context::openUrl) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    ProviderActionButton(
        text = stringResource(R.string.setting_provider_page_codex_oauth_login),
        accent = oauthTokens == null,
        onClick = { scope.launch { runCodexLogin() } },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderActionButton(
            text = stringResource(R.string.setting_provider_page_codex_oauth_fetch_models),
            onClick = {
                scope.launch {
                    oauthBusy = true
                    try {
                        val fetchedModels = runCatching {
                            providerManager.getProviderByType(provider)
                                .listModels(provider.codexOAuthReadyCopy())
                                .sortedBy { it.modelId }
                        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaultCodexOAuthModelList()
                        onCommit(provider.copy(models = provider.models.withoutCodexReviewModels()))
                        toaster.show(
                            context.getString(R.string.setting_provider_page_codex_oauth_models_loaded, fetchedModels.size),
                            type = ToastType.Success,
                        )
                    } catch (e: Exception) {
                        toaster.show(
                            context.getString(R.string.setting_provider_page_codex_oauth_models_failed, e.message ?: e.toString()),
                            type = ToastType.Error,
                        )
                    } finally {
                        oauthBusy = false
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        ProviderActionButton(
            text = stringResource(R.string.setting_provider_page_codex_oauth_refresh),
            onClick = {
                scope.launch {
                    oauthBusy = true
                    try {
                        oauthTokens = oauthClient.refresh(provider.id)
                        toaster.show(
                            context.getString(R.string.setting_provider_page_codex_oauth_refresh_success),
                            type = ToastType.Success,
                        )
                    } catch (e: Exception) {
                        toaster.show(
                            context.getString(R.string.setting_provider_page_codex_oauth_refresh_failed, e.message ?: e.toString()),
                            type = ToastType.Error,
                        )
                    } finally {
                        oauthBusy = false
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        ProviderActionButton(
            text = stringResource(R.string.setting_provider_page_codex_oauth_logout),
            onClick = {
                oauthClient.logout(provider.id)
                oauthTokens = null
                oauthDeviceCode = null
                oauthVerificationUrl = null
                toaster.show(
                    context.getString(R.string.setting_provider_page_codex_oauth_logged_out),
                    type = ToastType.Success,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GeminiOAuthConsole(
    provider: ProviderSetting.Google,
    onCommit: (ProviderSetting.Google) -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val geminiAuthStore = koinInject<GoogleGeminiAuthStore>()
    val geminiOAuthClient = koinInject<GoogleGeminiOAuthClient>()
    var geminiTokens by remember(provider.id) {
        mutableStateOf<GoogleGeminiAuthTokens?>(geminiAuthStore.get(provider.id))
    }
    var geminiOAuthBusy by remember(provider.id) { mutableStateOf(false) }

    suspend fun runGeminiLogin() {
        if (geminiOAuthBusy) return
        geminiOAuthBusy = true
        try {
            val tokens = geminiOAuthClient.authorize(context, provider.id)
            geminiTokens = tokens
            val isAllObsolete = provider.models.isNotEmpty() &&
                provider.models.all { it.modelId in OBSOLETE_GEMINI_OAUTH_MODEL_IDS }
            val newSelection = if (provider.models.isEmpty() || isAllObsolete) {
                defaultGeminiOAuthModelList()
            } else {
                provider.models
            }
            onCommit(
                provider.copy(
                    models = newSelection,
                    name = if (provider.name == "Google") "Gemini OAuth" else provider.name,
                )
            )
            toaster.show(
                context.getString(R.string.setting_provider_page_gemini_oauth_login_success, tokens.email ?: "Google 账号"),
                type = ToastType.Success,
            )
        } catch (e: Exception) {
            toaster.show(
                context.getString(R.string.setting_provider_page_gemini_oauth_login_failed, e.message ?: e.toString()),
                type = ToastType.Error,
            )
        } finally {
            geminiOAuthBusy = false
        }
    }

    val latestAutoStart by rememberUpdatedState(autoStartOAuth)
    val latestOnAutoStartConsumed by rememberUpdatedState(onAutoStartConsumed)
    LaunchedEffect(provider.id) {
        if (
            latestAutoStart &&
            provider.authMode == GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH &&
            geminiTokens == null &&
            !geminiOAuthBusy
        ) {
            latestOnAutoStartConsumed()
            runGeminiLogin()
        }
    }

    ProviderLabeledField("API Base URL") {
        ProviderTextField(
            value = checkNotNull(GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH.fixedBaseUrl()),
            onValueChange = {},
            mono = true,
            readOnly = true,
        )
    }
    ProviderMonoNote(
        geminiTokens?.let { tokens ->
            tokens.email?.let { stringResource(R.string.setting_provider_page_gemini_oauth_signed_in_as, it) }
                ?: stringResource(R.string.setting_provider_page_gemini_oauth_signed_in)
        } ?: stringResource(R.string.setting_provider_page_gemini_oauth_not_signed_in)
    )
    ProviderActionButton(
        text = if (geminiTokens != null) {
            stringResource(R.string.setting_provider_page_gemini_oauth_relogin)
        } else {
            stringResource(R.string.setting_provider_page_gemini_oauth_login)
        },
        accent = geminiTokens == null,
        onClick = { scope.launch { runGeminiLogin() } },
        modifier = Modifier.fillMaxWidth(),
    )
    if (geminiTokens != null) {
        ProviderActionButton(
            text = stringResource(R.string.setting_provider_page_gemini_oauth_logout),
            onClick = {
                geminiOAuthClient.logout(provider.id)
                geminiTokens = null
                toaster.show(
                    context.getString(R.string.setting_provider_page_gemini_oauth_logged_out),
                    type = ToastType.Success,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderMonoNote(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text,
        style = type.meta.copy(fontSize = 11.sp),
        color = t.ink3,
    )
}

@Composable
private fun rememberGoogleServiceAccountImport(
    provider: ProviderSetting.Google,
    onEdit: (ProviderSetting.Google) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                        ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                        ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                        ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }
}

private fun ProviderSetting.OpenAI.switchOpenAIAuthMode(mode: OpenAIAuthMode): ProviderSetting.OpenAI {
    val pinned = mode.fixedBaseUrl()
    return when (mode) {
        OpenAIAuthMode.CODEX_OAUTH -> copy(
            authMode = OpenAIAuthMode.CODEX_OAUTH,
            baseUrl = pinned ?: baseUrl,
            useResponseApi = true,
            name = if (name == "OpenAI") "OpenAI Codex OAuth" else name,
        )
        OpenAIAuthMode.ZHIPU_CODING_PLAN,
        OpenAIAuthMode.KIMI_CODING_PLAN,
        OpenAIAuthMode.MIMO_CODING_PLAN,
        OpenAIAuthMode.MINIMAX_TOKEN_PLAN -> copy(authMode = mode, baseUrl = pinned ?: baseUrl)
        OpenAIAuthMode.API_KEY -> {
            val leavingManagedMode = authMode != OpenAIAuthMode.API_KEY
            val restoredBaseUrl = if (leavingManagedMode) {
                (resetBaseUrlToDefault() as ProviderSetting.OpenAI).baseUrl
            } else {
                baseUrl
            }
            copy(
                authMode = OpenAIAuthMode.API_KEY,
                baseUrl = restoredBaseUrl,
                useResponseApi = if (leavingManagedMode) false else useResponseApi,
            )
        }
    }
}

private fun ProviderSetting.Google.switchGoogleAuthMode(mode: GoogleAuthMode): ProviderSetting.Google {
    val pinned = mode.fixedBaseUrl()
    return when (mode) {
        GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH -> copy(
            authMode = mode,
            baseUrl = pinned ?: baseUrl,
            vertexAI = false,
            useServiceAccount = false,
            apiKey = "",
            privateKey = "",
            serviceAccountEmail = "",
            projectId = "",
        )
        GoogleAuthMode.API_KEY -> {
            val knownPinnedUrls = setOfNotNull(GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH.fixedBaseUrl())
            val restoredBaseUrl = if (baseUrl in knownPinnedUrls) {
                (resetBaseUrlToDefault() as ProviderSetting.Google).baseUrl
            } else {
                baseUrl
            }
            copy(authMode = mode, baseUrl = restoredBaseUrl)
        }
    }
}

private fun OpenAIAuthMode.openAIAuthLabel(): String = when (this) {
    OpenAIAuthMode.API_KEY -> "API Key"
    OpenAIAuthMode.CODEX_OAUTH -> "OAuth"
    OpenAIAuthMode.ZHIPU_CODING_PLAN,
    OpenAIAuthMode.KIMI_CODING_PLAN,
    OpenAIAuthMode.MIMO_CODING_PLAN,
    OpenAIAuthMode.MINIMAX_TOKEN_PLAN -> "Token Plan"
}

private fun OpenAIAuthMode.isCodingPlan(): Boolean = this in setOf(
    OpenAIAuthMode.ZHIPU_CODING_PLAN,
    OpenAIAuthMode.KIMI_CODING_PLAN,
    OpenAIAuthMode.MIMO_CODING_PLAN,
    OpenAIAuthMode.MINIMAX_TOKEN_PLAN,
)

private fun ProviderSetting.OpenAI.codexOAuthReadyCopy(): ProviderSetting.OpenAI = copy(
    authMode = OpenAIAuthMode.CODEX_OAUTH,
    baseUrl = OPENAI_CODEX_BACKEND_BASE_URL,
    useResponseApi = true,
)

private fun List<Model>.withoutCodexReviewModels(): List<Model> =
    filterNot { it.isCodexOAuthReviewModel() }
