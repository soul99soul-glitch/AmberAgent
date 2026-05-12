package me.rerere.rikkahub.data.usage

import android.webkit.CookieManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OpenAIBrand
import me.rerere.ai.provider.OpenAIAuthMode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ProviderUsageStatus(
    val title: String,
    val planType: String? = null,
    val metrics: List<ProviderUsageMetric> = emptyList(),
)

data class ProviderUsageMetric(
    val label: String,
    val percent: Int? = null,
    val detail: String? = null,
)

class ProviderUsageClient(
    private val client: OkHttpClient,
) {
    suspend fun fetchUsage(provider: ProviderSetting.OpenAI, model: Model?): ProviderUsageStatus {
        return when {
            provider.brand == OpenAIBrand.ZHIPU || provider.looksLike(
                "zhipu",
                "bigmodel",
                "z.ai",
                "glm",
                model = model,
            ) ->
                fetchZaiUsage(provider)

            provider.brand == OpenAIBrand.KIMI || provider.looksLike("kimi", "moonshot", model = model) ->
                fetchKimiUsage(provider)

            provider.brand == OpenAIBrand.MIMO || provider.looksLike("mimo", "xiaomi", model = model) ->
                fetchMiMoUsage()

            provider.looksLike("minimax", "minimaxi", model = model) ->
                fetchMiniMaxUsage(provider)

            provider.balanceOption.enabled ->
                fetchGenericBalance(provider)

            else -> error("当前 provider 暂未配置 /usage 查询端点。")
        }
    }

    private suspend fun fetchKimiUsage(provider: ProviderSetting.OpenAI): ProviderUsageStatus {
        if (provider.authMode == OpenAIAuthMode.KIMI_CODING_PLAN) {
            val authToken = provider.apiKey.trim().ifBlank {
                CookieManager.getInstance().getCookie("https://www.kimi.com")
                    ?.cookieValue("kimi-auth")
                    .orEmpty()
            }
            if (authToken.isBlank()) {
                error("Kimi Coding Plan 需要 kimi-auth 登录态。请先在 WebView/WebMount 登录 https://www.kimi.com。")
            }
            return fetchKimiCodingPlan(authToken)
        }
        return fetchMoonshotBalance(provider)
    }

    private suspend fun fetchKimiCodingPlan(authToken: String): ProviderUsageStatus {
        val body = JSONObject()
            .put("scope", JSONArray().put("FEATURE_CODING"))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("https://www.kimi.com/apiv2/kimi.gateway.billing.v1.BillingService/GetUsages")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Cookie", "kimi-auth=$authToken")
            .addHeader("Origin", "https://www.kimi.com")
            .addHeader("Referer", "https://www.kimi.com/code/console")
            .addHeader("User-Agent", DESKTOP_CHROME_UA)
            .addHeader("connect-protocol-version", "1")
            .addHeader("x-language", "zh-CN")
            .addHeader("x-msh-platform", "web")
            .post(body)
            .build()
        val response = client.newCall(request).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Kimi Coding Plan 用量查询失败：HTTP ${response.code} ${text.take(180)}")
        }

        val usages = JSONObject(text).optJSONArray("usages") ?: JSONArray()
        val usage = usages.objects().firstOrNull { it.optString("scope") == "FEATURE_CODING" }
            ?: error("Kimi 响应中没有 FEATURE_CODING 用量。")
        val weekly = usage.optJSONObject("detail")
        val rateLimit = usage.optJSONArray("limits")?.optJSONObject(0)?.optJSONObject("detail")
        return ProviderUsageStatus(
            title = "Kimi Coding Plan",
            metrics = listOfNotNull(
                rateLimit?.toQuotaMetric("5h"),
                weekly?.toQuotaMetric("weekly"),
            ),
        )
    }

    private suspend fun fetchMoonshotBalance(provider: ProviderSetting.OpenAI): ProviderUsageStatus {
        val url = "${provider.baseUrl.trimEnd('/')}/users/me/balance"
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${provider.apiKey.trim()}")
                .get()
                .build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("Kimi/Moonshot 余额查询失败：HTTP ${response.code} ${text.take(180)}")
        }
        val data = JSONObject(text).optJSONObject("data") ?: JSONObject(text)
        return ProviderUsageStatus(
            title = "Kimi / Moonshot Balance",
            metrics = listOf(
                ProviderUsageMetric("balance", detail = data.firstString("available_balance", "total_balance", "balance")),
                ProviderUsageMetric("cash", detail = data.firstString("cash_balance", "cash")),
                ProviderUsageMetric("voucher", detail = data.firstString("voucher_balance", "voucher")),
            ).filter { !it.detail.isNullOrBlank() },
        )
    }

    private suspend fun fetchMiniMaxUsage(provider: ProviderSetting.OpenAI): ProviderUsageStatus {
        if (provider.apiKey.isNotBlank()) {
            runCatching { return fetchMiniMaxByApiToken(provider) }
        }
        return fetchMiniMaxByCookie(provider)
    }

    private suspend fun fetchMiniMaxByApiToken(provider: ProviderSetting.OpenAI): ProviderUsageStatus {
        val url = if (provider.baseUrl.contains("minimaxi", ignoreCase = true)) {
            "https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains"
        } else {
            "https://api.minimax.io/v1/api/openplatform/coding_plan/remains"
        }
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${provider.apiKey.trim()}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("MM-API-Source", "AmberAgent")
                .get()
                .build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("MiniMax Token Plan 查询失败：HTTP ${response.code} ${text.take(180)}")
        }
        return parseMiniMaxUsage(text)
    }

    private suspend fun fetchMiniMaxByCookie(provider: ProviderSetting.OpenAI): ProviderUsageStatus {
        val global = "https://platform.minimax.io"
        val china = "https://platform.minimaxi.com"
        val base = if (provider.baseUrl.contains("minimaxi", ignoreCase = true)) china else global
        val cookie = CookieManager.getInstance().getCookie(base).orEmpty()
        if (cookie.isBlank()) {
            error("MiniMax Token Plan 需要控制台登录态。请先在 WebView/WebMount 登录 $base。")
        }
        val response = client.newCall(
            Request.Builder()
                .url("$base/v1/api/openplatform/coding_plan/remains")
                .addHeader("Cookie", cookie)
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Referer", "$base/user-center/payment/coding-plan")
                .addHeader("Origin", base)
                .addHeader("User-Agent", DESKTOP_CHROME_UA)
                .get()
                .build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("MiniMax Token Plan 查询失败：HTTP ${response.code} ${text.take(180)}")
        }
        return parseMiniMaxUsage(text)
    }

    private fun parseMiniMaxUsage(text: String): ProviderUsageStatus {
        val root = JSONObject(text)
        val data = root.optJSONObject("data") ?: root
        val remains = data.optJSONArray("model_remains")
            ?: data.optJSONArray("modelRemains")
            ?: data.findArray("model_remains")
            ?: JSONArray()
        val metrics = remains.objects().mapNotNull { item ->
            val total = item.firstInt("current_interval_total_count", "currentIntervalTotalCount") ?: return@mapNotNull null
            if (total <= 0) return@mapNotNull null
            val remaining = item.firstInt("current_interval_usage_count", "currentIntervalUsageCount") ?: return@mapNotNull null
            val used = (total - remaining).coerceAtLeast(0)
            val percent = ((used.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            val name = item.firstString("model_name", "modelName", "service_type")?.ifBlank { null } ?: "5h"
            ProviderUsageMetric(
                label = name,
                percent = percent,
                detail = "$used/$total · remaining $remaining${item.resetDetail()}",
            )
        }.take(4)
        return ProviderUsageStatus(
            title = "MiniMax Token Plan",
            planType = data.firstString("plan_name", "planName", "package_name", "packageName"),
            metrics = metrics.ifEmpty { listOf(ProviderUsageMetric("Token Plan", detail = text.take(220))) },
        )
    }

    private suspend fun fetchZaiUsage(provider: ProviderSetting.OpenAI): ProviderUsageStatus {
        val url = if (provider.baseUrl.contains("z.ai", ignoreCase = true)) {
            "https://api.z.ai/api/monitor/usage/quota/limit"
        } else {
            "https://open.bigmodel.cn/api/monitor/usage/quota/limit"
        }
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${provider.apiKey.trim()}")
                .addHeader("Accept", "application/json")
                .get()
                .build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("智谱 GLM Coding Plan 用量查询失败：HTTP ${response.code} ${text.take(180)}")
        }
        val root = JSONObject(text)
        val data = root.optJSONObject("data") ?: error("智谱响应中缺少 data。")
        val limits = data.optJSONArray("limits") ?: JSONArray()
        val metrics = limits.objects().mapNotNull { limit ->
            val type = limit.optString("type")
            val number = limit.optInt("number", 0)
            val label = when {
                type == "TOKENS_LIMIT" && limit.optInt("unit") == 3 && number == 5 -> "5h"
                type == "TOKENS_LIMIT" -> limit.windowLabel().ifBlank { "tokens" }
                type == "TIME_LIMIT" -> "time"
                else -> type.lowercase(Locale.getDefault()).ifBlank { "quota" }
            }
            val percent = limit.optInt("percentage", -1).takeIf { it >= 0 }?.coerceIn(0, 100)
            val quota = limit.firstInt("usage")
            val remaining = limit.firstInt("remaining")
            val used = quota?.let { q -> remaining?.let { (q - it).coerceAtLeast(0) } ?: limit.firstInt("currentValue") }
            ProviderUsageMetric(
                label = label,
                percent = percent,
                detail = listOfNotNull(
                    if (quota != null && used != null) "$used/$quota" else null,
                    limit.firstLong("nextResetTime")?.formatResetDetail(),
                ).joinToString(" · ").ifBlank { null },
            )
        }
        return ProviderUsageStatus(
            title = "智谱 GLM Coding Plan",
            planType = data.firstString("planName", "plan", "plan_type", "packageName"),
            metrics = metrics,
        )
    }

    private suspend fun fetchMiMoUsage(): ProviderUsageStatus {
        val base = "https://platform.xiaomimimo.com"
        val cookie = CookieManager.getInstance().getCookie(base).orEmpty()
        if (!cookie.hasCookie("api-platform_serviceToken") || !cookie.hasCookie("userId")) {
            error("小米 MiMo Token Plan 需要控制台登录态。请先在 WebView/WebMount 登录 $base。")
        }
        val balance = runCatching { fetchMiMoJson("$base/api/v1/balance", cookie) }.getOrNull()
        val detail = runCatching { fetchMiMoJson("$base/api/v1/tokenPlan/detail", cookie) }.getOrNull()
        val usage = runCatching { fetchMiMoJson("$base/api/v1/tokenPlan/usage", cookie) }.getOrNull()
        val balanceData = balance?.optJSONObject("data")
        val detailData = detail?.optJSONObject("data")
        val usageItem = usage?.optJSONObject("data")
            ?.optJSONObject("monthUsage")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
        val used = usageItem?.firstInt("used") ?: 0
        val limit = usageItem?.firstInt("limit") ?: 0
        val rawPercent = usageItem?.optDouble("percent", Double.NaN)?.takeIf { !it.isNaN() }
        val percent = rawPercent?.let { if (it <= 1.0) it * 100.0 else it }?.toInt()?.coerceIn(0, 100)
        return ProviderUsageStatus(
            title = "小米 MiMo Token Plan",
            planType = detailData?.firstString("planCode", "plan_code"),
            metrics = listOfNotNull(
                if (limit > 0) ProviderUsageMetric(
                    label = "Token Plan",
                    percent = percent,
                    detail = "$used/$limit Credits${detailData?.firstString("currentPeriodEnd")?.let { " · $it" }.orEmpty()}",
                ) else null,
                balanceData?.firstString("balance")?.let {
                    ProviderUsageMetric("balance", detail = "$it ${balanceData.firstString("currency").orEmpty()}")
                },
            ),
        )
    }

    private suspend fun fetchMiMoJson(url: String, cookie: String): JSONObject {
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Cookie", cookie)
                .addHeader("Origin", "https://platform.xiaomimimo.com")
                .addHeader("Referer", "https://platform.xiaomimimo.com/#/console/balance")
                .addHeader("User-Agent", DESKTOP_CHROME_UA)
                .get()
                .build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("小米 MiMo 查询失败：HTTP ${response.code} ${text.take(180)}")
        }
        return JSONObject(text)
    }

    private suspend fun fetchGenericBalance(provider: ProviderSetting.OpenAI): ProviderUsageStatus {
        val path = provider.balanceOption.apiPath
        val url = if (path.startsWith("http")) path else "${provider.baseUrl.trimEnd('/')}$path"
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${provider.apiKey.trim()}")
                .get()
                .build()
        ).await()
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("${provider.name} 余额查询失败：HTTP ${response.code} ${text.take(180)}")
        }
        return ProviderUsageStatus(
            title = "${provider.name} Balance",
            metrics = listOf(ProviderUsageMetric("balance", detail = JSONObject(text).getByPath(provider.balanceOption.resultPath))),
        )
    }

    private fun ProviderSetting.OpenAI.looksLike(vararg hints: String, model: Model?): Boolean {
        val haystack = listOf(name, baseUrl, model?.modelId.orEmpty(), model?.displayName.orEmpty())
            .joinToString(" ")
            .lowercase(Locale.getDefault())
        return hints.any { it.lowercase(Locale.getDefault()) in haystack }
    }

    private fun JSONObject.toQuotaMetric(label: String): ProviderUsageMetric {
        val limit = firstInt("limit") ?: firstString("limit")?.toIntOrNull()
        val remaining = firstInt("remaining") ?: firstString("remaining")?.toIntOrNull()
        val used = firstInt("used") ?: firstString("used")?.toIntOrNull()
            ?: limit?.let { l -> remaining?.let { (l - it).coerceAtLeast(0) } }
        val percent = if (limit != null && limit > 0 && used != null) {
            ((used.toDouble() / limit.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else null
        return ProviderUsageMetric(
            label = label,
            percent = percent,
            detail = listOfNotNull(
                if (used != null && limit != null) "$used/$limit" else remaining?.let { "remaining $it" },
                firstString("resetTime", "reset_time")?.let { "reset $it" },
            ).joinToString(" · ").ifBlank { null },
        )
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { index -> optJSONObject(index) }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() && it != "null" } }

    private fun JSONObject.firstInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            when (val value = opt(key)) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull()
                else -> null
            }
        }

    private fun JSONObject.firstLong(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            when (val value = opt(key)) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        }

    private fun JSONObject.getByPath(path: String): String? {
        var current: Any? = this
        path.split(".").forEach { segment ->
            current = when (current) {
                is JSONObject -> (current as JSONObject).opt(segment)
                is JSONArray -> segment.toIntOrNull()?.let { (current as JSONArray).opt(it) }
                else -> null
            }
        }
        return current?.toString()
    }

    private fun JSONObject.findArray(name: String): JSONArray? {
        if (has(name) && opt(name) is JSONArray) return optJSONArray(name)
        keys().forEach { key ->
            val child = opt(key)
            when (child) {
                is JSONObject -> child.findArray(name)?.let { return it }
                is JSONArray -> child.objects().forEach { obj -> obj.findArray(name)?.let { return it } }
            }
        }
        return null
    }

    private fun JSONObject.resetDetail(): String {
        val end = firstLong("end_time", "endTime") ?: return ""
        return " · reset ${end.formatResetDetail()}"
    }

    private fun JSONObject.windowLabel(): String {
        val number = optInt("number", 0).takeIf { it > 0 } ?: return ""
        val unit = when (optInt("unit")) {
            1 -> "d"
            3 -> "h"
            5 -> "m"
            6 -> "w"
            else -> ""
        }
        return "$number$unit"
    }

    private fun String.cookieValue(name: String): String? =
        split(";").mapNotNull { part ->
            val index = part.indexOf("=")
            if (index <= 0) null
            else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.firstOrNull { it.first == name }?.second

    private fun String.hasCookie(name: String): Boolean = cookieValue(name)?.isNotBlank() == true

    private fun Long.formatResetDetail(): String {
        val millis = if (this < 10_000_000_000L) this * 1000L else this
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
    }
}
