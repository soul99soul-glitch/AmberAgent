package app.amber.feature.ui.components.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberType

private val CardGroupCorner = 14.dp
private val CardGroupItemSpacing = 1.dp

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
    internal var dividerColor: Color? = null
    internal var dividerStartPadding: Dp = 12.dp
    internal var dividerLeadingOffset: Dp = 38.dp

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
            dividerColor = dividerColor,
            dividerStartPadding = dividerStartPadding + if (leadingContent != null) dividerLeadingOffset else 0.dp,
        )
    }

    /** 渲染一个纯自定义行：内容原样渲染进卡片（带 ListItem 内边距与卡片圆角）。 */
    @Composable
    fun rawItem(
        modifier: Modifier = Modifier,
        colors: ListItemColors? = null,
        dividerStartPadding: Dp? = null,
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
            dividerColor = dividerColor,
            dividerStartPadding = dividerStartPadding ?: this.dividerStartPadding,
        )
    }
}

@Composable
private fun CardGroupListItem(
    item: CardGroupItem,
    defaultColors: ListItemColors?,
    dividerColor: Color?,
    dividerStartPadding: Dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = item.colors ?: defaultColors ?: CustomColors.listItemColors
    val type = LocalAmberType.current
    val hasSecondaryText = item.supportingContent != null || item.overlineContent != null
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = item.modifier
                .fillMaxWidth()
                .heightIn(min = if (hasSecondaryText) 56.dp else 48.dp)
                .background(colors.containerColor)
                .then(
                    if (item.onClick != null) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = item.onClick,
                        )
                    } else Modifier
                )
                .padding(horizontal = 12.dp, vertical = if (hasSecondaryText) 6.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.leadingContent?.let { leading ->
                CompositionLocalProvider(LocalContentColor provides colors.leadingContentColor) {
                    leading()
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item.overlineContent?.let { overline ->
                    CompositionLocalProvider(LocalContentColor provides colors.overlineContentColor) {
                        ProvideTextStyle(type.meta, overline)
                    }
                }
                CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
                    ProvideTextStyle(type.body, item.headlineContent)
                }
                item.supportingContent?.let { supporting ->
                    CompositionLocalProvider(LocalContentColor provides colors.supportingContentColor) {
                        ProvideTextStyle(type.secondary, supporting)
                    }
                }
            }
            item.trailingContent?.let { trailing ->
                CompositionLocalProvider(LocalContentColor provides colors.trailingContentColor) {
                    ProvideTextStyle(type.meta, trailing)
                }
            }
        }
        if (dividerColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dividerStartPadding)
                    .height(0.5.dp)
                    .background(dividerColor),
            )
        }
    }
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    colors: ListItemColors? = null,
    containerColor: Color? = null,
    border: androidx.compose.foundation.BorderStroke? = androidx.compose.foundation.BorderStroke(
        1.dp, MaterialTheme.colorScheme.outlineVariant,
    ),
    shadowElevation: Dp = 0.dp,
    itemSpacing: Dp = CardGroupItemSpacing,
    dividerColor: Color? = null,
    dividerStartPadding: Dp = 12.dp,
    dividerLeadingOffset: Dp = 38.dp,
    content: @Composable CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScope()
    scope.defaultColors = colors
    scope.dividerColor = dividerColor
    scope.dividerStartPadding = dividerStartPadding
    scope.dividerLeadingOffset = dividerLeadingOffset

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(LocalAmberType.current.eyebrow) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 2.dp, top = 6.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        title()
                        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }
            }
        }
        val shape = RoundedCornerShape(CardGroupCorner)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(shadowElevation, shape)
                .clip(shape)
                .background(containerColor ?: MaterialTheme.colorScheme.outlineVariant)
                .then(
                    if (border != null) Modifier.border(border, shape) else Modifier,
                ),
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
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
