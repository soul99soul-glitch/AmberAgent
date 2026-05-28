package app.amber.feature.ui.pages.setting.components

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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import kotlinx.coroutines.launch
import me.rerere.ai.provider.GoogleAuthMode
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.OpenAIBrand
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.fixedBaseUrl
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.rikkahub.R
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.feature.ui.components.ui.AutoAIIcon
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import kotlin.uuid.Uuid

/**
 * Bottom sheet shown by the provider list "+" button. Three sections:
 *
 *  - **OAuth quick-start** (always visible, 2 rows): "Sign in with ChatGPT" and "Sign in with
 *    Google". Picking one returns a pre-configured provider together with `autoStartOAuth =
 *    true`, so the editor that opens immediately triggers the OAuth flow without forcing the
 *    user to first add a generic provider and then dig into the auth-mode segmented row.
 *    Permanently visible so OAuth is discoverable on first launch, even when the default
 *    OpenAI / Gemini entries are still in the user's provider list.
 *
 *  - **Built-in brands** (rows = DEFAULT_PROVIDERS not yet in the user's list, filtered by
 *    id): brand-tagged entries the user has previously deleted, here for one-tap restore.
 *    Hidden entirely when nothing is filterable.
 *
 *  - **Custom** (always visible, 1 row): a blank `ProviderSetting.OpenAI()` that the user
 *    can retype in the editor — the editor's top type segmented row lets them switch to
 *    Google / Claude. Used to be 3 rows (OpenAI / Google / Claude), but that duplicated the
 *    editor's own type selector and made the picker feel cluttered.
 *
 * Picker hands the chosen initial state back via [onPick]; the caller is responsible for
 * showing the editor dialog. Sheet awaits its hide animation before notifying so the dialog
 * never opens on top of an in-flight slide-down.
 */
@Composable
fun ProviderTemplatePickerSheet(
    existingProviderIds: Set<Uuid>,
    onDismiss: () -> Unit,
    onPick: (initial: ProviderSetting, autoStartOAuth: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val bundledTemplates = remember(existingProviderIds) {
        DEFAULT_PROVIDERS.filter { it.id !in existingProviderIds }
    }

    // Guard against double-tap: row onClick suspends on `sheetState.hide()`. A second tap
    // before that returns would fire `onPick` twice and overwrite the dialog's initial state.
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
        // Cap raised from 560dp (old 2-section sheet) to 640dp because the new layout
        // is guaranteed to render at least 3 sections — 2 OAuth rows, optional Built-in
        // section, and 1 Custom row — even before bundled brands show up. 640dp keeps
        // the sheet from filling a tall phone when bundled is empty.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .heightIn(max = 640.dp)
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
                // === Section 1: OAuth quick-start (always visible) ===
                item(key = "section-oauth") {
                    SectionHeader(stringResource(R.string.setting_provider_page_template_section_oauth))
                }
                item(key = "oauth-codex") {
                    TemplateRow(
                        name = stringResource(R.string.setting_provider_page_template_oauth_codex_title),
                        subtitle = stringResource(R.string.setting_provider_page_template_oauth_codex_subtitle),
                        iconName = "OpenAI",
                        tagText = stringResource(R.string.setting_provider_page_template_oauth_tag),
                        onClick = { closeAndPick(buildCodexOAuthInitial(), true) },
                    )
                }
                item(key = "oauth-gemini") {
                    // Commit #2: the row is no longer a dimmed placeholder — it builds a
                    // pre-configured Google provider with authMode = GEMINI_CODE_ASSIST_OAUTH
                    // and asks the editor to auto-start the OAuth flow on first composition.
                    // ProviderConfigureGoogle's LaunchedEffect currently stubs the login (toasts
                    // "implementation pending"); commit #3 will replace that stub with the real
                    // loopback HTTP server + PKCE + token exchange.
                    TemplateRow(
                        name = stringResource(R.string.setting_provider_page_template_oauth_gemini_title),
                        subtitle = stringResource(R.string.setting_provider_page_template_oauth_gemini_subtitle),
                        iconName = "Gemini",
                        tagText = stringResource(R.string.setting_provider_page_template_oauth_tag),
                        onClick = { closeAndPick(buildGeminiOAuthInitial(), true) },
                    )
                }

                item(key = "divider-after-oauth") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }

                // === Section 2: Built-in brands (filterable) ===
                if (bundledTemplates.isNotEmpty()) {
                    item(key = "section-bundled") {
                        SectionHeader(stringResource(R.string.setting_provider_page_template_section_bundled))
                    }
                    items(bundledTemplates, key = { "bundled-${it.id}" }) { template ->
                        TemplateRow(
                            name = template.name,
                            tagText = template.brandTagText(),
                            // Defensive `.copyProvider()`: hand a fresh data-class instance
                            // instead of the DEFAULT_PROVIDERS singleton — the editor uses
                            // `.copy(...)` everywhere so direct mutation is unlikely, but a
                            // future change in ProviderConfigure that mutated a `var` field
                            // would silently corrupt the default for the rest of the session.
                            onClick = { closeAndPick(template.copyProvider(), false) },
                        )
                    }
                    item(key = "divider-after-bundled") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                // === Section 3: Custom (always visible, single blank row) ===
                item(key = "section-custom") {
                    SectionHeader(stringResource(R.string.setting_provider_page_template_section_custom))
                }
                item(key = "custom-blank") {
                    TemplateRow(
                        name = stringResource(R.string.setting_provider_page_template_custom_blank_title),
                        subtitle = stringResource(R.string.setting_provider_page_template_custom_blank_subtitle),
                        leadingIcon = Lucide.SlidersHorizontal,
                        onClick = { closeAndPick(ProviderSetting.OpenAI(), false) },
                    )
                }
            }
        }
    }
}

