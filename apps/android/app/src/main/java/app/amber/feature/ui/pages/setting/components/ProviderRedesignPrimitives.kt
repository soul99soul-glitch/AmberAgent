package app.amber.feature.ui.pages.setting.components

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.core.utils.writeClipboardText
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.reflect.KClass

internal data class ProviderSegOption<T>(
    val value: T,
    val label: String,
)

@Composable
internal fun ProviderSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier.padding(top = 22.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("//", style = type.eyebrow, color = t.accent)
        Text(text.uppercase(), style = type.eyebrow, color = t.ink3, maxLines = 1)
        if (count != null) {
            Text("· $count", style = type.meta.copy(fontSize = 11.sp), color = t.ink4)
        }
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(t.line)
        )
    }
}

@Composable
internal fun ProviderCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
internal fun ProviderHairline(modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(t.line)
    )
}

@Composable
internal fun ProviderMonogram(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    enabled: Boolean = true,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val displayText = text.trim().take(4).ifBlank { "AI" }
    val fontRatio = when {
        displayText.length >= 4 -> 0.27f
        displayText.length == 3 -> 0.31f
        else -> 0.33f
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(t.surface2)
            .border(1.dp, t.line, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = displayText,
            style = type.meta.copy(fontSize = (size.value * fontRatio).sp, fontWeight = FontWeight.Bold),
            color = if (enabled) t.ink2 else t.ink4,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
internal fun ProviderLiveDot(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 6.dp,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .size(size)
            .background(if (enabled) t.signal else t.ink4, CircleShape)
    )
}

@Composable
internal fun ProviderIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 36.dp,
    iconSize: Dp = 19.dp,
    rotate180: Boolean = false,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (tint == Color.Unspecified) t.accent else tint,
            modifier = Modifier
                .size(iconSize)
                .then(
                    if (rotate180) {
                        Modifier.graphicsLayerRotation(180f)
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

private fun Modifier.graphicsLayerRotation(degrees: Float): Modifier =
    graphicsLayer { rotationZ = degrees }

@Composable
internal fun <T> ProviderPillSeg(
    options: List<ProviderSegOption<T>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(t.surface2)
            .border(1.dp, t.line2, RoundedCornerShape(11.dp))
            .padding(3.dp),
    ) {
        options.forEach { option ->
            val on = option.value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) t.raised else Color.Transparent)
                    .pressable(onClick = { onSelected(option.value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = (if (mono) type.meta else type.secondary).copy(
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (on) t.accent else t.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ProviderFieldLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(start = 2.dp, bottom = 7.dp),
        style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
        color = t.ink3,
        maxLines = 1,
    )
}

/* v5 ledger: terminal-style filter — ▸ prompt + borderless input + hairline */
@Composable
internal fun ProviderTerminalFilter(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = "▸",
                style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = t.accent,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = type.meta.copy(fontSize = 12.sp, color = t.ink),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = type.meta.copy(fontSize = 12.sp),
                                color = t.ink4,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                ProviderSmallIconButton(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    onClick = { onValueChange("") },
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(t.line2)
        )
    }
}

@Composable
internal fun ProviderTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Dp = 46.dp,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val textStyle = (if (mono) type.meta else type.body).merge(
        LocalTextStyle.current.copy(color = t.ink)
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface2)
            .border(1.dp, t.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        readOnly = readOnly,
        singleLine = singleLine,
        textStyle = textStyle,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = t.ink4,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
internal fun ProviderLabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier.padding(top = 12.dp)) {
        ProviderFieldLabel(label)
        Box(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun ProviderSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val t = LocalAmberTokens.current
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface2)
            .border(1.dp, t.line, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = if (visible) value else value.toMaskedSecret(),
            onValueChange = { if (visible) onValueChange(it) },
            modifier = Modifier
                .weight(1f)
                .padding(top = 5.dp),
            readOnly = !visible,
            singleLine = false,
            textStyle = LocalAmberType.current.meta.copy(color = t.ink, lineHeight = 19.sp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = LocalAmberType.current.meta, color = t.ink4)
                    }
                    inner()
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ProviderSmallIconButton(
                imageVector = if (visible) HugeIcons.ViewOff else HugeIcons.View,
                contentDescription = null,
                onClick = { visible = !visible },
            )
            ProviderSmallIconButton(
                imageVector = HugeIcons.Copy01,
                contentDescription = null,
                onClick = { context.writeClipboardText(value) },
            )
        }
    }
}

@Composable
internal fun ProviderSmallIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (tint == Color.Unspecified) t.ink3 else tint,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
internal fun ProviderToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    Box(
        modifier = modifier
            .width(44.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (checked) t.accent else t.line2)
            .pressable(onClick = { onCheckedChange(!checked) })
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(t.raised, CircleShape)
        )
    }
}

@Composable
internal fun ProviderCapFlags(
    flags: List<String>,
    modifier: Modifier = Modifier,
    accentFirst: Boolean = true,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        flags.forEachIndexed { index, flag ->
            if (index > 0) {
                Text("·", style = type.meta.copy(fontSize = 11.sp), color = t.ink4)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = flag,
                style = type.meta.copy(fontSize = 11.sp),
                color = if (index == 0 && accentFirst) t.accent else t.ink3,
                maxLines = 1,
            )
            if (index < flags.lastIndex) {
                Spacer(Modifier.width(5.dp))
            }
        }
    }
}

