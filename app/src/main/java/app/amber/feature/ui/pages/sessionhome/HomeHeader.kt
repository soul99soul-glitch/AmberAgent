package app.amber.feature.ui.pages.sessionhome

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.components.ds.AmberContinuousShape
import app.amber.feature.ui.components.ds.AmberDepthStyle
import app.amber.feature.ui.components.ds.amberPressHighlight
import app.amber.feature.ui.components.ds.amberRim
import app.amber.feature.ui.components.ds.amberShadow
import app.amber.feature.ui.theme.JetBrainsMonoFamily
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalThemePack
import java.time.LocalDate
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/**
 * A short top scrim that softens content crossing the viewport edge, including when paused.
 * Keeping it as a separate composable avoids adding any per-item drawing work to
 * the scrolling list, and leaves the initial, unscrolled home completely unobscured.
 */
@Composable
internal fun HomeScrollTopFade(
    hazeState: HazeState,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scrim = if (LocalThemePack.current == null) tokens.bg else Color.Transparent
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(90)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        Box(
            modifier = Modifier
                .testTag("home-scroll-top-fade")
                .fillMaxWidth()
                .height(statusBarHeight + 40.dp)
                .hazeEffect(state = hazeState) {
                    backgroundColor = tokens.bg
                    blurRadius = 12.dp
                    // Haze's mask is the low-cost gradient-blur path. It keeps
                    // the effect soft without the extra progressive-blur cost.
                    mask = Brush.verticalGradient(
                        colors = listOf(Color.White, Color.Transparent),
                    )
                }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(scrim, scrim.copy(alpha = scrim.alpha * 0.6f), Color.Transparent),
                    )
                )
        )
    }
}

/* ------------------------------------------------------------------ header --- */

