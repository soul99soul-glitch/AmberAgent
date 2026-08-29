package app.amber.feature.ui.pages.extensions

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.amber.agent.R
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.files.SkillFrontmatterParser
import app.amber.core.files.SkillManager
import app.amber.core.files.SkillMetadata
import app.amber.core.files.SkillScanIssue
import java.util.LinkedHashMap
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

class SkillsVM(
    private val context: Application,
    private val skillManager: SkillManager,
    private val settingsStore: SettingsAggregator,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()
    private val _skillIssues = MutableStateFlow<List<SkillScanIssue>>(emptyList())
    val skillIssues = _skillIssues.asStateFlow()
    val settings = settingsStore.settingsFlow

    init {
        loadSkills()
    }

    fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            if (result != null) {
                enableSkill(result.name)
            }
            refreshSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            refreshSkills()
        }
    }

    fun setSkillEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsStore.update { settings ->
                settings.copy(
                    enabledSkills = if (enabled) {
                        settings.enabledSkills + name
                    } else {
                        settings.enabledSkills - name
                    },
                )
            }
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = parseGitHubUrl(repoUrl) ?: run {
                    withContext(Dispatchers.Main) {
                        onResult(false, context.getString(R.string.skills_page_import_invalid_repository))
                    }
                    return@launch
                }

                // Collect all files recursively via GitHub Contents API
                val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
                val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files)
                if (!listed) {
                    withContext(Dispatchers.Main) {
                        onResult(false, context.getString(R.string.skills_page_import_list_failed))
                    }
                    return@launch
                }

                val skillMdEntry = files.find { it.first == "SKILL.md" } ?: run {
                    withContext(Dispatchers.Main) {
                        onResult(false, context.getString(R.string.skills_page_import_missing_skill_md))
                    }
                    return@launch
                }

                val skillMdContent = downloadText(skillMdEntry.second) ?: run {
                    withContext(Dispatchers.Main) {
                        onResult(false, context.getString(R.string.skills_page_import_download_skill_md_failed))
                    }
                    return@launch
                }

                val frontmatter = SkillFrontmatterParser.parse(skillMdContent)
                val name = frontmatter["name"]
                if (name.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        onResult(false, context.getString(R.string.skills_page_name_error))
                    }
                    return@launch
                }

                val fileContents = LinkedHashMap<String, String>()
                for ((relativePath, downloadUrl) in files) {
                    val content = downloadText(downloadUrl)
                    if (content == null) {
                        withContext(Dispatchers.Main) {
                            onResult(
                                false,
                                context.getString(
                                    R.string.skills_page_import_download_file_failed,
                                    relativePath,
                                ),
                            )
                        }
                        return@launch
                    }
                    fileContents[relativePath] = content
                }

                val saved = skillManager.saveSkillFilesAtomically(name, fileContents)
                if (!saved) {
                    withContext(Dispatchers.Main) {
                        onResult(false, context.getString(R.string.skills_page_save_failed))
                    }
                    return@launch
                }

                enableSkill(name)
                refreshSkills()
                withContext(Dispatchers.Main) { onResult(true, name) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: context.getString(R.string.skills_page_import_unknown_error))
                }
            }
        }
    }

    private suspend fun refreshSkills() {
        skillManager.repairMissingDescriptions()
        _skills.value = skillManager.listSkills()
        _skillIssues.value = skillManager.listSkillIssues()
    }

    private suspend fun enableSkill(name: String) {
        settingsStore.update { settings ->
            settings.copy(
                enabledSkills = settings.enabledSkills + name,
            )
        }
    }

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadText(apiUrl) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun downloadText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode == 200) connection.inputStream.bufferedReader().readText()
            else null
        } finally {
            connection.disconnect()
        }
    }
}
