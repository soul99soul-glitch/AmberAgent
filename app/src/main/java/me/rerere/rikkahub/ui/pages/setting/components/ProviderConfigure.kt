package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.PulseGhostButton
import me.rerere.rikkahub.ui.components.ui.PulseTextField
import me.rerere.rikkahub.ui.components.ui.WorkspaceSegmentedChoice
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OPENAI_CODEX_BACKEND_BASE_URL
import me.rerere.ai.provider.providers.isCodexOAuthReviewModel
import me.rerere.ai.provider.providers.openai.OpenAICodexAuthStore
import me.rerere.ai.provider.providers.openai.OpenAICodexOAuthClient
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.writeClipboardText
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        // Type
        if (!provider.builtIn) {
            WorkspaceSegmentedChoice(
                options = ProviderSetting.Types,
                selected = provider::class,
                onSelected = { type -> onEdit(provider.convertTo(type)) },
                label = { Text(it.simpleName ?: "") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // [!] just for debugging
        // Text(JsonInstant.encodeToString(provider), fontSize = 10.sp)

        // Provider Configure
        when (provider) {
            is ProviderSetting.OpenAI -> {
                ProviderConfigureOpenAI(provider, onEdit)
            }

            is ProviderSetting.Google -> {
                ProviderConfigureGoogle(provider, onEdit)
            }

            is ProviderSetting.Claude -> {
                ProviderConfigureClaude(provider, onEdit)
            }
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) {
        return this
    }

    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
    }

    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            balanceOption = this.balanceOption,
            builtIn = this.builtIn,
            description = this.description,
            shortDescription = this.shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl
        )

        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            balanceOption = this.balanceOption,
            builtIn = this.builtIn,
            description = this.description,
            shortDescription = this.shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl
        )

        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            balanceOption = this.balanceOption,
            builtIn = this.builtIn,
            description = this.description,
            shortDescription = this.shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl
        )

        else -> error("Unsupported provider type: $type")
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
        }
    }

    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    }
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> this.copy(baseUrl = defaultBaseUrl)
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    return baseUrl == defaultBaseUrlForReset()
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) {
        return targetDefaultBaseUrl
    }

    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder()
        .encodedPath(convertedPath)
        .build()
        .toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()

    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }

    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") {
        return ""
    }
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

