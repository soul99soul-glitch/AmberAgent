package app.amber.feature.ui.components.nav

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
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController

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

    WorkspaceIconButton(
        onClick = onBack,
        modifier = modifier,
        size = 34.dp,
        iconSize = 17.dp,
        icon = HugeIcons.ArrowLeft01,
        contentDescription = contentDescription,
    )
}
