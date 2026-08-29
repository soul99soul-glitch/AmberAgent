package app.amber.feature.modelcouncil



/**
 * Prompt templates for the three Council Room discussion modes.
 *
 * Design intent (from the iOS spec):
 * - EXPLORE: divergence / broaden. Host = facilitator. Soft labels:
 *   idea / signal / question / possibility. Guests contribute breadth.
 * - DEBATE: convergence / adversarial. Host = moderator/chair. Sharp labels:
 *   claim / counterpoint / risk / evidence. Guests carry reply/continue refs.
 * - SYNTHESIZE: host collects evidence and produces the final verdict.
 *
 * All templates are pure functions of (room, participant, optional reference)
 * so they're trivially unit-testable and never touch IO / coroutines.
 *
 * The legacy batch-path prompts (openingPrompt / responsePrompt /
 * finalPositionPrompt / synthesisPrompt in ModelCouncilManager.kt) are NOT
 * reused — those operate on ModelCouncilTaskSpec + flat ModelCouncilTurn list,
 * whereas these operate on CouncilRoom + CouncilMessage with a reference graph.
 */
object CouncilRoomPrompts {

    // ── shared building blocks ─────────────────────────────────────────────

    /**
     * @param hasTools true when the host will actually be able to call tools this
     *   turn (FULL mode's pre-topic research). The "硬性边界" block then describes
     *   the tool set instead of asserting "you have no tools" — which otherwise
     *   contradicts the tools passed in [TextGenerationParams] and the host simply
     *   parrots "我没有工具" instead of searching. Default false: most host turns
     *   (opening / review / synthesis) are pure-text and genuinely tool-less.
     */
    fun hostSystemPrompt(room: CouncilRoom, hasTools: Boolean = false): String = """
        You are the host of the AmberAgent Council Room.
        Topic: ${room.objective}
        ${backgroundSection(room)}
        Mode: ${modeName(room.mode)}
        Participants: ${room.participants.joinToString(", ") { "${it.name} (${it.role})" }}

        Your responsibilities:
        - ${modeHostDuty(room.mode)}
        - Invite suitable participants, pass context, ask follow-up questions, and close the discussion when ready.
        - Do not decide for guests; base synthesis only on evidence already provided.

        Hard boundaries:
        ${if (hasTools) """
        - You may use these tools: search_web (web search), scrape_web (fetch a page), and time (current time).
        - For time-sensitive or factual topics (new releases, latest data, project status, or recent events), actively search or fetch reliable information instead of relying on memory.
        - All other tools are unavailable. Never claim to have inspected a file, page, or private data unless a tool actually provided it.
        """.trimIndent() else """
        - You have no tools.
        - Never claim to have inspected a file, page, or private data unless the discussion already contains the evidence.
        """.trimIndent()}
    """.trimIndent()

    fun guestSystemPrompt(room: CouncilRoom, guest: CouncilParticipant): String = """
        You are the Council Room guest "${guest.name}", role: ${guest.role}.
        Topic: ${room.objective}
        Mode: ${modeName(room.mode)}

        ${guest.systemPrompt.ifBlank { modeGuestDefault(room.mode) }}

        ${modeGuestGuidance(room.mode)}

        Hard boundaries:
        - You have no tools.
        - Never claim to have inspected a file, page, or private data unless the discussion already contains the evidence.
        - Be concise and evidence-based so the host can synthesize your contribution.
        - Output the contribution itself. Do not restate the topic, your role, or these instructions,
          and do not begin with meta-talk such as "The user wants me to...", "As a ... I will...", or "Sure, I will...".
    """.trimIndent()

    // ── EXPLORE mode ───────────────────────────────────────────────────────

    /**
     * EXPLORE opening: guest contributes breadth independently.
     * No reference to other guests — explore round 1 is divergence-first.
     */
    fun exploreOpening(room: CouncilRoom, guest: CouncilParticipant): String = """
        Topic: ${room.objective}
        ${backgroundSection(room)}

        This is the Explore phase. As "${guest.name}", contribute your perspective:
        - Offer an idea, signal, question, or possibility.
        - Broaden the information space and surface hidden issues; you do not need to agree with other guests.
        - Do not cite or rebut other guests yet; contribute breadth independently first.
    """.trimIndent()

