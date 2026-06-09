package app.amber.feature.board.hotlist

import java.security.MessageDigest
import java.util.Locale

class HotListAggregator(
    private val aliasDictionary: Map<String, String> = DEFAULT_ALIASES,
) {
    fun aggregate(snapshots: List<HotListProviderSnapshot>, limit: Int = 10): List<HotTopic> {
        val candidates = snapshots.flatMap { snapshot ->
            snapshot.items.map { item ->
                Candidate(
                    snapshot = snapshot,
                    item = item,
                    normalizedTitle = normalizeTitle(item.title),
                    entities = extractEntities(item.title),
                )
            }
        }
        val groups = mutableListOf<MutableList<Candidate>>()
        candidates.forEach { candidate ->
            val group = groups.firstOrNull { existing -> existing.any { areSameTopic(it, candidate) } }
            if (group == null) {
                groups += mutableListOf(candidate)
            } else {
                group += candidate
            }
        }

        return groups.map { group ->
            val sources = group
                .sortedWith(compareBy<Candidate> { it.item.rank }.thenBy { it.snapshot.providerId })
                .map { candidate ->
                    HotTopicSource(
                        providerId = candidate.snapshot.providerId,
                        providerName = candidate.snapshot.providerName,
                        rank = candidate.item.rank,
                        title = candidate.item.title,
                        displayTitle = candidate.item.displayTitle,
                        url = candidate.item.url,
                        heat = candidate.item.heat,
                        images = candidate.item.images,
                    )
                }
            val chosen = chooseTitleCandidate(group)
            val title = chosen.item.presentationTitle
            HotTopic(
                id = HotListRepository.topicId(chosen.item.title),
                title = title,
                sources = sources,
                sourceCount = sources.map { it.providerId }.distinct().size,
                bestRank = sources.minOfOrNull { it.rank } ?: Int.MAX_VALUE,
                latestFetchedAt = group.maxOfOrNull { it.snapshot.fetchedAt } ?: 0L,
            )
        }
            .sortedWith(
                compareByDescending<HotTopic> { it.sourceCount }
                    .thenBy { it.bestRank }
                    .thenByDescending { it.latestFetchedAt }
            )
            .take(limit)
    }

    fun extractEntities(title: String): Set<String> {
        val normalized = normalizeTitle(title)
        val entities = linkedSetOf<String>()

        aliasDictionary.forEach { (alias, canonical) ->
            if (normalized.contains(alias)) entities += canonical
        }

        ENGLISH_TOKEN.findAll(title.lowercase(Locale.ROOT)).forEach { match ->
            val token = match.value.trim('.').takeIf { it.length >= 2 } ?: return@forEach
            if (token !in ENGLISH_STOPWORDS) entities += aliasDictionary[token] ?: token
        }

        CHINESE_CHUNK.findAll(title).forEach { match ->
            val chunk = match.value
            if (chunk.length in 2..10 && chunk !in CHINESE_STOPWORDS) {
                entities += aliasDictionary[chunk] ?: chunk
            }
        }

        return entities
    }

    fun areSameTopic(left: Candidate, right: Candidate): Boolean {
        val sharedEntities = left.entities intersect right.entities
        if (sharedEntities.size >= 2) return true
        if (sharedEntities.size == 1 && left.normalizedTitle == right.normalizedTitle) return true
        if (sameLanguage(left.item.title, right.item.title)) {
            val jaccard = bigramJaccard(left.normalizedTitle, right.normalizedTitle)
            if (jaccard >= 0.4 && left.normalizedTitle.length >= 6 && right.normalizedTitle.length >= 6) return true
        }
        return false
    }

    private fun chooseTitleCandidate(group: List<Candidate>): Candidate =
        group.sortedWith(
            compareByDescending<Candidate> { it.snapshot.items.size }
                .thenBy { it.item.rank }
                .thenByDescending { it.item.title.length }
        ).first()

    private fun normalizeTitle(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("[#【】\\[\\]（）()《》\"'“”‘’！!？?，,。.:：;；/\\\\|_\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun bigramJaccard(left: String, right: String): Double {
        val a = left.bigrams()
        val b = right.bigrams()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / (a union b).size.toDouble()
    }

    private fun String.bigrams(): Set<String> {
        val compact = replace(" ", "")
        if (compact.length < 2) return emptySet()
        return compact.windowed(2).toSet()
    }

    private fun sameLanguage(left: String, right: String): Boolean =
        left.any { it in '\u4e00'..'\u9fff' } == right.any { it in '\u4e00'..'\u9fff' }

    data class Candidate(
        val snapshot: HotListProviderSnapshot,
        val item: HotListItem,
        val normalizedTitle: String,
        val entities: Set<String>,
    )

    companion object {
        private val ENGLISH_TOKEN = Regex("[a-z][a-z0-9.+-]*")
        private val CHINESE_CHUNK = Regex("[\\u4e00-\\u9fff]{2,10}")
        private val ENGLISH_STOPWORDS = setOf("the", "and", "for", "with", "from", "this", "that", "news")
        private val CHINESE_STOPWORDS = setOf("热搜", "最新", "今日", "官方", "回应", "发布", "宣布", "网友", "视频")

        val DEFAULT_ALIASES = mapOf(
            "马斯克" to "elon_musk",
            "musk" to "elon_musk",
            "elon" to "elon_musk",
            "elonmusk" to "elon_musk",
            "openai" to "openai",
            "chatgpt" to "openai",
            "gpt" to "openai",
            "人工智能" to "ai",
            "llm" to "llm",
            "大模型" to "llm",
            "agent" to "agent",
            "智能体" to "agent",
            "claude" to "claude",
            "anthropic" to "claude",
            "deepseek" to "deepseek",
            "gemini" to "gemini",
            "机器人" to "robotics",
            "robot" to "robotics",
            "robotics" to "robotics",
            "具身智能" to "embodied_ai",
            "自动驾驶" to "autonomous_driving",
            "芯片" to "chip",
            "半导体" to "semiconductor",
            "数码" to "digital",
            "3c" to "digital",
            "智能硬件" to "smart_hardware",
            "gpt5" to "gpt_5",
            "gpt-5" to "gpt_5",
            "苹果" to "apple",
            "apple" to "apple",
            "iphone" to "iphone",
            "ios" to "ios",
            "谷歌" to "google",
            "google" to "google",
            "微软" to "microsoft",
            "microsoft" to "microsoft",
            "tesla" to "tesla",
            "特斯拉" to "tesla",
            "英伟达" to "nvidia",
            "nvidia" to "nvidia",
            "小米" to "xiaomi",
            "xiaomi" to "xiaomi",
            "华为" to "huawei",
            "huawei" to "huawei",
            "字节" to "bytedance",
            "字节跳动" to "bytedance",
            "bytedance" to "bytedance",
            "抖音" to "douyin",
            "tiktok" to "tiktok",
            "b站" to "bilibili",
            "bilibili" to "bilibili",
        )
    }
}

internal fun stableHotId(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)
