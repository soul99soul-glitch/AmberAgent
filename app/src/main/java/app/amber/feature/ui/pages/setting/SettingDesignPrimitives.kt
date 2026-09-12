package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.workspaceColors

/**
 * The settings mock uses a small mono eyebrow and a hairline to separate each group.  Keep that
 * treatment in one local primitive so individual pages can keep their real controls and state
 * while sharing the same visual rhythm.
 */
@Composable
internal fun SettingSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = workspaceColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SectionLabel(text)
        Spacer(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.hairline),
        )
    }
}

/** Standard horizontal inset used by the redesigned settings screens. */
internal val SettingPageHorizontalInset = 16.dp

@Composable
internal fun SettingTileIcon(
    icon: ImageVector,
) {
    WorkspaceLeadingIcon(icon = icon, size = 32.dp, iconSize = 17.dp)
}
