package me.rerere.rikkahub.utils

class Version(
    val value: String,
) : Comparable<Version> {
    private val parsed = ParsedVersion.parse(value)

    override fun compareTo(other: Version): Int =
        parsed.compareTo(other.parsed)

    operator fun compareTo(other: String): Int =
        compareTo(Version(other))

    companion object {
        fun compare(first: String, second: String): Int =
            Version(first).compareTo(Version(second))
    }
}

operator fun String.compareTo(version: Version): Int =
    Version(this).compareTo(version)

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>,
) : Comparable<ParsedVersion> {
    override fun compareTo(other: ParsedVersion): Int {
        val maxCoreSize = maxOf(core.size, other.core.size)
        for (index in 0 until maxCoreSize) {
            val left = core.getOrElse(index) { 0 }
            val right = other.core.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }

        val leftIsRelease = prerelease.isEmpty()
        val rightIsRelease = other.prerelease.isEmpty()
        if (leftIsRelease || rightIsRelease) {
            return when {
                leftIsRelease && rightIsRelease -> 0
                leftIsRelease -> 1
                else -> -1
            }
        }

        val maxPrereleaseSize = maxOf(prerelease.size, other.prerelease.size)
        for (index in 0 until maxPrereleaseSize) {
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            val comparison = comparePrereleaseIdentifier(left, right)
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(value: String): ParsedVersion {
            val withoutBuild = value.substringBefore('+')
            val corePart = withoutBuild.substringBefore('-')
            val prereleasePart = withoutBuild.substringAfter('-', missingDelimiterValue = "")
            return ParsedVersion(
                core = corePart.split('.').map { it.toIntOrNull() ?: 0 },
                prerelease = prereleasePart.takeIf { it.isNotBlank() }?.split('.').orEmpty(),
            )
        }

        private fun comparePrereleaseIdentifier(left: String, right: String): Int {
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            return when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
    }
}