@Composable
internal fun ProviderCommandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    imageVector: ImageVector? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) t.accent else t.surface2)
            .border(1.dp, if (accent) Color.Transparent else t.line, RoundedCornerShape(10.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = if (accent) t.accentInk else t.ink3,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            style = type.meta.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
            color = if (accent) t.accentInk else t.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* v5 ledger: bordered ghost text button ("+ 添加" / "导出" / "+ 全选") */
@Composable
internal fun ProviderGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageVector: ImageVector? = null,
    accent: Boolean = true,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, if (accent) t.accent else t.line2, RoundedCornerShape(3.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = if (accent) t.accent else t.ink3,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text,
            style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = if (accent) t.accent else t.ink2,
            maxLines = 1,
        )
    }
}

/* v5 ledger: top underline tabs (mono uppercase, 2dp accent underline) */
@Composable
internal fun ProviderUnderlineTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                val on = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .pressable(onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style = type.eyebrow,
                        color = if (on) t.accent else t.ink4,
                        modifier = Modifier.padding(top = 11.dp, bottom = 9.dp),
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .height(2.dp)
                            .background(if (on) t.accent else Color.Transparent)
                    )
                }
            }
        }
        ProviderHairline()
    }
}

/* v5 ledger: square tag chip; solid = filled accent (table add buttons) */
@Composable
internal fun ProviderSquareTag(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    solid: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val filled = selected && solid
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                when {
                    filled -> t.accent
                    selected -> t.accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                if (selected) t.accent else t.line2,
                RoundedCornerShape(3.dp),
            )
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(
                fontSize = 10.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = when {
                filled -> t.accentInk
                selected -> t.accent
                else -> t.ink3
            },
            maxLines = 1,
        )
    }
}

/* v5 ledger: micro label + content + trailing hairline */
@Composable
internal fun ProviderLedgerRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ProviderFieldLabel(label)
        Column(Modifier.fillMaxWidth(), content = content)
        Spacer(Modifier.height(12.dp))
        ProviderHairline()
    }
}

/* v5 ledger: split cancel/confirm bottom bar */
@Composable
internal fun ProviderSplitBar(
    cancelText: String,
    onCancel: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(modifier = modifier.fillMaxWidth()) {
        ProviderHairline()
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = cancelText,
                    style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink3,
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(t.line)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (confirmEnabled) Modifier.pressable(onClick = onConfirm) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = confirmText,
                    style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = if (confirmEnabled) t.accent else t.ink4,
                )
            }
        }
    }
}

