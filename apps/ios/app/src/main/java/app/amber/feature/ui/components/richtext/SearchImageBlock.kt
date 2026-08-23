package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import app.amber.feature.ui.components.ui.ImagePreviewDialog
import app.amber.feature.ui.components.ui.LocalExportContext

/**
 * Renders a grouped search image layout. The message renderer derives these
 * entries from search tool output so image presentation stays outside assistant
 * markdown text.
 *
 * Entry format per line: `<url>` or `<url>|<caption>` (caption optional).
 *
 * Visual rules (mockup `docs/search-image-layout-mockup.html`, refined 2026-05-14):
 *   * **Single URL** → tries to span the full width, but uses
 *     [ContentScale.Inside]+[Alignment.Center] so small images (e.g. logos) are
 *     never upscaled — they sit centred at their intrinsic size. Captions render
 *     below the image as a small grey "title · domain" line (the mockup
 *     `.img-caption` rule).
 *   * **2–3 URLs** → horizontal Row, every cell `weight(1f)` + 4:3 cropped
 *     thumbnail (height follows width via aspectRatio). All cells fit on screen
 *     without scrolling.
 *   * **4+ URLs** → horizontally scrollable LazyRow. Per-cell width sized so the
 *     viewport shows 2 full thumbs + ~15% of the third — a visual hint that the
 *     strip can be scrolled, same affordance as the markdown table fallback.
 *     User swipes left to reveal more; right-edge thumbs slide in, left-edge
 *     thumbs slide out of view.
 *   * **Load failure** → keep the measured cell stable so a transient image
 *     error does not permanently collapse a thumbnail during scroll/viewer
 *     navigation.
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
                val normalizedUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("//") -> "https:$url"
                    else -> null
                }
                normalizedUrl?.let { SearchImageEntry(it, caption) }
            }
            .toList()
    }
    if (entries.isEmpty()) return

    when {
        entries.size == 1 -> SingleImage(
            entry = entries[0],
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )

        entries.size <= 3 -> Row(
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

        // 4+ thumbs: switch to horizontally scrollable strip. The viewport
        // shows 2 thumbs in full + ~15% of the third one as a scroll affordance.
        // BoxWithConstraints gives us the parent's maxWidth so per-cell width
        // can be computed exactly — without it we'd have to hard-code a dp
        // value that would look right on one screen size and wrong on another.
        else -> BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            val gap = 8.dp
            // Solve for cell width w such that: 2 * w + gap + 0.15 * w == maxWidth
            // i.e. fit 2.15 cells (2 full + 15% peek) inside the visible width,
            // with one inter-cell gap separating the two fully-visible cells.
            // The third cell's leading 15% sits flush against the second cell's
            // trailing gap, so we only count one gap in the equation. Result on
            // a typical 320dp message bubble: w ≈ (320 - 8) / 2.15 ≈ 145dp wide,
            // ~109dp tall (4:3) — comfortably above the legibility threshold.
            val cellWidth = (maxWidth - gap) / 2.15f
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(entries, key = { it.url }) { entry ->
                    ThumbnailImage(
                        url = entry.url,
                        modifier = Modifier.width(cellWidth),
                    )
                }
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
    var showViewer by remember(entry.url) { mutableStateOf(false) }

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
    var showViewer by remember(url) { mutableStateOf(false) }

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
            // Height follows width via aspectRatio(4:3). The width itself comes
            // from the caller's modifier (Modifier.weight(1f) inside a Row for
            // 2-3 thumbs; Modifier.width(cellWidth) inside a LazyRow for 4+
            // thumbs). With the 2.15-cells-per-viewport sizing in the LazyRow
            // path, cellWidth on a typical 320dp bubble is ~145dp → ~109dp tall,
            // so the aspectRatio gives a comfortably readable cell.
            // heightIn(min=72dp) is a legacy safety floor from the pre-LazyRow
            // implementation when 4-5 thumbs squeezed into a Row could end up
            // <50dp tall. With the LazyRow path replacing that case, it's now
            // only ever hit if the parent bubble is very narrow (<170dp wide)
            // — preferred over a sub-50dp sliver in that edge.
            // V3 设计稿: 网页预览缩略图 3:4 竖向 (不是 4:3 横向)
            // 因为预览内容是网页 / app / 文档类竖向素材
            .aspectRatio(3f / 4f)
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { showViewer = true },
        contentScale = ContentScale.Crop,
    )
    if (showViewer) {
        ImagePreviewDialog(images = listOf(url)) { showViewer = false }
    }
}
