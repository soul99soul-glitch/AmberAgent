package me.rerere.rikkahub.ui.pages.share.handler

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import me.rerere.rikkahub.R
import app.amber.feature.workspace.WorkspaceManager
import app.amber.core.files.FilesManager
import me.rerere.rikkahub.ui.context.LocalNavController
import app.amber.core.utils.base64Encode
import app.amber.core.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun ShareHandlerPage(text: String, streamUris: List<String>) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val navController = LocalNavController.current
    val workspaceManager: WorkspaceManager = koinInject()
    val filesManager: FilesManager = koinInject()

    LaunchedEffect(text, streamUris) {
        vm.selectAmberAgent()
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
                Log.w("ShareHandler", "Failed to stage $src to workspace; falling back to SAF URI", it)
                src
            }
        }
        navigateToChatPage(
            navigator = navController,
            initText = vm.shareText.base64Encode(),
            initFiles = stagedUris,
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.share_handler_page_forwarding),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
