package app.amber.feature.ui.pages.developer

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.FileCode2
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.core.ai.AILogging
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel

@Composable
fun DeveloperPage(vm: DeveloperVM = koinViewModel()) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.redesign_developer),
                        style = type.screenTitle,
                        color = t.ink,
                        maxLines = 1,
                    )
                },
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
    ) { innerPadding ->
        LoggingPaging(vm = vm, modifier = Modifier.padding(innerPadding))
    }
}

@Composable
fun LoggingPaging(vm: DeveloperVM, modifier: Modifier = Modifier) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionLabel(stringResource(R.string.redesign_logs))
        }
        if (logs.isEmpty()) {
            item {
                AmberCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkspaceLeadingIcon(icon = Lucide.FileCode2)
                        Text("暂无日志", style = type.sessionTitle, color = t.ink)
                        Text(
                            "Generation 类型的 AI 日志会显示在这里。",
                            style = type.secondary,
                            color = t.ink3,
                        )
                    }
                }
            }
        } else {
            items(logs.reversed()) { log ->
                when (log) {
                    is AILogging.Generation -> GenerationLogCard(log)
                }
            }
        }
    }
}

@Composable
private fun GenerationLogCard(log: AILogging.Generation) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(icon = Lucide.FileCode2)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RowLabel(
                    label = log.providerSetting.name,
                    value = if (log.stream) "stream" else "complete",
                )
                Text(
                    text = log.params.model.modelId,
                    style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = t.ink,
                )
                Text(
                    text = "${log.messages.size} 条消息",
                    style = type.meta,
                    color = t.ink3,
                )
            }
        }
    }
}

@Composable
private fun RowLabel(label: String, value: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink)
        Text(value, style = type.meta, color = t.accent)
    }
}