@Composable
private fun ColumnScope.ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit
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

    provider.description()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = {
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    PulseTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = stringResource(R.string.setting_provider_page_openai_auth_mode),
        style = MaterialTheme.typography.labelLarge,
    )
    WorkspaceSegmentedChoice(
        options = OpenAIAuthMode.entries.toList(),
        selected = provider.authMode,
        modifier = Modifier.fillMaxWidth(),
        onSelected = { authMode ->
            when (authMode) {
                OpenAIAuthMode.API_KEY -> onEdit(provider.copy(authMode = OpenAIAuthMode.API_KEY))
                OpenAIAuthMode.CODEX_OAUTH -> onEdit(
                    provider.copy(
                        authMode = OpenAIAuthMode.CODEX_OAUTH,
                        baseUrl = OPENAI_CODEX_BACKEND_BASE_URL,
                        useResponseApi = true,
                        name = if (provider.name == "OpenAI") "OpenAI Codex OAuth" else provider.name,
                    )
                )
            }
        },
        label = { authMode ->
            Text(
                when (authMode) {
                    OpenAIAuthMode.API_KEY -> stringResource(R.string.setting_provider_page_openai_auth_api_key)
                    OpenAIAuthMode.CODEX_OAUTH -> stringResource(R.string.setting_provider_page_openai_auth_codex_oauth)
                }
            )
        },
    )

    if (provider.authMode == OpenAIAuthMode.API_KEY) {
        PulseTextField(
            value = provider.apiKey,
            onValueChange = {
                onEdit(provider.copy(apiKey = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_key))
            },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )

        PulseTextField(
            value = provider.baseUrl,
            onValueChange = {
                onEdit(provider.copy(baseUrl = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_base_url))
            },
            modifier = Modifier.fillMaxWidth(),
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl()
        )

        if (!provider.useResponseApi) {
            PulseTextField(
                value = provider.chatCompletionsPath,
                onValueChange = {
                    onEdit(provider.copy(chatCompletionsPath = it.trim()))
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_api_path))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !provider.builtIn
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(id = R.string.setting_provider_page_response_api), modifier = Modifier.weight(1f))
            val responseAPIWarning = stringResource(id = R.string.setting_provider_page_response_api_warning)
            Checkbox(
                checked = provider.useResponseApi,
                onCheckedChange = {
                    onEdit(provider.copy(useResponseApi = it))

                    if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                        toaster.show(
                            message = responseAPIWarning,
                            type = ToastType.Warning
                        )
                    }
                }
            )
        }
    } else {
        Text(
            text = stringResource(R.string.setting_provider_page_codex_oauth_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PulseTextField(
            value = OPENAI_CODEX_BACKEND_BASE_URL,
            onValueChange = {},
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_base_url))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
        )
        Text(
            text = oauthTokens?.let { tokens ->
                val account = listOfNotNull(tokens.email, tokens.planType).joinToString(" · ")
                if (account.isBlank()) {
                    stringResource(R.string.setting_provider_page_codex_oauth_signed_in)
                } else {
                    stringResource(R.string.setting_provider_page_codex_oauth_signed_in_as, account)
                }
            } ?: stringResource(R.string.setting_provider_page_codex_oauth_not_signed_in),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (oauthDeviceCode != null) {
            Text(
                text = stringResource(R.string.setting_provider_page_codex_oauth_enter_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PulseTextField(
                value = oauthDeviceCode.orEmpty(),
                onValueChange = {},
                label = {
                    Text(stringResource(R.string.setting_provider_page_codex_oauth_device_code))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PulseGhostButton(
                    onClick = {
                        context.writeClipboardText(oauthDeviceCode.orEmpty())
                        toaster.show(
                            context.getString(R.string.setting_provider_page_codex_oauth_code_recopied),
                            type = ToastType.Success,
                        )
                    },
                    text = stringResource(R.string.setting_provider_page_codex_oauth_copy_code),
                    modifier = Modifier.weight(1f),
                )
                PulseGhostButton(
                    onClick = {
                        oauthVerificationUrl?.let(context::openUrl)
                    },
                    text = stringResource(R.string.setting_provider_page_codex_oauth_open_page),
                    modifier = Modifier.weight(1f),
                    enabled = oauthVerificationUrl != null,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PulseGhostButton(
                onClick = {
                    scope.launch {
                        oauthBusy = true
                        try {
                            val authorization = oauthClient.requestDeviceCode()
                            oauthDeviceCode = authorization.userCode
                            oauthVerificationUrl = authorization.verificationUrl
                            context.writeClipboardText(authorization.userCode)
                            context.openUrl(authorization.verificationUrl)
                            toaster.show(
                                context.getString(
                                    R.string.setting_provider_page_codex_oauth_code_copied,
                                    authorization.userCode
                                ),
                                type = ToastType.Info,
                            )
                            oauthTokens = oauthClient.pollDeviceCode(provider.id, authorization)
                            oauthDeviceCode = null
                            oauthVerificationUrl = null
                            val modelCount = runCatching {
                                val fetchedModels = providerManager.getProviderByType(provider)
                                    .listModels(provider.codexOAuthReadyCopy())
                                    .sortedBy { it.modelId }
                                onEdit(provider.copy(models = provider.models.withoutCodexReviewModels().mergeByModelId(fetchedModels)))
                                fetchedModels.size
                            }.getOrNull()
                            toaster.show(
                                if (modelCount != null) {
                                    context.getString(R.string.setting_provider_page_codex_oauth_login_success_with_models, modelCount)
                                } else {
                                    context.getString(R.string.setting_provider_page_codex_oauth_login_success)
                                },
                                type = ToastType.Success,
                            )
                        } catch (e: Exception) {
                            toaster.show(
                                context.getString(
                                    R.string.setting_provider_page_codex_oauth_login_failed,
                                    e.message ?: e.toString()
                                ),
                                type = ToastType.Error,
                            )
                        } finally {
                            oauthBusy = false
                        }
                    }
                },
                text = stringResource(R.string.setting_provider_page_codex_oauth_login),
                modifier = Modifier.fillMaxWidth(),
                enabled = !oauthBusy,
            )
            PulseGhostButton(
                onClick = {
                    scope.launch {
                        oauthBusy = true
                        try {
                            val fetchedModels = providerManager.getProviderByType(provider)
                                .listModels(provider.codexOAuthReadyCopy())
                                .sortedBy { it.modelId }
                            onEdit(provider.copy(models = provider.models.withoutCodexReviewModels().mergeByModelId(fetchedModels)))
                            toaster.show(
                                context.getString(
                                    R.string.setting_provider_page_codex_oauth_models_loaded,
                                    fetchedModels.size
                                ),
                                type = ToastType.Success,
                            )
                        } catch (e: Exception) {
                            toaster.show(
                                context.getString(
                                    R.string.setting_provider_page_codex_oauth_models_failed,
                                    e.message ?: e.toString()
                                ),
                                type = ToastType.Error,
                            )
                        } finally {
                            oauthBusy = false
                        }
                    }
                },
                text = stringResource(R.string.setting_provider_page_codex_oauth_fetch_models),
                modifier = Modifier.fillMaxWidth(),
                enabled = !oauthBusy && oauthTokens != null,
            )
            PulseGhostButton(
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
                                context.getString(
                                    R.string.setting_provider_page_codex_oauth_refresh_failed,
                                    e.message ?: e.toString()
                                ),
                                type = ToastType.Error,
                            )
                        } finally {
                            oauthBusy = false
                        }
                    }
                },
                text = stringResource(R.string.setting_provider_page_codex_oauth_refresh),
                modifier = Modifier.fillMaxWidth(),
                enabled = !oauthBusy && oauthTokens != null,
            )
            PulseGhostButton(
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
                text = stringResource(R.string.setting_provider_page_codex_oauth_logout),
                modifier = Modifier.fillMaxWidth(),
                enabled = !oauthBusy && oauthTokens != null,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.setting_provider_page_codex_oauth_text_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ProviderSetting.OpenAI.codexOAuthReadyCopy(): ProviderSetting.OpenAI {
    return copy(
        authMode = OpenAIAuthMode.CODEX_OAUTH,
        baseUrl = OPENAI_CODEX_BACKEND_BASE_URL,
        useResponseApi = true,
    )
}

private fun List<Model>.mergeByModelId(models: List<Model>): List<Model> {
    val existingIds = map { it.modelId }.toMutableSet()
    return this + models.filter { existingIds.add(it.modelId) }
}

private fun List<Model>.withoutCodexReviewModels(): List<Model> {
    return filterNot { it.isCodexOAuthReviewModel() }
}

@Composable
private fun ColumnScope.ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit
) {
    provider.description()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = {
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    PulseTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    PulseTextField(
        value = provider.apiKey,
        onValueChange = {
            onEdit(provider.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    PulseTextField(
        value = provider.baseUrl,
        onValueChange = {
            onEdit(provider.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl()
    )

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(id = R.string.setting_provider_page_claude_prompt_caching),
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = provider.promptCaching,
            onCheckedChange = {
                onEdit(provider.copy(promptCaching = it))
            }
        )
    }
}

@Composable
private fun ColumnScope.ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
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

    provider.description()

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_enable), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.enabled,
            onCheckedChange = {
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    PulseTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_vertex_ai), modifier = Modifier.weight(1f))
        Checkbox(
            checked = provider.vertexAI,
            onCheckedChange = {
                onEdit(provider.copy(vertexAI = it))
            }
        )
    }

    if (!(provider.vertexAI && provider.useServiceAccount)) {
        PulseTextField(
            value = provider.apiKey,
            onValueChange = {
                onEdit(provider.copy(apiKey = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_key))
            },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
    }

    if (!provider.vertexAI) {
        PulseTextField(
            value = provider.baseUrl,
            onValueChange = {
                onEdit(provider.copy(baseUrl = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_base_url))
            },
            modifier = Modifier.fillMaxWidth(),
            isError = provider.baseUrl.isNotBlank() && (
                !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
            ),
            supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                {
                    Text("The base URL usually ends with `/v1beta`")
                }
            } else null
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(id = R.string.setting_provider_page_use_service_account),
                modifier = Modifier.weight(1f)
            )
            Checkbox(
                checked = provider.useServiceAccount,
                onCheckedChange = {
                    onEdit(provider.copy(useServiceAccount = it))
                }
            )
        }

        if (provider.useServiceAccount) {
            PulseGhostButton(
                onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.setting_provider_page_import_service_account_json),
            )
            PulseTextField(
                value = provider.serviceAccountEmail,
                onValueChange = {
                    onEdit(provider.copy(serviceAccountEmail = it.trim()))
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_service_account_email))
                },
                modifier = Modifier.fillMaxWidth()
            )
            PulseTextField(
                value = provider.privateKey,
                onValueChange = {
                    onEdit(provider.copy(privateKey = it.trim()))
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_private_key))
                },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
                minLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            )
            PulseTextField(
                value = provider.location,
                onValueChange = {
                    onEdit(provider.copy(location = it.trim()))
                },
                label = {
                    // https://cloud.google.com/vertex-ai/generative-ai/docs/learn/locations#available-regions
                    Text(stringResource(id = R.string.setting_provider_page_location))
                },
                modifier = Modifier.fillMaxWidth()
            )
            PulseTextField(
                value = provider.projectId,
                onValueChange = {
                    onEdit(provider.copy(projectId = it.trim()))
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_project_id))
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