/** Amber wordmark（mono + 闪烁光标）+ 日期 + 设置齿轮 + 头像。 */
@Composable
internal fun HomeHeader(
    settings: Settings,
    searchExpanded: Boolean,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    hazeState: HazeState? = null,
    hazeSourceActive: Boolean = false,
    /** Two-pane only: a header + replaces the floating pill; the narrow list drops labels to fit it. */
    onNewSession: (() -> Unit)? = null,
) {
    val compact = onNewSession != null
    val tokens = LocalAmberTokens.current
    val themePack = LocalThemePack.current
    val defaultUserName = stringResource(R.string.user_default_name)
    val searchInteraction = remember { MutableInteractionSource() }
    val settingsInteraction = remember { MutableInteractionSource() }
    val profileInteraction = remember { MutableInteractionSource() }
    val newSessionInteraction = remember { MutableInteractionSource() }
    val controlRadius = themePack?.design?.components?.controlRadius?.toFloat()?.dp ?: 16.dp
    // Compact (two-pane) search is icon-only: a circle matching the settings and avatar buttons.
    val searchShape = remember(controlRadius, compact) {
        if (compact) CircleShape else AmberContinuousShape(controlRadius)
    }
    val searchSurfaceModifier = if (themePack == null) {
        Modifier.background(tokens.surface2, searchShape)
    } else {
        Modifier.homeGlassChrome(hazeState, hazeSourceActive, tokens, searchShape, themePack.glassChrome)
    }
    val settingsSurfaceModifier = if (themePack == null) {
        Modifier.background(tokens.surface2, CircleShape)
    } else {
        Modifier.homeGlassChrome(hazeState, hazeSourceActive, tokens, CircleShape, themePack.glassChrome)
    }

    // 终端光标：1.05s steps 闪烁（前半段不透明，后半段隐藏）
    val transition = rememberInfiniteTransition(label = "home-cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1050
                1f at 0
                1f at 520
                0f at 525
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "home-cursor-alpha",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (themePack == null) {
                    Icon(
                        painter = painterResource(R.drawable.amber_wordmark),
                        contentDescription = "Amber",
                        modifier = Modifier.size(width = 100.dp, height = 26.dp),
                        tint = tokens.ink,
                    )
                    Spacer(Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(15.dp)
                            .background(tokens.accent.copy(alpha = cursorAlpha)),
                    )
                } else {
                    HomeThemedBrandMark(
                        pack = themePack,
                        color = tokens.ink,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .widthIn(min = if (compact) 44.dp else 58.dp)
                    .height(44.dp)
                    .then(
                        if (!searchExpanded) {
                            Modifier.clickable(
                                interactionSource = searchInteraction,
                                indication = null,
                                onClick = onOpenSearch,
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !searchExpanded,
                    // 不裁切：按压放大时胶囊会超出自身边界几个像素。
                    enter = fadeIn(animationSpec = tween(190)) +
                        expandHorizontally(
                            expandFrom = Alignment.CenterHorizontally,
                            animationSpec = tween(190),
                            clip = false,
                        ),
                    exit = fadeOut(animationSpec = tween(170)) +
                        shrinkHorizontally(
                            shrinkTowards = Alignment.CenterHorizontally,
                            animationSpec = tween(170),
                            clip = false,
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .amberPressHighlight(searchInteraction, searchShape, DpOffset(0.dp, 6.dp), hdrHighlight = true)
                            .amberShadow(searchShape, AmberDepthStyle.Chip)
                            .then(searchSurfaceModifier)
                            .amberRim(searchShape, AmberDepthStyle.Chip)
                            .then(if (compact) Modifier.width(32.dp) else Modifier.padding(horizontal = 12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.Search,
                                contentDescription = stringResource(R.string.history_page_search),
                                modifier = Modifier.size(15.dp),
                                tint = tokens.ink,
                            )
                            if (!compact) {
                                Text(
                                    text = stringResource(R.string.history_page_search),
                                    fontSize = 12.5.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = tokens.ink,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            if (!compact) Spacer(Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .testTag("home-header-settings")
                    .clickable(
                        interactionSource = settingsInteraction,
                        indication = null,
                        onClick = onOpenSettings,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .amberPressHighlight(settingsInteraction, CircleShape, DpOffset(6.dp, 6.dp), hdrHighlight = true)
                        .amberShadow(CircleShape, AmberDepthStyle.Chip)
                        .then(settingsSurfaceModifier)
                        .amberRim(CircleShape, AmberDepthStyle.Chip),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Settings,
                        contentDescription = stringResource(R.string.settings),
                        modifier = Modifier.size(18.dp),
                        tint = tokens.ink,
                    )
                }
            }

            if (!compact) Spacer(Modifier.width(4.dp))

            // 头像点击进资料页（onUpdate=null 时 UIAvatar 不弹换头像框，仅响应 onClick）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = profileInteraction,
                        indication = null,
                        onClick = onOpenProfile,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .amberPressHighlight(profileInteraction, CircleShape, DpOffset(6.dp, 6.dp), hdrHighlight = true)
                        .amberRim(CircleShape, AmberDepthStyle.Chip),
                    contentAlignment = Alignment.Center,
                ) {
                    UIAvatar(
                        name = settings.displaySetting.userNickname.ifBlank { defaultUserName },
                        value = settings.displaySetting.userAvatar,
                        size = 32.dp,
                        containerColor = tokens.accent,
                        showEditBadge = false,
                    )
                }
            }

            if (onNewSession != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(
                            interactionSource = newSessionInteraction,
                            indication = null,
                            onClick = onNewSession,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .amberPressHighlight(
                                newSessionInteraction,
                                CircleShape,
                                DpOffset(6.dp, 6.dp),
                                hdrHighlight = true,
                                glowTint = tokens.accent,
                            )
                            .amberShadow(CircleShape, AmberDepthStyle.Accent)
                            .background(tokens.accent, CircleShape)
                            .amberRim(CircleShape, AmberDepthStyle.Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.Plus,
                            contentDescription = stringResource(R.string.history_page_new_conversation),
                            modifier = Modifier.size(18.dp),
                            tint = tokens.accentInk,
                        )
                    }
                }
            }
        }

        Text(
            text = todayLabel(),
            modifier = Modifier.fillMaxWidth(),
            fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
            fontSize = 10.sp,
            letterSpacing = 0.15.sp,
            lineHeight = 13.sp,
            color = tokens.ink3,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
    }
}

@Composable
private fun todayLabel(): String {
    val now = LocalDate.now()
    val weekday = arrayOf(
        stringResource(R.string.session_home_weekday_sunday),
        stringResource(R.string.session_home_weekday_monday),
        stringResource(R.string.session_home_weekday_tuesday),
        stringResource(R.string.session_home_weekday_wednesday),
        stringResource(R.string.session_home_weekday_thursday),
        stringResource(R.string.session_home_weekday_friday),
        stringResource(R.string.session_home_weekday_saturday),
    )[now.dayOfWeek.value % 7]
    return stringResource(
        R.string.session_home_today_label,
        now.monthValue,
        now.dayOfMonth,
        weekday,
    )
}
