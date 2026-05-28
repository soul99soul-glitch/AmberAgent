package app.amber.feature.ui.components.nav

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    val workspace = workspaceColors()
    IconButton(
        onClick = { navController.popBackStack() },
        modifier = modifier,
    ) {
        Icon(
            imageVector = HugeIcons.ArrowLeft01,
            contentDescription = stringResource(R.string.back),
            tint = workspace.ink,
        )
    }
}
