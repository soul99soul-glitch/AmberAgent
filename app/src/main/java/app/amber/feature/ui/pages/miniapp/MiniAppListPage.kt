package app.amber.feature.ui.pages.miniapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import app.amber.agent.Screen
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.miniapp.components.MiniAppGridCard
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniAppListPage(
    repository: MiniAppRepository = koinInject(),
) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val apps by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val exportMiniApp = rememberMiniAppHtmlExporter()
    var renameTarget by remember { mutableStateOf<MiniAppEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<MiniAppEntity?>(null) }
    var versionTarget by remember { mutableStateOf<MiniAppEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小应用", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.MiniAppSettings) }) {
                        Icon(HugeIcons.Settings03, contentDescription = "小应用设置")
                    }
                },
            )
        }
    ) { padding ->
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有保存的小应用",
                    style = LocalAmberType.current.secondary,
                    color = workspaceColors().muted,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(apps, key = { it.id }) { app ->
                    MiniAppGridCard(
                        app = app,
                        onClick = { navController.navigate(Screen.MiniAppRunner(app.id)) },
                        onTogglePinned = {
                            scope.launch {
                                repository.setPinned(app.id, !app.pinned)
                            }
                        },
                        onRename = { renameTarget = app },
                        onDelete = { deleteTarget = app },
                        onExport = { exportMiniApp(app) },
                        onVersions = { versionTarget = app },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        MiniAppRenameDialog(
            app = target,
            onDismiss = { renameTarget = null },
            onConfirm = { title, description ->
                scope.launch {
                    repository.rename(target.id, title, description)
                    renameTarget = null
                }
            },
        )
    }

    deleteTarget?.let { target ->
        MiniAppDeleteDialog(
            app = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch {
                    repository.delete(target.id)
                    deleteTarget = null
                }
            },
        )
    }

    versionTarget?.let { target ->
        val versions by repository.observeVersions(target.id).collectAsStateWithLifecycle(initialValue = emptyList())
        MiniAppVersionHistoryDialog(
            app = target,
            versions = versions,
            onDismiss = { versionTarget = null },
            onRestore = { version ->
                scope.launch {
                    repository.restoreVersion(target.id, version.versionNumber)
                    versionTarget = null
                }
            },
        )
    }
}
