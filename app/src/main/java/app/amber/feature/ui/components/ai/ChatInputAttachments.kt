package app.amber.feature.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessagePart
import app.amber.common.android.appTempFolder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.X
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Files
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.RefreshCw
import app.amber.agent.R
import app.amber.core.ai.vision.ImageAttachmentStatus
import app.amber.core.ai.vision.ImageAttachmentStatusKind
import app.amber.core.ai.vision.ImageAttachmentValidator
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.files.FilesManager
import app.amber.feature.ui.components.ui.permission.PermissionCamera
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.ChatInputAttachmentImport
import app.amber.feature.ui.hooks.ChatInputAttachmentImportStatus
import app.amber.feature.ui.hooks.ChatInputAttachmentKind
import app.amber.feature.ui.hooks.ChatInputState
import app.amber.feature.ui.pages.chat.LocalChatTheme
import org.koin.compose.koinInject
import java.io.File

/**
 * Composer attachment chips, file/image/video/audio picker buttons, full-screen
 * text editor, and the UCrop launcher hook. Extracted from the ChatInput god
 * class so the main composable stays focused on layout + state.
 *
 * Only `MediaFileInputRow`, `FilesPicker`, `FullScreenEditor`, `ImagePickButton`,
 * and `useCropLauncher` are called from the main `ChatInput()` composable —
 * those are `internal`. Other helpers stay `private` / preserve their original
 * top-level public visibility (TakePicButton / VideoPickButton / AudioPickButton
 * / FilePickButton were `public fun` before the extraction and stay that way).
 */

@Composable
internal fun MediaFileInputRow(
    state: ChatInputState,
    onRetryAttachment: (ChatInputAttachmentImport) -> Unit = {},
) {
    val filesManager: FilesManager = koinInject()
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val managedFiles by filesManager.observe().collectAsState(initial = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.messageContent = state.messageContent.filterNot { it == part }
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }

    fun removeImport(import: ChatInputAttachmentImport) {
        val removed = state.removeAttachmentImport(import.id) ?: return
        removed.part?.let { part ->
            if (state.shouldDeleteFileOnRemove(part)) {
                filesManager.deleteChatFiles(listOf(part.attachmentUrl().toUri()))
            }
        }
    }

    val trackedParts = state.attachmentImports.mapNotNull { it.part }.toSet()

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.attachmentImports.forEach { import ->
            key(import.id) {
                when (import.status) {
                    ChatInputAttachmentImportStatus.IMPORTING,
                    ChatInputAttachmentImportStatus.FAILED,
                        -> AttachmentImportChip(
                        import = import,
                        onRemove = { removeImport(import) },
                        onRetry = { onRetryAttachment(import) },
                        onErrorClick = {
                            import.errorMessage?.let { toaster.show(it, type = ToastType.Error) }
                        },
                    )

                    ChatInputAttachmentImportStatus.READY -> {
                        import.part?.let { part ->
                            AttachmentPartChip(
                                part = part,
                                imported = import,
                                settings = settings,
                                displayNameByRelativePath = displayNameByRelativePath,
                                displayNameByFileName = displayNameByFileName,
                                toaster = toaster,
                                onRemove = { removeImport(import) },
                            )
                        }
                    }
                }
            }
        }
        state.messageContent.fastForEach { part ->
            if (part !in trackedParts) {
                AttachmentPartChip(
                    part = part,
                    imported = null,
                    settings = settings,
                    displayNameByRelativePath = displayNameByRelativePath,
                    displayNameByFileName = displayNameByFileName,
                    toaster = toaster,
                    onRemove = { removePart(part, part.attachmentUrl()) },
                )
            }
        }
    }
}

