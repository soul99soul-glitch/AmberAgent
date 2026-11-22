package app.amber.feature.ui.pages.setting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.fixedBaseUrl
import app.amber.agent.R
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.launch
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import kotlin.uuid.Uuid

@Composable
fun ProviderTemplatePickerSheet(
    existingProviderIds: Set<Uuid>,
    onDismiss: () -> Unit,
    onPick: (initial: ProviderSetting, autoStartOAuth: Boolean) -> Unit,
) {
    val t = LocalAmberTokens.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val bundledTemplates = remember(existingProviderIds) {
        DEFAULT_PROVIDERS.filter { it.id !in existingProviderIds }
    }
    var picking by remember { mutableStateOf(false) }

    val closeAndPick: (ProviderSetting, Boolean) -> Unit = pick@{ initial, autoStart ->
        if (picking) return@pick
        picking = true
        scope.launch {
            sheetState.hide()
            onPick(initial, autoStart)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = t.raised,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            ProviderSheetHeader(stringResource(R.string.setting_provider_page_add_provider))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                item("section-oauth") {
                    ProviderSectionLabel(stringResource(R.string.setting_provider_page_template_section_oauth))
                }
                item("oauth-card") {
                    ProviderCard(modifier = Modifier.fillMaxWidth()) {
                        ProviderTemplateRow(
                            name = stringResource(R.string.setting_provider_page_template_oauth_siwc_title),
                            subtitle = stringResource(R.string.setting_provider_page_template_oauth_siwc_subtitle),
                            monogram = "CG",
                            tagText = "official",
                            onClick = { closeAndPick(buildChatGptSiwcInitial(), true) },
                        )
                        ProviderRowDivider(inset = TemplateRowDividerInset)
                        ProviderTemplateRow(
                            name = stringResource(R.string.setting_provider_page_template_oauth_codex_title),
                            subtitle = stringResource(R.string.setting_provider_page_template_oauth_codex_subtitle),
                            monogram = "OC",
                            tagText = "oauth",
                            onClick = { closeAndPick(buildCodexOAuthInitial(), true) },
                        )
                        ProviderRowDivider(inset = TemplateRowDividerInset)
                        ProviderTemplateRow(
                            name = stringResource(R.string.setting_provider_page_template_oauth_gemini_title),
                            subtitle = stringResource(R.string.setting_provider_page_template_oauth_gemini_subtitle),
                            monogram = "Gemi",
                            tagText = "oauth",
                            onClick = { closeAndPick(buildGeminiOAuthInitial(), true) },
                        )
                    }
                }

                if (bundledTemplates.isNotEmpty()) {
                    item("section-bundled") {
                        ProviderSectionLabel(stringResource(R.string.setting_provider_page_template_section_bundled))
                    }
                    itemsIndexed(bundledTemplates, key = { _, template -> "bundled-${template.id}" }) { index, template ->
                        ProviderGroupSegment(
                            first = index == 0,
                            last = index == bundledTemplates.lastIndex,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (index > 0) ProviderRowDivider(inset = TemplateRowDividerInset)
                            ProviderTemplateRow(
                                name = template.name,
                                subtitle = template.providerHostLabel(),
                                subtitleMono = true,
                                monogram = template.name.toProviderMonogram(),
                                tagText = template.brandTagText(),
                                onClick = { closeAndPick(template.copyProvider(), false) },
                            )
                        }
                    }
                }

                item("section-custom") {
                    ProviderSectionLabel(stringResource(R.string.setting_provider_page_template_section_custom))
                }
                item("custom-card") {
                    ProviderCard(modifier = Modifier.fillMaxWidth()) {
                        ProviderTemplateRow(
                            name = stringResource(R.string.setting_provider_page_template_custom_blank_title),
                            subtitle = stringResource(R.string.setting_provider_page_template_custom_blank_subtitle),
                            leadingIcon = Lucide.SlidersHorizontal,
                            tagText = "custom",
                            onClick = { closeAndPick(ProviderSetting.OpenAI(), false) },
                        )
                    }
                }
            }
        }
    }
}

/** 14dp row inset + 36dp tile + 12dp gap. */
private val TemplateRowDividerInset = 62.dp

internal fun buildChatGptSiwcInitial(): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
    id = Uuid.random(),
    name = "ChatGPT",
    baseUrl = OpenAIAuthMode.CHATGPT_SIWC.fixedBaseUrl()!!,
    apiKey = "",
    useResponseApi = true,
    enableResponsesResume = false,
    authMode = OpenAIAuthMode.CHATGPT_SIWC,
    brand = OpenAIBrand.OPENAI,
)

internal fun buildCodexOAuthInitial(): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
    id = Uuid.random(),
    name = "OpenAI Codex OAuth",
    baseUrl = OpenAIAuthMode.CODEX_OAUTH.fixedBaseUrl()!!,
    apiKey = "",
    useResponseApi = true,
    authMode = OpenAIAuthMode.CODEX_OAUTH,
    brand = OpenAIBrand.OPENAI,
)

private fun buildGeminiOAuthInitial(): ProviderSetting.Google = ProviderSetting.Google(
    id = Uuid.random(),
    name = "Gemini OAuth",
    baseUrl = GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH.fixedBaseUrl()!!,
    apiKey = "",
    vertexAI = false,
    useServiceAccount = false,
    authMode = GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH,
)

@Composable
private fun ProviderTemplateRow(
    name: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    monogram: String = name.toProviderMonogram(),
    tagText: String? = null,
    leadingIcon: ImageVector? = null,
) {
    val t = LocalAmberTokens.current
    ProviderListRow(
        title = name,
        subtitle = subtitle?.let { { ProviderRowSubtitle(it, mono = subtitleMono) } },
        leading = { ProviderTile(text = monogram, icon = leadingIcon) },
        trailing = {
            if (tagText != null) {
                ProviderTemplateTag(tagText)
            } else {
                Icon(
                    imageVector = Lucide.ChevronRight,
                    contentDescription = null,
                    tint = t.ink4,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun ProviderTemplateTag(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(t.surface2)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontSize = 11.sp),
            color = t.ink2,
            maxLines = 1,
        )
    }
}

private fun ProviderSetting.brandTagText(): String? {
    if (this !is ProviderSetting.OpenAI) return null
    return when (brand) {
        OpenAIBrand.OPENAI -> "oauth"
        OpenAIBrand.ZHIPU, OpenAIBrand.KIMI, OpenAIBrand.MIMO -> "plan"
        OpenAIBrand.MINIMAX -> "token"
        OpenAIBrand.GENERIC, OpenAIBrand.DEEPSEEK -> null
    }
}
