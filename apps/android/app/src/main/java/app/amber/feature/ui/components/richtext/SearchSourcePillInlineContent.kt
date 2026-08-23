package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.theme.JetbrainsMono

internal fun AnnotatedString.Builder.appendSearchSourcePill(
    key: String,
    label: String,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    onClick: () -> Unit,
) {
    val cleanLabel = label.trim().ifBlank { "source" }
    inlineContents.putIfAbsent(
        key,
        InlineTextContent(
            placeholder = Placeholder(
                width = sourcePillWidth(cleanLabel).sp,
                height = 1.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
            children = {
                Box(
                    modifier = Modifier
                        .clickable(onClick = onClick)
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(colorScheme.tertiaryContainer.copy(0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = cleanLabel,
                        modifier = Modifier.wrapContentSize(),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = TextStyle(
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            fontFamily = JetbrainsMono,
                            color = colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Thin,
                        ),
                    )
                }
            },
        ),
    )
    appendInlineContent(key)
}

private fun sourcePillWidth(label: String): Float {
    val units = label.sumOf { char ->
        when {
            char.isWideGlyph() -> 2.0
            char.isUpperCase() -> 1.15
            else -> 1.0
        }
    }.coerceAtLeast(2.0)
    return (units * 7.2 + 8).toFloat()
}

private fun Char.isWideGlyph(): Boolean =
    code in 0x2E80..0x9FFF ||
        code in 0xAC00..0xD7AF ||
        code in 0xF900..0xFAFF ||
        code in 0xFF00..0xFFEF
