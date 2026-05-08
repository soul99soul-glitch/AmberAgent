package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.PulseDialogButton
import me.rerere.rikkahub.ui.components.ui.PulseDialogVariant
import me.rerere.rikkahub.ui.components.ui.PulsePrimaryButton
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import org.koin.compose.koinInject

@Composable
fun BackgroundPicker(
    modifier: Modifier = Modifier,
    background: String?,
    backgroundOpacity: Float = 1.0f,
    onUpdate: (String?) -> Unit
) {
    val filesManager: FilesManager = koinInject()
    var showPickOption by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localUris = filesManager.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { localUri ->
                onUpdate(localUri.toString())
            }
        }
    }

    val previewOpacity = backgroundOpacity.coerceIn(0f, 1f)

    FormItem(
        modifier = modifier,
        label = {
            Text(stringResource(R.string.assistant_page_chat_background))
        },
        description = {
            Text(stringResource(R.string.assistant_page_chat_background_desc))
        }
    ) {
        PulsePrimaryButton(
            onClick = {
                showPickOption = true
            },
            modifier = Modifier.fillMaxWidth(),
            text = if (background != null) {
                stringResource(R.string.assistant_page_change_background)
            } else {
                stringResource(R.string.assistant_page_select_background)
            },
        )

        if (background != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Pulse Phase C HIGH: was colorScheme.primary
                // (chartreuse) text on cream — yellow-on-cream
                // disappeared. Switch to onSurface (ink) so the
                // "background set" confirmation reads clearly.
                Text(
                    text = stringResource(R.string.assistant_page_background_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                PulseDialogButton(
                    onClick = {
                        onUpdate(null)
                    },
                    text = stringResource(R.string.assistant_page_remove),
                    variant = PulseDialogVariant.Ghost,
                )
            }

            AsyncImage(
                model = background,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(previewOpacity)
            )
        }
    }

    val workspace = workspaceColors()

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            containerColor = workspace.paper,
            titleContentColor = workspace.ink,
            textContentColor = workspace.ink,
            title = {
                Text(stringResource(R.string.assistant_page_select_background))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PulsePrimaryButton(
                        onClick = {
                            showPickOption = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.assistant_page_select_from_gallery),
                    )
                    PulsePrimaryButton(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(R.string.assistant_page_enter_image_url),
                    )
                    if (background != null) {
                        PulsePrimaryButton(
                            onClick = {
                                showPickOption = false
                                onUpdate(null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.assistant_page_remove_background),
                        )
                    }
                }
            },
            confirmButton = {
                PulseDialogButton(
                    onClick = {
                        showPickOption = false
                    },
                    text = stringResource(R.string.assistant_page_cancel),
                    variant = PulseDialogVariant.Ghost,
                )
            }
        )
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            containerColor = workspace.paper,
            titleContentColor = workspace.ink,
            textContentColor = workspace.ink,
            title = {
                Text(stringResource(R.string.assistant_page_enter_image_url))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.assistant_page_image_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/image.jpg") },
                    singleLine = true
                )
            },
            confirmButton = {
                PulseDialogButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdate(urlInput.trim())
                            showUrlInput = false
                        }
                    },
                    text = stringResource(R.string.assistant_page_confirm),
                    variant = PulseDialogVariant.Primary,
                )
            },
            dismissButton = {
                PulseDialogButton(
                    onClick = {
                        showUrlInput = false
                    },
                    text = stringResource(R.string.assistant_page_cancel),
                    variant = PulseDialogVariant.Ghost,
                )
            }
        )
    }
}
