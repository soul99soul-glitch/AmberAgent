package app.amber.feature.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.AmberSans

/**
 * Amber flat search field. Uses [BasicTextField] under the hood so we
 * can fully control the look without inheriting Material's [OutlinedTextField]
 * label / shadow / 56dp height baggage. Renders as:
 *
 *   ╭──────────────────────────────────────╮
 *   │ 🔍  搜索…                         ×  │
 *   ╰──────────────────────────────────────╯
 *
 * - 48dp minimum height, 15dp rounded corners
 * - theme surface-2 background — no shadow, no elevation
 * - theme hairline 1dp border
 * - 16dp leading magnifier icon in the muted ink color
 * - Inline trailing × (only when text is present)
 *
 * Caller controls horizontal padding via [modifier]. Single-line, ime-action
 * "search" submits via [onSubmit] if provided.
 */
@Composable
fun WorkspaceSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onSubmit: ((String) -> Unit)? = null,
) {
    // Shared redesign search field: surface-2 fill, hairline border, and muted search icon.
    val chatTheme = LocalChatTheme.current
    val focusRequester = remember { FocusRequester() }
    val outerTapSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(chatTheme.searchBarBg)
            .border(BorderStroke(1.dp, chatTheme.hair), RoundedCornerShape(15.dp))
            .clickable(
                interactionSource = outerTapSource,
                indication = null,
                onClick = { focusRequester.requestFocus() },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = chatTheme.inkSoft,
            )
            Box(
                modifier = Modifier
                    .weight(1f, fill = true),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = AmberSans,
                        color = chatTheme.ink,
                        fontSize = 14.5.sp,
                        letterSpacing = 0.2.sp,
                    ),
                    cursorBrush = SolidColor(chatTheme.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSubmit?.invoke(value) },
                    ),
                )
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 14.5.sp,
                        color = chatTheme.inkFaint,
                        letterSpacing = 0.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = "Clear",
                        modifier = Modifier.size(14.dp),
                        tint = chatTheme.inkSoft,
                    )
                }
            }
        }
    }
}
