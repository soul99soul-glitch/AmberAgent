package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.ChevronRight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.BuildConfig
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.utils.plus
import app.amber.core.utils.openUrl
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.BlinkingCursor
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.easteregg.EmojiBurstHost
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide

@Composable
fun SettingAboutPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val navController = LocalNavController.current
    val emojiOptions = remember {
        listOf(
            "🎉", "✨", "🌟", "💫", "🎊", "🥳", "🎈", "🎆", "🎇", "🧨",
            "🌈", "🧧", "🎁", "🍬", "🍭", "🍉", "🍓", "🍒", "🍍", "🥭",
            "🐱", "🐶", "🦊", "🐼", "🦁", "🐯", "🐵", "🦄",
            "❤️", "🧡", "💛", "💚", "💙", "💜",
            "🇨🇳", "🌏", "🌍", "🌎",
            "🤗", "🤩", "😆", "😺", "😸", "🤡",
            "💡", "🔥", "💥", "🚀", "⭐", "🌙",
        )
    }
    var logoCenterPx by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.about_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        EmojiBurstHost(
            modifier = Modifier.fillMaxSize(),
            emojiOptions = emojiOptions,
            burstCount = 12,
        ) { onBurst ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(
                    horizontal = SettingPageHorizontalInset,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item("hero") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val tokens = LocalAmberTokens.current
                        Surface(
                            modifier = Modifier
                                .size(150.dp)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(tokens.surface2, tokens.surface),
                                    ),
                                    shape = CircleShape,
                                )
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInParent()
                                    val size = coordinates.size
                                    logoCenterPx = Offset(
                                        position.x + size.width / 2f,
                                        position.y + size.height / 2f,
                                    )
                                }
                                .combinedClickable(
                                    onClick = { onBurst(logoCenterPx) },
                                    onLongClick = {},
                                ),
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, tokens.line2),
                            ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.amber_wordmark),
                                    contentDescription = stringResource(R.string.about_page_logo_content_description),
                                    modifier = Modifier.size(width = 118.dp, height = 30.dp),
                                    tint = tokens.ink,
                                )
                                BlinkingCursor()
                            }
                        }
                        Text(
                            text = "AmberAgent",
                            style = LocalAmberType.current.screenTitle.copy(fontSize = 19.sp),
                            color = tokens.ink,
                        )
                    }
                }

                item("build") {
                    AmberCard(modifier = Modifier.fillMaxWidth()) {
                        AboutRow(
                            icon = Lucide.CodeXml,
                            title = stringResource(R.string.about_page_version),
                            value = "${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}",
                            onLongClick = { navController.navigate(Screen.Debug) },
                        )
                        AboutRowDivider()
                        AboutRow(
                            icon = Lucide.Smartphone,
                            title = stringResource(R.string.about_page_system),
                            supporting = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / " +
                                "Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}",
                            tall = true,
                        )
                    }
                }

                item("links") {
                    AmberCard(modifier = Modifier.fillMaxWidth()) {
                        AboutRow(
                            icon = Lucide.Earth,
                            title = stringResource(R.string.about_page_website),
                            meta = stringResource(R.string.about_page_website_description),
                            trailing = true,
                        )
                        AboutRowDivider()
                        AboutRow(
                            icon = Lucide.Github,
                            title = stringResource(R.string.about_page_github),
                            meta = stringResource(R.string.about_page_github_description),
                            trailing = true,
                            onClick = {
                                context.openUrl("https://github.com/soul99soul-glitch/AmberAgent")
                            },
                        )
                        AboutRowDivider()
                        AboutRow(
                            icon = Lucide.FileText,
                            title = stringResource(R.string.about_page_license),
                            meta = stringResource(R.string.about_page_license_description),
                            trailing = true,
                            onClick = {
                                context.openUrl("https://github.com/soul99soul-glitch/AmberAgent/blob/main/LICENSE")
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutRow(
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    value: String? = null,
    meta: String? = null,
    tall: Boolean = false,
    trailing: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = if (tall || supporting != null) 64.dp else 52.dp)
        .then(
            when {
                onLongClick != null -> Modifier.combinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                )
                onClick != null -> Modifier.pressable(onClick = onClick)
                else -> Modifier
            }
        )
        .padding(horizontal = 14.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(t.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = t.ink2,
                modifier = Modifier.size(17.dp),
            )
        }
        if (supporting != null) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = type.body,
                    color = t.ink,
                    maxLines = 1,
                )
                Text(
                    text = supporting,
                    style = type.meta.copy(fontSize = 12.sp),
                    color = t.ink3,
                )
            }
        } else {
            Text(
                text = title,
                style = type.body,
                color = t.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        value?.let {
            Text(
                text = it,
                style = type.meta.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                color = t.ink3,
                maxLines = 1,
            )
        }
        meta?.let {
            Text(
                text = it,
                style = type.secondary.copy(fontSize = 13.sp),
                color = t.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing) {
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = t.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AboutRowDivider() {
    Hairline(modifier = Modifier.padding(start = 58.dp))
}
