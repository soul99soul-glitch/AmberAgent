package app.amber.feature.ui.pages.councilroom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.pages.chat.LocalChatTheme

private enum class CouncilTab(val label: String) {
    Roster("成员"),
    Timeline("原始消息"),
    Synthesis("综合"),
}

/**
 * Three-part room switcher. Defaults to timeline because that is the live room.
 */
@Composable
fun CouncilRoomTabs(
    room: CouncilRoom,
    vm: CouncilRoomVM?,
) {
    var selected by remember { mutableIntStateOf(CouncilTab.Timeline.ordinal) }
    val tabs = CouncilTab.entries
    val safeSelected = selected.coerceIn(tabs.indices)

    CouncilSegmentedTabs(
        tabs = tabs,
        selected = safeSelected,
        onSelect = { selected = it },
    )

    when (tabs[safeSelected]) {
        CouncilTab.Roster -> CouncilRosterTab(
            room = room,
            modifier = Modifier.fillMaxSize(),
        )
        CouncilTab.Timeline -> CouncilTimelineTab(
            room = room,
            vm = vm,
            modifier = Modifier.fillMaxSize(),
        )
        CouncilTab.Synthesis -> CouncilSynthesisTab(
            room = room,
            vm = vm,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CouncilSegmentedTabs(
    tabs: List<CouncilTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = chatTheme.surface,
        border = workspaceBorder(),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                Text(
                    text = tab.label,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (active) chatTheme.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(vertical = 9.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) chatTheme.accent else chatTheme.inkFaint,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
