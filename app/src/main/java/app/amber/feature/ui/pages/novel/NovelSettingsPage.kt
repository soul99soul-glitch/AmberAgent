package app.amber.feature.ui.pages.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.provider.ModelType
import app.amber.core.settings.findProvider
import app.amber.core.utils.plus
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.hooks.rememberUserSettingsState
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlin.uuid.Uuid
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Novel settings — single writing card + light danger row.
 * Model selector lives inside the card (no floating orphan control).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelSettingsPage(
    projectId: String,
    viewModel: NovelSettingsViewModel = koinViewModel(parameters = { parametersOf(projectId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val document = state.document
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val tokens = LocalAmberTokens.current
    val settings by rememberUserSettingsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var undoConfirm by remember { mutableStateOf(false) }
    val savedPolish = document?.project?.polishPreference.orEmpty()
    var polishPref by remember(savedPolish) { mutableStateOf(savedPolish) }
    val polishDirty = polishPref != savedPolish

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.accent,
        unfocusedBorderColor = tokens.line,
        focusedContainerColor = tokens.surface,
        unfocusedContainerColor = tokens.surface,
        cursorColor = tokens.accent,
        focusedTextColor = tokens.ink,
        unfocusedTextColor = tokens.ink,
        focusedPlaceholderColor = tokens.ink4,
        unfocusedPlaceholderColor = tokens.ink4,
    )

    val fixedPolicy = document?.project?.modelPolicy as? NovelProjectModelPolicy.Fixed
    val selectedModelId = remember(fixedPolicy?.modelID, settings.chatModelId) {
        fixedPolicy?.modelID?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: settings.chatModelId
    }
    val followingGlobal = fixedPolicy == null
    val projectName = document?.project?.name?.ifBlank { null }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "小说设置",
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // One writing card: model + polish
            item("writing") {
                SectionLabel(
                    text = "写作",
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                AmberCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (projectName != null) {
                            Text(
                                text = projectName,
                                style = type.meta,
                                color = workspace.muted,
                            )
                        }

                        // —— 模型 ——
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "写作模型",
                                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                    color = workspace.ink,
                                )
                                NovelModelScopePill(
                                    followingGlobal = followingGlobal,
                                )
                            }
                            ModelSelector(
                                modelId = selectedModelId,
                                providers = settings.providers,
                                type = ModelType.CHAT,
                                inline = true,
                                allowClear = !followingGlobal,
                                emptyLabel = "选择模型",
                                clearContentDescription = "改回跟随全局",
                                modifier = Modifier.fillMaxWidth(),
                                onClear = {
                                    viewModel.setModelPolicy(NovelProjectModelPolicy.Global)
                                },
                                onSelect = { model ->
                                    val provider = model.findProvider(settings.providers)
                                        ?: return@ModelSelector
                                    viewModel.setModelPolicy(
                                        NovelProjectModelPolicy.Fixed(
                                            providerID = provider.id.toString(),
                                            modelID = model.id.toString(),
                                        ),
                                    )
                                },
                            )
                            Text(
                                text = if (followingGlobal) {
                                    "跟随全局聊天模型；点上方可固定为本项目模型"
                                } else {
                                    "已固定；点清除可改回跟随全局"
                                },
                                style = type.meta,
                                color = workspace.muted,
                            )
                        }

                        HorizontalDivider(color = tokens.line, thickness = 1.dp)

                        // —— 润色 ——
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "润色偏好",
                                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                color = workspace.ink,
                            )
                            Text(
                                text = "整章润色时参考，可留空",
                                style = type.meta,
                                color = workspace.muted,
                            )
                            OutlinedTextField(
                                value = polishPref,
                                onValueChange = { polishPref = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                maxLines = 6,
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors,
                                placeholder = {
                                    Text(
                                        "例如：文风更简洁，少用形容词",
                                        style = type.body,
                                        color = tokens.ink4,
                                    )
                                },
                                textStyle = type.body,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (polishDirty) {
                                    NovelPrimaryButton(
                                        text = if (state.busy) "保存中…" else "保存",
                                        onClick = { viewModel.setPolishPreference(polishPref) },
                                        enabled = !state.busy,
                                        accent = true,
                                    )
                                } else {
                                    Text(
                                        text = "已同步",
                                        style = type.meta,
                                        color = workspace.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Danger — single quiet row
            item("danger") {
                SectionLabel(
                    text = "危险操作",
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(workspace.paper)
                        .border(1.dp, workspace.hairline, RoundedCornerShape(14.dp))
                        .clickable(enabled = !state.busy) { undoConfirm = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "撤销最近一次收录",
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = workspace.red,
                        )
                        Text(
                            text = "回退上一次写入正文的操作",
                            style = type.meta,
                            color = workspace.muted,
                        )
                    }
                }
            }

            state.errorMessage?.let { msg ->
                item("error") {
                    Text(
                        msg,
                        color = workspace.red,
                        style = type.meta,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    if (undoConfirm) {
        AlertDialog(
            onDismissRequest = { undoConfirm = false },
            containerColor = workspace.paper,
            title = {
                Text("撤销最近收录？", fontWeight = FontWeight.SemiBold, color = workspace.ink)
            },
            text = {
                Text(
                    "将回退上一次收录到正文的操作。",
                    style = type.secondary,
                    color = workspace.muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.undoHead()
                        undoConfirm = false
                    },
                ) {
                    Text("撤销", color = workspace.red, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { undoConfirm = false }) {
                    Text("取消", color = workspace.muted)
                }
            },
        )
    }
}

@Composable
private fun NovelModelScopePill(followingGlobal: Boolean) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val label = if (followingGlobal) "全局" else "固定"
    val bg = if (followingGlobal) tokens.surface2 else tokens.accent.copy(alpha = 0.12f)
    val fg = if (followingGlobal) tokens.ink3 else tokens.accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}
