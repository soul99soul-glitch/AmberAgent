package app.amber.feature.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Non-modal controls for the app-wide theme try-on. Place this as a compact child above the
 * routed content in the root Column; actions use the id and digest from one observed candidate.
 */
@Composable
fun ThemeTryOnHost(
    modifier: Modifier = Modifier,
    manager: ThemePackageManager = koinInject(),
) {
    val tryOn by manager.tryOn.collectAsState(initial = null)
    val candidate = tryOn ?: return
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val colors = workspaceColors()
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(toastMessage) {
        toastMessage?.let { message ->
            toaster.show(message, type = ToastType.Info)
            toastMessage = null
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.paper,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.hairline),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_theme_library_previewing, candidate.pkg.name),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val packageId = candidate.pkg.id
                        val digest = candidate.candidateDigest
                        val restored = manager.discardTryOn(packageId, digest)
                        if (!restored) {
                            toastMessage = context.getString(R.string.setting_theme_library_try_on_failed)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.setting_theme_library_restore))
                }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            val packageId = candidate.pkg.id
                            val digest = candidate.candidateDigest
                            try {
                                toastMessage = try {
                                    when (manager.applyPrepared(packageId, digest)) {
                                        ThemePackageApplyResult.Applied,
                                        ThemePackageApplyResult.AlreadyApplied,
                                        -> null
                                        ThemePackageApplyResult.Reverted -> context.getString(
                                            R.string.setting_theme_library_apply_reverted_error,
                                        )
                                        else -> context.getString(R.string.setting_theme_library_apply_error)
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    context.getString(R.string.setting_theme_library_try_on_failed)
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.setting_theme_library_apply))
                }
            }
        }
    }
}
