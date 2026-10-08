package app.amber.feature.ui.pages.board

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadMoments
import app.amber.feature.ui.components.ds.AmberContinuousShape
import app.amber.feature.ui.pages.novel.animationsEnabled
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Dices
import com.composables.icons.lucide.FileStack
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MoonStar
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Stamp
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// Newsroom surface — ported from iOS DeepReadApp/Sources:
// masthead (issue dateline + hidden seal egg), shake-scatter lucky pick, and the
// generation-in-progress "newsroom" page (stage rail + letterpress type tray).
// ─────────────────────────────────────────────────────────────────────────────

// MARK: Masthead

private val MASTHEAD_INSCRIPTIONS = listOf("深读", "慢读", "求真", "存疑", "博观", "约取")

/**
 * Newspaper masthead: a thick ink rule draws in, then a dateline
 * (`No.XXX · date`), night/festival badges and a serif headline. Tapping the
 * headline slams a rotating inscriptions seal — iOS's hidden masthead egg.
 */
@Composable
fun DeepReadMasthead(issue: Int, modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val haptics = LocalHapticFeedback.current
    val shown = remember { Animatable(0f) }
    var seal by remember { mutableStateOf<Int?>(null) }
    var slam by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { shown.animateTo(1f, tween(650)) }

    val locale = LocalConfiguration.current.locales[0]
    val dateline = remember(locale) {
        val pattern = if (locale.language == "zh") "M月d日 EEEE" else "EEEE, MMMM d"
        LocalDate.now().format(DateTimeFormatter.ofPattern(pattern, locale))
    }
    val festival = remember { DeepReadMoments.festival() }
    val night = DeepReadMoments.isNight()
    val sealDesc = seal?.let { stringResource(R.string.newsroom_seal_desc, MASTHEAD_INSCRIPTIONS[it]) }

    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        // The ink rule draws itself left-to-right, like a press roller.
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    scaleX = shown.value
                }
                .background(tokens.ink),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedContent(targetState = issue, label = "issue") { value ->
                    Text(
                        "No.%03d".format(value),
                        style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        color = tokens.ink3,
                    )
                }
                Text(
                    dateline.uppercase(locale),
                    style = type.meta.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.7.sp,
                    ),
                    color = tokens.ink3,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (night) {
                    // Amber "desk lamp" tint — same pair WorkspaceColors uses for warnings.
                    val warnTint = if (tokens.isDark) Color(0xFFD8B575) else Color(0xFF9C6A26)
                    MastheadBadge(tint = warnTint, icon = Lucide.MoonStar, text = stringResource(R.string.board_masthead_night))
                }
                if (festival != null) DeepReadFestivalBadge(festival)
            }
        }
        // Thin second rule under the dateline — thick rule / dateline / hairline /
        // headline is the classic broadsheet stack.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 9.dp)
                .height(1.dp)
                .background(tokens.line2),
        )
        Box(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.board_masthead_headline),
                style = type.screenTitle.copy(
                    fontFamily = deepReadEditorialSerif,
                    fontSize = if (deepReadEditorialSerif != null) 28.sp else 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 34.sp,
                    letterSpacing = 0.01.sp,
                ),
                color = tokens.ink,
                modifier = Modifier
                    .padding(
                        top = 10.dp,
                        bottom = 12.dp,
                        // Keep the tail of a wrapped headline clear of the seal.
                        end = if (seal != null) 54.dp else 0.dp,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // Consecutive taps always land a different inscription.
                        val next = ((seal ?: -1) + 1 + (0..<MASTHEAD_INSCRIPTIONS.size - 1).random()) % MASTHEAD_INSCRIPTIONS.size
                        seal = next
                        slam += 1
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
            )
            seal?.let {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .semantics { contentDescription = sealDesc ?: "" },
                ) {
                    DeepReadInkStamp(
                        text = MASTHEAD_INSCRIPTIONS[it],
                        size = 46.dp,
                        modifier = Modifier.deepReadStampSlam(slam),
                    )
                }
            }
        }
    }
}

