package app.amber.feature.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.SlidersHorizontal
import me.rerere.hugeicons.stroke.Share01
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.ShareSheet
import app.amber.feature.ui.components.ui.rememberShareSheetState
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderHairline
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderLiveDot
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.providerAuthLabel
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingProviderDetailPage(id: Uuid, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val shareSheetState = rememberShareSheetState()
    val t = LocalAmberTokens.current

    val onEdit = { newProvider: ProviderSetting ->
        val newSettings = settings.copy(
            providers = settings.providers.map {
                if (newProvider.id == it.id) {
                    newProvider
                } else {
                    it
                }
            }
        )
        vm.updateSettings(newSettings)
    }
    val onDelete = {
        val newSettings = settings.copy(
            providers = settings.providers - provider
        )
        vm.updateSettings(newSettings)
        navController.popBackStack()
    }

    ShareSheet(shareSheetState)

    Scaffold(
        topBar = {
            ProviderDetailTopBar(
                provider = provider,
                onShare = { shareSheetState.show(provider) },
            )
        },
        bottomBar = {
            ProviderDetailTabs(
                selectedPage = pager.currentPage,
                onSelect = { page -> scope.launch { pager.animateScrollToPage(page) } },
            )
        },
        containerColor = t.bg,
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .padding(it)
                .consumeWindowInsets(it)
        ) { page ->
            when (page) {
                0 -> {
                    SettingProviderConfigPage(
                        provider = provider,
                        onEdit = {
                            onEdit(it)
                            toaster.show(
                                context.getString(R.string.setting_provider_page_save_success),
                                type = ToastType.Success
                            )
                        },
                        onDelete = {
                            onDelete()
                        }
                    )
                }

                1 -> {
                    SettingProviderModelPage(
                        provider = provider,
                        onEdit = onEdit
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDetailTopBar(
    provider: ProviderSetting,
    onShare: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackButton()
            ProviderMonogram(
                text = provider.name.toProviderMonogram(),
                enabled = provider.enabled,
                size = 38.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = provider.name,
                        style = type.screenTitle,
                        color = t.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (provider.enabled) {
                        ProviderLiveDot()
                    }
                }
                Text(
                    text = "${provider.providerSlugLabel()} · ${provider.providerAuthLabel()} · ${provider.models.size} models",
                    style = type.meta.copy(fontSize = 11.sp),
                    color = t.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProviderIconButton(
                imageVector = HugeIcons.Share01,
                contentDescription = null,
                rotate180 = true,
                onClick = onShare,
            )
        }
        ProviderHairline()
    }
}

@Composable
private fun ProviderDetailTabs(
    selectedPage: Int,
    onSelect: (Int) -> Unit,
) {
    val t = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        ProviderHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderDetailTab(
                label = stringResource(id = R.string.setting_provider_page_configuration),
                icon = HugeIcons.SlidersHorizontal,
                selected = selectedPage == 0,
                onClick = { onSelect(0) },
                modifier = Modifier.weight(1f),
            )
            ProviderDetailTab(
                label = stringResource(id = R.string.setting_provider_page_models),
                icon = HugeIcons.Package01,
                selected = selectedPage == 1,
                onClick = { onSelect(1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProviderDetailTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) t.surface2 else t.bg)
            .border(1.dp, if (selected) t.line2 else t.line, RoundedCornerShape(10.dp))
            .pressable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = if (selected) t.accent else t.ink3,
        )
        Text(
            text = label.uppercase(),
            modifier = Modifier.padding(start = 7.dp),
            style = type.meta.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) t.accent else t.ink3,
            maxLines = 1,
        )
    }
}
