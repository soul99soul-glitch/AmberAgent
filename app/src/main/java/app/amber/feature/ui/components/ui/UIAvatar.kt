package app.amber.feature.ui.components.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.rikkahub.R
import app.amber.core.files.FilesManager
import app.amber.core.model.Avatar
import app.amber.feature.ui.hooks.rememberAvatarShape
import org.koin.compose.koinInject

@Composable
fun TextAvatar(
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    color: Color = MaterialTheme.colorScheme.secondaryContainer
) {
    Box(
        modifier = modifier
            .clip(shape = rememberAvatarShape(loading))
            .background(color)
            .size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.take(1).uppercase(),
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 8.sp,
                maxFontSize = 32.sp,
                stepSize = 1.sp
            ),
            lineHeight = 0.8.em
        )
    }
}

@Composable
fun UIAvatar(
    name: String,
    value: Avatar,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    loading: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    editContainerColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    editContentColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    onUpdate: ((Avatar) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    // 显示右下角铅笔徽标。false 时点头像仍可换头像，只是不画铅笔小图标
    // (V3 drawer footer 用 false —— 设计稿头像是干净圆头像，无铅笔徽标)
    showEditBadge: Boolean = true,
) {
    if (onUpdate == null) {
        UIAvatarFrame(
            name = name,
            value = value,
            modifier = modifier,
            size = size,
            loading = loading,
            containerColor = containerColor,
            editContainerColor = editContainerColor,
            editContentColor = editContentColor,
            showEditIcon = false,
            onClick = onClick,
        )
        return
    }

    val filesManager: FilesManager = koinInject()
    var showPickOption by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localUris = filesManager.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { localUri ->
                onUpdate(Avatar.Image(localUri.toString()))
            }
        }
    }

    UIAvatarFrame(
        name = name,
        value = value,
        modifier = modifier,
        size = size,
        loading = loading,
        containerColor = containerColor,
        editContainerColor = editContainerColor,
        editContentColor = editContentColor,
        showEditIcon = showEditBadge,
        onClick = {
            onClick?.invoke()
            showPickOption = true
        },
    )

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            title = {
                Text(text = stringResource(id = R.string.avatar_change_avatar))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showPickOption = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.avatar_pick_image))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            showEmojiPicker = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.avatar_pick_emoji))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.avatar_input_url))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            onUpdate(Avatar.Dummy)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.avatar_reset))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPickOption = false
                    }
                ) {
                    Text(stringResource(id = R.string.avatar_cancel))
                }
            }
        )
    }

    if (showEmojiPicker) {
        ModalBottomSheet(
            onDismissRequest = {
                showEmojiPicker = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            EmojiPicker(
                onEmojiSelected = { emoji ->
                    onUpdate(Avatar.Emoji(content = emoji.emoji))
                    showEmojiPicker = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(16.dp)
            )
        }
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            title = {
                Text(text = stringResource(id = R.string.avatar_url_dialog_title))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(id = R.string.avatar_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdate(Avatar.Image(urlInput.trim()))
                            showUrlInput = false
                        }
                    }
                ) {
                    Text(stringResource(id = R.string.avatar_url_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUrlInput = false
                    }
                ) {
                    Text(stringResource(id = R.string.avatar_cancel))
                }
            }
        )
    }
}

@Composable
private fun UIAvatarFrame(
    name: String,
    value: Avatar,
    modifier: Modifier,
    size: Dp,
    loading: Boolean,
    containerColor: Color,
    editContainerColor: Color,
    editContentColor: Color,
    showEditIcon: Boolean,
    onClick: (() -> Unit)?,
) {
    Box(modifier = modifier.size(size)) {
        if (onClick != null) {
            Surface(
                shape = rememberAvatarShape(loading),
                modifier = Modifier.fillMaxSize(),
                onClick = onClick,
                tonalElevation = 4.dp,
                color = containerColor,
            ) {
                UIAvatarContent(name = name, value = value, size = size)
            }
        } else {
            Surface(
                shape = rememberAvatarShape(loading),
                modifier = Modifier.fillMaxSize(),
                tonalElevation = 4.dp,
                color = containerColor,
            ) {
                UIAvatarContent(name = name, value = value, size = size)
            }
        }

        if (showEditIcon) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.44f)
                    .clip(MaterialTheme.shapes.small)
                    .background(editContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Edit03,
                    contentDescription = "Edit",
                    modifier = Modifier
                        .size(size * 0.31f)
                        .padding(1.dp),
                    tint = editContentColor
                )
            }
        }
    }
}

@Composable
private fun UIAvatarContent(
    name: String,
    value: Avatar,
    size: Dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        when (value) {
            is Avatar.Image -> {
                AsyncImage(
                    model = value.url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            is Avatar.Emoji -> {
                Text(
                    text = value.content,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 15.sp,
                        maxFontSize = 30.sp,
                    ),
                    lineHeight = 0.8.em,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(5.dp)
                )
            }

            is Avatar.Dummy -> {
                Text(
                    text = name
                        .ifBlank { stringResource(R.string.user_default_name) }
                        .takeIf { it.isNotEmpty() }
                        ?.firstOrNull()?.toString()?.uppercase() ?: "A",
                    fontSize = (size.value * 0.62f).sp,
                    lineHeight = 1.em
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewUIAvatar() {
    var loading by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UIAvatar(
            name = "John Doe",
            value = Avatar.Dummy,
            loading = false
        )

        UIAvatar(
            name = "John Doe",
            value = Avatar.Dummy,
            loading = loading,
        )

        Button(
            onClick = {
                loading = !loading
            }
        ) {
            Text("Toggle Loading")
        }
    }
}
