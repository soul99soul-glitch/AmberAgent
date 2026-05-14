package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext

/**
 * Renders a `search-images` fenced code block (one entry per line) as a grouped
 * image layout. SearchImageInjectorTransformer emits these blocks after generation
 * finish so the rendering rule lives in one place instead of being scattered
 * across every markdown image we touch.
 *
 * Entry format per line: `<url>` or `<url>|<caption>` (caption optional).
 *
 * Visual rules (mockup `docs/search-image-layout-mockup.html`, refined 2026-05-14):
 *   * **Multiple URLs** → horizontal Row, every cell `weight(1f)` + 16:9 cropped
 *     thumbnail, 56dp tall, 8dp gap. Captions ignored for the strip — mockup's
 *     thumbnail row is photo-only and any "title · domain" would just clip.
 *   * **Single URL** → tries to span the full width, but uses
 *     [ContentScale.Inside]+[Alignment.Center] so small images (e.g. logos) are
 *     never upscaled — they sit centred at their intrinsic size. Captions render
 *     below the image as a small grey "title · domain" line (the mockup
 *     `.img-caption` rule).
 *   * **Load failure** → cell collapses to height 0 instead of leaving a
 *     placeholder rectangle. Avoids the "wall of grey blocks" effect when half
 *     the search results 404 or hot-link-block their images.
 *   * **Tap** → opens [ImagePreviewDialog] zoomable viewer.
 */
@Composable
fun SearchImageBlock(
    urls: List<String>,
    modifier: Modifier = Modifier,
) {
    val entries = remember(urls) {
        urls.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { raw ->
                val pipeIdx = raw.indexOf('|')
                val url = if (pipeIdx >= 0) raw.substring(0, pipeIdx).trim() else raw
                val caption = if (pipeIdx >= 0) {
                    raw.substring(pipeIdx + 1).trim().takeIf { it.isNotBlank() }
                } else null
                if (!url.startsWith("http")) null else SearchImageEntry(url, caption)
            }
            .toList()
    }
    if (entries.isEmpty()) return

    when (entries.size) {
        1 -> SingleImage(
            entry = entries[0],
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )

        else -> Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                ThumbnailImage(
                    url = entry.url,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private data class SearchImageEntry(val url: String, val caption: String?)

@Composable
private fun SingleImage(
    entry: SearchImageEntry,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val export = LocalExportContext.current
    var failed by remember(entry.url) { mutableStateOf(false) }
    var showViewer by remember { mutableStateOf(false) }

    if (failed) return // collapse to zero height — no placeholder rectangle

    val request = remember(entry.url, export) {
        ImageRequest.Builder(context)
            .data(entry.url)
            .crossfade(false)
            .allowHardware(!export)
            .build()
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = request,
            contentDescription = entry.caption,
            modifier = Modifier
                .fillMaxWidth()
                // heightIn(max=800dp) is a safety net for the rare portrait source
                // (3:4 phone screenshots etc.) that would otherwise push the chat
                // bubble taller than the screen. Landscape sources — the common
                // case from Brave/Tavily/Reuters — scale by width via Inside and
                // never come close to the cap. Earlier 320dp cap caused visible
                // letterbox bands on 16:9 sources (reviewer flag H3).
                .heightIn(max = 800.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { showViewer = true },
            // Inside keeps small images (favicons, logos) at intrinsic size and
            // centred — they don't get upscaled. Large images scale down to fit
            // the container width, height follows the aspect ratio so the bubble
            // grows downward instead of stretching the photo.
            contentScale = ContentScale.Inside,
            alignment = Alignment.Center,
            onError = { failed = true },
        )
        entry.caption?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    if (showViewer) {
        ImagePreviewDialog(images = listOf(entry.url)) { showViewer = false }
    }
}

@Composable
private fun ThumbnailImage(
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val export = LocalExportContext.current
    var failed by remember(url) { mutableStateOf(false) }
    var showViewer by remember { mutableStateOf(false) }

    if (failed) {
        // Collapse this cell. Other cells in the Row keep their weight=1f slot —
        // the strip just gets visually narrower. Re-distributing the freed weight
        // would shift all the surviving thumbnails on each load, which looks worse
        // than a static gap.
        Box(modifier = modifier.height(0.dp))
        return
    }

    val request = remember(url, export) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
            .allowHardware(!export)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier
            // 56dp matches the mockup `.thumbnail-strip img` height; weight(1f)
            // on the Row distributes width so 2/3/4/5 thumbnails always fit on
            // one line. Crop normalises mixed-aspect sources into a clean strip.
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { showViewer = true },
        contentScale = ContentScale.Crop,
        onError = { failed = true },
    )
    if (showViewer) {
        ImagePreviewDialog(images = listOf(url)) { showViewer = false }
    }
}
