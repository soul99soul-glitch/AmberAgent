package app.amber.feature.webmount.adapters.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.long
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string
import app.amber.feature.webmount.core.WebMountToolHooks

class GithubTools(private val client: GithubClient) {

    suspend fun probe(bearerToken: String?): Boolean = client.probe(bearerToken)

    fun buildTools(hooks: WebMountToolHooks, tokenSupplier: () -> String?): List<Tool> = listOf(
        repoSearchTool(hooks, tokenSupplier),
        repoReadTool(hooks, tokenSupplier),
        issueListTool(hooks, tokenSupplier),
        pullListTool(hooks, tokenSupplier),
        fileReadTool(hooks, tokenSupplier),
        userReadTool(hooks, tokenSupplier),
    )

    private fun repoSearchTool(hooks: WebMountToolHooks, token: () -> String?) = Tool(
        name = "github_repo_search",
        description = """
            Search public GitHub repositories. `q` accepts the GitHub search syntax (e.g. "react language:typescript stars:>100").
            `sort` ∈ {stars, forks, help-wanted-issues, updated}; `order` ∈ {asc, desc}.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", stringProp("GitHub repo search query"))
                    put("sort", stringProp("Sort key (optional)"))
                    put("order", stringProp("asc | desc (optional)"))
                    put("limit", integerProp("1-100, default 20"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            hooks.track("github_repo_search", "GitHub 搜索", input) {
                val query = input.requiredString("query")
                val sort = input.string("sort")
                val order = input.string("order")
                val limit = (input.long("limit") ?: 20L).coerceIn(1L, 100L).toInt()
                val result = client.searchRepos(query, sort, order, limit, token())
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("query", result.query)
                    put("total_count", result.totalCount)
                    put("count", result.items.size)
                    put("items", buildJsonArray { result.items.forEach { add(repoJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun repoReadTool(hooks: WebMountToolHooks, token: () -> String?) = Tool(
        name = "github_repo_read",
        description = "Get full metadata about one GitHub repo: stars, forks, language, default branch, topics, timestamps.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", stringProp("Repo owner login"))
                    put("repo", stringProp("Repo name"))
                },
                required = listOf("owner", "repo"),
            )
        },
        execute = { input ->
            hooks.track("github_repo_read", "GitHub Repo", input) {
                val owner = input.requiredString("owner")
                val repo = input.requiredString("repo")
                val info = client.repoInfo(owner, repo, token())
                listOf(UIMessagePart.Text(repoJson(info).toString()))
            }
        },
    )

    private fun issueListTool(hooks: WebMountToolHooks, token: () -> String?) = Tool(
        name = "github_issue_list",
        description = "List issues in a repo. `state` ∈ {open, closed, all}, default open.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", stringProp("Repo owner"))
                    put("repo", stringProp("Repo name"))
                    put("state", stringProp("open | closed | all (default open)"))
                    put("limit", integerProp("1-100, default 30"))
                },
                required = listOf("owner", "repo"),
            )
        },
        execute = { input ->
            hooks.track("github_issue_list", "GitHub Issues", input) {
                val owner = input.requiredString("owner")
                val repo = input.requiredString("repo")
                val state = input.string("state") ?: "open"
                val limit = (input.long("limit") ?: 30L).coerceIn(1L, 100L).toInt()
                val issues = client.listIssues(owner, repo, state, limit, token())
                    .filter { !it.isPullRequest }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("owner", owner)
                    put("repo", repo)
                    put("state", state)
                    put("count", issues.size)
                    put("issues", buildJsonArray { issues.forEach { add(issueJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun pullListTool(hooks: WebMountToolHooks, token: () -> String?) = Tool(
        name = "github_pr_list",
        description = "List pull requests in a repo. `state` ∈ {open, closed, all}, default open.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", stringProp("Repo owner"))
                    put("repo", stringProp("Repo name"))
                    put("state", stringProp("open | closed | all (default open)"))
                    put("limit", integerProp("1-100, default 30"))
                },
                required = listOf("owner", "repo"),
            )
        },
        execute = { input ->
            hooks.track("github_pr_list", "GitHub PRs", input) {
                val owner = input.requiredString("owner")
                val repo = input.requiredString("repo")
                val state = input.string("state") ?: "open"
                val limit = (input.long("limit") ?: 30L).coerceIn(1L, 100L).toInt()
                val pulls = client.listPulls(owner, repo, state, limit, token())
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("owner", owner)
                    put("repo", repo)
                    put("state", state)
                    put("count", pulls.size)
                    put("pulls", buildJsonArray { pulls.forEach { add(issueJson(it)) } })
                }.toString()))
            }
        },
    )

    private fun fileReadTool(hooks: WebMountToolHooks, token: () -> String?) = Tool(
        name = "github_file_read",
        description = """
            Read a single text file from a GitHub repo. `path` is the file path within the repo;
            `ref` (optional) selects a branch, tag, or commit SHA. Returns decoded UTF-8 content
            up to `max_chars` (default 60000, hard cap 200000).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("owner", stringProp("Repo owner"))
                    put("repo", stringProp("Repo name"))
                    put("path", stringProp("File path within the repo"))
                    put("ref", stringProp("Branch / tag / SHA (optional, default repo default branch)"))
                    put("max_chars", integerProp("Truncate to this many chars, default 60000"))
                },
                required = listOf("owner", "repo", "path"),
            )
        },
        execute = { input ->
            hooks.track("github_file_read", "GitHub File", input) {
                val owner = input.requiredString("owner")
                val repo = input.requiredString("repo")
                val path = input.requiredString("path")
                val ref = input.string("ref")
                val maxChars = (input.long("max_chars") ?: 60_000L).coerceIn(1L, 200_000L).toInt()
                val content = client.readFile(owner, repo, path, ref, token())
                    ?: error("File not found or not a file: $owner/$repo/$path")
                val truncated = content.content.length > maxChars
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("path", content.path)
                    put("size_bytes", content.sizeBytes)
                    put("content", if (truncated) content.content.take(maxChars) else content.content)
                    put("total_chars", content.content.length)
                    put("truncated", truncated)
                    content.htmlUrl?.let { put("html_url", it) }
                    content.downloadUrl?.let { put("download_url", it) }
                }.toString()))
            }
        },
    )

    private fun userReadTool(hooks: WebMountToolHooks, token: () -> String?) = Tool(
        name = "github_user_read",
        description = "Read a public GitHub user's profile metadata.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { put("username", stringProp("GitHub login")) },
                required = listOf("username"),
            )
        },
        execute = { input ->
            hooks.track("github_user_read", "GitHub User", input) {
                val username = input.requiredString("username")
                val user = client.readUser(username, token())
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("username", username)
                    put("user", user)
                }.toString()))
            }
        },
    )

    private fun repoJson(r: GithubRepoSummary): JsonObject = buildJsonObject {
        put("full_name", r.fullName)
        put("name", r.name)
        r.ownerLogin?.let { put("owner", it) }
        r.description?.let { put("description", it) }
        r.htmlUrl?.let { put("html_url", it) }
        put("stars", r.stars)
        put("forks", r.forks)
        put("open_issues", r.openIssues)
        r.language?.let { put("language", it) }
        put("default_branch", r.defaultBranch)
        put("created_at_ms", r.createdAtMs)
        put("pushed_at_ms", r.pushedAtMs)
        if (r.topics.isNotEmpty()) put("topics", buildJsonArray { r.topics.forEach { add(JsonPrimitive(it)) } })
    }

    private fun issueJson(i: GithubIssueSummary): JsonObject = buildJsonObject {
        put("number", i.number)
        put("title", i.title)
        put("state", i.state)
        i.author?.let { put("author", it) }
        put("comments", i.commentCount)
        i.bodyPreview?.let { put("body_preview", it) }
        i.htmlUrl?.let { put("html_url", it) }
        put("created_at_ms", i.createdAtMs)
        put("updated_at_ms", i.updatedAtMs)
        if (i.isPullRequest) put("is_pull_request", true)
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string"); put("description", description)
    }
    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer"); put("description", description)
    }
}
