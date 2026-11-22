package app.amber.feature.ui.pages.setting.provider

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.BalanceResultPath
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.fixedBaseUrl
import app.amber.ai.provider.providers.openai.supportsResponsesResume
import app.amber.agent.R
import app.amber.feature.ui.components.ai.ProviderBalanceText
import app.amber.feature.ui.pages.setting.components.LocalProviderRowInset
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderSecretField
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

/*
 * Row builders for the provider config cards. Each entry is one card row; callers feed the
 * list to ProviderCardRows, which draws the inset hairlines between rows.
 */

internal typealias ProviderRows = MutableList<@Composable () -> Unit>

/** 基本: enable toggle, name, and (for non-OAuth providers) the protocol picker. */
internal fun ProviderRows.addBasicRows(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    showProtocol: Boolean,
) {
    add {
        ProviderSwitchRow(
            title = stringResource(R.string.setting_provider_page_enabled),
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) },
        )
    }
    add {
        ProviderLabeledField(stringResource(R.string.provider_field_name)) {
            ProviderTextField(
                value = provider.name,
                onValueChange = { onEdit(provider.copyProvider(name = it.trim())) },
                placeholder = "Provider",
            )
        }
    }
    if (showProtocol) {
        add {
            ProviderLabeledField(stringResource(R.string.provider_field_protocol)) {
                ProviderConfigCapsule(
                    options = ProviderSetting.Types.map { type ->
                        ProviderSegOption(type, type.simpleName ?: "")
                    },
                    selected = provider::class,
                    onSelected = { type -> onEdit(provider.convertTo(type)) },
                )
            }
        }
    }
}

internal fun ProviderRows.addApiKeyRow(
    apiKey: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    add {
        ProviderLabeledField(stringResource(R.string.provider_field_api_key)) {
            ProviderSecretField(
                value = apiKey,
                onValueChange = { onValueChange(it.trim()) },
                placeholder = placeholder,
            )
        }
    }
}

/** Endpoint rows (base URL / path / protocol switches) for the merged 连接 card. */
internal fun ProviderRows.addEndpointRows(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onImportGoogleServiceAccount: () -> Unit,
) {
    when (provider) {
        is ProviderSetting.OpenAI -> addOpenAIEndpointRows(provider, onEdit)
        is ProviderSetting.Google -> addGoogleEndpointRows(provider, onEdit, onImportGoogleServiceAccount)
        is ProviderSetting.Claude -> addClaudeEndpointRows(provider, onEdit)
    }
}

private fun ProviderRows.addBaseUrlRow(
    value: String,
    fixed: Boolean,
    onValueChange: (String) -> Unit,
) {
    add {
        ProviderLabeledField(
            stringResource(if (fixed) R.string.provider_field_base_url_fixed else R.string.provider_field_base_url),
        ) {
            ProviderTextField(
                value = value,
                onValueChange = { if (!fixed) onValueChange(it.trim()) },
                mono = true,
                readOnly = fixed,
            )
        }
    }
}

private fun ProviderRows.addOpenAIEndpointRows(
    provider: ProviderSetting.OpenAI,
    onEdit: (ProviderSetting.OpenAI) -> Unit,
) {
    // Grok OAuth pins the CLI proxy endpoint exactly like Codex does.
    val fixed = provider.authMode == OpenAIAuthMode.CODEX_OAUTH ||
        provider.authMode == OpenAIAuthMode.CHATGPT_SIWC ||
        provider.authMode == OpenAIAuthMode.GROK_OAUTH
    addBaseUrlRow(
        value = if (fixed) checkNotNull(provider.authMode.fixedBaseUrl()) else provider.baseUrl,
        fixed = fixed,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
    )
    if (!provider.authMode.isCodingPlan() && !fixed) {
        if (!provider.useResponseApi) {
            add {
                ProviderLabeledField(stringResource(R.string.provider_field_api_path)) {
                    ProviderTextField(
                        value = provider.chatCompletionsPath,
                        onValueChange = { onEdit(provider.copy(chatCompletionsPath = it.trim())) },
                        mono = true,
                        readOnly = provider.builtIn,
                    )
                }
            }
        }
        add {
            ProviderSwitchRow(
                title = stringResource(R.string.setting_provider_page_response_api),
                checked = provider.useResponseApi,
                onCheckedChange = { onEdit(provider.copy(useResponseApi = it)) },
            )
        }
        // P6-01: server-side resume is only offered on the strictly matching
        // official configuration (api.openai.com + API key + Responses API);
        // custom endpoints, Codex OAuth and third-party providers never show
        // the switch (plan §P6-01 不适用清单).
        if (provider.supportsResponsesResume()) {
            add {
                ProviderSwitchRow(
                    title = stringResource(R.string.setting_provider_page_responses_resume),
                    checked = provider.enableResponsesResume,
                    onCheckedChange = { onEdit(provider.copy(enableResponsesResume = it)) },
                )
            }
        }
    }
}