/**
 * Build the pre-configured OpenAI provider used by the "Sign in with ChatGPT" quick-start.
 * Mirrors the per-field setup that ProviderConfigureOpenAI's auth-mode segmented row does
 * when switching to Codex OAuth — pinned baseUrl, `useResponseApi = true`, branded name,
 * fresh UUID so it never collides with the default OpenAI entry. `baseUrl` resolves via
 * [OpenAIAuthMode.fixedBaseUrl] so the picker and the editor's segmented branch stay
 * in lock-step if the backend URL ever changes; `!!` is safe because CODEX_OAUTH is one of
 * the modes whose `fixedBaseUrl` is non-null by definition.
 */
private fun buildCodexOAuthInitial(): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
    id = Uuid.random(),
    name = "OpenAI Codex OAuth",
    baseUrl = OpenAIAuthMode.CODEX_OAUTH.fixedBaseUrl()!!,
    apiKey = "",
    useResponseApi = true,
    authMode = OpenAIAuthMode.CODEX_OAUTH,
    brand = OpenAIBrand.OPENAI,
)

/**
 * Pre-configured Google provider used by the "Sign in with Google" quick-start.
 * Mirrors what ProviderConfigureGoogle's auth-mode segmented row does when switching
 * to Gemini OAuth — pinned baseUrl to cloudcode-pa, vertexAI off, branded name, fresh
 * UUID so it never collides with the default Gemini entry. Real OAuth flow lands in
 * commit #3; this factory only stamps the provider so the editor opens already in
 * OAuth mode.
 */
private fun buildGeminiOAuthInitial(): ProviderSetting.Google = ProviderSetting.Google(
    id = Uuid.random(),
    name = "Gemini OAuth",
    baseUrl = GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH.fixedBaseUrl()!!,
    apiKey = "",
    // The segmented-row OAuth onClick branch explicitly force-offs these for symmetry
    // with the type-switch path; mirror that here so the factory and the switch path
    // produce identical providers byte-for-byte (reviewer flag L1).
    vertexAI = false,
    useServiceAccount = false,
    authMode = GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH,
)

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
    /** Override the icon-resolution name (e.g. "Gemini" so AutoAIIcon shows the Google G). */
    iconName: String = name,
    /** If non-null, render this vector glyph as the leading icon instead of [AutoAIIcon].
     *  Used by the Custom row, which doesn't correspond to a real brand and would
     *  otherwise fall back to a `TextAvatar` showing the row title's first character. */
    leadingIcon: ImageVector? = null,
    /** Visual not-yet-available state — used by the placeholder Gemini OAuth row until
     *  its real implementation lands. Card stays clickable so the caller can still show
     *  a "coming soon" toast, but text / icon / tag are alpha-faded to signal "this isn't
     *  a real action yet". */
    dimmed: Boolean = false,
) {
    val contentAlpha = if (dimmed) 0.55f else 1f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (dimmed) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
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
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    modifier = Modifier
                        .size(28.dp)
                        .padding(2.dp),
                )
            } else {
                AutoAIIcon(
                    name = iconName,
                    modifier = Modifier
                        .size(32.dp)
                        .alpha(contentAlpha),
                )
            }
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tagText != null) {
                Tag(type = TagType.INFO) {
                    Text(
                        text = tagText,
                        color = LocalContentColor.current.copy(alpha = contentAlpha),
                    )
                }
            }
        }
    }
}

/**
 * Tag shown next to brand name in the built-in section. Only OpenAI-compatible bundled
 * providers with more than just API_KEY auth get a tag — the visual hint is "this entry has
 * special auth modes you might not realize are available". DeepSeek (only API_KEY today)
 * gets no tag because it would just be visual noise.
 */
private fun ProviderSetting.brandTagText(): String? {
    if (this !is ProviderSetting.OpenAI) return null
    return when (brand) {
        OpenAIBrand.OPENAI -> "Codex OAuth"
        OpenAIBrand.ZHIPU, OpenAIBrand.KIMI, OpenAIBrand.MIMO -> "Coding Plan"
        OpenAIBrand.GENERIC, OpenAIBrand.DEEPSEEK -> null
    }
}
