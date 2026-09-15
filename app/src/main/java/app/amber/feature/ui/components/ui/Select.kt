package app.amber.feature.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import app.amber.feature.ui.pages.chat.LocalChatTheme

@Composable
fun <T> Select(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionToString: @Composable (T) -> String = { it.toString() },
    optionLeading: @Composable ((T) -> Unit)? = null,
    leading: @Composable () -> Unit = {},
    labelMaxWidth: Dp? = null,
    trailing: @Composable () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    // 胶囊造型 + 主题色 (替代固定蓝色 workspace.blueContainer), 颜色跟随 LocalChatTheme.accent
    val chatTheme = LocalChatTheme.current

    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        Surface(
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            shape = CircleShape,
            color = chatTheme.accentSoft,
            contentColor = chatTheme.accent,
            border = BorderStroke(1.dp, chatTheme.accent.copy(alpha = 0.22f)),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .heightIn(min = 34.dp)
        ) {
            // V3 ValueChip 内容自适应：去掉 fillMaxWidth + Text.weight(1f) 让 Row wrap content
            // 否则在 ListItem trailing slot 里会撑满剩余宽度把 headline 文字挤到左边
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { expanded = true }
                    .padding(vertical = 7.dp, horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                leading()
                Text(
                    text = optionToString(selectedOption),
                    modifier = labelMaxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = if (labelMaxWidth != null) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
                trailing()
                Icon(
                    imageVector = if (expanded) Lucide.ArrowUp else Lucide.ArrowDown,
                    contentDescription = "expand",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            matchAnchorWidth = false,
            onDismissRequest = {
                expanded = false
            }
        ) {
            options.fastForEach { option ->
                DropdownMenuItem(
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                    text = {
                        // The popup measures its options independently of the compact value chip.
                        Text(text = optionToString(option), softWrap = false)
                    },
                    leadingIcon = optionLeading?.let {
                        { it(option) }
                    },
                    modifier = Modifier.widthIn(min = 140.dp),
                )
            }
        }
    }
}
