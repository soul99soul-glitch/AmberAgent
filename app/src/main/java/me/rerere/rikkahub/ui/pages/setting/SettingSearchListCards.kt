package me.rerere.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import kotlin.uuid.Uuid

@Composable
internal fun SearchRecommendationCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_recommend_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.setting_page_search_recommend_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BuiltinFreeSearchCard(
    jinaEnabled: Boolean,
    duckDuckGoEnabled: Boolean,
    bingEnabled: Boolean,
    wikipediaEnabled: Boolean,
    hackerNewsEnabled: Boolean,
    googleWebViewFallbackEnabled: Boolean,
    onJinaEnabledChange: (Boolean) -> Unit,
    onDuckDuckGoEnabledChange: (Boolean) -> Unit,
    onBingEnabledChange: (Boolean) -> Unit,
    onWikipediaEnabledChange: (Boolean) -> Unit,
    onHackerNewsEnabledChange: (Boolean) -> Unit,
    onGoogleWebViewFallbackEnabledChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setting_page_search_builtin_sources),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setting_page_search_builtin_sources_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_jina),
                description = stringResource(R.string.setting_page_search_builtin_jina_desc),
                checked = jinaEnabled,
                onCheckedChange = onJinaEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_duckduckgo),
                description = stringResource(R.string.setting_page_search_builtin_duckduckgo_desc),
                checked = duckDuckGoEnabled,
                onCheckedChange = onDuckDuckGoEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_bing),
                description = stringResource(R.string.setting_page_search_builtin_bing_desc),
                checked = bingEnabled,
                onCheckedChange = onBingEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_wikipedia),
                description = stringResource(R.string.setting_page_search_builtin_wikipedia_desc),
                checked = wikipediaEnabled,
                onCheckedChange = onWikipediaEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_builtin_hackernews),
                description = stringResource(R.string.setting_page_search_builtin_hackernews_desc),
                checked = hackerNewsEnabled,
                onCheckedChange = onHackerNewsEnabledChange,
            )
            BuiltinSourceRow(
                title = stringResource(R.string.setting_page_search_google_webview_fallback),
                description = stringResource(R.string.setting_page_search_google_webview_fallback_desc),
                checked = googleWebViewFallbackEnabled,
                onCheckedChange = onGoogleWebViewFallbackEnabledChange,
            )
        }
    }
}

@Composable
private fun BuiltinSourceRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

internal data class SearchServiceEditorTarget(
    val index: Int?,
    val service: SearchServiceOptions,
)

internal fun resolveSelectedSearchIndex(
    services: List<SearchServiceOptions>,
    selectedServiceId: Uuid?,
): Int {
    if (services.isEmpty()) return 0
    return selectedServiceId
        ?.let { id -> services.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 }
        ?: 0
}

@Composable
internal fun AgentSearchEnableCard(
    enabled: Boolean,
    enabledCount: Int,
    serviceCount: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_page_search_agent_search),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setting_page_search_agent_search_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.setting_page_search_enabled_services_count,
                        enabledCount,
                        serviceCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
internal fun SearchProviderCard(
    service: SearchServiceOptions,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEditService: () -> Unit,
    onDeleteService: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit = {}
) {
    val name = SearchServiceOptions.TYPES[service::class] ?: "Search"
    // Plan B compact card. Header row carries identity + Switch (the high-frequency
    // toggle). Action row holds the secondary controls — all rendered as plain
    // IconButtons for visual consistency (the previous card mixed IconButton +
    // outlined TextButton + Switch which read as four different button languages).
    //
    // Dropped from the prior version:
    //   - The "用于 Agent 搜索" subtitle (Switch state already conveys this)
    //   - The hard-coded "搜索" ability tag (every search service trivially has it,
    //     so the tag carries no information on this page)
    //   - The whole SearchAbilityTagLine row — `scrape` and other differentiators
    //     are visible inside the editor sheet where they actually matter
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Header: icon + name + Switch.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AutoAIIcon(
                    name = name,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            // Action strip — IconButtons forced to 36dp (vs Material default 40dp) to
            // shave 4dp off the action row's contribution to overall card height. The
            // 20dp glyph + 36dp ripple still sits comfortably above Material's 32dp
            // dense-row floor.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onEditService,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Edit01,
                        contentDescription = stringResource(R.string.edit),
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (canDelete) {
                    IconButton(
                        onClick = onDeleteService,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_page_search_delete_provider),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                dragHandle()
            }
        }
    }
}

@Composable
internal fun CommonOptions(
    settings: Settings,
    onUpdate: (SearchCommonOptions) -> Unit
) {
    var commonOptions by remember(settings.searchCommonOptions) {
        mutableStateOf(settings.searchCommonOptions)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_common_options),
                style = MaterialTheme.typography.titleMedium
            )

            FormItem(
                label = {
                    Text(stringResource(R.string.setting_page_search_result_size))
                }
            ) {
                OutlinedNumberInput(
                    value = commonOptions.resultSize,
                    onValueChange = {
                        commonOptions = commonOptions.copy(
                            resultSize = it
                        )
                        onUpdate(commonOptions)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
