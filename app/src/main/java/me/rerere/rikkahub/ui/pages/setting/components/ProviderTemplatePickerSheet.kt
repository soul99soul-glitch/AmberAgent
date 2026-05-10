package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.OpenAIBrand
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import kotlin.uuid.Uuid

/**
 * Bottom sheet shown by the provider list "+" button. Replaces the previous one-step
 * AlertDialog that defaulted to a blank `ProviderSetting.OpenAI()`.
 *
 * Two sections:
 *  - **Bundled brands**: every entry in [DEFAULT_PROVIDERS] that is *not yet* in the user's
 *    list (filtered by id). Picking one opens the editor pre-filled with the bundled config
 *    (brand-tagged, correct base URL, balance options, etc.) — same UUID as the default, so
 *    if the user later deletes it the entry reappears here for one-tap restore.
 *  - **Custom**: blank `ProviderSetting.OpenAI()` / `Google()` / `Claude()` for users
 *    bringing their own gateway. These get a fresh UUID per click.
 *
 * The picker hands the chosen initial state back via [onPick]; the caller is responsible
 * for showing the editor dialog. The sheet itself awaits its own hide animation before
 * notifying so the dialog never opens on top of an in-flight slide-down.
 */
@Composable
fun ProviderTemplatePickerSheet(
    existingProviderIds: Set<Uuid>,
    onDismiss: () -> Unit,
    onPick: (ProviderSetting) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Filter: only show bundled templates the user doesn't already have. Compare by id so
    // "delete then re-add" returns the entry to the picker on the next render — no extra
    // "user-removed defaults" persistence needed. Keyed on a small `Set<Uuid>`, not the full
    // provider list, so unrelated edits (typing in apiKey, refreshing models) don't refire
    // the filter.
    val bundledTemplates = remember(existingProviderIds) {
        DEFAULT_PROVIDERS.filter { it.id !in existingProviderIds }
    }

    // Guard against double-tap: the row onClick launches a coroutine that suspends on
    // `sheetState.hide()`. A second tap before that returns would fire `onPick` twice and
    // overwrite the AlertDialog's initial state mid-open — visually the user sees the
    // wrong template land in the editor.
    var picking by remember { mutableStateOf(false) }

    val closeAndPick: (ProviderSetting) -> Unit = pick@ { initial ->
        if (picking) return@pick
        picking = true
        scope.launch {
            sheetState.hide()
            onPick(initial)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }
            ) {
                Icon(HugeIcons.ArrowDown01, null)
            }
        }
    ) {
        // Wrap-height + cap so the sheet sizes to its contents. With only the 3 custom
        // rows (all bundled templates already added), the previous `fillMaxHeight(0.85f)`
        // left a giant blank area below the list.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_add_provider),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (bundledTemplates.isNotEmpty()) {
                    item(key = "section-bundled") {
                        SectionHeader(stringResource(R.string.setting_provider_page_template_section_bundled))
                    }
                    items(bundledTemplates, key = { "bundled-${it.id}" }) { template ->
                        TemplateRow(
                            name = template.name,
                            tagText = template.brandTagText(),
                            // Defensive `.copyProvider()` — passes a fresh data-class instance
                            // instead of the singleton from DEFAULT_PROVIDERS. The editor uses
                            // `.copy(...)` everywhere so direct mutation is unlikely, but a
                            // future change in ProviderConfigure that mutated `var` fields
                            // would silently corrupt the default for the rest of the session.
                            onClick = { closeAndPick(template.copyProvider()) },
                        )
                    }
                    item(key = "divider") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                item(key = "section-custom") {
                    SectionHeader(stringResource(R.string.setting_provider_page_template_section_custom))
                }
                items(CustomTemplates, key = { "custom-${it.id}" }) { custom ->
                    TemplateRow(
                        name = stringResource(custom.titleRes),
                        subtitle = stringResource(custom.subtitleRes),
                        onClick = { closeAndPick(custom.factory()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun TemplateRow(
    name: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    tagText: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = name,
                modifier = Modifier.size(32.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tagText != null) {
                Tag(type = TagType.INFO) {
                    Text(text = tagText)
                }
            }
        }
    }
}

/**
 * Tag shown next to the brand name in the picker. Only OpenAI-compatible bundled providers
 * with more than just API_KEY auth get a tag — the visual hint is "this entry has special
 * auth modes you might not realize are available". DeepSeek (only API_KEY today) gets no
 * tag because it would just be visual noise.
 */
private fun ProviderSetting.brandTagText(): String? {
    if (this !is ProviderSetting.OpenAI) return null
    return when (brand) {
        OpenAIBrand.OPENAI -> "Codex OAuth"
        OpenAIBrand.ZHIPU, OpenAIBrand.KIMI, OpenAIBrand.MIMO -> "Coding Plan"
        OpenAIBrand.GENERIC, OpenAIBrand.DEEPSEEK -> null
    }
}

private data class CustomTemplate(
    val id: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val factory: () -> ProviderSetting,
)

private val CustomTemplates: List<CustomTemplate> = listOf(
    CustomTemplate(
        id = "openai",
        titleRes = R.string.setting_provider_page_template_custom_openai_title,
        subtitleRes = R.string.setting_provider_page_template_custom_openai_subtitle,
        factory = { ProviderSetting.OpenAI() },
    ),
    CustomTemplate(
        id = "google",
        titleRes = R.string.setting_provider_page_template_custom_google_title,
        subtitleRes = R.string.setting_provider_page_template_custom_google_subtitle,
        factory = { ProviderSetting.Google() },
    ),
    CustomTemplate(
        id = "claude",
        titleRes = R.string.setting_provider_page_template_custom_claude_title,
        subtitleRes = R.string.setting_provider_page_template_custom_claude_subtitle,
        factory = { ProviderSetting.Claude() },
    ),
)

