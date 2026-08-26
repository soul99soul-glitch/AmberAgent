package app.amber.ai.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the small result-path language used by provider balance endpoints.
 *
 * The syntax is deliberately limited to object fields, array indexes, and one
 * numeric subtraction between two paths. It is not a general JSON expression
 * language; keeping the contract narrow makes invalid provider configuration
 * visible instead of silently evaluating a different expression.
 */
object BalanceResultPath {

    fun isValid(expression: String): Boolean =
        runCatching { parse(expression) }.isSuccess

    fun extract(root: JsonObject, expression: String): String {
        val parsed = parse(expression)
        val left = primitiveContent(root, parsed.left)
        val right = parsed.right ?: return left

        val leftNumber = left.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Balance result path '$expression' resolved to a non-number: '$left'")
        val rightContent = primitiveContent(root, right)
        val rightNumber = rightContent.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Balance result path '$expression' resolved to a non-number: '$rightContent'")
        return leftNumber.subtract(rightNumber).stripTrailingZeros().toPlainString()
    }

    private fun parse(expression: String): ParsedExpression {
        val input = expression.trim()
        require(input.isNotEmpty()) { "Balance result path must not be empty" }

        val separator = input.indexOf('-')
        val secondSeparator = if (separator >= 0) input.indexOf('-', separator + 1) else -1
        require(secondSeparator < 0) {
            "Balance result path supports at most one subtraction"
        }

        val leftEnd = if (separator >= 0) separator else input.length
        val left = parsePath(input.substring(0, leftEnd).trim(), expression)
        val right = if (separator >= 0) {
            parsePath(input.substring(separator + 1).trim(), expression)
        } else {
            null
        }
        return ParsedExpression(left, right)
    }

    private fun parsePath(path: String, expression: String): List<Segment> {
        require(path.isNotEmpty()) {
            "Balance result path '$expression' contains an empty path"
        }
        val segments = mutableListOf<Segment>()
        var index = 0
        parseField(path, index, expression).let { (field, next) ->
            segments += Segment.Field(field)
            index = next
        }

        while (index < path.length) {
            index = skipWhitespace(path, index)
            when (path.getOrNull(index)) {
                '.' -> {
                    index = skipWhitespace(path, index + 1)
                    parseField(path, index, expression).let { (field, next) ->
                        segments += Segment.Field(field)
                        index = next
                    }
                }

                '[' -> {
                    index = skipWhitespace(path, index + 1)
                    val start = index
                    while (path.getOrNull(index)?.isDigit() == true) index++
                    require(index > start) {
                        "Balance result path '$expression' has an invalid array index"
                    }
                    val arrayIndex = path.substring(start, index).toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "Balance result path '$expression' has an out-of-range array index",
                        )
                    index = skipWhitespace(path, index)
                    require(path.getOrNull(index) == ']') {
                        "Balance result path '$expression' is missing ']'"
                    }
                    segments += Segment.Index(arrayIndex)
                    index++
                }

                else -> throw IllegalArgumentException(
                    "Balance result path '$expression' has an invalid path segment",
                )
            }
        }
        return segments
    }

    private fun parseField(path: String, start: Int, expression: String): Pair<String, Int> {
        val index = skipWhitespace(path, start)
        val first = path.getOrNull(index)
        require(first == '_' || first?.isLetter() == true) {
            "Balance result path '$expression' must start with an object field"
        }
        var end = index + 1
        while (path.getOrNull(end) == '_' || path.getOrNull(end)?.isLetterOrDigit() == true) {
            end++
        }
        return path.substring(index, end) to end
    }

    private fun primitiveContent(root: JsonObject, path: List<Segment>): String {
        var current: JsonElement = root
        path.forEach { segment ->
            current = when (segment) {
                is Segment.Field -> (current as? JsonObject)?.get(segment.name)
                    ?: throw IllegalArgumentException(
                        "Balance result path is missing object field '${segment.name}'",
                    )

                is Segment.Index -> (current as? JsonArray)?.getOrNull(segment.value)
                    ?: throw IllegalArgumentException(
                        "Balance result path is missing array index ${segment.value}",
                    )
            }
        }
        return (current as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Balance result path must resolve to a primitive value")
    }

    private fun skipWhitespace(path: String, start: Int): Int {
        var index = start
        while (path.getOrNull(index)?.isWhitespace() == true) index++
        return index
    }

    private data class ParsedExpression(
        val left: List<Segment>,
        val right: List<Segment>?,
    )

    private sealed interface Segment {
        data class Field(val name: String) : Segment
        data class Index(val value: Int) : Segment
    }
}
