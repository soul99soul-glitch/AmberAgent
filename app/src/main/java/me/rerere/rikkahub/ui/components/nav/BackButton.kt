package me.rerere.rikkahub.ui.components.nav

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.WorkspaceIconButton
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    val onBack = { navController.popBackStack() }
    val contentDescription = stringResource(R.string.back)

    if (BuildConfig.NOTION_LIKE) {
        val workspace = workspaceColors()
        IconButton(
            onClick = onBack,
            modifier = modifier,
        ) {
            Icon(
                imageVector = HugeIcons.ArrowLeft01,
                contentDescription = contentDescription,
                tint = workspace.ink,
            )
        }
        return
    }

    // Pulse circular outlined back button (36dp, 18dp icon). Inherits
    // the cream-on-ink-hairline treatment from WorkspaceIconButton's
    // Pulse-pivoted defaults — matches every other top-bar circular
    // affordance in the Pulse mockup at the same visual density.
    WorkspaceIconButton(
        onClick = onBack,
        modifier = modifier,
        size = 36.dp,
        iconSize = 18.dp,
        icon = HugeIcons.ArrowLeft01,
        contentDescription = contentDescription,
    )
}
