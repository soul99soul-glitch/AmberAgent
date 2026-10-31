package app.amber.feature.ui.components.message

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.runtime.SshApprovalTargetGuard
import app.amber.feature.terminal.SshHostKeyProbe
import app.amber.feature.terminal.SshHopException
import app.amber.feature.terminal.SshHostTrust
import app.amber.feature.terminal.SshProfilesState
import app.amber.feature.terminal.SshRouteResolver
import app.amber.feature.terminal.SshTrustPolicy
import app.amber.feature.terminal.TerminalRuntime
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.koin.core.context.GlobalContext

internal fun extractSshApprovalProfileId(toolName: String, arguments: JsonElement?): String? {
    if (!SshApprovalTargetGuard.handles(toolName)) return null
    val runtime = arguments.getStringContent("runtime")?.trim()?.lowercase()
    if (runtime != null && runtime != "remote_ssh") return null
    return arguments.getStringContent("profile_id")?.trim()?.takeIf { it.isNotEmpty() }
        ?: arguments.getStringContent("ssh_profile_id")?.trim()?.takeIf { it.isNotEmpty() }
}

@Composable
internal fun rememberSshApprovalRouteState(
    tool: UIMessagePart.Tool,
    arguments: JsonElement?,
): Pair<Boolean, (@Composable () -> Unit)?> {
    if (!SshApprovalTargetGuard.handles(tool.toolName)) return true to null
    val target = tool.metadata?.get(SshApprovalTargetGuard.METADATA_KEY) as? JsonObject
    val profileId = target.getStringContent("profile_id")
        ?: extractSshApprovalProfileId(tool.toolName, arguments)
        ?: return true to null
    val koin = remember { GlobalContext.getOrNull() } ?: return false to null
    val store = remember(koin) { koin.get<SshProfileStore>() }
    val runtime = remember(koin) { koin.get<TerminalRuntime>() }
    val context = LocalContext.current
    val profiles by store.state.collectAsStateWithLifecycle(initialValue = SshProfilesState())
    val routeResult = remember(profiles, profileId) {
        runCatching { SshRouteResolver.resolveRoute(profileId, profiles.profiles) }
    }
    val route = routeResult.getOrNull().orEmpty()
    var probe by remember(route) { mutableStateOf<SshHostKeyProbe?>(null) }
    var busy by remember(profileId) { mutableStateOf(false) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trust = probe?.let { SshTrustPolicy.evaluate(it.profile, it.fingerprint) }
    val expectedDigest = target.getStringContent("route_digest")
    val changed = expectedDigest != null && route.isNotEmpty() && expectedDigest != SshRouteResolver.routeDigest(route)
    val allTrusted = route.isNotEmpty() && route.all {
        SshTrustPolicy.evaluate(it, it.acceptedHostKeyFingerprint) is SshHostTrust.Trusted
    }
    val canApprove = !busy && !changed && routeResult.isSuccess && allTrusted && trust !is SshHostTrust.Mismatch
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SSH 路由：本机 → " + route.mapIndexed { i, hop ->
                "第 ${i + 1} 跳 ${hop.name} (${hop.username}@${hop.host}:${hop.port})"
            }.joinToString(" → "), style = LocalAmberType.current.meta)
            if (changed) {
                Text("SSH 配置或信任已变化，请拒绝本次调用并重新发起。", color = MaterialTheme.colorScheme.error)
            }
            routeResult.exceptionOrNull()?.let {
                Text("SSH 路由不可用，请检查设置。", color = MaterialTheme.colorScheme.error)
            }
            val observed = probe
            if (observed != null) {
                Text("第 ${observed.hopIndex}/${observed.hopCount} 跳 · ${observed.profile.name}\n" +
                    SshTrustPolicy.displayFingerprint(observed.fingerprint),
                    fontFamily = FontFamily.Monospace, style = LocalAmberType.current.meta)
                when (trust) {
                    is SshHostTrust.Mismatch -> Text(
                        "主机指纹变化，已拦截认证。已保存：${SshTrustPolicy.displayFingerprint(trust.acceptedFingerprint)}。只能在设置页核对并替换。",
                        color = MaterialTheme.colorScheme.error)
                    is SshHostTrust.Untrusted -> OutlinedButton(enabled = !busy && !changed, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val keyguard = context.getSystemService(KeyguardManager::class.java)
                                check(keyguard != null && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) {
                                    "请解锁设备后确认 SSH 指纹。"
                                }
                                store.acceptHostKey(observed.profile, observed.fingerprint, observed.route)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                error = "信任未保存，请重新探测。"
                            } finally { busy = false }
                        }
                    }) { Text("确认第 ${observed.hopIndex} 跳指纹并信任") }
                    is SshHostTrust.Trusted -> Text("此跳主机指纹已核验。", style = LocalAmberType.current.meta)
                    null -> Unit
                }
            }
            OutlinedButton(enabled = !busy && !changed && route.isNotEmpty(), onClick = {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        probe = runtime.probeSshHostKey(profileId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is SshHopException && e.status == "host_key_changed" && e.presentedFingerprint != null) {
                            probe = SshHostKeyProbe(e.profile, requireNotNull(e.presentedFingerprint), "SSH", e.hopIndex, e.hopCount, route)
                        } else { error = "指纹探测失败，请检查路由和凭据设置。" }
                    } finally { busy = false }
                }
            }) { Text(if (busy) "探测中…" else "探测 / 核验逐跳指纹") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LocalAmberType.current.meta) }
        }
    }
    return canApprove to content
}
