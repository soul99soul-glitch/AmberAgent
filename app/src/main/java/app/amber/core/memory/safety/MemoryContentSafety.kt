package app.amber.core.memory.safety

internal fun isSensitiveMemoryContent(content: String): Boolean {
    val lower = content.lowercase()
    return sensitiveMemoryTerms.any { it in lower }
}

private val sensitiveMemoryTerms = listOf(
    "身份证", "护照", "银行卡", "密码", "宗教", "政治观点",
    "criminal", "password", "passport", "credit card", "religion", "sexual",
)
