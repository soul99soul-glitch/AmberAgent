package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import app.amber.agent.R
import app.amber.feature.ui.components.ui.ImagePreviewDialog
import app.amber.feature.ui.components.ui.LocalExportContext
import app.amber.feature.ui.modifier.shimmer
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.feature.ui.utils.ChatPerfImageModelOverride
import app.amber.feature.ui.utils.ChatPerfProbeToken
import app.amber.feature.ui.utils.LocalImageReadinessDispose
import app.amber.feature.ui.utils.LocalImageReadinessProbe

/** Test hook: decoded pixel size after Coil success. Null in production. */
internal val LocalMarkdownImageDecodeProbe = staticCompositionLocalOf<((Int, Int) -> Unit)?> { null }

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val decodeProbe = LocalMarkdownImageDecodeProbe.current
    val readinessProbe = LocalImageReadinessProbe.current
    val readinessDispose = LocalImageReadinessDispose.current
    val readinessToken = remember(model, readinessProbe) {
        if (readinessProbe != null && !model.isNullOrBlank()) ChatPerfProbeToken.next() else 0
    }
    // Fixture-only URL -> local file mapping. Production leaves this null.
    val effectiveModel = remember(model) {
        model?.let { ChatPerfImageModelOverride.resolve?.invoke(it) } ?: model
    }
    // 必须记住请求：流式渲染每帧都会重组，每次新建 ImageRequest 会让 Coil 把它当成
    // 新请求重启加载，图还在屏幕里就退回占位/水波纹——这正是"滚回屏幕前一直是空白"。
    // data 是稳定 String，remember 后同一个请求复用内存缓存，命中即绘制。
    val coilModel = remember(effectiveModel, export, context, placeholder) {
        ImageRequest.Builder(context)
            .data(effectiveModel)
            .placeholder(placeholder)
            .crossfade(false)
            .allowHardware(!export)
            .build()
    }
    DisposableEffect(readinessToken) {
        onDispose {
            if (readinessToken != 0) readinessDispose?.invoke(readinessToken)
        }
    }
    var loading by remember(effectiveModel) { mutableStateOf(effectiveModel.isNullOrBlank()) }
    // 一旦这张图成功画过，就再也不用水波纹盖住它：水波纹是"还没有图"的提示，
    // 覆盖已画好的内容只会看起来像图片又变空白了。
    var loaded by remember(effectiveModel) { mutableStateOf(false) }
    SideEffect {
        if (readinessToken != 0) readinessProbe?.invoke(readinessToken, !loading)
    }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .shimmer(isLoading = loading && !loaded)
            .clickable {
                showImageViewer = true
            },
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = { state ->
            loading = false
            loaded = true
            val size = state.painter.intrinsicSize
            if (size.width > 0f && size.height > 0f) {
                decodeProbe?.invoke(size.width.toInt(), size.height.toInt())
            }
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(effectiveModel ?: "")) {
            showImageViewer = false
        }
    }
}
