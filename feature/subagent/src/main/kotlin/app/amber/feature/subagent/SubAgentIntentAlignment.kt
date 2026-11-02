package app.amber.feature.subagent

/**
 * Catalog-role intent check after [SubAgentValidator] has already resolved
 * the definition. Implementations must not grant tools or skip allowlists.
 */
fun interface SubAgentIntentAlignment {
    suspend fun assess(
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        runKey: String?,
    ): Result

    data class Result(
        val judged: Boolean,
        val aligned: Boolean,
        val reason: String? = null,
    ) {
        companion object {
            val NotJudged = Result(judged = false, aligned = true)

            fun aligned(): Result = Result(judged = true, aligned = true)

            fun misaligned(reason: String): Result =
                Result(judged = true, aligned = false, reason = reason)
        }
    }
}
