package app.amber.feature.ui.pages.zcode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.Screen
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.core.utils.plus
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * ZCode companion: paste the share URL from 智谱 ZCode and open its mobile web UI.
 * No special bridge — ZCode is already a mobile-ready HTML page.
 */
@Composable
fun ZCodePage(
    store: ZCodeUrlStore = koinInject(),
) {
    val navController = LocalNavController.current
    val workspace = workspaceColors()
    val scope = rememberCoroutineScope()
    val saved by store.urlFlow.collectAsStateWithLifecycle(initialValue = "")
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saved) {
        if (draft.isBlank() && saved.isNotBlank()) {
            draft = saved
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "ZCode",
                navigationIcon = { BackButton() },
            )
        },
        containerColor = workspace.canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "打开智谱 ZCode 移动页",
                style = MaterialTheme.typography.titleMedium,
                color = workspace.ink,
            )
            Text(
                text = "把 ZCode 分享/远程链接粘贴到下方，Amber 用内置浏览器打开（已适配移动端的 HTML 页面）。",
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.muted,
            )

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ZCode 链接") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val url = normalizeZCodeUrl(draft)
                    if (url == null) {
                        error = "请输入有效的 http(s) 链接"
                        return@Button
                    }
                    scope.launch {
                        store.save(url)
                        navController.navigate(Screen.ZCodeSession(url = url))
                    }
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开")
            }
        }
    }
}

/** Accept bare host by prefixing https; reject non-http schemes. */
internal fun normalizeZCodeUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        trimmed.contains("://") -> return null
        else -> "https://$trimmed"
    }
    return runCatching {
        val uri = java.net.URI(withScheme)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        withScheme
    }.getOrNull()
}
