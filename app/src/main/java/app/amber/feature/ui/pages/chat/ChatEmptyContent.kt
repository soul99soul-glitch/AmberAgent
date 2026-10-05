package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.ui.components.ds.BlinkingCursor
import app.amber.feature.ui.theme.LocalAmberTokens

@Composable
internal fun ChatEmptyContent(
    heroText: String,
    generatedSuggestions: List<String>,
    loading: Boolean,
    showSuggestions: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val heroDim = if (loading) 0.45f else 1f
    BoxWithConstraints(modifier) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.amber_wordmark),
                        contentDescription = "Amber",
                        modifier = Modifier.size(width = 118.dp, height = 30.dp),
                        tint = tokens.ink.copy(alpha = heroDim),
                    )
                    BlinkingCursor(width = 8.dp, height = 18.dp)
                }
                Text(
                    text = heroText,
                    modifier = Modifier.padding(top = 16.dp).widthIn(max = 288.dp),
                    color = chatTheme.ink.copy(alpha = heroDim),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.24).sp,
                    lineHeight = 29.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            if (showSuggestions) {
                EmptyChatSuggestions(
                    generatedSuggestions = generatedSuggestions,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    onSelect = onSelect,
                )
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
        if (loading) {
            CircularProgressIndicator(
                color = chatTheme.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).padding(top = 120.dp).size(28.dp),
            )
        }
    }
}

@Composable
private fun EmptyChatSuggestions(
    modifier: Modifier = Modifier,
    generatedSuggestions: List<String> = emptyList(),
    onSelect: (String) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val suggestions = generatedSuggestions.ifEmpty {
        listOf(
            stringResource(R.string.amber_redesign_suggestion_board),
            stringResource(R.string.amber_redesign_suggestion_reply),
            stringResource(R.string.amber_redesign_suggestion_concept),
        )
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onSelect(suggestion) },
                modifier = Modifier.heightIn(min = 40.dp),
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = tokens.ink2,
                border = BorderStroke(1.dp, tokens.accent.copy(alpha = 0.10f)),
                tonalElevation = 0.dp,
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = suggestion,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.ink2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
