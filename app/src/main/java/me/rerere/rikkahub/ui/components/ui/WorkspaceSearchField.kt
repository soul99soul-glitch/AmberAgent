package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X

/**
 * Notion-style flat search field. Uses [BasicTextField] under the hood so we
 * can fully control the look without inheriting Material's [OutlinedTextField]
 * label / shadow / 56dp height baggage. Renders as:
 *
 *   ╭──────────────────────────────────────╮
 *   │ 🔍  搜索…                         ×  │
 *   ╰──────────────────────────────────────╯
 *
 * - 40dp tall, 10dp rounded corners
 * - workspace.row background (very light gray) — no shadow, no elevation
 * - workspace.hairline 1dp border
 * - 16dp leading magnifier icon in workspace.muted
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
    val workspace = workspaceColors()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(workspace.row)
            .border(BorderStroke(1.dp, workspace.hairline), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        // contentAlignment vertically centers the Row inside the 40dp Box —
        // without this the Row hugs the top edge and the magnifier icon /
        // text input visibly float above the vertical midline.
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = workspace.muted,
            )
            Box(
                modifier = Modifier
                    .weight(1f, fill = true),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = workspace.ink,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    ),
                    cursorBrush = SolidColor(workspace.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSubmit?.invoke(value) },
                    ),
                )
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        CompositionLocalProvider(LocalContentColor provides workspace.faint) {
                            Text(
                                text = placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
                        tint = workspace.muted,
                    )
                }
            }
        }
    }
}