    /**
     * EXPLORE response: guest sees prior signals, builds on or adds new ones.
     * [priorMessages] already filtered to non-self, newest-first capped.
     */
    fun exploreResponse(
        room: CouncilRoom,
        guest: CouncilParticipant,
        priorMessages: List<CouncilMessage>,
    ): String = """
        Topic: ${room.objective}

        Existing signals:
        ${priorMessages.joinToString("\n\n") { it.summaryBlock() }}

        As "${guest.name}":
        - Continue and expand an existing idea, signal, question, or possibility.
        - Add a new angle when useful, while avoiding repetition.
        - Keep the discussion divergent; do not converge yet.
    """.trimIndent()

    // ── DEBATE mode ────────────────────────────────────────────────────────

    /** DEBATE opening: guest takes a stance (claim). */
    fun debateOpening(room: CouncilRoom, guest: CouncilParticipant): String = """
        Topic: ${room.objective}
        ${backgroundSection(room)}

        This is the Debate phase. As "${guest.name}", take a position:
        - Give a claim, counterpoint, risk, or piece of evidence.
        - Make the position clear and the reasoning testable.
        - In round 1, state your position independently rather than responding to others.
    """.trimIndent()

    /**
     * DEBATE response: guest defends/revises stance against others, with explicit
     * reference graph. [referenceMessage] is the message being replied-to or
     * continued-from (may be null for round-2 generic response).
     */
    fun debateResponse(
        room: CouncilRoom,
        guest: CouncilParticipant,
        priorMessages: List<CouncilMessage>,
        referenceMessage: CouncilMessage?,
    ): String = """
        Topic: ${room.objective}

        Debate so far:
        ${priorMessages.joinToString("\n\n") { it.summaryBlock() }}

        ${if (referenceMessage != null) {
            "Respond to ${referenceMessage.authorName}'s contribution (claim/counterpoint/risk/evidence)." +
                " In your conclusion, state explicitly which point you agree with, partly agree with, or oppose."
        } else {
            "Revise or defend your position, focusing on disagreements and missing evidence."
        }}

        Speak as "${guest.name}".
    """.trimIndent()

    /**
     * DEBATE final position (last round): guest gives a decisive concise stance.
     * Carried via continuesFromMessageId referencing their own prior message.
     */
    fun debateFinalPosition(
        room: CouncilRoom,
        guest: CouncilParticipant,
        ownPriorMessages: List<CouncilMessage>,
    ): String = """
        Topic: ${room.objective}

        Your previous contributions:
        ${ownPriorMessages.joinToString("\n\n") { it.summaryBlock() }}

        This is the final Debate round. As "${guest.name}", give a clear and concise final position.
    """.trimIndent()

    // ── SYNTHESIZE mode ────────────────────────────────────────────────────

    /**
     * Host synthesis: the host wraps up the council as the moderator — restating
     * the proposition, weighing each member's contribution, and delivering a
     * decisive closing statement (共识/分歧/最强证据/风险/最终建议). Written in
     * the host's first-person moderator voice so it reads as the 主持人收尾,
     * not a neutral report. Output feeds room.synthesis.
     */
    fun synthesize(room: CouncilRoom): String = """
        Topic: ${room.objective}
        ${backgroundSection(room)}

        Discussion record (guest contributions):
        ${room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .joinToString("\n\n") { it.summaryBlock(limit = 1_200) }}

        Now close the discussion as the host. You are the meeting host, not a neutral report generator:
        - Use first person ("I") and sound like a host who heard every contribution and is wrapping up the meeting.
        - Briefly recap the topic and each member's core position to show that you heard the discussion.
        - Then give your host judgment with this structure:
          - consensus: where the participants agree
          - conflicts: important disagreements that remain
          - strongest evidence: the most compelling support for the conclusion
          - risks: risks to consider when adopting the conclusion
          - final recommendation: your clear recommendation as host
        - Base the closing statement only on evidence in the discussion; do not introduce unsupported facts.
    """.trimIndent()

    /**
     * Host's OPENING when a council starts — the host RECEIVES the user's topic,
     * restates/clarifies the real intent, turns it into a clear core proposition,
     * and frames how the members should approach it. This is the "主持承接命题"
     * turn that runs BEFORE any member speaks.
     */
    fun hostOpeningPrompt(room: CouncilRoom): String = """
        Topic (from the initiator): ${room.objective}
        ${backgroundSection(room)}
        Mode: ${modeName(room.mode)}
        Members: ${room.participants
            .filter { it.kind == CouncilParticipantKind.GUEST }
            .joinToString(", ") { "${it.name} (${it.role})" }}

        As host, open the meeting in a few concise sentences:
        - Restate and clarify the real problem, turning the initiator's need into a clear core proposition.
        - State this round's focus and boundaries.
        - Briefly explain what each member should address from their role.
        Do not decide for the members; frame the discussion and pass the topic on.
    """.trimIndent()

