package app.amber.feature.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import app.amber.agent.R
import com.composables.icons.lucide.BookText
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Zap
import app.amber.agent.Screen
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.core.utils.plus

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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 0.dp),
                    title = { SectionLabel(stringResource(R.string.extensions_page_section_extensions)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.QuickMessages) },
                        leadingContent = {
                            WorkspaceLeadingIcon(
                                icon = Lucide.Zap,
                                size = 32.dp,
                                iconSize = 18.dp,
                                tone = WorkspaceTone.Accent,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.quick_messages_page_title)) },
                        trailingContent = { Icon(Lucide.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Favorite) },
                        leadingContent = {
                            WorkspaceLeadingIcon(
                                icon = Lucide.Heart,
                                size = 32.dp,
                                iconSize = 18.dp,
                                tone = WorkspaceTone.Accent,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.favorite_page_title)) },
                        trailingContent = { Icon(Lucide.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Prompts) },
                        leadingContent = {
                            WorkspaceLeadingIcon(
                                icon = Lucide.BookText,
                                size = 32.dp,
                                iconSize = 18.dp,
                                tone = WorkspaceTone.Accent,
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.extensions_page_prompts)) },
                        trailingContent = { Icon(Lucide.ChevronRight, null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }
    }
}
