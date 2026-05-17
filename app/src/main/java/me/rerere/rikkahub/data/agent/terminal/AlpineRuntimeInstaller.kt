package me.rerere.rikkahub.data.agent.terminal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import java.io.File

class AlpineRuntimeInstaller(private val context: Context) {
    private val prefixDir: File = context.filesDir.parentFile ?: context.filesDir
    private val runtimeVersion = BuildConfig.VERSION_CODE
    private val runtimeVersionFile: File = context.filesDir.resolve("embedded-terminal-runtime.version")
    val localDir: File = prefixDir.resolve("local")
    val localBinDir: File = localDir.resolve("bin")
    val localLibDir: File = localDir.resolve("lib")
    val tempDir: File = context.cacheDir.resolve("amberagent-terminal")

    suspend fun ensureInstalled(): InstallStatus = withContext(Dispatchers.IO) {
        val current = getInstallStatus()
        if (current.success) return@withContext current
        installRuntime(overwriteRuntimeFiles = true)
    }

    suspend fun installOrRepair(): InstallStatus = withContext(Dispatchers.IO) {
        installRuntime(overwriteRuntimeFiles = true)
    }

    suspend fun getInstallStatus(): InstallStatus = withContext(Dispatchers.IO) {
        val missing = requiredRuntimeFiles().mapNotNull { file ->
            val target = file.target
            when {
                !target.exists() || target.length() == 0L -> file.label
                file.executable && !target.canExecute() -> "${file.label} (not executable)"
                else -> null
            }
        } + listOfNotNull(runtimeVersionIssue())
        InstallStatus(
            success = missing.isEmpty(),
            message = if (missing.isEmpty()) {
                "Alpine/proot runtime is installed."
            } else {
                "Runtime is not installed or needs repair: ${missing.joinToString()}"
            },
            prefix = prefixDir.absolutePath
        )
    }

    private fun installRuntime(overwriteRuntimeFiles: Boolean): InstallStatus {
        return runCatching {
            localDir.mkdirs()
            localBinDir.mkdirs()
            localLibDir.mkdirs()
            tempDir.mkdirs()
            copyRuntimeAsset(
                "embedded-terminal-runtime/proot",
                context.filesDir.resolve("proot"),
                executable = true,
                overwrite = overwriteRuntimeFiles
            )
            copyRuntimeAsset(
                "embedded-terminal-runtime/libtalloc.so.2",
                context.filesDir.resolve("libtalloc.so.2"),
                overwrite = overwriteRuntimeFiles
            )
            copyRuntimeAsset(
                assetCandidates = listOf(
                    "embedded-terminal-runtime/alpine.tar.gz",
                    "embedded-terminal-runtime/alpine.tar"
                ),
                target = context.filesDir.resolve("alpine.tar.gz"),
                overwrite = overwriteRuntimeFiles
            )
            copyRuntimeAsset(
                "amberagent-terminal/init-host.sh",
                localBinDir.resolve("init-host"),
                executable = true,
                overwrite = overwriteRuntimeFiles
            )
            copyRuntimeAsset(
                "amberagent-terminal/init.sh",
                localBinDir.resolve("init"),
                executable = true,
                overwrite = overwriteRuntimeFiles
            )
            runtimeVersionFile.writeText(runtimeVersion)
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

    private fun copyRuntimeAsset(
        assetPath: String,
        target: File,
        executable: Boolean = false,
        overwrite: Boolean = false,
    ) {
        copyRuntimeAsset(listOf(assetPath), target, executable, overwrite)
    }

    private fun copyRuntimeAsset(
        assetCandidates: List<String>,
        target: File,
        executable: Boolean = false,
        overwrite: Boolean = false,
    ) {
        if (overwrite || !target.exists() || target.length() == 0L) {
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

    private fun requiredRuntimeFiles(): List<RuntimeFile> = listOf(
        RuntimeFile("proot", context.filesDir.resolve("proot"), executable = true),
        RuntimeFile("libtalloc.so.2", context.filesDir.resolve("libtalloc.so.2")),
        RuntimeFile("alpine.tar.gz", context.filesDir.resolve("alpine.tar.gz")),
        RuntimeFile("init-host", localBinDir.resolve("init-host"), executable = true),
        RuntimeFile("init", localBinDir.resolve("init"), executable = true),
    )

    private data class RuntimeFile(
        val label: String,
        val target: File,
        val executable: Boolean = false,
    )

    private fun runtimeVersionIssue(): String? {
        val installedVersion = runtimeVersionFile.takeIf { it.exists() }?.readText()?.trim()
        return if (installedVersion == runtimeVersion) null else "runtime assets (outdated)"
    }
}

data class InstallStatus(
    val success: Boolean,
    val message: String,
    val prefix: String,
)