@Composable
private fun MastheadBadge(tint: Color, icon: ImageVector, text: String) {
    val tokens = LocalAmberTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(AmberContinuousShape(20.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
        Text(
            text,
            style = LocalAmberType.current.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

// MARK: Shake scatter + lucky pick

/**
 * Deterministic "tossed newspaper" offsets. Each shake increments [seed] so the
 * cards fly differently every time, but a given (index, seed) always lands the
 * same — every row must return to exactly zero when [active] flips off.
 */
fun Modifier.deepReadScatter(index: Int, seed: Int, active: Boolean): Modifier = composed {
    // Deterministic per (index, seed) — each shake tosses differently, and every
    // row returns to exactly zero when [active] flips off. Targets are captured
    // once per activation so unrelated recompositions can't re-roll mid-flight.
    val (tx, ty, rot) = remember(index, seed, active) {
        if (!active) return@remember Triple(0f, 0f, 0f)
        val r = kotlin.random.Random(seed.toLong() * 31L + index)
        Triple((r.nextFloat() * 2f - 1f) * 70f, r.nextFloat() * -36f - 6f, (r.nextFloat() * 2f - 1f) * 9f)
    }
    val motion = animationsEnabled()
    val x by animateFloatAsState(tx, if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium) else snap(), label = "sx")
    val y by animateFloatAsState(ty, if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium) else snap(), label = "sy")
    val r by animateFloatAsState(rot, if (motion) spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium) else snap(), label = "sr")
    graphicsLayer {
        translationX = x.dp.toPx()
        translationY = y.dp.toPx()
        rotationZ = r
    }
}

data class LuckyPick(
    val title: String,
    val detail: String,
    val onRead: () -> Unit,
)

/**
 * Bottom floating banner for the shake easter egg — iOS shows a glass card with
 * a dice icon, the pick's provenance and a "读这篇" action.
 */
@Composable
fun DeepReadLuckyBanner(
    pick: LuckyPick,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val bounce = rememberInfiniteTransition(label = "dice")
    val diceAngle by bounce.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "diceAngle",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(AmberContinuousShape(22.dp))
            .background(tokens.surface.copy(alpha = 0.96f))
            .border(1.dp, tokens.line2, AmberContinuousShape(22.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Lucide.Dices,
            contentDescription = null,
            tint = tokens.accent,
            modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = diceAngle },
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.deepread_lucky_prefix, pick.detail),
                style = type.meta.copy(fontSize = 11.sp),
                color = tokens.ink3,
            )
            Text(
                pick.title,
                style = type.sessionTitle.copy(
                    fontFamily = deepReadEditorialSerif,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = tokens.ink,
                maxLines = 2,
            )
        }
        Box(
            Modifier.heightIn(min = 44.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.deepread_lucky_read),
                style = type.meta.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = tokens.accentInk,
                modifier = Modifier
                    .clip(AmberContinuousShape(16.dp))
                    .background(tokens.accent)
                    .clickable(onClick = pick.onRead)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(Lucide.X, contentDescription = stringResource(R.string.chat_page_close), tint = tokens.ink3, modifier = Modifier.size(15.dp))
        }
    }
}

// MARK: Newsroom (generation in progress)

enum class NewsroomStage(val titleRes: Int, val headlineRes: Int) {
    INTERVIEW(R.string.newsroom_stage_interview, R.string.newsroom_headline_interview),
    COLLATE(R.string.newsroom_stage_collate, R.string.newsroom_headline_collate),
    WRITE(R.string.newsroom_stage_write, R.string.newsroom_headline_write),
    PRESS(R.string.newsroom_stage_press, R.string.newsroom_headline_press),
    ;

    companion object {
        fun from(phase: DeepReadGenerationPhase?): NewsroomStage = when (phase) {
            DeepReadGenerationPhase.COLLECTING, DeepReadGenerationPhase.IDLE, null -> INTERVIEW
            DeepReadGenerationPhase.PLANNING -> COLLATE
            DeepReadGenerationPhase.WRITING -> WRITE
            DeepReadGenerationPhase.VERIFYING, DeepReadGenerationPhase.COMPLETE -> PRESS
        }
    }
}

private val NEWSROOM_NOTES = intArrayOf(
    R.string.newsroom_note_1, R.string.newsroom_note_2, R.string.newsroom_note_3,
    R.string.newsroom_note_4, R.string.newsroom_note_5,
)

/**
 * Full-page generation-in-progress state: stage rail, a letterpress type tray
 * setting the title, and rotating desk notes. Replaces the bare progress pill
 * while no article section is ready yet.
 */
