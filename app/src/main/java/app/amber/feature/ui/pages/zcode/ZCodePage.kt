package app.amber.feature.ui.pages.zcode

import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.utils.plus
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanQrCode
import org.koin.compose.koinInject

/**
 * ZCode companion: paste the share URL from 智谱 ZCode and open its mobile web UI.
 * No special bridge — ZCode is already a mobile-ready HTML page.
 */
@Composable
fun ZCodePage(
    store: ZCodeUrlStore = koinInject(),
) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val workspace = workspaceColors()
    val scope = rememberCoroutineScope()
    val scanNotUrlMessage = stringResource(R.string.zcode_scan_not_url)
    val cameraPermissionMessage = stringResource(R.string.zcode_camera_permission_required)
    val scanFailedShortMessage = stringResource(R.string.zcode_scan_failed_short)
    val saved by store.urlFlow.collectAsStateWithLifecycle(initialValue = "")
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<ZCodeUiMessage?>(null) }

    fun openZCodeUrl(raw: String) {
        val url = normalizeZCodeUrl(raw)
        if (url == null) {
            error = ZCodeUiMessage(R.string.zcode_invalid_url)
            return
        }
        scope.launch {
            store.save(url)
            navController.navigate(Screen.ZCodeSession(url = url))
        }
    }

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> {
                val content = result.content.rawValue.orEmpty().trim()
                if (content.isEmpty()) {
                    error = ZCodeUiMessage(R.string.zcode_empty_qr_content)
                    return@rememberLauncherForActivityResult
                }
                draft = content
                error = null
                val url = normalizeZCodeUrl(content)
                if (url == null) {
                    error = ZCodeUiMessage(R.string.zcode_invalid_scanned_url)
                    toaster.show(scanNotUrlMessage, type = ToastType.Error)
                } else {
                    openZCodeUrl(url)
                }
            }

            QRResult.QRMissingPermission -> {
                error = ZCodeUiMessage(R.string.zcode_camera_permission_required)
                toaster.show(cameraPermissionMessage, type = ToastType.Error)
            }

            is QRResult.QRError -> {
                error = ZCodeUiMessage(
                    R.string.zcode_scan_failed,
                    listOf(result.exception.message ?: result.toString()),
                )
                toaster.show(scanFailedShortMessage, type = ToastType.Error)
            }

            QRResult.QRUserCanceled -> Unit
        }
    }

    LaunchedEffect(saved) {
        if (draft.isBlank() && saved.isNotBlank()) {
            draft = saved
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "ZCode",
                navigationIcon = { BackButton() },
            )
        },
        containerColor = workspace.canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.zcode_page_title),
                style = MaterialTheme.typography.titleMedium,
                color = workspace.ink,
            )
            Text(
                text = stringResource(R.string.zcode_page_description),
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.muted,
            )

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.zcode_url_label)) },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = { scanQrCodeLauncher.launch(null) }) {
                        Icon(
                            imageVector = Lucide.ScanQrCode,
                            contentDescription = stringResource(R.string.zcode_scan_qr),
                        )
                    }
                },
            )

            error?.let {
                Text(
                    text = stringResource(it.resourceId, *it.formatArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { openZCodeUrl(draft) },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.zcode_open))
            }
        }
    }
}

private data class ZCodeUiMessage(
    @StringRes val resourceId: Int,
    val formatArgs: List<Any> = emptyList(),
)

/** Accept bare host by prefixing https; reject non-http schemes. */
internal fun normalizeZCodeUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        trimmed.contains("://") -> return null
        else -> "https://$trimmed"
    }
    return runCatching {
        val uri = java.net.URI(withScheme)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        withScheme
    }.getOrNull()
}
