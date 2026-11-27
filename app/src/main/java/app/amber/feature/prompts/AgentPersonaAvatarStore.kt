package app.amber.feature.prompts

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val AvatarMaxPixels = 384

/** The persona portrait stays out of the synced Agent Soul and user profile. */
internal class AgentPersonaAvatarStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "agent_persona_avatar.png"))
    private val writeMutex = Mutex()
    private val savedAvatar = MutableStateFlow(
        runCatching { file.readFully() }.getOrNull()?.takeIf { it.isNotEmpty() },
    )
    val avatar = savedAvatar.asStateFlow()

    suspend fun importPhoto(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(AvatarMaxPixels)
            .scale(Scale.FIT)
            .precision(Precision.EXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .build()
        val image = (context.imageLoader.execute(request) as? SuccessResult)?.image
            ?: throw IOException("Could not decode persona portrait")
        if (image.width !in 1..AvatarMaxPixels || image.height !in 1..AvatarMaxPixels) {
            throw IOException("Unexpected persona portrait dimensions")
        }
        val bitmap = image.toBitmap()
        try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Could not encode persona portrait")
            }
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun save(image: ByteArray?) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val output = file.startWrite()
            try {
                if (image != null) output.write(image)
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
            savedAvatar.value = image
        }
    }
}
