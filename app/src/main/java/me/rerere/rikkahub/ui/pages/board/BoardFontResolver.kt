package me.rerere.rikkahub.ui.pages.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.amber.feature.board.TodayBoardReadingFontMode
import me.rerere.rikkahub.data.font.FontPackState
import me.rerere.rikkahub.ui.theme.NotoSerifSC
import java.io.File

@Composable
internal fun rememberBoardReadingFontFamily(
    mode: TodayBoardReadingFontMode,
    fontPackId: String?,
    fontStates: List<FontPackState>,
): FontFamily? = remember(mode, fontPackId, fontStates) {
    when (mode) {
        TodayBoardReadingFontMode.SYSTEM -> null
        TodayBoardReadingFontMode.SERIF -> NotoSerifSC
        TodayBoardReadingFontMode.SLIDES_PACK -> {
            val file = fontStates
                .firstOrNull { it.pack.id == fontPackId && it.installed }
                ?.installedPath
                ?.let(::File)
                ?.takeIf { it.isFile }
            runCatching {
                file?.let { FontFamily(Font(it, weight = FontWeight.Normal)) }
            }.getOrNull() ?: NotoSerifSC
        }
    }
}