@Composable
private fun AttachmentPartChip(
    part: UIMessagePart,
    imported: ChatInputAttachmentImport?,
    settings: app.amber.core.settings.Settings,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
    toaster: ToasterState,
    onRemove: () -> Unit,
) {
    val importedStatus = imported?.let { import ->
        when {
            import.readWarning != null -> stringResource(R.string.parity_attachment_read_failed)
            import.textWasTruncated -> stringResource(R.string.parity_attachment_truncated)
            import.sizeBytes != null -> formatAttachmentSize(import.sizeBytes)
            else -> stringResource(R.string.parity_attachment_ready)
        }
    }
    val importedStatusColor = if (imported?.readWarning != null) {
        MaterialTheme.colorScheme.error
    } else {
        LocalChatTheme.current.accentDeep.copy(alpha = 0.78f)
    }
    val onStatusClick: (() -> Unit)? = imported?.readWarning?.let { warning ->
        { toaster.show(warning, type = ToastType.Error) }
    }
    val titleFor = { fallback: String ->
        imported?.displayName ?: attachmentNameFromUrl(
            url = part.attachmentUrl(),
            fallback = fallback,
            displayNameByRelativePath = displayNameByRelativePath,
            displayNameByFileName = displayNameByFileName,
        )
    }

    when (part) {
        is UIMessagePart.Image -> {
            val status by produceState(
                ImageAttachmentValidator.checking(),
                part.url,
                settings.chatModelId,
                settings.ocrModelId,
                settings.providers,
            ) {
                value = withContext(Dispatchers.IO) {
                    ImageAttachmentValidator.inspectImage(part, settings)
                }
            }
            AttachmentChip(
                title = titleFor("image"),
                leading = {
                    ImageAttachmentPreview(
                        url = part.url,
                        status = status,
                        onStatusClick = {
                            if (status.blocksSend) {
                                toaster.show(status.message, type = ToastType.Error)
                            }
                        },
                    )
                },
                statusText = importedStatus,
                statusColor = importedStatusColor,
                onStatusClick = onStatusClick,
                onRemove = onRemove,
            )
        }

        is UIMessagePart.Video -> AttachmentChip(
            title = titleFor("video"),
            leading = { AttachmentLeadingIcon(icon = Lucide.Video) },
            statusText = importedStatus,
            statusColor = importedStatusColor,
            onStatusClick = onStatusClick,
            onRemove = onRemove,
        )

        is UIMessagePart.Audio -> AttachmentChip(
            title = titleFor("audio"),
            leading = { AttachmentLeadingIcon(icon = Lucide.Music) },
            statusText = importedStatus,
            statusColor = importedStatusColor,
            onStatusClick = onStatusClick,
            onRemove = onRemove,
        )

        is UIMessagePart.Document -> AttachmentChip(
            title = titleFor(part.fileName),
            leading = { AttachmentLeadingIcon(icon = Lucide.FileText) },
            statusText = importedStatus,
            statusColor = importedStatusColor,
            onStatusClick = onStatusClick,
            onRemove = onRemove,
        )

        else -> Unit
    }
}

@Composable
private fun AttachmentImportChip(
    import: ChatInputAttachmentImport,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onErrorClick: () -> Unit,
) {
    val isFailed = import.status == ChatInputAttachmentImportStatus.FAILED
    val statusText = when {
        isFailed -> stringResource(R.string.parity_attachment_import_failed_short)
        else -> stringResource(R.string.parity_attachment_importing)
    }
    val statusColor = if (isFailed) MaterialTheme.colorScheme.error else LocalChatTheme.current.accentDeep
    AttachmentChip(
        title = import.displayName,
        leading = { AttachmentLeadingIcon(icon = import.kind.icon) },
        statusText = statusText,
        statusColor = statusColor,
        onStatusClick = if (isFailed) onErrorClick else null,
        onRetry = if (isFailed) onRetry else null,
        onRemove = onRemove,
    )
}

private val ChatInputAttachmentKind.icon: ImageVector
    get() = when (this) {
        ChatInputAttachmentKind.IMAGE -> Lucide.Image
        ChatInputAttachmentKind.VIDEO -> Lucide.Video
        ChatInputAttachmentKind.AUDIO -> Lucide.Music
        ChatInputAttachmentKind.DOCUMENT -> Lucide.FileText
    }

private fun UIMessagePart.attachmentUrl(): String = when (this) {
    is UIMessagePart.Image -> url
    is UIMessagePart.Video -> url
    is UIMessagePart.Audio -> url
    is UIMessagePart.Document -> url
    else -> ""
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> "${bytes / (1024L * 1024L * 1024L)} GB"
}

@Composable
private fun ImageAttachmentPreview(
    url: String,
    status: ImageAttachmentStatus,
    onStatusClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                enabled = status.blocksSend,
                onClick = onStatusClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(24.dp)) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = stringResource(R.string.chat_input_attachment_status),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // CHECKING 状态显示一个旋转的加载指示器，而不是几乎不可见的灰色小点
            // 这样用户能明确知道图片正在被处理（大图片压缩可能需要较长时间）
            if (status.kind == ImageAttachmentStatusKind.CHECKING) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 1.5.dp,
                )
            } else {
                Surface(
                    modifier = Modifier
                        .size(7.dp)
                        .align(Alignment.TopEnd),
                    shape = CircleShape,
                    color = status.dotColor(),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {}
            }
        }
    }
}

