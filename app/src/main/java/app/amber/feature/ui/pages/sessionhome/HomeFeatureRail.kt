package app.amber.feature.ui.pages.sessionhome

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.amber.agent.R
import app.amber.feature.ui.components.ds.AmberContinuousShape
import app.amber.feature.ui.components.ds.AmberDepthStyle
import app.amber.feature.ui.components.ds.amberPressHighlight
import app.amber.feature.ui.components.ds.amberRim
import app.amber.feature.ui.components.ds.amberShadow
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalThemePack
import app.amber.feature.home.ContinueCandidate
import app.amber.feature.home.ContinueSourceKind
import androidx.compose.material3.Text
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Pen

private data class FeatureEntry(
    val systemIcon: ImageVector,
    val phosphorIcon: HomeSessionIcon,
    val pixelIcon: HomePixelShortcut,
    val label: String,
    val onClick: () -> Unit,
)

private data class ContinueFeatureSpec(
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun continueFeatureSpec(sourceKind: ContinueSourceKind): ContinueFeatureSpec = when (sourceKind) {
    ContinueSourceKind.COUNCIL -> ContinueFeatureSpec(
        icon = Lucide.MessageCircle,
        label = stringResource(R.string.session_home_feature_council),
    )
    ContinueSourceKind.MINIAPP_DRAFT,
    ContinueSourceKind.MINIAPP_RUNNER,
    -> ContinueFeatureSpec(
        icon = Lucide.Grid2x2,
        label = stringResource(R.string.session_home_feature_mini_apps),
    )
    ContinueSourceKind.IMAGE_GENERATION -> ContinueFeatureSpec(
        icon = Lucide.Image,
        label = stringResource(R.string.setting_page_built_in_tools_image_generation),
    )
}

/** Keep the title visible when idle; only a real running summary may crossfade in. */
@Composable
private fun ContinueCandidateSubtitle(
    candidate: ContinueCandidate,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val hasRunningSummary = candidate.isRunning && candidate.summary.isNotBlank()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showSummary by remember(candidate.sourceKind, candidate.sourceId) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        lifecycle,
        candidate.sourceKind,
        candidate.sourceId,
        candidate.title,
        candidate.summary,
        candidate.isRunning,
    ) {
        showSummary = false
        if (hasRunningSummary) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                showSummary = false
                while (true) {
                    kotlinx.coroutines.delay(if (showSummary) 2_000L else 4_000L)
                    showSummary = !showSummary
                }
            }
        }
    }
    val subtitle = if (showSummary && hasRunningSummary) candidate.summary else candidate.title
    Crossfade(
        targetState = subtitle,
        animationSpec = tween(220),
        label = "continue-candidate-subtitle",
        modifier = modifier.fillMaxWidth(),
    ) { text ->
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = 15.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 继续卡片 + 功能入口：真实数据驱动，空数据时只保留功能入口。「继续」只恢复展示中的最新候选。 */
@Composable
internal fun HomeFeatureRail(
    resumeCandidate: ContinueCandidate?,
    onOpenResume: (ContinueCandidate) -> Unit,
    onMiniApps: () -> Unit,
    onWebMount: () -> Unit,
    onCouncil: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val components = LocalThemePack.current?.design?.components
    val cardRadius = components?.cardRadius?.toFloat()?.dp ?: 22.dp
    val controlRadius = components?.controlRadius?.toFloat()?.dp
    val features = listOf(
        FeatureEntry(
            Lucide.Grid2x2,
            HomeSessionIcon.SQUARES_FOUR,
            HomePixelShortcut.GRID,
            stringResource(R.string.session_home_feature_mini_apps),
            onMiniApps,
        ),
        FeatureEntry(
            Lucide.Earth,
            HomeSessionIcon.GLOBE,
            HomePixelShortcut.GLOBE,
            stringResource(R.string.session_home_feature_sites),
            onWebMount,
        ),
        FeatureEntry(
            Lucide.MessageCircle,
            HomeSessionIcon.CHAT_CIRCLE_DOTS,
            HomePixelShortcut.CHAT,
            stringResource(R.string.session_home_feature_council),
            onCouncil,
        ),
    )

    val railShape = AmberContinuousShape(cornerRadius = cardRadius)
    val resumeInteraction = remember { MutableInteractionSource() }
    val resumeButtonInteraction = remember { MutableInteractionSource() }
    val resumeIconShape = remember(controlRadius) { AmberContinuousShape(controlRadius ?: 12.dp) }
    val resumeButtonShape = remember(controlRadius) { AmberContinuousShape(controlRadius ?: 18.dp) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .amberShadow(railShape, AmberDepthStyle.Card)
            .clip(railShape)
            .background(tokens.surface)
            .amberRim(railShape, AmberDepthStyle.Card),
    ) {
        resumeCandidate?.let { candidate ->
            val feature = continueFeatureSpec(candidate.sourceKind)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = resumeInteraction,
                        indication = null,
                        onClick = { onOpenResume(candidate) },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .amberPressHighlight(
                            resumeInteraction,
                            resumeIconShape,
                            pressOffset = DpOffset(14.dp, 10.dp),
                            scaleOnPress = false,
                        )
                        .background(tokens.accent.copy(alpha = 0.12f), resumeIconShape)
                        .amberRim(resumeIconShape, AmberDepthStyle.Chip, drawBottom = false),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = feature.icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = tokens.accent,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = feature.label,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ContinueCandidateSubtitle(
                        candidate = candidate,
                        color = tokens.ink2,
                        fontSize = 10.5.sp,
                    )
                }
                Row(
                    modifier = Modifier
                        .amberPressHighlight(resumeButtonInteraction, resumeButtonShape)
                        .background(tokens.accent.copy(alpha = 0.14f), resumeButtonShape)
                        .amberRim(resumeButtonShape, AmberDepthStyle.Chip, drawBottom = false)
                        .clickable(
                            interactionSource = resumeButtonInteraction,
                            indication = null,
                            onClick = { onOpenResume(candidate) },
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = stringResource(R.string.session_home_continue),
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.accent,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(0.5.dp)
                    .background(tokens.line.copy(alpha = 0.34f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            features.forEach { feature ->
                val interaction = remember { MutableInteractionSource() }
                val featureShape = remember(controlRadius) { AmberContinuousShape(controlRadius ?: 10.dp) }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .amberPressHighlight(interaction, featureShape, scaleOnPress = false)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = feature.onClick,
                        )
                        .heightIn(min = 48.dp)
                        .padding(vertical = 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    HomeShortcutIcon(
                        systemIcon = feature.systemIcon,
                        phosphorIcon = feature.phosphorIcon,
                        pixelIcon = feature.pixelIcon,
                        contentDescription = feature.label,
                        modifier = Modifier.size(20.dp),
                        tint = tokens.ink2,
                    )
                    Text(
                        text = feature.label,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