@Composable
fun DeepReadNewsroomView(
    title: String,
    stage: NewsroomStage,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(300); tick += 1 }
    }

    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
        ) {
            StageRail(stage)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The stage symbol "breathes" while its room is working.
                val breathe = rememberInfiniteTransition(label = "stageBreathe")
                val breatheScale by breathe.animateFloat(
                    initialValue = 0.94f,
                    targetValue = 1.06f,
                    animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
                    label = "breatheScale",
                )
                AnimatedContent(targetState = stage, label = "stageIcon") { s ->
                    Icon(
                        stageIcon(s),
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(34.dp).scale(breatheScale),
                    )
                }
                AnimatedContent(targetState = stage, label = "stageHeadline") { s ->
                    Text(
                        stringResource(s.headlineRes),
                        style = type.sessionTitle.copy(
                            fontFamily = deepReadEditorialSerif,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = tokens.ink,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            TypeTray(title = title, tick = tick)
            DeskNote(tick = tick)
            Text(
                stringResource(R.string.newsroom_leave_hint),
                style = type.meta.copy(fontSize = 12.sp),
                color = tokens.ink3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun stageIcon(stage: NewsroomStage) = when (stage) {
    NewsroomStage.INTERVIEW -> Lucide.Search
    NewsroomStage.COLLATE -> Lucide.FileStack
    NewsroomStage.WRITE -> Lucide.PenLine
    NewsroomStage.PRESS -> Lucide.Stamp
}

@Composable
private fun StageRail(stage: NewsroomStage) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val pulse = rememberInfiniteTransition(label = "stagePulse")
    val pulseT by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "pulseT",
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top,
    ) {
        NewsroomStage.entries.forEachIndexed { i, item ->
            if (i > 0) {
                // Connector capsule fills as the press moves forward.
                val fill by animateFloatAsState(
                    if (item.ordinal <= stage.ordinal) 1f else 0f,
                    tween(450), label = "railFill$i",
                )
                Box(
                    Modifier.weight(1f).padding(top = 16.dp).height(2.dp).clip(CircleShape)
                        .background(tokens.line2),
                ) {
                    Box(Modifier.fillMaxWidth(fill).height(2.dp).clip(CircleShape).background(tokens.accent))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (item == stage) {
                        // Pulsing "someone is in this room" ring.
                        Box(
                            Modifier
                                .size(34.dp)
                                .scale(1f + 0.55f * pulseT)
                                .border(2.dp, tokens.accent.copy(alpha = (1f - pulseT)), CircleShape),
                        )
                    }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (item.ordinal < stage.ordinal) tokens.accent else tokens.surface)
                            .border(1.5.dp, if (item.ordinal <= stage.ordinal) tokens.accent else tokens.line2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (item.ordinal < stage.ordinal) Lucide.Check else stageIcon(item),
                            contentDescription = null,
                            tint = when {
                                item.ordinal < stage.ordinal -> tokens.accentInk
                                item == stage -> tokens.accent
                                else -> tokens.ink3
                            },
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Text(
                    stringResource(item.titleRes),
                    style = type.meta.copy(
                        fontSize = 11.sp,
                        fontWeight = if (item == stage) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (item.ordinal <= stage.ordinal) tokens.ink else tokens.ink3,
                )
            }
        }
    }
}

/**
 * The title's characters drop into a type case one glyph per tick, hold, then
 * the tray empties and starts over — iOS's letterpress flourish.
 */
@Composable
private fun TypeTray(title: String, tick: Int) {
    val tokens = LocalAmberTokens.current
    val glyphs = remember(title) {
        // Letters/digits only — punctuation and emoji stay out of the case.
        title.filter { it.isLetterOrDigit() }.take(10).toList()
    }
    val cycle = glyphs.size + 6
    val placed = if (cycle <= 0) 0 else (tick % cycle).coerceAtMost(glyphs.size)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        glyphs.forEachIndexed { index, glyph ->
            Box(
                Modifier
                    .width(28.dp).height(32.dp)
                    .border(1.dp, tokens.line2, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (index < placed) {
                    val drop = remember { Animatable(-28f) }
                    LaunchedEffect(Unit) {
                        drop.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .offset(y = drop.value.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(tokens.ink),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            glyph.toString(),
                            color = tokens.bg,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = deepReadEditorialSerif,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeskNote(tick: Int) {
    val tokens = LocalAmberTokens.current
    val index = (tick / 14) % NEWSROOM_NOTES.size
    Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                    (slideOutVertically { -it } + fadeOut())
            },
            label = "deskNote",
        ) { i ->
            Text(
                stringResource(NEWSROOM_NOTES[i]),
                style = LocalAmberType.current.body.copy(fontSize = 14.sp, fontStyle = FontStyle.Italic),
                color = tokens.ink3,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
