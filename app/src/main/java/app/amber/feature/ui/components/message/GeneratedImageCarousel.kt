package app.amber.feature.ui.components.message

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.Share2
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.provider.ProviderCatalog
import app.amber.core.event.AppEvent
import app.amber.core.event.AppEventBus
import app.amber.core.files.FilesManager
import app.amber.core.settings.findProvider
import app.amber.core.settings.getCurrentImageGenerationModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.components.ui.ImagePreviewDialog
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.io.File

/**
 * Inline carousel for images produced by the `generate_image` tool. One image
 * → single full-width card; 2–4 → horizontal scroll with the next card peeking
 * to advertise swipability. Tap any card to open the fullscreen lightbox
 * (reuses [ImagePreviewDialog] which already supports pinch-zoom + save button
 * via FilesManager.saveMessageImage). Long-press opens a bottom-sheet action
 * menu with save / copy / share.
 *
 * P6-02: when the configured image-generation provider declares edit
 * capability, the sheet gains a "修改" entry. Confirming opens a dialog that
 * shows the source image and a prefilled modification prompt; confirming
 * emits [AppEvent.EditGeneratedImage] which the chat screen routes back
 * through the generate_image tool's edit mode (equivalent chain — no new
 * parallel tool). Providers without edit capability never show the entry
 * (plan red line: no fake-support copy).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GeneratedImageCarousel(
    images: List<UIMessagePart.Image>,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val context = LocalContext.current
    val workspace = workspaceColors()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val filesManager: FilesManager = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val providerCatalog: ProviderCatalog = koinInject()
    val eventBus: AppEventBus = koinInject()

    // P6-02 capability gate — no edit entry when the provider cannot edit.
    // Keyed on the model/provider identity so switching the image-generation
    // model or provider recomputes the capability.
    val settingsSnapshot by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val editModel = settingsSnapshot.getCurrentImageGenerationModel()
    val editProvider = editModel?.findProvider(settingsSnapshot.providers)
    val editSupported = remember(editModel?.id, editProvider?.id) {
        val model = editModel ?: return@remember false
        val provider = editProvider ?: return@remember false
        providerCatalog.image(provider).supportsImageEdit(provider)
    }

    var lightboxStartIndex by remember { mutableIntStateOf(-1) }
    var actionSheetTarget by remember { mutableStateOf<UIMessagePart.Image?>(null) }
    var editTarget by remember { mutableStateOf<UIMessagePart.Image?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val containerWidth = maxWidth
        if (images.size == 1) {
            // Single image: take full available width, preserve aspect via
            // ContentScale.Fit + heightIn cap so portrait shots don't hog the
            // whole screen height.
            val image = images.first()
            ImageCard(
                url = image.url,
                widthDp = containerWidth,
                heightCapDp = (containerWidth.value * 1.4f).dp.coerceAtMost(420.dp),
                onClick = { lightboxStartIndex = 0 },
                onLongClick = { actionSheetTarget = image },
            )
        } else {
            // Multi-image: each card ~85% width with a 16dp leading inset so
            // the previous card's trailing edge can peek into view as the
            // user scrolls — gives ~10% peek on the right-most card and a
            // visible affordance that the row is scrollable.
            val cardWidth = (containerWidth.value * 0.85f).dp
            LazyRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(start = 0.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(images) { image ->
                    val index = images.indexOf(image)
                    ImageCard(
                        url = image.url,
                        widthDp = cardWidth,
                        heightCapDp = (cardWidth.value * 1.4f).dp.coerceAtMost(360.dp),
                        onClick = { lightboxStartIndex = index },
                        onLongClick = { actionSheetTarget = image },
                    )
                }
            }
        }
    }

    if (lightboxStartIndex >= 0) {
        // Coil's AsyncImage takes file:// URIs directly; ImagePreviewDialog's
        // imageLoader passes the model through, so we feed it the same urls.
        val urls = images.map { it.url }
        // ImagePreviewDialog starts at currentPage=0 internally; if the user
        // tapped a non-first card we want to open there. Wrap with a key on
        // lightboxStartIndex so internal state resets to the chosen page.
        key(lightboxStartIndex) {
            ImagePreviewDialog(
                images = urls.drop(lightboxStartIndex) + urls.take(lightboxStartIndex),
                onDismissRequest = { lightboxStartIndex = -1 },
            )
        }
    }

    actionSheetTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { actionSheetTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ActionRow(
                icon = Lucide.Download,
                label = "保存到相册",
                onClick = {
                    actionSheetTarget = null
                    scope.launch {
                        runCatching {
                            toaster.show("正在保存")
                            filesManager.saveMessageImage(context, target.url)
                            toaster.show(message = "已保存图片", type = ToastType.Success)
                        }.onFailure {
                            toaster.show(message = it.toString(), type = ToastType.Error)
                        }
                    }
                },
            )
            ActionRow(
                icon = Lucide.Copy,
                label = "复制图片",
                onClick = {
                    actionSheetTarget = null
                    runCatching { copyImageToClipboard(context, target.url) }
                        .onSuccess { toaster.show(message = "已复制图片", type = ToastType.Success) }
                        .onFailure { toaster.show(message = it.toString(), type = ToastType.Error) }
                },
            )
            ActionRow(
                icon = Lucide.Share2,
                label = "分享",
                onClick = {
                    actionSheetTarget = null
                    runCatching { shareImage(context, target.url) }
                        .onFailure { toaster.show(message = it.toString(), type = ToastType.Error) }
                },
            )
            if (editSupported && target.url.startsWith("file://")) {
                ActionRow(
                    icon = Lucide.PenLine,
                    label = "修改",
                    onClick = {
                        actionSheetTarget = null
                        editTarget = target
                    },
                )
            }
            // Tiny bottom inset so the last row isn't flush with the gesture bar.
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
        }
    }

    editTarget?.let { target ->
        EditGeneratedImageDialog(
            sourceImageUrl = target.url,
            onDismiss = { editTarget = null },
            onConfirm = { prompt ->
                editTarget = null
                scope.launch { eventBus.emit(AppEvent.EditGeneratedImage(sourceImageUrl = target.url, prompt = prompt)) }
            },
        )
    }
}

/**
 * P6-02 edit dialog: shows the source image being modified plus a text field
 * prefilled with a modification hint. Confirming hands the prompt + the
 * source URL to the chat screen via [AppEvent.EditGeneratedImage] (which
 * routes through the generate_image tool's edit mode).
 */