    // ── host-action turn prompts (the directive the host passes to a guest) ─

    /** Prompt for a guest being directly invited by the host. */
    fun invitedByHostPrompt(
        room: CouncilRoom,
        guest: CouncilParticipant,
        instruction: String,
    ): String = """
        ${if (room.mode == CouncilRoomMode.EXPLORE) exploreOpening(room, guest) else debateOpening(room, guest)}

        Additional host instruction: ${instruction.ifBlank { "Speak from your assigned role." }}
    """.trimIndent()

    /** Prompt for a guest following up on a specific seed message. */
    fun followUpPrompt(
        room: CouncilRoom,
        guest: CouncilParticipant,
        seed: CouncilMessage,
    ): String = """
        Topic: ${room.objective}

        Contribution to address (from ${seed.authorName}):
        ${seed.summaryBlock(limit = 2_000)}

        As "${guest.name}", add to, support, or challenge the contribution above.
        ${modeGuestGuidance(room.mode)}
    """.trimIndent()

    /**
     * Host's redirect when the user interjects WITHOUT targeting a specific member.
     * The host reads the recent discussion + the user's instruction and produces a
     * short, concrete steer that the next members should follow.
     */
    fun hostInterjectionPrompt(room: CouncilRoom, userInstruction: String): String = """
        Topic: ${room.objective}

        Current discussion (excerpt):
        ${room.messages
            .filter { it.authorId != COUNCIL_ROOM_USER_ID && it.status == CouncilMessageStatus.COMPLETED }
            .takeLast(6)
            .joinToString("\n\n") { it.summaryBlock(limit = 500) }}

        User interjection: $userInstruction

        As host, give one or a few clear directions: whether to correct course, what to focus on, what to avoid, and what to say next.
        Keep it concise and actionable for the next speakers; do not decide for them.
    """.trimIndent()

    /**
     * FULL mode: host's PRE-TOPIC research turn — runs BEFORE the council
     * deliberates, so that facts (recent releases, benchmarks, project status,
     * specific data) enter the room BEFORE members speak. This is the fix for the
     * "members discuss glm5.2 with no training-data knowledge" problem: the host
     * gathers the facts first, they land in [CouncilRoom.context] (the "背景"
     * field every member/synthesis prompt renders), AND the research summary is
     * appended as a host message so [appendSteeringNote] injects it into later
     * rounds too.
     *
     * The host should ALWAYS try to gather concrete facts for the topic; the
     * caller (CouncilHostToolProvider) gates availability on a configured search
     * provider. Output = a concise factual digest (200-400 chars, with sources)
     * that becomes the room's shared fact base. If the topic genuinely needs no
     * external facts, the host says so in one line — but for time-sensitive /
     * factual / recent topics (new model releases, latest benchmarks, project
     * status, recent news) it MUST search and scrape.
     */
    fun hostPreTopicResearchPrompt(room: CouncilRoom): String = """
        Topic (from the initiator): ${room.objective}

        You are the host of a multi-model council. Before directing the discussion, establish a shared factual foundation for everyone.

        Decide whether this topic needs external facts:
        - If it involves recent events, new releases, concrete data, project status, or current evaluations (for example, a new model release, latest benchmark, current product capability, or recent news), you must use search_web and scrape_web to obtain accurate, current information.
        - The models in the discussion, including you, may not have the latest information in their training data. Always check time-sensitive or factual topics instead of relying on memory.
        - Only purely subjective, creative, or abstract topics may not need external research.

        Retrieval guidance (important):
        - Use multiple dimensions and queries. For a complex topic, search each important dimension separately (for example, release overview, benchmarks, coding and reasoning, language support, price, and context window when evaluating a new model).
        - Do not artificially limit the result count with a tiny max_results. Select the most relevant candidates from the available results and use scrape_web to verify details when needed.
        - Prefer depth: for each dimension, look for authoritative sources and concrete data; scrape the detail page when a summary is insufficient.

        After retrieval, output a 200-400 word digest of key facts as the shared factual basis for the discussion:
        - Organize it by dimension and state only objective facts you found (capability data, release information, evaluation results, and so on), with sources.
        - Do not add your opinion or conclusion; that belongs to the members and synthesis phase.
        - If external facts genuinely are not needed, say so in one sentence and briefly explain why.

        This digest will be written into the shared context for all members, the host, and synthesis to reference.
    """.trimIndent()

