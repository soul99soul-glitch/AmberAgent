package app.amber.ai.registry

import app.amber.ai.provider.Modality
import app.amber.ai.provider.ModelAbility

interface ModelSelector {
    fun match(modelId: String): Boolean
}

class ModelDefinition(
    private val matcher: TokenMatcher,
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val abilities: Set<ModelAbility>,
    val contextWindowTokens: Int?,
) : ModelSelector {
    override fun match(modelId: String): Boolean = score(modelId) != null

    fun matchScore(modelId: String): Int? = score(modelId)

    internal fun matchScore(modelId: String, tokens: List<String>): Int? =
        matcher.score(modelId, tokens)

    private fun score(modelId: String): Int? = matcher.score(modelId, tokenize(modelId))
}

class ModelGroup internal constructor(
    private val members: List<ModelSelector>
) : ModelSelector {
    override fun match(modelId: String): Boolean = members.any { it.match(modelId) }
}

fun defineModel(block: ModelDefinitionBuilder.() -> Unit): ModelDefinition =
    ModelDefinitionBuilder().apply(block).build()

fun defineGroup(block: ModelGroupBuilder.() -> Unit): ModelGroup =
    ModelGroupBuilder().apply(block).build()

fun tokenRegex(pattern: String): TokenSpec = TokenRegex(pattern.toRegex(RegexOption.IGNORE_CASE))

class ModelDefinitionBuilder {
    private val predicates = mutableListOf<TokenMatcher>()
    private val inputModalities = mutableSetOf(Modality.TEXT)
    private val outputModalities = mutableSetOf(Modality.TEXT)
    private val abilities = mutableSetOf<ModelAbility>()
    private var contextWindowTokens: Int? = null

    fun tokens(vararg specs: String) {
        predicates += tokenSequenceMatcher(specs.map(::parseTokenSpec))
    }

    fun tokens(vararg specs: TokenSpec) {
        predicates += tokenSequenceMatcher(specs.toList())
    }

    fun notTokens(vararg specs: String) {
        predicates += notSequence(specs.map(::parseTokenSpec))
    }

    fun notTokens(vararg specs: TokenSpec) {
        predicates += notSequence(specs.toList())
    }

    fun exact(id: String) {
        predicates += exactMatcher(id)
    }

    fun input(vararg modalities: Modality) {
        inputModalities.clear()
        inputModalities.addAll(modalities)
    }

    fun output(vararg modalities: Modality) {
        outputModalities.clear()
        outputModalities.addAll(modalities)
    }

    fun ability(vararg abilities: ModelAbility) {
        this.abilities.addAll(abilities)
    }

    fun contextWindow(tokens: Int) {
        contextWindowTokens = tokens
    }

    fun build(): ModelDefinition {
        val matcher = predicates.toMatcher()
        return ModelDefinition(
            matcher = matcher,
            inputModalities = inputModalities.toSet(),
            outputModalities = outputModalities.toSet(),
            abilities = abilities.toSet(),
            contextWindowTokens = contextWindowTokens,
        )
    }
}

private fun List<TokenMatcher>.toMatcher(): TokenMatcher = when (size) {
    0 -> noMatch
    1 -> first()
    else -> allOf(toList())
}

class ModelGroupBuilder {
    private val members = mutableListOf<ModelSelector>()

    fun add(vararg models: ModelSelector) {
        members.addAll(models)
    }

    fun build(): ModelGroup = ModelGroup(members.toList())
}

sealed interface TokenSpec {
    fun matches(token: String): Boolean
}

private data class TokenAlternatives(val options: Set<String>) : TokenSpec {
    override fun matches(token: String): Boolean = options.contains(token)
}

private data class TokenRegex(val regex: Regex) : TokenSpec {
    override fun matches(token: String): Boolean = regex.matches(token)
}

interface TokenMatcher {
    fun score(modelId: String, tokens: List<String>): Int?
}

private val noMatch: TokenMatcher = scorer { _, _ -> null }

private fun scorer(block: (String, List<String>) -> Int?): TokenMatcher =
    object : TokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? = block(modelId, tokens)
    }

private fun allOf(matchers: List<TokenMatcher>): TokenMatcher = when (matchers.size) {
    0 -> noMatch
    1 -> matchers[0]
    else -> scorer { modelId, tokens -> sumScores(matchers, modelId, tokens) }
}

private fun sumScores(
    matchers: List<TokenMatcher>,
    modelId: String,
    tokens: List<String>,
): Int? {
    var total = 0
    for (matcher in matchers) {
        total += matcher.score(modelId, tokens) ?: return null
    }
    return total
}

private fun tokenSequenceMatcher(specs: List<TokenSpec>): TokenMatcher = scorer { _, tokens ->
    sequenceScore(specs, tokens)
}

private fun notSequence(specs: List<TokenSpec>): TokenMatcher = scorer { _, tokens ->
    if (sequenceScore(specs, tokens) == null) 0 else null
}

private fun exactMatcher(id: String): TokenMatcher = scorer { modelId, tokens ->
    if (modelId.equals(id, ignoreCase = true)) EXACT_ID_BONUS + tokens.size else null
}

private fun sequenceScore(specs: List<TokenSpec>, tokens: List<String>): Int? {
    if (specs.isEmpty()) return null

    var expected = 0
    for (token in tokens) {
        if (specs[expected].matches(token)) {
            expected += 1
            if (expected == specs.size) return specs.size
        }
    }
    return null
}

private fun parseTokenSpec(spec: String): TokenSpec {
    return TokenAlternatives(
        options = spec.split('|')
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    )
}

private const val EXACT_ID_BONUS = 1000

private fun tokenize(modelId: String): List<String> {
    if (modelId.isEmpty()) return emptyList()

    val tokens = ArrayList<String>()
    val buffer = StringBuilder()
    var kind = TokenKind.NONE

    fun emitBuffer() {
        if (buffer.isNotEmpty()) {
            tokens += buffer.toString()
            buffer.clear()
        }
    }

    for (character in modelId.lowercase()) {
        val nextKind = when {
            character.isLetter() -> TokenKind.LETTER
            character.isDigit() -> TokenKind.DIGIT
            else -> TokenKind.NONE
        }
        if (nextKind == TokenKind.NONE) {
            emitBuffer()
            tokens += character.toString()
            kind = TokenKind.NONE
        } else {
            if (kind != nextKind) emitBuffer()
            buffer.append(character)
            kind = nextKind
        }
    }
    emitBuffer()
    return tokens
}

private enum class TokenKind {
    NONE,
    LETTER,
    DIGIT,
}
