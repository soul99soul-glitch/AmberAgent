package me.rerere.rikkahub.ui.components.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03

/**
 * Pulse Performance signature floating bottom navigation bar.
 *
 * Renders a dark ink-colored pill hovering 14dp above the bottom edge
 * with rounded 26dp corners and a soft shadow. Five slots, in order:
 *
 *   1. Chats     — active when currently on a chat screen / chat list
 *   2. Search    — opens the message search index
 *   3. + (center)— starts a new conversation; rendered as a slightly
 *                  larger cream-filled circle with an ink + icon, to
 *                  read as the primary CTA distinct from the four nav
 *                  items flanking it
 *   4. Skills    — skills / extensions index
 *   5. Settings  — settings root
 *
 * Active state on flanking items: chartreuse-filled inner pill with the
 * destination icon + ALL-CAPS label inline. Inactive: just the icon at
 * 55% opacity cream, no label.
 *
 * Visibility: this composable doesn't decide where to render — that's
 * the caller's job (RouteActivity hides it on detail pages by passing
 * `currentDestination = null`). When the active destination isn't in the
 * primary set, callers should simply not render the bar; passing an
 * unknown destination just leaves all four flanking items inactive.
 */
@Composable
fun PulseBottomBar(
    activeDestination: PulseNavDestination?,
    onDestinationClick: (PulseNavDestination) -> Unit,
    onPlusClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.tertiary,           // ink spotlight
            contentColor = MaterialTheme.colorScheme.onTertiary,  // cream
            shadowElevation = 12.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                NavSlot(
                    destination = PulseNavDestination.Chats,
                    active = activeDestination == PulseNavDestination.Chats,
                    onClick = { onDestinationClick(PulseNavDestination.Chats) },
                )
                NavSlot(
                    destination = PulseNavDestination.Search,
                    active = activeDestination == PulseNavDestination.Search,
                    onClick = { onDestinationClick(PulseNavDestination.Search) },
                )
                CenterPlusButton(onClick = onPlusClick)
                NavSlot(
                    destination = PulseNavDestination.Skills,
                    active = activeDestination == PulseNavDestination.Skills,
                    onClick = { onDestinationClick(PulseNavDestination.Skills) },
                )
                NavSlot(
                    destination = PulseNavDestination.Settings,
                    active = activeDestination == PulseNavDestination.Settings,
                    onClick = { onDestinationClick(PulseNavDestination.Settings) },
                )
            }
        }
    }
}

@Composable
private fun NavSlot(
    destination: PulseNavDestination,
    active: Boolean,
    onClick: () -> Unit,
) {
    if (active) {
        // Active: chartreuse-filled inner pill with icon + ALL-CAPS label
        Surface(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,         // chartreuse
            contentColor = MaterialTheme.colorScheme.onPrimary, // ink
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.label,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = destination.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.05.em,
                    ),
                )
            }
        }
    } else {
        // Inactive: icon-only at 55% cream
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = destination.label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun CenterPlusButton(onClick: () -> Unit) {
    // Cream-filled circle, slightly larger than the flanking items so it
    // reads as the primary CTA rather than another nav slot. Soft inner
    // ink contrast keeps the + icon visible against the cream fill even
    // when the surrounding ink pill darkens the page.
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)  // cream
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = HugeIcons.MessageAdd01,
            contentDescription = "New chat",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,  // ink
        )
    }
}

/**
 * Primary nav destinations. Order matches the bar's left-to-right slots
 * (Chats / Search / [+] / Skills / Settings); the [+] is special-cased
 * in PulseBottomBar so it doesn't appear here.
 */
enum class PulseNavDestination(
    val label: String,
    val icon: ImageVector,
) {
    Chats(label = "Chats", icon = HugeIcons.BubbleChatQuestion),
    Search(label = "Search", icon = HugeIcons.Search01),
    Skills(label = "Skills", icon = HugeIcons.MagicWand01),
    Settings(label = "Settings", icon = HugeIcons.Settings03),
}
