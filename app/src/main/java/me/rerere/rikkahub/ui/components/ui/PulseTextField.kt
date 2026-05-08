package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Pulse-themed wrapper around M3's [OutlinedTextField].
 *
 * Identical parameter signature to [OutlinedTextField] — drop-in
 * replacement at every call site that wants Pulse styling without
 * having to remember the right colorScheme slots:
 *
 *   - focused border + label  → primary (chartreuse)
 *   - unfocused border        → outlineVariant (warm tan-3 hairline)
 *   - unfocused label         → onSurfaceVariant (warm grey)
 *   - cursor                  → onSurface (ink)
 *   - error border + label    → error (sport-orange)
 *   - container               → transparent (lets the parent surface
 *                                show through, matches the cream-on-cream
 *                                outlined-field look from the Pulse mockup)
 *
 * Default shape is [RoundedCornerShape] 12dp — quieter cousin of the
 * PulsePrimaryButton 20dp asymmetric squircle, gives form fields a
 * deliberate but understated rounding that pairs with the Pulse card
 * vocabulary (CardGroup, WorkspaceLeadingIcon).
 *
 * Usage:
 * ```
 * PulseTextField(
 *     value = apiKey,
 *     onValueChange = { apiKey = it },
 *     label = { Text("API Key") },
 *     singleLine = true,
 * )
 * ```
 *
 * If a call site needs to override colors (e.g. for a non-standard
 * surface background), pass an explicit `colors = ...` argument — the
 * default factory is [pulseTextFieldColors].
 */
@Composable
fun PulseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: TextFieldColors = pulseTextFieldColors(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors,
    )
}

/**
 * Default Pulse-aligned [TextFieldColors] for [PulseTextField]. Exposed
 * publicly so call sites can copy + tweak a single slot via
 * [TextFieldColors.copy] without rebuilding the whole pairing.
 *
 * All colors reach for [MaterialTheme.colorScheme] slots so the
 * factory naturally tracks the Pulse light / dark / AMOLED schemes.
 */
@Composable
fun pulseTextFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = scheme.outlineVariant,
        disabledBorderColor = scheme.outlineVariant.copy(alpha = 0.38f),
        errorBorderColor = scheme.error,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        disabledLabelColor = scheme.onSurfaceVariant.copy(alpha = 0.38f),
        errorLabelColor = scheme.error,
        cursorColor = scheme.onSurface,
        errorCursorColor = scheme.error,
        focusedTextColor = scheme.onSurface,
        unfocusedTextColor = scheme.onSurface,
        disabledTextColor = scheme.onSurface.copy(alpha = 0.38f),
        errorTextColor = scheme.onSurface,
        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        errorContainerColor = androidx.compose.ui.graphics.Color.Transparent,
        focusedPlaceholderColor = scheme.onSurfaceVariant.copy(alpha = 0.65f),
        unfocusedPlaceholderColor = scheme.onSurfaceVariant.copy(alpha = 0.55f),
        focusedSupportingTextColor = scheme.onSurfaceVariant,
        unfocusedSupportingTextColor = scheme.onSurfaceVariant,
        errorSupportingTextColor = scheme.error,
    )
}
