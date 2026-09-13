package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderHairline
import app.amber.feature.ui.pages.setting.components.ProviderLiveDot
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
    var fetchedCandidates by remember(provider.id) { mutableStateOf<ProviderModelCandidates?>(null) }
    var modelListRefreshKey by remember(provider.id) { mutableIntStateOf(0) }
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    val onEdit: (ProviderSetting) -> Unit = { newProvider ->
        vm.updateSettings { currentSettings ->
            currentSettings.copy(
                providers = currentSettings.providers.map {
                    if (newProvider.id == it.id) newProvider else it
                }
            )
        }
    }
    val onDelete = {
        vm.updateSettings { currentSettings ->
            currentSettings.copy(
                providers = currentSettings.providers.filterNot { it.id == provider.id }
            )
        }
        navController.popBackStack()
    }

    ShareSheet(shareSheetState)

    Scaffold(
        modifier = Modifier.amberCanvas(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(start = 4.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton()
                    Spacer(modifier = Modifier.weight(1f))
                    ProviderGhostButton(
                        text = stringResource(R.string.export_title),
                        onClick = { shareSheetState.show(provider) },
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text("//", style = type.eyebrow, color = t.accent)
                        Text(
                            stringResource(R.string.setting_page_providers),
                            style = type.eyebrow,
                            color = t.ink2,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(
                            text = provider.providerSlugLabel(),
                            style = type.meta.copy(
                                fontSize = 24.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (provider.enabled) {
                            ProviderLiveDot(size = 8.dp)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = provider.name,
                            style = type.secondary,
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("·", style = type.meta, color = t.ink4)
                        Text(
                            text = provider.providerAuthLabel(),
                            style = type.meta.copy(fontSize = 10.5.sp),
                            color = t.ink2,
                            maxLines = 1,
                        )
                        Text("·", style = type.meta, color = t.ink4)
                        Text(
                            text = stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size,
                            ),
                            style = type.meta.copy(fontSize = 10.5.sp),
                            color = t.ink2,
                            maxLines = 1,
                        )
                    }
                    ProviderDetailTabs(
                        tabs = listOf(
                            stringResource(id = R.string.setting_provider_page_configuration),
                            stringResource(id = R.string.setting_provider_page_models),
                        ),
                        selected = pager.currentPage,
                        onSelect = { page -> scope.launch { pager.animateScrollToPage(page) } },
                    )
                }
            }
        },
        containerColor = Color.Transparent,
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
                        onModelsFetched = { fetchedCandidates = it },
                        onModelCandidatesInvalidated = {
                            fetchedCandidates = null
                            modelListRefreshKey += 1
                        },
                        onDelete = {
                            onDelete()
                        }
                    )
                }

                1 -> {
                    SettingProviderModelPage(
                        provider = provider,
                        onEdit = onEdit,
                        fetchedCandidates = fetchedCandidates,
                        modelListRefreshKey = modelListRefreshKey,
                        currentModelId = settings.chatModelId,
                        onSetCurrent = { model ->
                            vm.updateSettings { currentSettings ->
                                currentSettings.copy(chatModelId = model.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDetailTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .height(44.dp)
                        .pressable(onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = type.secondary.copy(
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = if (isSelected) t.ink else t.ink3,
                            maxLines = 1,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (isSelected) t.accent else Color.Transparent),
                    )
                }
            }
        }
        ProviderHairline()
    }
}
