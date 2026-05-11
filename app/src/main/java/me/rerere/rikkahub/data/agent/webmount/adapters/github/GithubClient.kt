package me.rerere.rikkahub.data.agent.webmount.adapters.github

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * GitHub REST API v3 client (anonymous). Public surface only — write
 * operations are a Phase 2 concern since they need a real OAuth App or
 * Personal Access Token flow that this client deliberately doesn't expose
 * in Phase 1.
 *
 * Auth optional: if [bearerToken] is non-null (e.g. a GitHub PAT pasted by
 * the user), requests include `Authorization: Bearer <token>` which lifts
 * the rate limit from 60/hr → 5000/hr.
 */
class GithubClient(
    private val http: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun searchRepos(query: String, sort: String?, order: String?, limit: Int, bearerToken: String?): GithubRepoSearch {
        require(query.isNotBlank()) { "query is required" }
        val response = http.get("$BASE/search/repositories") {
            applyHeaders(bearerToken)
            parameter("q", query)
            sort?.takeIf { it.isNotBlank() }?.let { parameter("sort", it) }
            order?.takeIf { it.isNotBlank() }?.let { parameter("order", it) }
            parameter("per_page", limit.coerceIn(1, 100))
        }
        val parsed = parseJson(response, "repo search") as? JsonObject ?: error("unexpected search response")
        val totalCount = parsed["total_count"]?.jsonPrimitive?.intOrNull ?: 0
        val items = (parsed["items"] as? JsonArray).orEmpty().mapNotNull { entry ->
            (entry as? JsonObject)?.let { parseRepoSummary(it) }
        }
        return GithubRepoSearch(query, totalCount, items)
    }

    suspend fun repoInfo(owner: String, repo: String, bearerToken: String?): GithubRepoSummary {
        val response = http.get("$BASE/repos/$owner/$repo") { applyHeaders(bearerToken) }
        val parsed = parseJson(response, "repo info") as? JsonObject
            ?: error("repo not found: $owner/$repo")
        return parseRepoSummary(parsed)
    }

    suspend fun listIssues(
        owner: String,
        repo: String,
        state: String,
        limit: Int,
        bearerToken: String?,
    ): List<GithubIssueSummary> {
        val response = http.get("$BASE/repos/$owner/$repo/issues") {
            applyHeaders(bearerToken)
            parameter("state", state)
            parameter("per_page", limit.coerceIn(1, 100))
        }
        val arr = parseJson(response, "issues list") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { entry -> (entry as? JsonObject)?.let { parseIssueSummary(it) } }
    }

    suspend fun listPulls(
        owner: String,
        repo: String,
        state: String,
        limit: Int,
        bearerToken: String?,
    ): List<GithubIssueSummary> {
        val response = http.get("$BASE/repos/$owner/$repo/pulls") {
            applyHeaders(bearerToken)
            parameter("state", state)
            parameter("per_page", limit.coerceIn(1, 100))
        }
        val arr = parseJson(response, "pulls list") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { entry -> (entry as? JsonObject)?.let { parseIssueSummary(it) } }
    }

    suspend fun readFile(
        owner: String,
        repo: String,
        path: String,
        ref: String?,
        bearerToken: String?,
    ): GithubFileContent? {
        val response = http.get("$BASE/repos/$owner/$repo/contents/$path") {
            applyHeaders(bearerToken)
            if (!ref.isNullOrBlank()) parameter("ref", ref)
            // Request raw content when possible; falls back to base64-encoded JSON.
            headers { append(HttpHeaders.Accept, "application/vnd.github.v3+json") }
        }
        val parsed = parseJson(response, "file contents")
        // Could be an array (directory listing) — we only support file reads here.
        val obj = parsed as? JsonObject ?: error("path is a directory or unknown shape: $path")
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "file") return null
        val encoded = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrElse { return null }
        return GithubFileContent(
            path = obj.s("path") ?: path,
            sha = obj.s("sha"),
            sizeBytes = obj.l("size") ?: 0L,
            encoding = obj.s("encoding") ?: "base64",
            content = content,
            htmlUrl = obj.s("html_url"),
            downloadUrl = obj.s("download_url"),
        )
    }

    suspend fun readUser(username: String, bearerToken: String?): JsonObject {
        val response = http.get("$BASE/users/$username") { applyHeaders(bearerToken) }
        return parseJson(response, "user info") as? JsonObject
            ?: error("user not found: $username")
    }

    suspend fun probe(bearerToken: String?): Boolean = runCatching {
        // /rate_limit is the cheapest authenticated/anonymous endpoint.
        val response = http.get("$BASE/rate_limit") { applyHeaders(bearerToken) }
        response.status.isSuccess()
    }.getOrElse { false }

    // ----------------------------------------------------------------------

    private fun HttpRequestBuilder.applyHeaders(bearerToken: String?) {
        headers {
            append(HttpHeaders.Accept, "application/vnd.github.v3+json")
            append(HttpHeaders.UserAgent, USER_AGENT)
            bearerToken?.takeIf { it.isNotBlank() }?.let {
                append(HttpHeaders.Authorization, "Bearer $it")
            }
        }
    }

    private suspend fun parseJson(response: HttpResponse, label: String): JsonElement {
        val text = response.bodyAsText()
        require(response.status.isSuccess()) {
            "$label failed: HTTP ${response.status.value} ${text.take(500)}"
        }
        return json.parseToJsonElement(text)
    }

    private fun parseRepoSummary(obj: JsonObject): GithubRepoSummary = GithubRepoSummary(
        fullName = obj.s("full_name") ?: "",
        name = obj.s("name") ?: "",
        ownerLogin = (obj["owner"] as? JsonObject)?.s("login"),
        description = obj.s("description"),
        htmlUrl = obj.s("html_url"),
        stars = obj.i("stargazers_count") ?: 0,
        forks = obj.i("forks_count") ?: 0,
        openIssues = obj.i("open_issues_count") ?: 0,
        language = obj.s("language"),
        defaultBranch = obj.s("default_branch") ?: "main",
        pushedAtMs = obj.iso8601("pushed_at"),
        createdAtMs = obj.iso8601("created_at"),
        topics = (obj["topics"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
    )

    private fun parseIssueSummary(obj: JsonObject): GithubIssueSummary = GithubIssueSummary(
        number = obj.i("number") ?: 0,
        title = obj.s("title") ?: "",
        state = obj.s("state") ?: "",
        author = (obj["user"] as? JsonObject)?.s("login"),
        commentCount = obj.i("comments") ?: 0,
        bodyPreview = obj.s("body")?.take(500),
        htmlUrl = obj.s("html_url"),
        createdAtMs = obj.iso8601("created_at"),
        updatedAtMs = obj.iso8601("updated_at"),
        isPullRequest = obj["pull_request"] != null,
    )

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.l(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonObject.iso8601(name: String): Long {
        val s = s(name) ?: return 0L
        return runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrElse { 0L }
    }
    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    companion object {
        private const val BASE = "https://api.github.com"
        private const val USER_AGENT = "AmberAgent-WebMount/1.0"
    }
}

data class GithubRepoSummary(
    val fullName: String,
    val name: String,
    val ownerLogin: String?,
    val description: String?,
    val htmlUrl: String?,
    val stars: Int,
    val forks: Int,
    val openIssues: Int,
    val language: String?,
    val defaultBranch: String,
    val pushedAtMs: Long,
    val createdAtMs: Long,
    val topics: List<String>,
)

data class GithubRepoSearch(
    val query: String,
    val totalCount: Int,
    val items: List<GithubRepoSummary>,
)

data class GithubIssueSummary(
    val number: Int,
    val title: String,
    val state: String,
    val author: String?,
    val commentCount: Int,
    val bodyPreview: String?,
    val htmlUrl: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val isPullRequest: Boolean,
)

data class GithubFileContent(
    val path: String,
    val sha: String?,
    val sizeBytes: Long,
    val encoding: String,
    val content: String,
    val htmlUrl: String?,
    val downloadUrl: String?,
)
