package app.amber.feature.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Icon1stBracket
import me.rerere.hugeicons.stroke.McpServer
import app.amber.core.ai.mcp.McpManager
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.mcp.McpStatus
import app.amber.core.model.Assistant
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import org.koin.compose.koinInject


@Composable
fun McpPicker(
    assistant: Assistant,
    servers: List<McpServerConfig>,
    modifier: Modifier = Modifier,
    onUpdateAssistant: (Assistant) -> Unit
) {
    val mcpManager = koinInject<McpManager>()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(servers.fastFilter { it.commonOptions.enable }) { server ->
            val status by mcpManager.getStatus(server).collectAsStateWithLifecycle(McpStatus.Idle)
            Card {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (status) {
                        McpStatus.Idle -> Icon(HugeIcons.Icon1stBracket, null)
                        McpStatus.Connecting -> CircularProgressIndicator(
                            modifier = Modifier.size(
                                24.dp
                            )
                        )

                        McpStatus.Connected -> Icon(HugeIcons.McpServer, null)
                        is McpStatus.Reconnecting -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                        is McpStatus.Error -> Icon(HugeIcons.Alert01, null)
                        McpStatus.Authorizing -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                        McpStatus.NeedsAuthorization -> Icon(HugeIcons.Alert01, null)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = server.commonOptions.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = when (val s = status) {
                                is McpStatus.Idle -> "Idle"
                                is McpStatus.Connecting -> "Connecting"
                                is McpStatus.Connected -> "Connected"
                                is McpStatus.Reconnecting -> "Reconnecting (${s.attempt}/${s.maxAttempts})"
                                is McpStatus.Error -> "Error: ${s.message}"
                                is McpStatus.Authorizing -> "Authorizing"
                                is McpStatus.NeedsAuthorization -> "Needs authorization"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 5
                        )
                        if (status == McpStatus.Connected) {
                            val tools = server.commonOptions.tools
                            val enabledTools = tools.fastFilter { it.enable }
                            Tag(
                                type = TagType.INFO
                            ) {
                                Text("${enabledTools.size}/${tools.size} tools")
                            }
                        }
                    }
                    Switch(
                        checked = server.id in assistant.mcpServers,
                        onCheckedChange = {
                            if (it) {
                                val newServers = assistant.mcpServers.toMutableSet()
                                newServers.add(server.id)
                                newServers.removeIf { servers.none { s -> s.id == server.id } } // remove invalid servers
                                onUpdateAssistant(
                                    assistant.copy(
                                        mcpServers = newServers.toSet()
                                    )
                                )
                            } else {
                                val newServers = assistant.mcpServers.toMutableSet()
                                newServers.remove(server.id)
                                newServers.removeIf { servers.none { s -> s.id == server.id } } //  remove invalid servers
                                onUpdateAssistant(
                                    assistant.copy(
                                        mcpServers = newServers.toSet()
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}
