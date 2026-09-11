package app.amber.feature.ui.components.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.CustomColors

// V3 settings-screen.jsx:79 borderRadius: 18 —— 之前 12dp 偏紧、不像 editorial 卡
private val CardGroupCorner = 18.dp
private val CardGroupItemSpacing = 1.dp
private val CardGroupInnerCorner = 2.dp

data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: @Composable () -> Unit,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
)

class CardGroupScope {
    internal var defaultColors: ListItemColors? = null

    @Composable
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        headlineContent: @Composable () -> Unit,
    ) {
        CardGroupListItem(
            item = CardGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
            ),
            defaultColors = defaultColors,
        )
    }

    /** 渲染一个纯自定义行：内容原样渲染进卡片（带 ListItem 内边距与卡片圆角）。 */
    @Composable
    fun rawItem(
        modifier: Modifier = Modifier,
        colors: ListItemColors? = null,
        content: @Composable () -> Unit,
    ) {
        CardGroupListItem(
            item = CardGroupItem(
                onClick = null,
                modifier = modifier,
                overlineContent = null,
                headlineContent = content,
                supportingContent = null,
                leadingContent = null,
                trailingContent = null,
                colors = colors,
            ),
            defaultColors = defaultColors,
        )
    }
}

@Composable
private fun CardGroupListItem(
    item: CardGroupItem,
    defaultColors: ListItemColors?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val corner = if (isPressed) CardGroupCorner else CardGroupInnerCorner

    ListItem(
        headlineContent = item.headlineContent,
        modifier = item.modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = corner,
                    topEnd = corner,
                    bottomStart = corner,
                    bottomEnd = corner,
                )
            )
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            ),
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        colors = item.colors ?: defaultColors ?: CustomColors.listItemColors,
    )
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    colors: ListItemColors? = null,
    content: @Composable CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScope()
    scope.defaultColors = colors

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                    Box(modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 8.dp)) {
                        title()
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardGroupCorner)),
            verticalArrangement = Arrangement.spacedBy(CardGroupItemSpacing),
        ) {
            scope.content()
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Card Group")
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = { Text("About") },
            ) {
                item(
                    headlineContent = { Text("First item") },
                )
                item(
                    headlineContent = { Text("Second item") },
                    supportingContent = { Text("Supporting text") },
                )
                item(
                    onClick = {},
                    headlineContent = { Text("Third item") },
                    trailingContent = { Text("→") },
                )
            }
        }
    }
}
