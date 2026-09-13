package app.amber.feature.ui.pages.extensions

import com.composables.icons.lucide.Lucide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.utils.plus
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.BookText
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Zap

/** The extensions hub follows the compact, single card navigation pattern. */
@Composable
fun ExtensionsPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.extensions_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionLabel(
                    text = stringResource(R.string.extensions_page_section_extensions),
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp, bottom = 2.dp),
                )
            }
            item {
                AmberCard(modifier = Modifier.fillMaxWidth()) {
                    ExtensionRow(
                        icon = Lucide.Zap,
                        title = stringResource(R.string.quick_messages_page_title),
                        onClick = { navController.navigate(Screen.QuickMessages) },
                    )
                    Hairline()
                    ExtensionRow(
                        icon = Lucide.Heart,
                        title = stringResource(R.string.favorite_page_title),
                        onClick = { navController.navigate(Screen.Favorite) },
                    )
                    Hairline()
                    ExtensionRow(
                        icon = Lucide.BookText,
                        title = stringResource(R.string.extensions_page_prompts),
                        onClick = { navController.navigate(Screen.Prompts) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tokens.surface2)
                .border(1.dp, tokens.line, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tokens.ink2,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = tokens.ink,
            maxLines = 1,
        )
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tokens.ink3,
        )
    }
}