private fun ProviderRows.addGoogleEndpointRows(
    provider: ProviderSetting.Google,
    onEdit: (ProviderSetting.Google) -> Unit,
    onImportServiceAccount: () -> Unit,
) {
    if (provider.authMode == GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH || provider.authMode == GoogleAuthMode.ANTIGRAVITY_OAUTH) {
        addBaseUrlRow(
            value = checkNotNull(provider.authMode.fixedBaseUrl()),
            fixed = true,
            onValueChange = {},
        )
        return
    }

    if (!provider.vertexAI) {
        addBaseUrlRow(
            value = provider.baseUrl,
            fixed = false,
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        )
    } else if (provider.useServiceAccount) {
        add {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalProviderRowInset.current, vertical = 4.dp),
            ) {
                ProviderCommandButton(
                    text = stringResource(R.string.setting_provider_page_import_service_account_json),
                    onClick = onImportServiceAccount,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        add {
            ProviderLabeledField("Service Account Email") {
                ProviderTextField(
                    value = provider.serviceAccountEmail,
                    onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
                    mono = true,
                )
            }
        }
        add {
            ProviderLabeledField("Private Key") {
                ProviderSecretField(
                    value = provider.privateKey,
                    onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
                )
            }
        }
        add {
            ProviderLabeledField("Location") {
                ProviderTextField(
                    value = provider.location,
                    onValueChange = { onEdit(provider.copy(location = it.trim())) },
                    mono = true,
                )
            }
        }
        add {
            ProviderLabeledField("Project ID") {
                ProviderTextField(
                    value = provider.projectId,
                    onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
                    mono = true,
                )
            }
        }
    }
}

private fun ProviderRows.addClaudeEndpointRows(
    provider: ProviderSetting.Claude,
    onEdit: (ProviderSetting.Claude) -> Unit,
) {
    addBaseUrlRow(
        value = provider.baseUrl,
        fixed = false,
        onValueChange = { onEdit(provider.copy(baseUrl = it)) },
    )
    add {
        ProviderSwitchRow(
            title = stringResource(R.string.setting_provider_page_claude_prompt_caching),
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) },
        )
    }
}

/** 高级: account-balance query (OpenAI-compatible only). */
internal fun ProviderRows.addBalanceRows(
    provider: ProviderSetting.OpenAI,
    onEdit: (ProviderSetting.OpenAI) -> Unit,
) {
    add {
        ProviderSwitchRow(
            title = stringResource(R.string.setting_provider_page_get_account_balance),
            checked = provider.balanceOption.enabled,
            onCheckedChange = {
                onEdit(provider.copy(balanceOption = provider.balanceOption.copy(enabled = it)))
            },
        )
    }
    if (provider.balanceOption.enabled) {
        add {
            val t = LocalAmberTokens.current
            val type = LocalAmberType.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalProviderRowInset.current, vertical = 12.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                ProviderBalanceText(
                    providerSetting = provider,
                    style = type.meta.copy(color = t.signal),
                )
            }
        }
        add {
            ProviderLabeledField("API Path") {
                ProviderTextField(
                    value = provider.balanceOption.apiPath,
                    onValueChange = {
                        onEdit(provider.copy(balanceOption = provider.balanceOption.copy(apiPath = it.trim())))
                    },
                    mono = true,
                )
            }
        }
        add {
            ProviderLabeledField("Result Path") {
                ProviderTextField(
                    value = provider.balanceOption.resultPath,
                    onValueChange = {
                        onEdit(provider.copy(balanceOption = provider.balanceOption.copy(resultPath = it.trim())))
                    },
                    isError = !BalanceResultPath.isValid(provider.balanceOption.resultPath),
                    mono = true,
                )
            }
        }
    }
}
