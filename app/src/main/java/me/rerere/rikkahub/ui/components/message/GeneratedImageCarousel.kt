package me.rerere.rikkahub.ui.components.message

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalToaster
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
 * v1 deliberately omits a "regenerate" action — wiring that up cleanly needs
 * a new `SetComposerText` AppEvent that the chat input subscribes to. For now
 * the user re-asks the assistant naturally ("再画一张") which routes back
 * through the same generate_image tool.
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

    var lightboxStartIndex by remember { mutableIntStateOf(-1) }
    var actionSheetTarget by remember { mutableStateOf<UIMessagePart.Image?>(null) }

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
            // Multi-image: each card ~85% width, ~10dp peek for the next card.
            val cardWidth = (containerWidth.value * 0.85f).dp
            LazyRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(end = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(images) { image ->
                    val index = images.indexOf(image)
                    Surface(
                        modifier = Modifier
                            .heightIn(min = 180.dp),
                        color = workspace.paper,
                    ) {
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
            // Tiny bottom inset so the last row isn't flush with the gesture bar.
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
        }
    }
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
        shape = RoundedCornerShape(16.dp),
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .heightIn(max = heightCapDp)
                .padding(0.dp),
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
            .combinedClickable(onClick = onClick, onLongClick = onClick)
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

