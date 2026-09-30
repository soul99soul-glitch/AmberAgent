package app.amber.feature.ui.pages.share.handler

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.files.FilesManager
import app.amber.feature.workspace.WorkspaceManager
import app.amber.feature.ui.context.LocalNavController
import app.amber.core.utils.navigateToChatPage
import app.amber.feature.ui.theme.ThemePackageImportResult
import app.amber.feature.ui.theme.ThemePackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.CancellationException

@Composable
fun ShareHandlerPage(
    text: String,
    streamUris: List<String>,
    sourceAction: String? = null,
    declaredMimeType: String? = null,
    deliveryId: String = "",
) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val navController = LocalNavController.current
    val context = LocalContext.current
    val workspaceManager: WorkspaceManager = koinInject()
    val filesManager: FilesManager = koinInject()
    val themePackageManager: ThemePackageManager = koinInject()
    var importFailure by rememberSaveable(deliveryId) { mutableStateOf<String?>(null) }

    LaunchedEffect(deliveryId, text, streamUris, sourceAction, declaredMimeType) {
        val themeUri = when {
            sourceAction == Intent.ACTION_SEND && streamUris.size == 1 -> streamUris.single()
            sourceAction == Intent.ACTION_VIEW && streamUris.size == 1 -> streamUris.single()
            else -> null
        }
        val sharedThemeJson = themeUri?.let { raw ->
            try {
                withContext(Dispatchers.IO) {
                    ThemeShareImport.readV1DocumentIfPresent(context, raw.toUri(), declaredMimeType)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ShareHandler", "Unable to inspect shared JSON; keeping the regular attachment path", error)
                null
            }
        }
        if (sharedThemeJson != null) {
            try {
                when (val result = themePackageManager.prepareImport(sharedThemeJson)) {
                    is ThemePackageImportResult.Preview -> {
                        navController.popBackStack()
                        navController.navigate(Screen.SettingAppearance) { launchSingleTop = true }
                        return@LaunchedEffect
                    }
                    is ThemePackageImportResult.Rejected -> {
                        importFailure = result.issues.joinToString("\n")
                        return@LaunchedEffect
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                importFailure = error.localizedMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.setting_theme_library_try_on_failed)
                return@LaunchedEffect
            }
        }

        // Stage shared files into the POSIX workspace mirror under /workspace/uploads/
        // so the Agent's terminal/file tools can actually find them. Pre-mirror code path
        // dropped these into filesDir/upload/<uuid>, which the Agent never sees — leading
        // to "I can't find your file" loops after the user shared something. Failures fall
        // back to the original SAF URI; ChatPage's existing copy-to-upload path will then
        // handle it (file goes to chat-only storage, Agent still won't find it but at
        // least the chat shows the attachment).
        val stagedUris = streamUris.map { raw ->
            val src = raw.toUri()
            runCatching {
                val displayName = filesManager.getFileNameFromUri(src) ?: src.lastPathSegment ?: "file"
                workspaceManager.copyUriToUploads(src, displayName)
            }.getOrElse {
                if (it is CancellationException) throw it
                Log.w("ShareHandler", "Failed to stage $src to workspace; falling back to SAF URI", it)
                src
            }
        }
        navigateToChatPage(
            navigator = navController,
            // navigateToChatPage base64-encodes initText itself; passing pre-encoded
            // text used to double-encode and show garbage in the input box.
            initText = vm.shareText,
            initFiles = stagedUris,
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val failure = importFailure
        if (failure == null) {
            Text(
                text = stringResource(R.string.share_handler_page_forwarding),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.setting_theme_library_import_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
