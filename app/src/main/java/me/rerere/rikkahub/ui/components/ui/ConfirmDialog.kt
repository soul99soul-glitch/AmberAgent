package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Shared confirmation dialog wrapper — historically routed through this
 * composable so all confirm/cancel decisions read consistently. After
 * the Pulse pivot, the footer buttons use PulseDialogButton variants
 * (chartreuse Primary for confirm, ghost for dismiss). The default
 * confirm tone is Primary; pass `destructive = true` for delete-style
 * actions to switch to sport-orange Secondary.
 */
@Composable
fun RikkaConfirmDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    text: @Composable () -> Unit,
) {
    if (!show) {
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text,
        confirmButton = {
            PulseDialogButton(
                onClick = onConfirm,
                text = confirmText,
                variant = if (destructive) {
                    PulseDialogVariant.Secondary
                } else {
                    PulseDialogVariant.Primary
                },
            )
        },
        dismissButton = {
            PulseDialogButton(
                onClick = onDismiss,
                text = dismissText,
                variant = PulseDialogVariant.Ghost,
            )
        }
    )
}
