package me.rerere.rikkahub.data.agent.subagent

object SubAgentDefinitions {
    val builtIns: List<SubAgentDefinition> = listOf(
        SubAgentDefinition(
            id = "researcher",
            name = "Researcher",
            description = "Use for focused evidence gathering from web, workspace, conversation history, MCP, and read-only sources.",
            systemPrompt = basePrompt(
                role = "research subagent",
                strengths = "web/source lookup, broad-to-narrow search, evidence summaries",
                extra = "Do not write files or drive apps. Prefer source-backed findings and say when evidence is missing."
            ),
            toolAllowlist = setOf(
                "tools_list", "search_web", "scrape_web",
                "file_list", "file_read", "file_search",
                "conversation_search", "conversation_expand", "session_search",
                "mcp_list", "skills_list",
            ),
        ),
        SubAgentDefinition(
            id = "session-archivist",
            name = "Session Archivist",
            description = "Use for reading one or a small shard of authorized historical sessions and extracting questions, decisions, open items, and evidence.",
            systemPrompt = basePrompt(
                role = "historical session archivist subagent",
                strengths = "bounded transcript reading, source-backed session summaries, open-item extraction",
                extra = "Only read sessions inside the provided SessionAccessGrant. Do not infer across missing sessions. Every finding should keep source_message_ids when available."
            ),
            toolAllowlist = setOf(
                "tools_list", "session_search", "session_read", "session_expand",
                "conversation_search", "conversation_expand",
            ),
        ),
        SubAgentDefinition(
            id = "history-synthesizer",
            name = "History Synthesizer",
            description = "Use for merging multiple session-archivist outputs into deduplicated themes, timelines, and source-backed conclusions.",
            systemPrompt = basePrompt(
                role = "history synthesis subagent",
                strengths = "deduplication, cross-session synthesis, timeline and table construction",
                extra = "Prefer synthesis from provided worker summaries. Use session_expand only for source verification inside the provided grant. Mark partial or missing shards clearly."
            ),
            toolAllowlist = setOf(
                "tools_list", "session_search", "session_read", "session_expand",
                "conversation_search", "conversation_expand",
            ),
        ),
        SubAgentDefinition(
            id = "topic-miner",
            name = "Topic Miner",
            description = "Use for mining a specific topic across authorized historical sessions and returning compact source-backed excerpts.",
            systemPrompt = basePrompt(
                role = "topic mining subagent",
                strengths = "keyword/topic extraction, excerpt selection, source id tracking",
                extra = "Stay within the topic and grant. Return relevant snippets with session_id and source_message_ids; do not broaden the search without supervisor instructions."
            ),
            toolAllowlist = setOf(
                "tools_list", "session_search", "session_read", "session_expand",
                "conversation_search", "conversation_expand",
            ),
        ),
        SubAgentDefinition(
            id = "reviewer",
            name = "Reviewer",
            description = "Use for independent read-only review of code, architecture plans, risk, and missing tests.",
            systemPrompt = basePrompt(
                role = "review subagent",
                strengths = "correctness review, edge cases, security and missing test checks",
                extra = "Do not edit files. Return only actionable findings with evidence."
            ),
            toolAllowlist = setOf(
                "tools_list", "file_list", "file_read", "file_search",
                "conversation_search", "conversation_expand",
            ),
        ),
        SubAgentDefinition(
            id = "officepro-analyst",
            name = "OfficePro Analyst",
            description = "Use for 小米办公 Pro / 飞书办公 context capture, notification digestion, and workspace document analysis.",
            systemPrompt = basePrompt(
                role = "office work analysis subagent",
                strengths = "OfficePro context digestion, market/product report framing, document todo extraction",
                extra = "Do not send messages or write comments. Use write/report tools only when explicitly granted."
            ),
            toolAllowlist = setOf(
                "tools_list", "officepro_status", "officepro_dashboard",
                "officepro_capture_context", "officepro_context_digest",
                "officepro_read_screen", "file_list", "file_read", "file_search",
                "mcp_list", "conversation_search", "conversation_expand",
            ),
        ),
        SubAgentDefinition(
            id = "terminal-operator",
            name = "Terminal Operator",
            description = "Use for isolated terminal checks and command observation when the main task needs shell evidence.",
            systemPrompt = basePrompt(
                role = "terminal operation subagent",
                strengths = "short terminal verification, job status checks, install/runtime diagnostics",
                extra = "Prefer read/status commands. Any mutating or long command still follows the normal approval policy."
            ),
            toolAllowlist = setOf(
                "tools_list", "terminal_execute", "terminal_job_start",
                "terminal_job_read", "terminal_job_wait", "terminal_job_stop",
                "terminal_workspace_flush", "file_list", "file_read", "file_search",
            ),
        ),
        SubAgentDefinition(
            id = "risk-checker",
            name = "Risk Checker",
            description = "Use for permission, privacy, data-loss, and high-risk action review before execution.",
            systemPrompt = basePrompt(
                role = "risk review subagent",
                strengths = "permission checks, privacy boundaries, destructive-action review",
                extra = "Do not execute risky actions. Return a concise allow/block/ask recommendation."
            ),
            toolAllowlist = setOf(
                "tools_list", "permissions_status", "apps_list", "apps_installed_list",
                "file_read", "file_search", "conversation_search", "conversation_expand",
            ),
        ),
    )

    fun find(id: String): SubAgentDefinition? =
        builtIns.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) }

    private fun basePrompt(role: String, strengths: String, extra: String): String = """
        You are a narrow $role for AmberAgent.

        === HARD BOUNDARIES ===
        - You are a subagent. Do NOT spawn subagents.
        - Execute only the assigned task. Do not continue into implementation unless it is explicitly inside the task boundaries.
        - Use only the tools granted to this run.
        - Report once and stop.

        Strengths: $strengths.
        $extra

        Report format:
        Return concise JSON-compatible prose with summary, findings, evidence, risks, and recommended_next_steps.
    """.trimIndent()
}
