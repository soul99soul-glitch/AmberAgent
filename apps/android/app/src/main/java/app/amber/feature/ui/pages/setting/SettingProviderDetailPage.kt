package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.ShareSheet
import app.amber.feature.ui.components.ui.rememberShareSheetState
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderLiveDot
import app.amber.feature.ui.pages.setting.components.ProviderUnderlineTabs
import app.amber.feature.ui.pages.setting.components.providerAuthLabel
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
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
    val type = LocalAmberType.current

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(t.bg)
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 14.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BackButton()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text("//", style = type.eyebrow, color = t.accent)
                            Text("提供商", style = type.eyebrow, color = t.ink3)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                text = provider.providerSlugLabel(),
                                style = type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                                color = t.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (provider.enabled) {
                                ProviderLiveDot()
                            }
                            Text(
                                text = "${provider.name} · ${provider.providerAuthLabel()} · ${provider.models.size} 模型",
                                style = type.meta.copy(fontSize = 10.5.sp),
                                color = t.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    ProviderGhostButton(
                        text = "导出",
                        onClick = { shareSheetState.show(provider) },
                    )
                }
                ProviderUnderlineTabs(
                    tabs = listOf(
                        stringResource(id = R.string.setting_provider_page_configuration),
                        stringResource(id = R.string.setting_provider_page_models),
                    ),
                    selected = pager.currentPage,
                    onSelect = { page -> scope.launch { pager.animateScrollToPage(page) } },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
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
