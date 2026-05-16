package me.rerere.rikkahub.data.agent.subagent

object SmartSubAgentNames {
    private val pool = listOf(
        "Alice", "Henry", "Oliver", "Emma", "Jack", "Charlotte",
        "William", "Amelia", "James", "Grace", "George", "Sophie",
        "Thomas", "Lucy", "Daniel", "Ella", "Samuel", "Mia",
    )

    fun pick(seed: String, usedNames: Set<String> = emptySet()): String {
        val available = pool.filterNot { name -> usedNames.any { it.equals(name, ignoreCase = true) } }
            .ifEmpty { pool }
        val index = seed.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) } % available.size
        return available[index]
    }
}