@Composable
private fun EditGeneratedImageDialog(
    sourceImageUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (prompt: String) -> Unit,
) {
    val workspace = workspaceColors()
    val prefill = "请修改这张图片："
    var prompt by remember { mutableStateOf(prefill) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = workspace.paper,
        titleContentColor = workspace.ink,
        textContentColor = workspace.ink,
        title = { Text("修改图片", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = workspace.row,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    AsyncImage(
                        model = sourceImageUrl,
                        contentDescription = "待修改的图片",
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("修改提示") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.trim().isNotEmpty() && prompt.trim() != prefill,
                onClick = { onConfirm(prompt.trim()) },
            ) {
                Text("生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ImageCard(
    url: String,
    widthDp: androidx.compose.ui.unit.Dp,
    heightCapDp: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier
            .width(widthDp)
            .heightIn(max = heightCapDp),
        shape = RoundedCornerShape(16.dp),
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        )
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.height(20.dp),
            tint = workspace.ink,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = workspace.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun copyImageToClipboard(context: Context, url: String) {
    val uri = resolveContentUri(context, url)
    val clip = ClipData.newUri(context.contentResolver, "image", uri)
    // Grant temporary read access to the clipboard host so paste targets
    // (other apps' input fields, screenshot tools, etc.) can decode the file.
    // Without this, the pasted URI is unusable on Android 10+ scoped storage.
    val description = clip.description
    description?.extras = description?.extras?.apply {
        putInt("flags", Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } ?: android.os.PersistableBundle().apply {
        putInt("flags", Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(clip)
}

private fun shareImage(context: Context, url: String) {
    val uri = resolveContentUri(context, url)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Share image")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/**
 * Generated images are stored in `filesDir/chat_images/{conversationId}/…`,
 * so they live in the app's private storage. Both clipboard and share intents
 * need a content:// URI that grants read permission to third-party apps —
 * we hand that off through the existing `FileProvider` declared in the
 * manifest (authority = "${BuildConfig.APPLICATION_ID}.fileprovider").
 */
private fun resolveContentUri(context: Context, url: String): android.net.Uri {
    val cleaned = url.removePrefix("file://")
    val file = File(cleaned)
    if (!file.exists()) return url.toUri()
    val authority = context.packageName + ".fileprovider"
    return FileProvider.getUriForFile(context, authority, file)
}