@Composable
internal fun ProviderAuthBadge(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(t.surface2)
            .border(1.dp, t.line, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = if (text == "—") t.ink4 else t.ink3,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ProviderSheetGrabber() {
    val t = LocalAmberTokens.current
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .width(42.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(t.line2)
    )
}

internal fun String.toProviderMonogram(): String {
    val normalized = trim().lowercase()
    when {
        "deepseek" in normalized -> return "DS"
        "kimi" in normalized || "月之暗面" in normalized -> return "kimi"
        "mimo" in normalized || "小米" in normalized -> return "mimo"
        "zhipu" in normalized || "智谱" in normalized || "z.ai" in normalized -> return "Z.AI"
    }
    val words = trim().split(Regex("\\s+|-|_")).filter { it.isNotBlank() }
    val raw = when {
        words.size >= 2 -> words.take(2).joinToString("") { it.first().uppercase() }
        words.isNotEmpty() -> words.first().take(4)
        else -> "AI"
    }
    return raw.take(4).ifBlank { "AI" }
}

internal fun String.toProviderSlug(): String =
    trim()
        .lowercase()
        .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-")
        .trim('-')
        .ifBlank { "provider" }

internal fun ProviderSetting.providerSlugLabel(): String = when (this) {
    is ProviderSetting.OpenAI -> if (brand.name.lowercase() == "generic") {
        name.toProviderSlug()
    } else {
        brand.name.lowercase().replace('_', '-')
    }
    is ProviderSetting.Google -> "google-gemini"
    is ProviderSetting.Claude -> "claude"
}

internal fun ProviderSetting.providerAuthLabel(): String = when (this) {
    is ProviderSetting.OpenAI -> when (authMode) {
        OpenAIAuthMode.API_KEY -> if (apiKey.isBlank()) "—" else "key"
        OpenAIAuthMode.CODEX_OAUTH -> "oauth"
        OpenAIAuthMode.ZHIPU_CODING_PLAN,
        OpenAIAuthMode.KIMI_CODING_PLAN,
        OpenAIAuthMode.MIMO_CODING_PLAN,
        OpenAIAuthMode.MINIMAX_TOKEN_PLAN -> if (apiKey.isBlank()) "—" else "token"
    }
    is ProviderSetting.Google -> when (authMode) {
        GoogleAuthMode.API_KEY -> if (apiKey.isBlank() && privateKey.isBlank()) "—" else "key"
        GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH -> "oauth"
    }
    is ProviderSetting.Claude -> if (apiKey.isBlank()) "—" else "key"
}


internal fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) {
        return this
    }

    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> apiKey
        is ProviderSetting.Google -> apiKey
        is ProviderSetting.Claude -> apiKey
    }
    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            balanceOption = balanceOption,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl,
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            balanceOption = balanceOption,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl,
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            balanceOption = balanceOption,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
            apiKey = apiKey,
            baseUrl = convertedBaseUrl,
        )
        else -> error("Unsupported provider type: $type")
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
        }
    }

    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    }
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> copy(baseUrl = defaultBaseUrl)
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }
    return baseUrl == defaultBaseUrlForReset()
}

internal fun Int?.toContextLabel(): String = when (val v = this) {
    null -> ""
    in 1_000_000..Int.MAX_VALUE -> "${v / 1_000_000}M"
    in 1_000..999_999 -> "${v / 1_000}K"
    else -> v.toString()
}

private fun String.toMaskedSecret(): String =
    if (isBlank()) "" else "•".repeat(40)

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) {
        return targetDefaultBaseUrl
    }

    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder()
        .encodedPath(convertedPath)
        .build()
        .toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = normalizePath()
    val target = targetPath.normalizePath()

    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }

    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = trim()
    if (value.isEmpty() || value == "/") {
        return ""
    }
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST,
)
