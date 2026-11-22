package app.amber.feature.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.amber.agent.R
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.openai.OPENAI_CHATGPT_SIWC_API_BASE_URL
import app.amber.ai.provider.providers.openai.OPENAI_CHATGPT_SIWC_USAGE_URL
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcAuthStore
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcOAuthClient
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcTokens
import app.amber.core.utils.openUrl
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderStatusChip
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.pages.setting.components.ProviderTone
import app.amber.feature.ui.pages.setting.provider.ProviderLabeledField
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
internal fun ChatGptSiwcConsole(
    provider: ProviderSetting.OpenAI,
    onCommit: (ProviderSetting.OpenAI) -> Unit,
    onModelsFetched: (ProviderModelCandidates) -> Unit,
    onModelCandidatesInvalidated: () -> Unit,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val providerCatalog = koinInject<ProviderCatalog>()
    val authStore = koinInject<OpenAIChatGptSiwcAuthStore>()
    val oauthClient = koinInject<OpenAIChatGptSiwcOAuthClient>()
    val lifecycleOwner = LocalLifecycleOwner.current
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    var siwcTokens by remember(provider.id, authStore) { mutableStateOf<OpenAIChatGptSiwcTokens?>(null) }
    var tokensLoaded by remember(provider.id, authStore) { mutableStateOf(false) }
    val tokenReadGeneration = remember(provider.id, authStore) { mutableStateOf(0) }
    suspend fun reloadTokens() {
        val requestGeneration = ++tokenReadGeneration.value
        val cached = withContext(Dispatchers.IO) { authStore.get(provider.id) }
        if (tokenReadGeneration.value == requestGeneration) {
            siwcTokens = cached
            tokensLoaded = true
        }
    }
    LaunchedEffect(provider.id, authStore, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reloadTokens()
        }
    }

    var busy by remember(provider.id) { mutableStateOf(false) }
    var oauthGeneration by remember(provider.id) { mutableStateOf(0) }
    var browserClosed by remember(provider.id) { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    val browser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        browserClosed?.complete(Unit)
    }
    val latestProvider by rememberUpdatedState(provider)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestOnModelsFetched by rememberUpdatedState(onModelsFetched)
    val latestOnModelCandidatesInvalidated by rememberUpdatedState(onModelCandidatesInvalidated)

    fun dismissWelcome(openUsage: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { authStore.markWelcomeShown(provider.id) }
            tokenReadGeneration.value += 1
            siwcTokens = siwcTokens?.copy(welcomeShown = true)
            if (openUsage) context.openUrl(OPENAI_CHATGPT_SIWC_USAGE_URL)
        }
    }

    suspend fun runSiwcLogin() {
        if (busy || !tokensLoaded) return
        tokenReadGeneration.value += 1
        oauthGeneration += 1
        val loginGeneration = oauthGeneration
        latestOnModelCandidatesInvalidated()
        busy = true
        val tabClosed = CompletableDeferred<Unit>()
        browserClosed = tabClosed
        val needsConsent = siwcTokens?.hasDirectUsageScope == false
        try {
            val tokens = withContext(Dispatchers.IO) {
                oauthClient.authorize(
                    providerId = provider.id,
                    forceConsent = needsConsent,
                    browserClosed = tabClosed,
                    openBrowser = { url ->
                        withContext(Dispatchers.Main.immediate) {
                            browser.launch(CustomTabsIntent.Builder().setShowTitle(true).build().intent.setData(url.toUri()))
                        }
                    },
                )
            }
            if (loginGeneration != oauthGeneration) return
            tokenReadGeneration.value += 1
            siwcTokens = tokens
            if (!tokens.hasDirectUsageScope) return

            val readyProvider = latestProvider.chatGptSiwcReadyCopy()
            val fetchedModels = runCatching {
                providerCatalog.text(readyProvider).listModels(readyProvider)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                reloadTokens()
                toaster.show(context.getString(R.string.setting_provider_page_siwc_models_failed, error.message), type = ToastType.Error)
                return
            }
            if (loginGeneration != oauthGeneration) return
            val currentProvider = latestProvider
            if (codexOAuthResultIsCurrent(
                    requestGeneration = loginGeneration,
                    currentGeneration = oauthGeneration,
                    requestKey = readyProvider.modelListRequestKey(),
                    currentProvider = currentProvider,
                )
            ) {
                if (fetchedModels.isNotEmpty()) {
                    latestOnModelsFetched(
                        ProviderModelCandidates(
                            requestKey = readyProvider.modelListRequestKey(),
                            models = fetchedModels,
                        )
                    )
                }
                val newSelection = if (currentProvider.models.isEmpty()) {
                    listOfNotNull(fetchedModels.firstOrNull())
                } else {
                    currentProvider.models
                }
                latestOnCommit(
                    currentProvider.chatGptSiwcReadyCopy().copy(models = newSelection)
                )
                toaster.show(
                    if (fetchedModels.isNotEmpty()) {
                        context.getString(R.string.setting_provider_page_siwc_login_success_with_models, fetchedModels.size)
                    } else {
                        context.getString(R.string.setting_provider_page_siwc_signed_in)
                    },
                    type = ToastType.Success,
                )
            } else {
                toaster.show(
                    context.getString(R.string.setting_provider_page_siwc_signed_in),
                    type = ToastType.Info,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            toaster.show(
                context.getString(R.string.setting_provider_page_siwc_login_failed, e.message ?: e.toString()),
                type = ToastType.Error,
            )
        } finally {
            browserClosed = null
            busy = false
        }
    }

    val latestAutoStart by rememberUpdatedState(autoStartOAuth)
    val latestOnAutoStartConsumed by rememberUpdatedState(onAutoStartConsumed)
    LaunchedEffect(provider.id, tokensLoaded) {
        if (
            latestAutoStart &&
            provider.authMode == OpenAIAuthMode.CHATGPT_SIWC &&
            tokensLoaded &&
            siwcTokens == null &&
            !busy
        ) {
            latestOnAutoStartConsumed()
            runSiwcLogin()
        }
    }

    val planReady = siwcTokens?.isUsable() == true
    val needsConsent = siwcTokens?.hasDirectUsageScope == false

    ProviderLabeledField("API Base URL") {
        ProviderTextField(
            value = OPENAI_CHATGPT_SIWC_API_BASE_URL,
            onValueChange = {},
            mono = true,
            readOnly = true,
        )
    }

    if (planReady || needsConsent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderStatusChip(
                text = stringResource(if (needsConsent) R.string.setting_provider_page_siwc_plan_disabled else R.string.setting_provider_page_siwc_using_plan),
                tone = if (needsConsent) ProviderTone.Neutral else ProviderTone.Ok,
            )
            siwcTokens?.email?.takeIf { it.isNotBlank() }?.let { email ->
                Text(
                    text = email,
                    style = type.secondary,
                    color = t.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        ProviderMonoNote(stringResource(R.string.setting_provider_page_siwc_not_signed_in))
    }

    if (!planReady) {
        ProviderCommandButton(
            text = stringResource(if (needsConsent) R.string.setting_provider_page_siwc_enable_plan else R.string.setting_provider_page_siwc_continue_with_chatgpt),
            accent = true,
            enabled = tokensLoaded && !busy,
            onClick = { scope.launch { runSiwcLogin() } },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_siwc_manage_usage),
                accent = true,
                enabled = tokensLoaded && !busy,
                onClick = { context.openUrl(OPENAI_CHATGPT_SIWC_USAGE_URL) },
                modifier = Modifier.weight(1f),
            )
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_siwc_fetch_models),
                enabled = tokensLoaded && !busy,
                onClick = {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            try {
                                val fetchGeneration = oauthGeneration
                                val readyProvider = latestProvider.chatGptSiwcReadyCopy()
                                val fetchedModels = providerCatalog.text(readyProvider)
                                    .listModels(readyProvider)
                                val currentProvider = latestProvider
                                if (!codexOAuthResultIsCurrent(
                                        requestGeneration = fetchGeneration,
                                        currentGeneration = oauthGeneration,
                                        requestKey = readyProvider.modelListRequestKey(),
                                        currentProvider = currentProvider,
                                    )
                                ) {
                                    return@launch
                                }
                                latestOnModelsFetched(
                                    ProviderModelCandidates(
                                        requestKey = readyProvider.modelListRequestKey(),
                                        models = fetchedModels,
                                    )
                                )
                                if (currentProvider.models.isEmpty() && fetchedModels.isNotEmpty()) {
                                    latestOnCommit(
                                        currentProvider.chatGptSiwcReadyCopy().copy(
                                            models = listOf(fetchedModels.first())
                                        )
                                    )
                                }
                                if (fetchedModels.isEmpty()) {
                                    toaster.show(
                                        context.getString(R.string.setting_provider_page_no_models),
                                        type = ToastType.Info,
                                    )
                                } else {
                                    toaster.show(
                                        context.getString(
                                            R.string.setting_provider_page_siwc_models_loaded,
                                            fetchedModels.size,
                                        ),
                                        type = ToastType.Success,
                                    )
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (e: Exception) {
                                reloadTokens()
                                toaster.show(
                                    context.getString(
                                        R.string.setting_provider_page_siwc_models_failed,
                                        e.message ?: e.toString(),
                                    ),
                                    type = ToastType.Error,
                                )
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (siwcTokens?.isSignedIn == true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_siwc_refresh),
                enabled = planReady && !siwcTokens?.refreshToken.isNullOrBlank() && !busy,
                onClick = {
                    if (!busy) {
                        scope.launch {
                            tokenReadGeneration.value += 1
                            busy = true
                            try {
                                val refreshed = withContext(Dispatchers.IO) { oauthClient.refresh(provider.id) }
                                tokenReadGeneration.value += 1
                                siwcTokens = refreshed
                                toaster.show(
                                    context.getString(R.string.setting_provider_page_siwc_refresh_success),
                                    type = ToastType.Success,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (e: Exception) {
                                reloadTokens()
                                toaster.show(
                                    context.getString(
                                        R.string.setting_provider_page_siwc_refresh_failed,
                                        e.message ?: e.toString(),
                                    ),
                                    type = ToastType.Error,
                                )
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_siwc_logout),
                enabled = tokensLoaded && !busy,
                onClick = {
                    if (!busy) {
                        scope.launch {
                            tokenReadGeneration.value += 1
                            oauthGeneration += 1
                            latestOnModelCandidatesInvalidated()
                            busy = true
                            try {
                                val revokedRemotely = withContext(Dispatchers.IO) {
                                    oauthClient.signOut(provider.id)
                                }
                                tokenReadGeneration.value += 1
                                siwcTokens = null
                                toaster.show(
                                    context.getString(
                                        if (revokedRemotely) {
                                            R.string.setting_provider_page_siwc_logged_out
                                        } else {
                                            R.string.setting_provider_page_siwc_logged_out_local_only
                                        }
                                    ),
                                    type = if (revokedRemotely) ToastType.Success else ToastType.Info,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (planReady && siwcTokens?.welcomeShown == false) {
        ChatGptSiwcWelcomeDialog(
            onDismiss = { dismissWelcome(openUsage = false) },
            onManageUsage = { dismissWelcome(openUsage = true) },
        )
    }
}

@Composable
private fun ChatGptSiwcWelcomeDialog(
    onDismiss: () -> Unit,
    onManageUsage: () -> Unit,
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
                    text = stringResource(R.string.setting_provider_page_siwc_welcome_title),
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = t.ink,
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_siwc_welcome_body),
                    style = type.secondary,
                    color = t.ink3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ProviderCommandButton(
                        text = stringResource(R.string.setting_provider_page_siwc_manage_usage),
                        onClick = onManageUsage,
                        plain = true,
                        modifier = Modifier.weight(1f),
                    )
                    ProviderCommandButton(
                        text = stringResource(R.string.setting_provider_page_siwc_welcome_got_it),
                        onClick = onDismiss,
                        accent = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun ProviderSetting.OpenAI.chatGptSiwcReadyCopy(): ProviderSetting.OpenAI = copy(
    authMode = OpenAIAuthMode.CHATGPT_SIWC,
    baseUrl = OPENAI_CHATGPT_SIWC_API_BASE_URL,
    useResponseApi = true,
    enableResponsesResume = false,
)
