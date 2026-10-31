package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Server
import app.amber.agent.R
import app.amber.feature.terminal.SshHostKeyProbe
import app.amber.feature.terminal.SshHostTrust
import app.amber.feature.terminal.SshTrustPolicy
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun HostKeyTrustDialog(
    pending: PendingHostKeyTrust,
    accepting: Boolean,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
) {
    val mismatch = pending.trust as? SshHostTrust.Mismatch
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (mismatch == null) Lucide.CircleAlert else Lucide.Server, null) },
        title = {
            Text(
                stringResource(
                    if (mismatch == null) R.string.setting_sandbox_ssh_probe_title
                    else R.string.setting_sandbox_ssh_probe_mismatch_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending.probe.hopCount > 1) {
                    Text(
                        text = "链路第 ${pending.probe.hopIndex}/${pending.probe.hopCount} 跳 · ${pending.probe.profile.name} (${pending.probe.route.joinToString(" → ") { it.name }})",
                        style = LocalAmberType.current.meta,
                    )
                }
                Text(
                    stringResource(
                        R.string.setting_sandbox_ssh_probe_endpoint,
                        pending.probe.profile.host,
                        pending.probe.profile.port,
                    ),
                )
                Text(stringResource(R.string.setting_sandbox_ssh_probe_algorithm, pending.probe.algorithm))
                if (mismatch == null) {
                    Text(stringResource(R.string.setting_sandbox_ssh_probe_untrusted))
                } else {
                    Text(stringResource(R.string.setting_sandbox_ssh_probe_mismatch_desc))
                    Text(
                        stringResource(
                            R.string.setting_sandbox_ssh_probe_old_fingerprint,
                            displayFingerprintSafely(mismatch.acceptedFingerprint),
                        ),
                        style = LocalAmberType.current.meta,
                    )
                }
                Text(
                    stringResource(
                        R.string.setting_sandbox_ssh_probe_new_fingerprint,
                        displayFingerprintSafely(pending.probe.fingerprint),
                    ),
                    style = LocalAmberType.current.meta,
                )
            }
        },
        confirmButton = {
            SandboxAction(
                label = stringResource(
                    if (mismatch == null) R.string.setting_sandbox_ssh_probe_accept
                    else R.string.setting_sandbox_ssh_probe_accept_new,
                ),
                onClick = onAccept,
                enabled = !accepting,
                primary = true,
                icon = Lucide.CircleCheck,
            )
        },
        dismissButton = {
            SandboxAction(
                label = stringResource(R.string.setting_sandbox_ssh_cancel),
                onClick = onDismiss,
                enabled = !accepting,
            )
        },
    )
}

internal data class PendingHostKeyTrust(val probe: SshHostKeyProbe, val trust: SshHostTrust)

internal fun displayFingerprintSafely(raw: String): String =
    runCatching { SshTrustPolicy.displayFingerprint(raw) }.getOrDefault(raw)
