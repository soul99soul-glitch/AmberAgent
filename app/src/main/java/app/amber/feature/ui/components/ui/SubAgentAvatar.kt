package app.amber.feature.ui.components.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.ui.pages.chat.ChatTheme
import app.amber.feature.ui.pages.chat.LocalChatTheme
import kotlinx.coroutines.delay

@Composable
fun SubAgentAvatar(
    id: String,
    name: String,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 20.dp,
    status: SubAgentRunStatus? = null,
) {
    val spec = remember(id, name) { SubAgentAvatarSpec.resolve(id = id, name = name) }
    val chatTheme = LocalChatTheme.current
    val palette = remember(spec, chatTheme) { spec.palette.themeAware(chatTheme) }
    val animating = status == SubAgentRunStatus.RUNNING
    var frame by remember(id, name) { mutableIntStateOf(0) }
    LaunchedEffect(animating, id, name) {
        if (!animating) {
            frame = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(FRAME_INTERVAL_MS)
            frame = (frame + 1) % FRAME_COUNT
        }
    }
    val alpha = when (status) {
        SubAgentRunStatus.FAILED,
        SubAgentRunStatus.CANCELLED,
        SubAgentRunStatus.TIMED_OUT,
        SubAgentRunStatus.INTERRUPTED -> 0.52f
        else -> 1f
    }

    Canvas(modifier = modifier.size(avatarSize)) {
        val canvasSize = size
        val cell = minOf(canvasSize.width, canvasSize.height) / SubAgentAvatarSpec.GRID_SIZE
        val left = (canvasSize.width - cell * SubAgentAvatarSpec.GRID_SIZE) / 2f
        val top = (canvasSize.height - cell * SubAgentAvatarSpec.GRID_SIZE) / 2f

        spec.rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, token ->
                val displayToken = if (animating) {
                    animatedToken(token, rowIndex, colIndex, frame)
                } else {
                    token
                }
                val color = spec.colorFor(displayToken, palette)?.copy(alpha = alpha) ?: return@forEachIndexed
                val bounceOffset = if (animating && frame == 1 && displayToken != '.') -cell * 0.18f else 0f
                drawRect(
                    color = color,
                    topLeft = Offset(left + colIndex * cell, top + rowIndex * cell + bounceOffset),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

private data class SubAgentAvatarSpec(
    val rows: List<String>,
    val palette: SubAgentAvatarPalette,
) {
    fun colorFor(token: Char, themedPalette: SubAgentAvatarPalette): Color? = when (token) {
        '1' -> themedPalette.main
        '2' -> themedPalette.accent
        '3' -> themedPalette.shadow
        else -> null
    }

    companion object {
        const val GRID_SIZE = 9

        fun resolve(id: String, name: String): SubAgentAvatarSpec {
            val key = id.lowercase()
            val nameKey = name.lowercase()
            return BUILT_IN_AVATARS[key]
                ?: BUILT_IN_AVATARS[nameKey]
                ?: generated(seedText = "$id:$name")
        }

        private fun generated(seedText: String): SubAgentAvatarSpec {
            var state = seedText.fold(0x6D2B79F5) { acc, char ->
                nextState(acc xor char.code)
            }
            val palette = GENERATED_PALETTES[positiveModulo(state, GENERATED_PALETTES.size)]
            val rows = MutableList(GRID_SIZE) { CharArray(GRID_SIZE) { '.' } }

            for (row in 1..7) {
                for (col in 1..4) {
                    state = nextState(state + row * 31 + col * 17)
                    val token = when (positiveModulo(state, 100)) {
                        in 0..42 -> '.'
                        in 43..82 -> '1'
                        in 83..94 -> '2'
                        else -> '3'
                    }
                    rows[row][col] = token
                    rows[row][GRID_SIZE - 1 - col] = token
                }
            }

            rows[1][4] = '2'
            rows[2][3] = '3'
            rows[2][4] = '1'
            rows[2][5] = '3'
            rows[3][2] = '1'
            rows[3][6] = '1'
            rows[6][3] = '1'
            rows[6][5] = '1'

            return SubAgentAvatarSpec(rows.map { it.concatToString() }, palette)
        }
    }
}

private data class SubAgentAvatarPalette(
    val main: Color,
    val accent: Color,
    val shadow: Color,
) {
    fun themeAware(theme: ChatTheme): SubAgentAvatarPalette =
        SubAgentAvatarPalette(
            main = lerp(main, theme.toolIconInk, if (theme.isDark) 0.55f else 0.45f),
            accent = lerp(accent, theme.accentTint, if (theme.isDark) 0.42f else 0.55f),
            shadow = lerp(shadow, theme.accentDeep, if (theme.isDark) 0.50f else 0.40f),
        )
}

private const val FRAME_COUNT = 3
private const val FRAME_INTERVAL_MS = 420L

private fun animatedToken(token: Char, row: Int, col: Int, frame: Int): Char = when {
    token == '.' -> token
    frame == 1 && token == '1' && (row + col) % 5 == 0 -> '2'
    frame == 2 && token == '2' && (row + col) % 2 == 0 -> '1'
    else -> token
}

private val BUILT_IN_AVATARS = mapOf(
    "explorer" to SubAgentAvatarSpec(
        rows = listOf(
            "..222....",
            ".21112...",
            "2111112..",
            "2112112..",
            "2111112..",
            ".21112...",
            "..212....",
            "..1......",
            ".........",
        ),
        palette = SubAgentAvatarPalette(
            main = Color(0xFF1677E8),
            accent = Color(0xFF58B7FF),
            shadow = Color(0xFF0A4D9F),
        ),
    ),
    "historian" to SubAgentAvatarSpec(
        rows = listOf(
            ".111.222.",
            "11112222.",
            "11212212.",
            "11212212.",
            "11212212.",
            "11212212.",
            "11112222.",
            ".111.222.",
            ".........",
        ),
        palette = SubAgentAvatarPalette(
            main = Color(0xFF9B5DE5),
            accent = Color(0xFFE0C3FF),
            shadow = Color(0xFF5B2CB6),
        ),
    ),
    "oracle" to SubAgentAvatarSpec(
        rows = listOf(
            ".........",
            "..222....",
            ".21112...",
            "2111112..",
            "2113112..",
            "2111112..",
            ".21112...",
            "..222....",
            ".........",
        ),
        palette = SubAgentAvatarPalette(
            main = Color(0xFFE83E3E),
            accent = Color(0xFFFF8B8B),
            shadow = Color(0xFFA90F24),
        ),
    ),
    "designer" to SubAgentAvatarSpec(
        rows = listOf(
            "..111....",
            ".12212...",
            "1222221..",
            "1212212..",
            "1222221..",
            ".1221....",
            "..11..33.",
            "......33.",
            ".........",
        ),
        palette = SubAgentAvatarPalette(
            main = Color(0xFF00A884),
            accent = Color(0xFFFFD166),
            shadow = Color(0xFFEF476F),
        ),
    ),
    "writer" to SubAgentAvatarSpec(
        rows = listOf(
            "......22.",
            ".....221.",
            "....221..",
            "...221...",
            "..221....",
            ".221.....",
            "111......",
            "111......",
            ".........",
        ),
        palette = SubAgentAvatarPalette(
            main = Color(0xFFEC4899),
            accent = Color(0xFFFFB4D8),
            shadow = Color(0xFF9D174D),
        ),
    ),
    "fixer" to SubAgentAvatarSpec(
        rows = listOf(
            "11....11.",
            "111..111.",
            ".111111..",
            "..1111...",
            "...11....",
            "..2112...",
            ".21..12..",
            "21....12.",
            ".........",
        ),
        palette = SubAgentAvatarPalette(
            main = Color(0xFF2563EB),
            accent = Color(0xFF93C5FD),
            shadow = Color(0xFF1E3A8A),
        ),
    ),
)

private val GENERATED_PALETTES = listOf(
    SubAgentAvatarPalette(Color(0xFF1D71E8), Color(0xFF7CC7FF), Color(0xFF0A4D9F)),
    SubAgentAvatarPalette(Color(0xFFEF4444), Color(0xFFFCA5A5), Color(0xFF991B1B)),
    SubAgentAvatarPalette(Color(0xFF7C3AED), Color(0xFFC4B5FD), Color(0xFF4C1D95)),
    SubAgentAvatarPalette(Color(0xFF0EA5E9), Color(0xFFBAE6FD), Color(0xFF075985)),
    SubAgentAvatarPalette(Color(0xFF10B981), Color(0xFFA7F3D0), Color(0xFF065F46)),
    SubAgentAvatarPalette(Color(0xFFF59E0B), Color(0xFFFDE68A), Color(0xFF92400E)),
    SubAgentAvatarPalette(Color(0xFFEC4899), Color(0xFFFBCFE8), Color(0xFF9D174D)),
)

private fun nextState(value: Int): Int {
    var x = if (value == 0) 0x45D9F3B else value
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return x
}

private fun positiveModulo(value: Int, modulo: Int): Int =
    ((value % modulo) + modulo) % modulo
