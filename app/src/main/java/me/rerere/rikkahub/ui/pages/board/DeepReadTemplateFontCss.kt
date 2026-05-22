package me.rerere.rikkahub.ui.pages.board

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.rerere.rikkahub.data.agent.board.DEEP_READ_FONT_SCALE_MAX
import me.rerere.rikkahub.data.agent.board.DEEP_READ_FONT_SCALE_MIN
import me.rerere.rikkahub.data.agent.board.TodayBoardReadingFontMode
import me.rerere.rikkahub.data.font.FontPackState
import java.io.File

@Composable
internal fun rememberDeepReadTemplateFontCss(
    mode: TodayBoardReadingFontMode,
    fontPackId: String?,
    fontStates: List<FontPackState>,
    fontScale: Float,
): String = remember(mode, fontPackId, fontStates, fontScale) {
    val safeScale = fontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX)
    when (mode) {
        TodayBoardReadingFontMode.SYSTEM -> deepReadFontVars(
            serif = "\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif",
            sans = "\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif",
            fontScale = safeScale,
        )

        TodayBoardReadingFontMode.SERIF -> """
            @font-face{
              font-family:"AmberDeepReadSerif";
              src:url("$DEEP_READ_BUILTIN_SERIF_FONT_URL") format("opentype");
              font-weight:400;
              font-style:normal;
              font-display:swap;
            }
            ${deepReadFontVars(
            serif = "\"AmberDeepReadSerif\",\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif",
            sans = "\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif",
            fontScale = safeScale,
        )}
        """.trimIndent()

        TodayBoardReadingFontMode.SLIDES_PACK -> {
            val state = fontStates
                .firstOrNull { it.pack.id == fontPackId && it.installed }
                ?.takeIf { File(it.installedPath.orEmpty()).isFile }
            if (state == null) {
                deepReadFontVars(
                    serif = "\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif",
                    sans = "\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif",
                    fontScale = safeScale,
                )
            } else {
                val family = "AmberDeepRead-${state.pack.id}"
                val format = when (state.pack.fileName.substringAfterLast('.', "").lowercase()) {
                    "otf" -> "opentype"
                    "ttf" -> "truetype"
                    "woff" -> "woff"
                    "woff2" -> "woff2"
                    else -> "truetype"
                }
                val source = "https://$DEEP_READ_SLIDES_FONT_HOST/fonts/${state.pack.id}/${state.pack.fileName}"
                val serif = "\"$family\",\"Noto Serif SC\",\"Source Han Serif SC\",\"Songti SC\",serif"
                val sans = "\"$family\",\"PingFang SC\",\"Source Han Sans SC\",\"Noto Sans SC\",system-ui,sans-serif"
                """
                    @font-face{
                      font-family:"$family";
                      src:url("$source") format("$format");
                      font-weight:400;
                      font-style:normal;
                      font-display:swap;
                    }
                    ${deepReadFontVars(serif = serif, sans = sans, fontScale = safeScale)}
                """.trimIndent()
            }
        }
    }
}

private fun deepReadFontVars(serif: String, sans: String, fontScale: Float): String =
    """
        :root{
          --deep-read-serif:$serif;
          --deep-read-sans:$sans;
          --deep-read-font-scale:$fontScale;
        }
    """.trimIndent()
