package app.amber.feature.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.core.settings.ThemeDesign
import app.amber.feature.ui.context.LocalToaster
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Undo2
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Controls for one exact try-on candidate, shown beside its import result. */
@Composable
fun ThemeTryOnHost(
    modifier: Modifier = Modifier,
    packageId: String,
    candidateDigest: String,
    manager: ThemePackageManager = koinInject(),
    isApplying: Boolean = false,
    onApplyRequest: (() -> Unit)? = null,
    onRestoreRequest: (() -> Unit)? = null,
) {
    val tryOn by manager.tryOn.collectAsState(initial = null)
    val candidate = tryOn?.takeIf {
        it.pkg.id == packageId && it.candidateDigest == candidateDigest
    } ?: return
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    var busy by remember(packageId, candidateDigest) { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(candidate.candidate.themePack?.design?.components?.controlRadius?.toFloat()?.dp ?: 18.dp),
        color = tokens.surface.copy(alpha = 0.94f),
        contentColor = tokens.ink,
        border = BorderStroke(0.7.dp, tokens.accent.copy(alpha = 0.34f)),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(30.dp)
                        .background(tokens.accent.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Palette, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(15.dp))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_theme_library_try_on_title),
                        style = type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = stringResource(R.string.setting_theme_library_try_on_summary),
                        style = type.secondary,
                        color = tokens.ink3,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = candidate.pkg.name,
                    style = type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = tokens.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ThemeTryOnMiniPreview(
                    design = candidate.candidate.themePack?.design,
                    canvasStyle = candidate.candidate.themePack?.canvasStyle,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Button(
                    onClick = {
                        if (manager.tryOn.value?.let {
                                it.pkg.id == packageId && it.candidateDigest == candidateDigest
                            } != true
                        ) {
                            toaster.show(context.getString(R.string.setting_theme_library_try_on_failed), type = ToastType.Info)
                            onRestoreRequest?.invoke()
                        } else {
                            val restored = manager.discardTryOn(packageId, candidateDigest)
                            if (!restored) {
                                toaster.show(context.getString(R.string.setting_theme_library_try_on_failed), type = ToastType.Info)
                            } else {
                                toaster.show(context.getString(R.string.chat_message_tool_status_succeeded), type = ToastType.Info)
                            }
                            onRestoreRequest?.invoke()
                        }
                    },
                    enabled = !busy && !isApplying,
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.surface2.copy(alpha = 0.86f),
                        contentColor = tokens.ink2,
                    ),
                ) {
                    Icon(Lucide.Undo2, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.setting_theme_library_restore), style = type.secondary.copy(fontWeight = FontWeight.SemiBold))
                }
                Button(
                    onClick = {
                        if (isApplying) {
                            Unit
                        } else if (manager.tryOn.value?.let {
                                it.pkg.id == packageId && it.candidateDigest == candidateDigest
                            } != true
                        ) {
                            toaster.show(context.getString(R.string.setting_theme_library_try_on_failed), type = ToastType.Info)
                            onRestoreRequest?.invoke()
                        } else if (onApplyRequest != null) {
                            onApplyRequest.invoke()
                        } else {
                            scope.launch {
                                busy = true
                                try {
                                    val result = manager.applyPrepared(packageId, candidateDigest)
                                    val message = when (result) {
                                        ThemePackageApplyResult.Applied,
                                        ThemePackageApplyResult.AlreadyApplied,
                                        -> {
                                            toaster.show(
                                                context.getString(R.string.chat_message_tool_status_succeeded),
                                                type = ToastType.Info,
                                            )
                                            null
                                        }
                                        ThemePackageApplyResult.Reverted -> context.getString(
                                            R.string.setting_theme_library_apply_reverted_error,
                                        )
                                        else -> context.getString(R.string.setting_theme_library_apply_error)
                                    }
                                    message?.let { toaster.show(it, type = ToastType.Info) }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    toaster.show(
                                        context.getString(R.string.setting_theme_library_try_on_failed),
                                        type = ToastType.Info,
                                    )
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy && !isApplying,
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.accent,
                        contentColor = tokens.accentInk,
                    ),
                ) {
                    Icon(Lucide.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(
                            if (busy || isApplying) R.string.chat_message_tool_status_running
                            else R.string.setting_theme_library_commit,
                        ),
                        style = type.secondary.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/** The same miniature surface, accent and text bars used by the iOS theme approval card. */
@Composable
private fun ThemeTryOnMiniPreview(design: ThemeDesign?, canvasStyle: String?) {
    val tokens = LocalAmberTokens.current
    val label = stringResource(R.string.setting_theme_library_import_preview)
    val shape = RoundedCornerShape(18.dp)
    Canvas(
        modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth().aspectRatio(1.5f)
            .clip(shape)
            .border(1.dp, tokens.line2, shape)
            .semantics { contentDescription = label },
    ) {
        drawRect(tokens.bg)
        themeGradientBrush(design?.gradient, tokens.isDark, size)?.let { drawRect(it) }
        design?.patterns?.forEach { drawThemePattern(it) }
        drawThemeCanvasStyle(canvasStyle, tokens.isDark)

        val scale = size.width / 180f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun extent(width: Float, height: Float) = Size(width * scale, height * scale)
        fun radius(value: Float) = CornerRadius(value * scale)
        val cardRadius = (design?.components?.cardRadius?.toFloat()?.times(0.5f) ?: 11f)
        drawRoundRect(tokens.surface, point(13f, 13f), extent(154f, 48f), radius(cardRadius))
        design?.components?.borderWidth?.toFloat()?.takeIf { it > 0f }?.let { width ->
            drawRoundRect(
                tokens.line, point(13f, 13f), extent(154f, 48f), radius(cardRadius),
                style = Stroke(width * scale),
            )
        }
        drawCircle(tokens.accent, 7.5f * scale, point(31.5f, 37f))
        drawRoundRect(tokens.ink2, point(48f, 28f), extent(58f, 6f), radius(3f))
        drawRoundRect(tokens.ink3, point(48f, 40f), extent(34f, 6f), radius(3f))
        drawRoundRect(tokens.surface2, point(13f, 71f), extent(154f, 7f), radius(3.5f))
        drawRoundRect(tokens.surface2, point(13f, 88f), extent(92f, 7f), radius(3.5f))
    }
}
