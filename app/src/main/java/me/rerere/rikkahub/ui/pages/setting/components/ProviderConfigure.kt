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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.GoogleAuthMode
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.OpenAIBrand
import me.rerere.ai.provider.availableAuthModes
import me.rerere.ai.provider.fixedBaseUrl
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OPENAI_CODEX_BACKEND_BASE_URL
import me.rerere.ai.provider.providers.defaultCodexOAuthModelList
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
    /**
     * Picker passes `true` when the user picked an OAuth quick-start row. The editor's
     * OpenAI / Google subforms react by firing their OAuth handler in a LaunchedEffect on
     * first composition, so the user lands directly in the device-code / browser flow
     * instead of having to click the auth-mode segmented row themselves. Default `false`
     * for the "edit existing provider" call sites where the user is just tweaking fields.
     *
     * Must be paired with [onAutoStartConsumed]: a one-shot semantic. Without consumption,
     * a user type-switch (OpenAI → Google → OpenAI) would destroy & recreate
     * ProviderConfigureOpenAI, its LaunchedEffect would re-enter with the same true flag,
     * and a fresh device-code request would fire unprompted. The callback writes the flag
     * back to `false` in the parent's state the moment the auto-fire decision is taken,
     * so the second composition sees `false` via rememberUpdatedState and stays idle.
     */
    autoStartOAuth: Boolean = false,
    /** Invoked exactly once by the subform's LaunchedEffect when it commits to running
     *  the OAuth auto-flow. Parent uses it to clear its `autoStartOAuth` state. See the
     *  [autoStartOAuth] docstring for the lifecycle reason. */
    onAutoStartConsumed: () -> Unit = {},
    /**
     * Immediate top-level commit — used by **async outcomes the user can't undo**, like a
     * successful Codex OAuth login or a `listModels` refresh. Without this, those results
     * only live in the local `internalProvider` and the user has to remember to hit Save
     * before switching tabs, otherwise the Models tab shows blank.
     *
     * Null = no separate commit channel; commit calls fall through to [onEdit].
     * Placed BEFORE `onEdit` so callers using trailing-lambda syntax (`ProviderConfigure(p) {}`)
     * still bind that lambda to `onEdit`, not `onCommit`.
     */
    onCommit: ((provider: ProviderSetting) -> Unit)? = null,
    /**
     * Local edit — usually wired to `internalProvider = it` so the user can keep tweaking
     * fields before pressing the Save button.
     */
    onEdit: (provider: ProviderSetting) -> Unit,
) {
    val effectiveCommit = onCommit ?: onEdit
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        // Type
        if (!provider.builtIn) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                ProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderSetting.Types.size
                        ),
                        label = {
                            Text(type.simpleName ?: "")
                        },
                        selected = provider::class == type,
                        onClick = {
                            onEdit(provider.convertTo(type))
                        }
                    )
                }
            }
        }

        // [!] just for debugging
        // Text(JsonInstant.encodeToString(provider), fontSize = 10.sp)

        // Provider Configure
        when (provider) {
            is ProviderSetting.OpenAI -> {
                ProviderConfigureOpenAI(
                    provider = provider,
                    onEdit = onEdit,
                    onCommit = { effectiveCommit(it) },
                    autoStartOAuth = autoStartOAuth,
                    onAutoStartConsumed = onAutoStartConsumed,
                )
            }

            is ProviderSetting.Google -> {
                ProviderConfigureGoogle(
                    provider = provider,
                    onEdit = onEdit,
                    autoStartOAuth = autoStartOAuth,
                    onAutoStartConsumed = onAutoStartConsumed,
                )
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
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit,
    /** Top-level commit, used by Codex OAuth login / model refresh — see ProviderConfigure docs. */
    onCommit: (provider: ProviderSetting.OpenAI) -> Unit = onEdit,
    /** Picker's "Sign in with ChatGPT" row sets this true so the OAuth flow runs the
     *  moment the editor opens, without forcing the user to also click the in-form
     *  login button. See [ProviderConfigure] docs. */
    autoStartOAuth: Boolean = false,
    /** One-shot reset callback — see [ProviderConfigure] docs. */
    onAutoStartConsumed: () -> Unit = {},
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

    // OAuth body shared between the in-form "Sign in" button and the picker's
    // autoStartOAuth path.
    //
    // Reentrancy safety: both call sites read `oauthBusy` before any suspension point;
    // Compose dispatches button onClick and LaunchedEffect bodies on Main, so the
    // read-then-write pair runs atomically within a single dispatch turn. Concurrent
    // double-trigger on Main is therefore impossible — the second caller sees
    // `oauthBusy == true` and short-circuits.
    //
    // Cancel-on-dispose: the lambda may suspend inside `pollDeviceCode` (long poll up
    // to ~15 minutes). If the user closes the dialog or switches provider type during
    // that wait, ProviderConfigureOpenAI leaves composition, `rememberCoroutineScope`
    // tears down launched coroutines, and the LaunchedEffect parent is cancelled — the
    // final `onCommit(provider.copy(...))` line below is therefore never reached with
    // a stale `provider` snapshot.
    val runCodexLogin: suspend () -> Unit = body@{
        if (oauthBusy) return@body
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
            // listModels MAY throw (network, OAuth race, server side problems).
            // Fall back to bundled defaults so the candidate pool is never empty.
            val fetchedModels = runCatching {
                providerManager.getProviderByType(provider)
                    .listModels(provider.codexOAuthReadyCopy())
                    .sortedBy { it.modelId }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaultCodexOAuthModelList()
            // First-login: pick the first fetched model so the Models tab isn't empty.
            // Subsequent logins / refreshes: leave selection alone (user-driven).
            val newSelection = if (provider.models.withoutCodexReviewModels().isEmpty()) {
                listOfNotNull(fetchedModels.firstOrNull())
            } else {
                provider.models.withoutCodexReviewModels()
            }
            onCommit(provider.copy(models = newSelection))
            toaster.show(
                context.getString(
                    R.string.setting_provider_page_codex_oauth_login_success_with_models,
                    fetchedModels.size
                ),
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

    // Auto-fire when picker said "the user wants OAuth".
    //
    // Keyed only on `provider.id` (not on `autoStartOAuth`) so the LaunchedEffect's
    // own coroutine doesn't get cancelled the moment we consume the flag. We commit
    // to running the flow right away, fire `onAutoStartConsumed` to flip the parent's
    // state to false, then carry on with `runCodexLogin()` in the same coroutine.
    //
    // The `latestAutoStart` indirection lets a recomposition (e.g. user typed into
    // the name field while polling) update the value without restarting the effect.
    // If the user type-switches to Google and back, ProviderConfigureOpenAI is
    // destroyed → recreated; the new LaunchedEffect enters fresh and sees
    // `latestAutoStart == false` (already consumed in the parent) → stays idle.
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

    OutlinedTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth(),
    )

    // Segment buttons for auth modes — filtered by the provider's brand. A DeepSeek provider
    // doesn't show "Codex OAuth"; a Kimi provider shows "Coding Plan" instead. Hide the row
    // entirely when the brand has only one available mode (saves a redundant row of UI).
    val availableAuthModes = provider.brand.availableAuthModes()
    if (availableAuthModes.size > 1) {
        Text(
            text = stringResource(R.string.setting_provider_page_openai_auth_mode),
            style = MaterialTheme.typography.labelLarge,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            availableAuthModes.forEachIndexed { index, authMode ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = availableAuthModes.size,
                    ),
                    label = {
                        Text(
                            when (authMode) {
                                OpenAIAuthMode.API_KEY -> stringResource(R.string.setting_provider_page_openai_auth_api_key)
                                OpenAIAuthMode.CODEX_OAUTH -> stringResource(R.string.setting_provider_page_openai_auth_codex_oauth)
                                OpenAIAuthMode.ZHIPU_CODING_PLAN,
                                OpenAIAuthMode.KIMI_CODING_PLAN,
                                OpenAIAuthMode.MIMO_CODING_PLAN -> stringResource(R.string.setting_provider_page_openai_auth_coding_plan)
                            }
                        )
                    },
                    selected = provider.authMode == authMode,
                    onClick = {
                        // For modes with a fixed base URL (Codex / Coding Plans), pin baseUrl
                        // and let the runtime use that. API_KEY restores the brand's user-
                        // editable default — but only when the *current* baseUrl is itself a
                        // pinned one (i.e. the user is leaving a Codex/Coding-Plan mode). If
                        // they had a custom baseUrl typed in for vanilla API_KEY use, we leave
                        // it alone so we don't clobber their edit.
                        val pinned = authMode.fixedBaseUrl()
                        val knownPinnedUrls = setOf(
                            OpenAIAuthMode.CODEX_OAUTH.fixedBaseUrl(),
                            OpenAIAuthMode.ZHIPU_CODING_PLAN.fixedBaseUrl(),
                            OpenAIAuthMode.KIMI_CODING_PLAN.fixedBaseUrl(),
                            OpenAIAuthMode.MIMO_CODING_PLAN.fixedBaseUrl(),
                        )
                        val newProvider = when (authMode) {
                            OpenAIAuthMode.CODEX_OAUTH -> provider.copy(
                                authMode = OpenAIAuthMode.CODEX_OAUTH,
                                baseUrl = pinned ?: provider.baseUrl,
                                useResponseApi = true,
                                name = if (provider.name == "OpenAI") "OpenAI Codex OAuth" else provider.name,
                            )
                            OpenAIAuthMode.ZHIPU_CODING_PLAN,
                            OpenAIAuthMode.KIMI_CODING_PLAN,
                            OpenAIAuthMode.MIMO_CODING_PLAN -> provider.copy(
                                authMode = authMode,
                                baseUrl = pinned ?: provider.baseUrl,
                            )
                            OpenAIAuthMode.API_KEY -> {
                                val leavingPinnedMode = provider.baseUrl in knownPinnedUrls
                                val restoredBaseUrl = if (leavingPinnedMode) {
                                    (provider.resetBaseUrlToDefault() as ProviderSetting.OpenAI).baseUrl
                                } else {
                                    provider.baseUrl
                                }
                                // CODEX_OAUTH force-sets `useResponseApi = true` on entry.
                                // When the user switches back to plain API_KEY we have to
                                // unset it, otherwise the Response API checkbox stays ticked
                                // for non-OpenAI hosts where the protocol isn't supported and
                                // chats fail with no obvious cause.
                                val restoredUseResponseApi = if (leavingPinnedMode) {
                                    false
                                } else {
                                    provider.useResponseApi
                                }
                                provider.copy(
                                    authMode = OpenAIAuthMode.API_KEY,
                                    baseUrl = restoredBaseUrl,
                                    useResponseApi = restoredUseResponseApi,
                                )
                            }
                        }
                        onEdit(newProvider)
                    },
                )
            }
        }
    } else if (provider.authMode !in availableAuthModes) {
        // Stored authMode is no longer valid for this brand (e.g. user-defined provider that
        // somehow has CODEX_OAUTH stored from an older build). Silently reset to API_KEY so the
        // form doesn't render in a broken state.
        LaunchedEffect(provider.id, provider.brand) {
            onEdit(provider.copy(authMode = OpenAIAuthMode.API_KEY))
        }
    }

    val isCodingPlan = provider.authMode in setOf(
        OpenAIAuthMode.ZHIPU_CODING_PLAN,
        OpenAIAuthMode.KIMI_CODING_PLAN,
        OpenAIAuthMode.MIMO_CODING_PLAN,
    )
    if (provider.authMode == OpenAIAuthMode.API_KEY || isCodingPlan) {
        OutlinedTextField(
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

        // baseUrl: editable for plain API_KEY, read-only when pinned by a Coding Plan mode.
        // Without the read-only state the user could clobber the brand-specific URL and the
        // request would silently 404 with no obvious cause.
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = {
                if (!isCodingPlan) onEdit(provider.copy(baseUrl = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_base_url))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isCodingPlan,
            isError = !isCodingPlan && provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl()
        )

        // chatCompletionsPath / useResponseApi only relevant for vanilla API_KEY mode. Coding
        // Plans hit a fixed endpoint, no need to expose the protocol knobs.
        if (!isCodingPlan) {
            if (!provider.useResponseApi) {
                OutlinedTextField(
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
        }
    } else {
        Text(
            text = stringResource(R.string.setting_provider_page_codex_oauth_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
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
            OutlinedTextField(
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
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.writeClipboardText(oauthDeviceCode.orEmpty())
                        toaster.show(
                            context.getString(R.string.setting_provider_page_codex_oauth_code_recopied),
                            type = ToastType.Success,
                        )
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_codex_oauth_copy_code))
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        oauthVerificationUrl?.let(context::openUrl)
                    },
                    enabled = oauthVerificationUrl != null,
                ) {
                    Text(stringResource(R.string.setting_provider_page_codex_oauth_open_page))
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { runCodexLogin() } },
                enabled = !oauthBusy,
            ) {
                Text(stringResource(R.string.setting_provider_page_codex_oauth_login))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        oauthBusy = true
                        try {
                            // Same fallback rationale as the login button above — guarantee a
                            // non-empty model list lands in provider.models even if listModels fails.
                            val fetchedModels = runCatching {
                                providerManager.getProviderByType(provider)
                                    .listModels(provider.codexOAuthReadyCopy())
                                    .sortedBy { it.modelId }
                            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaultCodexOAuthModelList()
                            // Refresh: only filter out review models from the user's selection,
                            // do NOT auto-add any of the fetched ones. Refresh updates the
                            // candidate pool (modelList in ModelList composable, also driven by
                            // listModels) — selection stays in the user's hands.
                            onCommit(provider.copy(models = provider.models.withoutCodexReviewModels()))
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
                enabled = !oauthBusy && oauthTokens != null,
            ) {
                Text(stringResource(R.string.setting_provider_page_codex_oauth_fetch_models))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
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
                enabled = !oauthBusy && oauthTokens != null,
            ) {
                Text(stringResource(R.string.setting_provider_page_codex_oauth_refresh))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
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
                enabled = !oauthBusy && oauthTokens != null,
            ) {
                Text(stringResource(R.string.setting_provider_page_codex_oauth_logout))
            }
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

    OutlinedTextField(
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

    OutlinedTextField(
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

    OutlinedTextField(
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
    onEdit: (provider: ProviderSetting.Google) -> Unit,
    /** Picker's "Sign in with Google" row sets this true; see [ProviderConfigure] docs. */
    autoStartOAuth: Boolean = false,
    /** One-shot reset callback paired with [autoStartOAuth]; see [ProviderConfigure] docs. */
    onAutoStartConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    // Commit #2 stub: real OAuth flow (loopback HTTP server, PKCE, refresh, cloudcode-pa
    // onboarding) lands in commit #3. Until then both the "Sign in with Google" button and
    // the autoStartOAuth LaunchedEffect just toast and clear the one-shot flag.
    val oauthPendingToast = stringResource(R.string.setting_provider_page_gemini_oauth_pending_toast)
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

    OutlinedTextField(
        value = provider.name,
        onValueChange = {
            onEdit(provider.copy(name = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_name))
        },
        modifier = Modifier.fillMaxWidth()
    )

    // Auth mode segmented row — mirrors the OpenAI side. API_KEY keeps the existing
    // Generative Language API key / Vertex AI / service-account fields visible.
    // GEMINI_CODE_ASSIST_OAUTH pins baseUrl to cloudcode-pa, hides the API-key fields
    // and exposes a Google sign-in button. Switching modes shuffles baseUrl back to
    // the per-mode pinned value, matching how OpenAIAuthMode swaps it on toggle.
    val availableGoogleAuthModes = GoogleAuthMode.entries
    Text(
        text = stringResource(R.string.setting_provider_page_google_auth_mode),
        style = MaterialTheme.typography.labelLarge,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        availableGoogleAuthModes.forEachIndexed { index, mode ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = availableGoogleAuthModes.size,
                ),
                label = {
                    Text(
                        when (mode) {
                            GoogleAuthMode.API_KEY -> stringResource(R.string.setting_provider_page_google_auth_api_key)
                            GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH ->
                                stringResource(R.string.setting_provider_page_google_auth_oauth)
                        }
                    )
                },
                selected = provider.authMode == mode,
                onClick = {
                    val pinned = mode.fixedBaseUrl()
                    val knownPinnedUrls = setOfNotNull(
                        GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH.fixedBaseUrl(),
                    )
                    val newProvider = when (mode) {
                        GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH -> provider.copy(
                            authMode = mode,
                            baseUrl = pinned ?: provider.baseUrl,
                            // OAuth path doesn't go through Vertex AI; force-off both
                            // sub-toggles so the API_KEY fields don't bleed visual state
                            // into OAuth mode when the user toggles back.
                            vertexAI = false,
                            useServiceAccount = false,
                            // Scrub API_KEY-mode secrets when switching to OAuth so a
                            // subsequent sync/backup snapshot doesn't carry stale
                            // plaintext credentials the user thinks they've moved off of.
                            // Reviewer flag H2: provider.copy() without these clears
                            // would persist whatever the user pasted in API_KEY mode
                            // into the Serializable JSON forever.
                            apiKey = "",
                            privateKey = "",
                            serviceAccountEmail = "",
                            projectId = "",
                        )
                        GoogleAuthMode.API_KEY -> {
                            val leavingPinnedMode = provider.baseUrl in knownPinnedUrls
                            val restoredBaseUrl = if (leavingPinnedMode) {
                                (provider.resetBaseUrlToDefault() as ProviderSetting.Google).baseUrl
                            } else {
                                provider.baseUrl
                            }
                            provider.copy(
                                authMode = mode,
                                baseUrl = restoredBaseUrl,
                            )
                        }
                    }
                    onEdit(newProvider)
                },
            )
        }
    }

    if (provider.authMode == GoogleAuthMode.API_KEY) {
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
            OutlinedTextField(
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
            OutlinedTextField(
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
                OutlinedButton(
                    onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.setting_provider_page_import_service_account_json))
                }
                OutlinedTextField(
                    value = provider.serviceAccountEmail,
                    onValueChange = {
                        onEdit(provider.copy(serviceAccountEmail = it.trim()))
                    },
                    label = {
                        Text(stringResource(id = R.string.setting_provider_page_service_account_email))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
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
                OutlinedTextField(
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
                OutlinedTextField(
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
    } else {
        // OAuth mode skeleton — buttons stub until commit #3 lands the real flow.
        Text(
            text = stringResource(R.string.setting_provider_page_gemini_oauth_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = checkNotNull(GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH.fixedBaseUrl()),
            onValueChange = {},
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_base_url))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
        )
        Text(
            text = stringResource(R.string.setting_provider_page_gemini_oauth_not_signed_in),
            style = MaterialTheme.typography.bodyMedium,
        )
        // Button is disabled while the OAuth implementation is pending (commit #3).
        // Reviewer flag M3: an enabled button that just toasts "implementation pending"
        // invites repeat-clicks; disable + a "Coming soon" supporting line is more
        // honest. The toaster is still kept around for the autoStart auto-fire path
        // below — that fires once on entry, not on user action, so the same anti-
        // repeat-click argument doesn't apply.
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setting_provider_page_gemini_oauth_login))
        }
        Text(
            text = stringResource(R.string.setting_provider_page_gemini_oauth_pending_toast),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.setting_provider_page_gemini_oauth_tos),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Same one-shot pattern as the OpenAI side: LaunchedEffect keyed only on
        // provider.id, autoStartOAuth read via rememberUpdatedState so the parent's
        // post-consume reset doesn't cancel this coroutine. Stub for now — commit #3
        // will replace the toast with the actual OAuth flow.
        val latestAutoStart by rememberUpdatedState(autoStartOAuth)
        val latestOnAutoStartConsumed by rememberUpdatedState(onAutoStartConsumed)
        LaunchedEffect(provider.id) {
            if (
                latestAutoStart &&
                provider.authMode == GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH
            ) {
                latestOnAutoStartConsumed()
                toaster.show(oauthPendingToast, type = ToastType.Info)
            }
        }
    }
}
