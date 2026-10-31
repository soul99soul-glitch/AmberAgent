package app.amber.feature.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.terminal.SshKey
import app.amber.feature.terminal.SshKeyGenType
import app.amber.feature.terminal.SshProfile
import app.amber.feature.terminal.SshRouteResolver
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2

@Composable
internal fun SshKeysCard(
    keys: List<SshKey>,
    profiles: List<SshProfile>,
    busy: Boolean,
    onGenerateClick: () -> Unit,
    onDeleteKey: (SshKey) -> Unit,
    onCopiedPublicKey: (SshKey) -> Unit,
) {
    val context = LocalContext.current
    val colors = workspaceColors()
    SandboxCard {
        SandboxSettingsRow(
            title = "SSH 本机密钥",
            trailing = {
                SandboxAction(
                    label = "生成密钥",
                    onClick = onGenerateClick,
                    enabled = !busy,
                    primary = true,
                    compact = true,
                    icon = Lucide.Plus,
                )
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = colors.hairline.copy(alpha = 0.55f),
        )
        if (keys.isEmpty()) {
            Text(
                text = "尚未生成本机密钥。点击「生成密钥」创建 Ed25519 密钥并复制公钥到服务器 authorized_keys。",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = LocalAmberType.current.secondary,
                color = colors.muted,
            )
        }
        keys.forEachIndexed { index, key ->
            val usedBy = SshRouteResolver.profilesUsingKey(key.id, profiles)
            val summary = buildString {
                append(key.algorithm)
                append(" · ")
                append(key.fingerprintSha256.take(28))
                if (usedBy.isNotEmpty()) {
                    append(" · 已关联 ${usedBy.size} 个配置")
                }
            }
            SandboxSettingsRow(
                title = key.name,
                summary = summary,
                leading = Lucide.Key,
                enabled = !busy,
                onClick = {
                    copyPublicKeyToClipboard(context, key)
                    onCopiedPublicKey(key)
                },
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SandboxAction(
                            label = "复制公钥",
                            onClick = {
                                copyPublicKeyToClipboard(context, key)
                                onCopiedPublicKey(key)
                            },
                            enabled = !busy,
                            compact = true,
                            icon = Lucide.Copy,
                        )
                        IconButton(
                            onClick = { onDeleteKey(key) },
                            enabled = !busy && usedBy.isEmpty(),
                        ) {
                            Icon(
                                imageVector = Lucide.Trash2,
                                contentDescription = stringResource(R.string.setting_sandbox_ssh_delete),
                                tint = if (usedBy.isEmpty()) colors.red else colors.muted,
                            )
                        }
                    }
                },
            )
            if (index != keys.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = colors.hairline.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SshKeyGenerateDialog(
    busy: Boolean,
    generatedKey: SshKey?,
    onDismiss: () -> Unit,
    onGenerate: (name: String, type: SshKeyGenType) -> Unit,
    onCopyGeneratedKey: (SshKey) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SshKeyGenType.ED25519) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (generatedKey == null) "生成 SSH 本机密钥" else "密钥已生成") },
        text = {
            if (generatedKey != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "请复制以下 OpenSSH 公钥并追加到目标服务器的 ~/.ssh/authorized_keys：",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = generatedKey.publicKeyOpenSsh,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OpenSSH 公钥 (${generatedKey.algorithm})") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    Text(
                        text = "指纹：${generatedKey.fingerprintSha256}",
                        style = LocalAmberType.current.meta,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密钥名称 (例如 bastion-key)") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    Text("算法类型", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == SshKeyGenType.ED25519,
                            onClick = { type = SshKeyGenType.ED25519 },
                            enabled = !busy,
                            label = { Text("Ed25519 (推荐)") },
                        )
                        FilterChip(
                            selected = type == SshKeyGenType.ECDSA_P256,
                            onClick = { type = SshKeyGenType.ECDSA_P256 },
                            enabled = !busy,
                            label = { Text("ECDSA P-256") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (generatedKey != null) {
                SandboxAction(
                    label = "复制公钥并完成",
                    onClick = {
                        copyPublicKeyToClipboard(context, generatedKey)
                        onCopyGeneratedKey(generatedKey)
                        onDismiss()
                    },
                    primary = true,
                    icon = Lucide.Copy,
                )
            } else {
                SandboxAction(
                    label = "生成密钥",
                    onClick = { onGenerate(name.trim(), type) },
                    enabled = name.trim().isNotEmpty() && !busy,
                    primary = true,
                    icon = Lucide.Key,
                )
            }
        },
        dismissButton = {
            SandboxAction(
                label = stringResource(R.string.setting_sandbox_ssh_cancel),
                onClick = onDismiss,
                enabled = !busy,
            )
        },
    )
}

internal fun copyPublicKeyToClipboard(context: Context, key: SshKey) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key (${key.name})", key.publicKeyOpenSsh))
}
