package app.amber.feature.ui.pages.setting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.fixedBaseUrl
import app.amber.agent.R
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SlidersHorizontal
import kotlin.uuid.Uuid

@Composable
fun ProviderTemplatePickerSheet(
    existingProviderIds: Set<Uuid>,
    onDismiss: () -> Unit,
    onPick: (initial: ProviderSetting, autoStartOAuth: Boolean) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
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
        containerColor = t.bg,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 18.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_add_provider),
                style = type.screenTitle.copy(fontSize = 22.sp),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("section-oauth") {
                    ProviderSectionLabel(stringResource(R.string.setting_provider_page_template_section_oauth))
                }
                item("oauth-codex") {
                    ProviderTemplateRow(
                        name = stringResource(R.string.setting_provider_page_template_oauth_codex_title),
                        subtitle = stringResource(R.string.setting_provider_page_template_oauth_codex_subtitle),
                        monogram = "OC",
                        tagText = "oauth",
                        onClick = { closeAndPick(buildCodexOAuthInitial(), true) },
                    )
                }
                item("oauth-gemini") {
                    ProviderTemplateRow(
                        name = stringResource(R.string.setting_provider_page_template_oauth_gemini_title),
                        subtitle = stringResource(R.string.setting_provider_page_template_oauth_gemini_subtitle),
                        monogram = "Gemi",
                        tagText = "oauth",
                        onClick = { closeAndPick(buildGeminiOAuthInitial(), true) },
                    )
                }

                if (bundledTemplates.isNotEmpty()) {
                    item("section-bundled") {
                        ProviderSectionLabel(stringResource(R.string.setting_provider_page_template_section_bundled))
                    }
                    items(bundledTemplates, key = { "bundled-${it.id}" }) { template ->
                        ProviderTemplateRow(
                            name = template.name,
                            subtitle = template.providerSlugLabel(),
                            monogram = template.name.toProviderMonogram(),
                            tagText = template.brandTagText(),
                            onClick = { closeAndPick(template.copyProvider(), false) },
                        )
                    }
                }

                item("section-custom") {
                    ProviderSectionLabel(stringResource(R.string.setting_provider_page_template_section_custom))
                }
                item("custom-blank") {
                    ProviderTemplateRow(
                        name = stringResource(R.string.setting_provider_page_template_custom_blank_title),
                        subtitle = stringResource(R.string.setting_provider_page_template_custom_blank_subtitle),
                        leadingIcon = HugeIcons.SlidersHorizontal,
                        tagText = "custom",
                        onClick = { closeAndPick(ProviderSetting.OpenAI(), false) },
                    )
                }
            }
        }
    }
}

private fun buildCodexOAuthInitial(): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
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
    monogram: String = name.toProviderMonogram(),
    tagText: String? = null,
    leadingIcon: ImageVector? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    ProviderCard(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon == null) {
                ProviderMonogram(text = monogram, size = 38.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(t.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = t.ink3,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = name,
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = type.meta.copy(fontSize = 11.sp),
                        color = t.ink4,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tagText != null) {
                ProviderTemplateTag(tagText)
            }
        }
    }
}

@Composable
private fun ProviderTemplateTag(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(t.surface2)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
            color = t.ink3,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProviderSheetGrabber() {
    val t = LocalAmberTokens.current
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .width(42.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(t.line2)
    )
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