    /**
     * FULL mode: host's end-of-round REVIEW turn. After all guests have spoken in
     * a round, the host decides whether the round warrants commentary. If there
     * are contradictions, unverified claims, or the discussion is drifting, the
     * host writes a pointed review + a steer for the next round (which members
     * pick up via [appendSteeringNote]). If the round already converged cleanly,
     * the host emits the [NO_COMMENT_SENTINEL] so the caller skips appending an
     * empty message.
     */
    fun hostRoundReviewPrompt(room: CouncilRoom, round: Int, totalRounds: Int): String = """
        Topic: ${room.objective}
        Current: round $round / $totalRounds (mode: ${modeName(room.mode)})

        Guest contributions this round:
        ${room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .takeLast(8)
            .joinToString("\n\n") { it.summaryBlock(limit = 600) }}

        As host, decide whether this round needs your review and guidance:
        - If there are contradictions, incorrect information, obvious drift, or important gaps, give a brief review: identify the problem, the next focus, claims that were disproved, and points that need deeper work.
        - If a key question needs clarification from the user (ambiguous goal, preference, constraint, trade-off, or missing premise only the user knows), output one line beginning with $ASK_USER_SENTINEL followed by a direct question. The council pauses until the user answers.
        - If the round has converged sufficiently and needs no correction or addition, output exactly one line: $NO_COMMENT_SENTINEL (and nothing else).

        Your review is passed to the next round to help members build on accumulated conclusions. Keep it concise, focused, and actionable.
    """.trimIndent()

    /** Sentinel the host emits when a round needs no commentary. */
    const val NO_COMMENT_SENTINEL = "[no_comment]"

    /**
     * Sentinel the host emits (at the start of the line) when it wants to ask the
     * user a clarifying question during the end-of-round review. The text AFTER the
     * sentinel is the question to display. e.g. `[ask_user] 你更关注 glm5.2 的代码能力还是中文表现？`
     */
    const val ASK_USER_SENTINEL = "[ask_user]"

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Shared "背景：…" block rendered by every prompt that wants room.context.
     * Empty string (renders nothing) when no background is set, so prompts stay
     * clean in the common no-context case. Used by host/member/synthesis prompts.
     */
    private fun backgroundSection(room: CouncilRoom): String =
        if (room.context.isBlank()) "" else "Background: ${room.context}\n"

    private fun modeName(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "Explore"
        CouncilRoomMode.DEBATE -> "Debate"
        CouncilRoomMode.SYNTHESIZE -> "Synthesize"
    }

    private fun modeHostDuty(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "Facilitator: group ideas, ask follow-ups, prevent drift, and keep the discussion useful."
        CouncilRoomMode.DEBATE -> "Moderator: control rounds, require evidence, redirect repetition, and drive convergence."
        CouncilRoomMode.SYNTHESIZE -> "Adjudicator: synthesize only the evidence provided and give a final recommendation."
    }

    private fun modeGuestDefault(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "Contribute an idea, signal, question, or possibility to broaden the information space."
        CouncilRoomMode.DEBATE -> "Give a claim, counterpoint, risk, or piece of evidence with a clear, testable position."
        CouncilRoomMode.SYNTHESIZE -> "The synthesis phase normally does not need a guest contribution."
    }

    private fun modeGuestGuidance(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "Prefer divergence: broaden first and do not rush to converge."
        CouncilRoomMode.DEBATE -> "Prefer constructive opposition: focus on disagreements, counterexamples, risks, and evidence."
        CouncilRoomMode.SYNTHESIZE -> "The council has entered the synthesis phase."
    }
}

/** Render a message as a labeled block for prompt inclusion. */
internal fun CouncilMessage.summaryBlock(limit: Int = 700): String {
    val refs = listOfNotNull(
        replyToMessageId?.let { "reply #$it" },
        continuesFromMessageId?.let { "continues #$it" },
    ).joinToString(", ").ifBlank { "" }
    val header = listOf(roundLabel(), "$authorName ($role)", refs.ifBlank { "" })
        .filter { it.isNotBlank() }
        .joinToString(" / ")
    val body = (text.ifBlank { error }).take(limit)
    return "$header:\n$body"
}

private fun CouncilMessage.roundLabel(): String = "Round $round"