@Composable
private fun ImageAttachmentStatus.dotColor(): Color = when (kind) {
    ImageAttachmentStatusKind.CHECKING -> workspaceColors().muted
    ImageAttachmentStatusKind.READY -> Color(0xFF2EAD5B)
    ImageAttachmentStatusKind.FALLBACK -> Color(0xFFFFB020)
    ImageAttachmentStatusKind.BLOCKED -> MaterialTheme.colorScheme.error
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    statusText: String? = null,
    statusColor: Color = LocalChatTheme.current.accentDeep.copy(alpha = 0.78f),
    onStatusClick: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onRemove: () -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    val chipBg = if (chatTheme.isDark) chatTheme.toolPillBg else chatTheme.accentTint
    val chipInk = if (chatTheme.isDark) chatTheme.accent else chatTheme.accentDeep
    Surface(
        shape = RoundedCornerShape(50),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = chipBg,
        contentColor = chipInk,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall) {
            Row(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .padding(start = 8.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                leading()
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = 36.dp, max = 210.dp),
                )
                if (!statusText.isNullOrBlank()) {
                    Text(
                        text = statusText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = statusColor,
                        modifier = Modifier
                            .widthIn(min = 24.dp, max = 84.dp)
                            .then(
                                if (onStatusClick != null) {
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable(onClick = onStatusClick)
                                } else {
                                    Modifier
                                }
                            ),
                    )
                }
                if (onRetry != null) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(40.dp)
                            .clickable(onClick = onRetry),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.RefreshCw,
                            contentDescription = stringResource(R.string.parity_attachment_retry),
                            tint = chipInk.copy(alpha = 0.82f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(48.dp)
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.chat_input_remove_attachment),
                        tint = chipInk.copy(alpha = 0.72f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: ImageVector,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = LocalChatTheme.current.let { if (it.isDark) it.accent else it.accentDeep },
        modifier = Modifier.size(20.dp),
    )
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
): String {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val relativePath = parsed?.path?.substringAfter("/files/", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    if (relativePath != null) {
        displayNameByRelativePath[relativePath]?.let { return it }
    }

    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        displayNameByFileName[storedFileName]?.let { return it }
        return storedFileName
    }

    return fallback
}

@Composable
internal fun FilesPicker(
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
) {
    val settings = LocalSettings.current
    val provider = settings.getCurrentChatModel()?.findProvider(providers = settings.providers)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TakePicButton(onLaunchCamera = onTakePic)

            ImagePickButton(onClick = onPickImage)

            if (provider != null && provider is ProviderSetting.Google) {
                VideoPickButton(onClick = onPickVideo)

                AudioPickButton(onClick = onPickAudio)
            }

            FilePickButton(onClick = onPickFile)
        }
    }
}

@Composable
internal fun FullScreenEditor(
    state: ChatInputState, onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit, onCleanup: (() -> Unit)? = null
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            cropOutputUri?.let { croppedUri ->
                onCroppedImageReady(croppedUri)
            }
        }
        // Clean up crop output file
        cropOutputUri?.toFile()?.delete()
        cropOutputUri = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, "crop_output_${System.currentTimeMillis()}.jpg")
        cropOutputUri = Uri.fromFile(outputFile)

        val cropIntent = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(true)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            setCompressionFormat(Bitmap.CompressFormat.PNG)
        }).withMaxResultSize(4096, 4096).getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}

@Composable
internal fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(Lucide.Image, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    val cameraPermission = rememberPermissionState(PermissionCamera)

    // 使用权限管理器包装
    PermissionManager(
        permissionState = cameraPermission
    ) {
        BigIconTextButton(icon = {
            Icon(Lucide.Camera, null)
        }, text = {
            Text(stringResource(R.string.take_picture))
        }) {
            if (cameraPermission.allRequiredPermissionsGranted) {
                onLaunchCamera()
            } else {
                // 请求权限
                cameraPermission.requestPermissions()
            }
        }
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(Lucide.Video, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(Lucide.Music, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(Lucide.Files, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}


@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(Lucide.Image, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
