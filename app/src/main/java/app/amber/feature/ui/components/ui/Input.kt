package app.amber.feature.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.JetbrainsMono

@Composable
fun <T : Number> OutlinedNumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    var textFieldValue by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        modifier = modifier,
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            if (textFieldValue.isValidNumberInput()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val newVal = when (value) {
                        is Int -> newValue.toInt() as T
                        is Float -> newValue.toFloat() as T
                        is Double -> newValue.toDouble() as T
                        else -> throw IllegalArgumentException("Unsupported number type")
                    }
                    onValueChange(newVal)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !textFieldValue.isValidNumberInput(),
        colors = colors
    )
}

@Composable
fun <T : Number> NumberInput(
    value: T,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    colors: TextFieldColors = TextFieldDefaults.colors()
) {
    var textFieldValue by remember(value) { mutableStateOf(value.toString()) }
    TextField(
        modifier = modifier,
        value = textFieldValue,
        onValueChange = { newValue ->
            textFieldValue = newValue
            if (textFieldValue.isValidNumberInput()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val newVal = when (value) {
                        is Int -> newValue.toInt() as T
                        is Float -> newValue.toFloat() as T
                        is Double -> newValue.toDouble() as T
                        else -> throw IllegalArgumentException("Unsupported number type")
                    }
                    onValueChange(newVal)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !textFieldValue.isValidNumberInput(),
        colors = colors
    )
}

private val NumberRegex = Regex("^[+-]?\\d+(\\.\\d+)?$")
private fun String.isValidNumberInput() = this.isNotEmpty() && NumberRegex.matches(this)

/**
 * V3 平卡输入 —— provider-screens.jsx Field 设计稿：
 *   - label 在上：12sp inkFaint letterSpacing 0.4 + paddingStart 2dp + marginBottom 8dp
 *   - 输入卡：padding 12/14 + 12dp 圆角 + chatTheme.surface + 1dp hair border + 14.5sp ink
 *   - error: border 走 error 色
 *   - mono: 字体切 JetbrainsMono（API key / private key / base url 等）
 */
@Composable
fun FlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    singleLine: Boolean = true,
    mono: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val theme = LocalChatTheme.current
    val errorColor = MaterialTheme.colorScheme.error
    val borderColor = when {
        isError -> errorColor
        else -> theme.hair
    }
    val textColor = if (enabled) theme.ink else theme.inkSoft
    val selectionColors = TextSelectionColors(
        handleColor = theme.accent,
        backgroundColor = theme.accent.copy(alpha = 0.30f),
    )
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = theme.inkFaint,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
        )
        // V3 review P3 #5: singleLine 时默认 ImeAction.Done (BasicTextField 不像 M3 TextField 自动配)
        // V3 review P3 #6: BasicTextField 默认按内容宽度, 加 fillMaxWidth 让整个 surface 都可点击聚焦
        val effectiveKeyboardOptions = if (singleLine && keyboardOptions == KeyboardOptions.Default) {
            KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
        } else {
            keyboardOptions
        }
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(theme.surface)
                    .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
                keyboardOptions = effectiveKeyboardOptions,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(theme.accent),
                textStyle = TextStyle(
                    fontSize = 14.5.sp,
                    color = textColor,
                    letterSpacing = 0.2.sp,
                    lineHeight = 21.sp,
                    fontFamily = if (mono) JetbrainsMono else FontFamily.Default,
                ),
            )
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                fontSize = 11.5.sp,
                color = if (isError) errorColor else theme.inkFaint,
                letterSpacing = 0.3.sp,
                modifier = Modifier.padding(start = 2.dp, top = 6.dp),
            )
        }
    }
}
