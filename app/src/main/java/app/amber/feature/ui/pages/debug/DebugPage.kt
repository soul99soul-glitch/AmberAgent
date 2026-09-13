package app.amber.feature.ui.pages.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.model.Avatar
import app.amber.core.settings.Capability
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.richtext.MarkdownBlock
import app.amber.feature.ui.components.richtext.MathBlock
import app.amber.feature.ui.components.richtext.Mermaid
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid

@Composable
fun DebugPage(vm: DebugVM = koinViewModel()) {
    val scope = rememberCoroutineScope()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.redesign_debug_mode), style = type.screenTitle, color = t.ink) },
                navigationIcon = { BackButton() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = t.bg,
                    scrolledContainerColor = t.bg,
                    titleContentColor = t.ink,
                    navigationIconContentColor = t.ink2,
                ),
            )
        },
        modifier = Modifier.amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { contentPadding ->
        val state = rememberPagerState { 2 }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = state.currentPage,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ) {
                Tab(
                    selected = state.currentPage == 0,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(0)
                        }
                    },
                    text = {
                        Text(
                            stringResource(R.string.redesign_debug_main),
                            style = type.secondary,
                            color = if (state.currentPage == 0) t.ink else t.ink3,
                        )
                    }
                )
                Tab(
                    selected = state.currentPage == 1,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(1)
                        }
                    },
                    text = {
                        Text(
                            stringResource(R.string.redesign_logs),
                            style = type.secondary,
                            color = if (state.currentPage == 1) t.ink else t.ink3,
                        )
                    }
                )
            }
            HorizontalPager(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> MainPage(vm)
                    1 -> DebugLoggingPage()
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: DebugVM) {
    val settings = LocalSettings.current
    val context = LocalContext.current
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        var avatar: Avatar by remember { mutableStateOf(Avatar.Emoji("😎")) }
        val toaster = LocalToaster.current
        var toastCounter by remember { mutableIntStateOf(0) }
        SectionLabel(stringResource(R.string.redesign_debug_tools))
        AmberCard {
            Column {
                DebugActionRow("头像编辑器") {
                    UIAvatar(
                        value = avatar,
                        onUpdate = {
                            println("Avatar updated: $it")
                            avatar = it
                        },
                        name = "A",
                    )
                }
                Hairline()
                DebugActionRow("Mermaid 思维导图预览") {
                    Mermaid(
                        code = """
                            mindmap
                              root((mindmap))
                                Origins
                                  Long history
                                  Popularisation
                                Research
                                  On effectiveness
                                Tools
                                  Pen and paper
                                  Mermaid
                        """.trimIndent(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Hairline()
                DebugButtonRow("toast") {
                    toaster.show(context.getString(R.string.debug_test_toast, toastCounter++))
                    toaster.show(context.getString(R.string.debug_test_toast, toastCounter++), type = ToastType.Info)
                    toaster.show(context.getString(R.string.debug_test_toast, toastCounter++), type = ToastType.Error)
                }
                Hairline()
                DebugActionRow("Council Room 调试入口") {
                    app.amber.feature.ui.pages.councilroom.CouncilRoomDevEntry()
                }
                Hairline()
                DebugButtonRow(stringResource(R.string.debug_reset_chat_model)) {
                    vm.updateSettings(settings.copy(chatModelId = Uuid.random()))
                }
                Hairline()
                DebugButtonRow(stringResource(R.string.debug_crash_button), danger = true) {
                    error(context.getString(R.string.debug_crash_message, Random.nextInt(0..1000)))
                }
                Hairline()
                DebugButtonRow(stringResource(R.string.debug_create_oversized_button, 30)) {
                    vm.createOversizedConversation(30)
                    toaster.show(context.getString(R.string.debug_create_oversized_toast, 30))
                }
                Hairline()
                DebugButtonRow(stringResource(R.string.debug_create_messages_button, 1024)) {
                    vm.createConversationWithMessages(1024)
                    toaster.show(context.getString(R.string.debug_create_messages_toast, 1024))
                }
            }
        }

        SectionLabel(stringResource(R.string.redesign_capability_flags))
        val capabilities by vm.capabilities.collectAsStateWithLifecycle()
        AmberCard {
            Column {
                Capability.entries.forEachIndexed { index, capability ->
                    if (index > 0) Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = capability.id,
                            modifier = Modifier.weight(1f),
                            style = type.meta,
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Switch(
                            checked = capability in capabilities.enabled,
                            onCheckedChange = { vm.setCapability(capability, it) },
                        )
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.setting_sandbox_runtime_section))
        AmberCard {
            Column {
                DebugFactRow("schema version", vm.secretMigrationVersion.toString())
                Hairline()
                DebugFactRow("ledger version", vm.ledgerVersion.toString())
                Hairline()
                DebugFactRow("terminal version", vm.terminalVersion.toString())
                Hairline()
                DebugFactRow("thread graph version", vm.threadGraphVersion.toString())
            }
        }

        // P1-04: final token fit receipts — what was trimmed from the last
        // provider-bound requests and why.
        val tokenFitReceipts by vm.tokenFitReceipts.collectAsStateWithLifecycle()
        SectionLabel(stringResource(R.string.redesign_token_fit_recent, tokenFitReceipts.size))
        AmberCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tokenFitReceipts.isEmpty()) {
                    Text(stringResource(R.string.debug_no_token_fit_records), style = type.secondary, color = t.ink3)
                } else {
                    tokenFitReceipts.take(8).forEach { receipt ->
                        val trimmed = if (receipt.trimmedMessages.isEmpty()) {
                            stringResource(R.string.debug_no_trimmed_messages)
                        } else {
                            receipt.trimmedMessages.joinToString(", ") { "${it.provenance.name}×${it.count}" }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${receipt.providerFamily} ${receipt.modelId.take(12)} " +
                                    "${receipt.estimatedBefore}→${receipt.estimatedAfter}/${receipt.budgetTokens}",
                                style = type.meta,
                                color = t.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (receipt.contextTooLarge) "ContextTooLarge" else trimmed,
                                style = type.meta,
                                color = if (receipt.contextTooLarge) MaterialTheme.colorScheme.error else t.ink3,
                            )
                        }
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.redesign_launch_stats))
        AmberCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("launchCount", style = type.meta, color = t.ink2)

                var launchCountInput by remember(settings.launchCount) { mutableStateOf(settings.launchCount.toString()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = launchCountInput,
                        onValueChange = { launchCountInput = it },
                        label = { Text("当前 ${settings.launchCount}") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(onClick = {
                        launchCountInput.toIntOrNull()?.let { vm.updateSettings(settings.copy(launchCount = it)) }
                    }) { Text("Set") }
                }
            }
        }

        SectionLabel(stringResource(R.string.redesign_rich_text_preview))
        var markdown by remember { mutableStateOf("") }
        AmberCard {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MarkdownBlock(markdown, modifier = Modifier.fillMaxWidth())
                MathBlock(markdown)
                OutlinedTextField(
                    value = markdown,
                    onValueChange = { markdown = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        }
    }
}

@Composable
private fun DebugActionRow(
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = LocalAmberType.current.body, color = LocalAmberTokens.current.ink)
        content()
    }
}

@Composable
private fun DebugButtonRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = type.body,
            color = if (danger) MaterialTheme.colorScheme.error else t.ink,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = type.body, color = t.ink3)
    }
}

@Composable
private fun DebugFactRow(label: String, value: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.meta, color = t.ink2)
        Text(value, style = type.meta, color = t.ink)
    }
}

@Composable
private fun DebugLoggingPage() {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionLabel(stringResource(R.string.redesign_logs))
        AmberCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("日志入口", style = type.body, color = t.ink)
                Text("网络请求日志与生成记录会在日志页面显示。", style = type.secondary, color = t.ink3)
            }
        }
    }
}
