package app.amber.feature.ui.components.workspace

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.X
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.RefreshCw
import app.amber.agent.R
import app.amber.core.utils.appLocale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceFileSheet(
    vm: WorkspaceFileVM,
    artifactsVm: ArtifactsVM,
    artifactsEnabled: Boolean,
    onDismiss: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenSourceConversation: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val recent by vm.recent.collectAsStateWithLifecycle()
    val currentPath by vm.currentPath.collectAsStateWithLifecycle()
    val currentItems by vm.currentDirItems.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    // Long-press a file row → set pendingAction → choose share/delete.
    var pendingAction by remember { mutableStateOf<WorkspaceFileItem?>(null) }
    var pendingDelete by remember { mutableStateOf<WorkspaceFileItem?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.78f),
        ) {
            HeaderRow(
                onRefresh = { vm.refresh() },
                onDismiss = onDismiss,
            )

            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = {
                        Text(
                            text = stringResource(R.string.workspace_recent_files),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (pagerState.currentPage == 0) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    },
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = {
                        Text(
                            text = stringResource(R.string.workspace_directory),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (pagerState.currentPage == 1) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    },
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = {
                        Text(
                            text = "Artifacts",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (pagerState.currentPage == 2) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    },
                )
            }

            Spacer(Modifier.height(4.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    when {
                        loading && recent.isEmpty() && currentItems.isEmpty() ->
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
                        page == 0 -> RecentFilesList(
                            files = recent,
                            onOpenFile = onOpenFile,
                            onLongPressFile = { pendingAction = it },
                        )
                        page == 1 -> DirectoryBrowser(
                            currentPath = currentPath,
                            items = currentItems,
                            onNavigate = { vm.navigateTo(it) },
                            onNavigateUp = { vm.navigateUp() },
                            onOpenFile = onOpenFile,
                            onLongPressFile = { pendingAction = it },
                        )
                        else -> WorkspaceArtifactsTab(
                            vm = artifactsVm,
                            artifactsEnabled = artifactsEnabled,
                            onOpenContent = onOpenFile,
                            onOpenSourceConversation = onOpenSourceConversation,
                        )
                    }
                }
            }
        }
    }

    pendingAction?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(target.name) },
            text = { Text(stringResource(R.string.workspace_file_action_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        shareWorkspaceFile(context, vm.shareableFile(target.path), target)
                    }
                ) { Text(stringResource(R.string.share)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = {
                            pendingAction = null
                            pendingDelete = target
                        }
                    ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = {
                Text(stringResource(R.string.workspace_delete_file_message, target.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteFile(target.path)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun HeaderRow(onRefresh: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.workspace_files_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                Icon(
                    Lucide.RefreshCw,
                    contentDescription = stringResource(R.string.setting_officepro_refresh),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(R.string.update_card_close),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentFilesList(
    files: List<WorkspaceFileItem>,
    onOpenFile: (String) -> Unit,
    onLongPressFile: (WorkspaceFileItem) -> Unit,
) {
    if (files.isEmpty()) {
        EmptyState(message = stringResource(R.string.workspace_no_recent_files))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(files, key = { it.path }) { file ->
            RecentFileRow(
                file = file,
                onClick = { onOpenFile(file.path) },
                onLongClick = { onLongPressFile(file) },
            )
        }
    }
}

@Composable
private fun DirectoryBrowser(
    currentPath: String,
    items: List<WorkspaceFileItem>,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenFile: (String) -> Unit,
    onLongPressFile: (WorkspaceFileItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Breadcrumb(
            currentPath = currentPath,
            onNavigate = onNavigate,
            onNavigateUp = onNavigateUp,
        )
        if (items.isEmpty()) {
            EmptyState(
                message = stringResource(
                    if (currentPath.isEmpty()) R.string.workspace_no_files else R.string.workspace_empty_folder,
                )
            )
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items, key = { it.path }) { entry ->
                if (entry.isDirectory) {
                    FolderRow(folder = entry, onClick = { onNavigate(entry.path) })
                } else {
                    FileRow(
                        file = entry,
                        onClick = { onOpenFile(entry.path) },
                        onLongClick = { onLongPressFile(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(
    currentPath: String,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (currentPath.isNotEmpty()) {
            IconButton(onClick = onNavigateUp, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Lucide.ArrowLeft,
                    contentDescription = stringResource(R.string.workspace_back_to_parent),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = currentPath.isNotEmpty()) { onNavigate("") }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            color = Color.Transparent,
        ) {
            Text(
                text = "/",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (currentPath.isEmpty())
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.primary,
            )
        }
        if (currentPath.isNotEmpty()) {
            val parts = currentPath.split('/').filter { it.isNotEmpty() }
            parts.forEachIndexed { index, name ->
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val pathSoFar = parts.subList(0, index + 1).joinToString("/")
                val isCurrent = index == parts.lastIndex
                Surface(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !isCurrent) { onNavigate(pathSoFar) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    color = Color.Transparent,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (isCurrent)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: WorkspaceFileItem, onClick: () -> Unit) {
    val appLocale = LocalContext.current.appLocale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.Folder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatTime(folder.lastModified, appLocale),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: WorkspaceFileItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    val extColor = extColor(file.extension)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.FileText,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildMeta(file, appLocale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        ExtBadge(file.extension, extColor)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentFileRow(
    file: WorkspaceFileItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    val extColor = extColor(file.extension)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.FileText,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildRecentMeta(file, appLocale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ExtBadge(file.extension, extColor)
    }
}

@Composable
private fun ExtBadge(extension: String, color: androidx.compose.ui.graphics.Color) {
    val appLocale = LocalContext.current.appLocale()
    if (extension.isBlank()) return
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = extension.uppercase(appLocale).take(4),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = color,
        )
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 48.dp),
        )
    }
}

@Composable
private fun extColor(extension: String): androidx.compose.ui.graphics.Color = when (extension) {
    "md" -> MaterialTheme.colorScheme.primary
    "json" -> MaterialTheme.colorScheme.tertiary
    "html", "htm" -> MaterialTheme.colorScheme.secondary
    "png", "jpg", "jpeg", "gif", "webp" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun buildMeta(file: WorkspaceFileItem, locale: Locale): String {
    val parts = mutableListOf<String>()
    file.sizeBytes?.let { parts.add(formatSize(it, locale)) }
    if (file.lastModified > 0) parts.add(formatTime(file.lastModified, locale))
    return parts.joinToString(" · ")
}

@Composable
private fun buildRecentMeta(file: WorkspaceFileItem, locale: Locale): String {
    val parent = file.path.substringBeforeLast('/', "").ifEmpty { "/" }
    val parts = mutableListOf(parent)
    file.sizeBytes?.let { parts.add(formatSize(it, locale)) }
    if (file.lastModified > 0) parts.add(formatTime(file.lastModified, locale))
    return parts.joinToString(" · ")
}

@Composable
internal fun formatSize(bytes: Long): String =
    formatSize(bytes, LocalContext.current.appLocale())

private fun formatSize(bytes: Long, locale: Locale): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "${"%.1f".format(locale, bytes.toDouble() / (1024 * 1024))}MB"
}

@Composable
internal fun formatTime(epochMs: Long): String =
    formatTime(epochMs, LocalContext.current.appLocale())

@Composable
private fun formatTime(epochMs: Long, locale: Locale): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000 -> stringResource(R.string.workspace_time_just_now)
        diff < 3600_000 -> stringResource(R.string.workspace_time_minutes_ago, diff / 60_000)
        diff < 86_400_000 -> stringResource(R.string.workspace_time_hours_ago, diff / 3600_000)
        else -> SimpleDateFormat("MM-dd HH:mm", locale).format(Date(epochMs))
    }
}

private fun shareWorkspaceFile(
    context: Context,
    file: File?,
    item: WorkspaceFileItem,
) {
    if (file == null || !file.isFile) {
        Toast.makeText(context, R.string.workspace_file_not_shareable, Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(item.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.workspace_share_file_title, item.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onFailure { error ->
        Toast.makeText(
            context,
            error.message ?: context.getString(R.string.workspace_share_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
