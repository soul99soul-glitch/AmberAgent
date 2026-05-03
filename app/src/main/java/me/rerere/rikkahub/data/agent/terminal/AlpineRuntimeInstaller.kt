package me.rerere.rikkahub.data.agent.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AlpineRuntimeInstaller(private val context: Context) {
    private val prefixDir: File = context.filesDir.parentFile ?: context.filesDir
    val localDir: File = prefixDir.resolve("local")
    val localBinDir: File = localDir.resolve("bin")
    val localLibDir: File = localDir.resolve("lib")
    val tempDir: File = context.cacheDir.resolve("amberagent-terminal")

    suspend fun ensureInstalled(): InstallStatus = withContext(Dispatchers.IO) {
        runCatching {
            localDir.mkdirs()
            localBinDir.mkdirs()
            localLibDir.mkdirs()
            tempDir.mkdirs()
            copyRuntimeAsset("embedded-terminal-runtime/proot", context.filesDir.resolve("proot"), executable = true)
            copyRuntimeAsset("embedded-terminal-runtime/libtalloc.so.2", context.filesDir.resolve("libtalloc.so.2"))
            copyRuntimeAsset(
                assetCandidates = listOf(
                    "embedded-terminal-runtime/alpine.tar.gz",
                    "embedded-terminal-runtime/alpine.tar"
                ),
                target = context.filesDir.resolve("alpine.tar.gz")
            )
            copyRuntimeAsset("amberagent-terminal/init-host.sh", localBinDir.resolve("init-host"), executable = true)
            copyRuntimeAsset("amberagent-terminal/init.sh", localBinDir.resolve("init"), executable = true)
            InstallStatus(
                success = true,
                message = "Alpine/proot runtime is installed.",
                prefix = prefixDir.absolutePath
            )
        }.getOrElse { error ->
            InstallStatus(
                success = false,
                message = error.message ?: "Failed to install Alpine/proot runtime.",
                prefix = prefixDir.absolutePath
            )
        }
    }

    fun environment(workspacePath: String, sessionId: String): Map<String, String> {
        val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
        val env = linkedMapOf(
            "PATH" to "${System.getenv("PATH").orEmpty()}:/sbin:${localBinDir.absolutePath}",
            "HOME" to "/root",
            "COLORTERM" to "truecolor",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "BIN" to localBinDir.absolutePath,
            "PREFIX" to prefixDir.absolutePath,
            "LD_LIBRARY_PATH" to localLibDir.absolutePath,
            "LINKER" to linker,
            "NATIVE_LIB_DIR" to context.applicationInfo.nativeLibraryDir,
            "PKG" to context.packageName,
            "PKG_PATH" to context.applicationInfo.sourceDir,
            "AMBERAGENT_HOST_WORKSPACE" to workspacePath,
            "PROOT_TMP_DIR" to tempDir.resolve(sessionId).apply { mkdirs() }.absolutePath,
            "TMPDIR" to tempDir.absolutePath,
        )
        val loader = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        if (loader.exists()) {
            env["PROOT_LOADER"] = loader.absolutePath
        }
        return env
    }

    private fun copyRuntimeAsset(assetPath: String, target: File, executable: Boolean = false) {
        copyRuntimeAsset(listOf(assetPath), target, executable)
    }

    private fun copyRuntimeAsset(assetCandidates: List<String>, target: File, executable: Boolean = false) {
        if (!target.exists() || target.length() == 0L) {
            val assetPath = assetCandidates.firstOrNull { candidate ->
                runCatching { context.assets.open(candidate).close() }.isSuccess
            } ?: error("Missing runtime asset: ${assetCandidates.joinToString(" or ")}")
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        target.setReadable(true, false)
        if (executable) target.setExecutable(true, false)
    }
}

data class InstallStatus(
    val success: Boolean,
    val message: String,
    val prefix: String,
)
