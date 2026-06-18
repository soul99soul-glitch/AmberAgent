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

    fun hostSystemPrompt(room: CouncilRoom): String = """
        你是 AmberAgent Council Room 的主持人（Host）。
        当前议题：${room.objective}
        ${if (room.context.isBlank()) "" else "背景：${room.context}\n"}
        当前模式：${modeName(room.mode)}
        参与者：${room.participants.joinToString("、") { "${it.name}（${it.role}）" }}

        你的职责：
        - ${modeHostDuty(room.mode)}
        - 邀请合适的参与者发言，传递上下文，追问或收束。
        - 不替嘉宾下结论；综合时只基于已给出的证据。

        硬性边界：
        - 你没有工具。
        - 不要声称检查了文件/网页/私有数据，除非证据已在讨论中给出。
    """.trimIndent()

    fun guestSystemPrompt(room: CouncilRoom, guest: CouncilParticipant): String = """
        你是 Council Room 的嘉宾「${guest.name}」，角色：${guest.role}。
        议题：${room.objective}
        当前模式：${modeName(room.mode)}

        ${guest.systemPrompt.ifBlank { modeGuestDefault(room.mode) }}

        ${modeGuestGuidance(room.mode)}

        硬性边界：
        - 你没有工具。
        - 不要声称检查了文件/网页/私有数据，除非证据已在讨论中给出。
        - 回复要简洁、有据，面向主持人综合。
        - 直接输出你的发言内容本身；不要复述题目、你的角色或这些指令，
          不要以"用户想让我…""作为…我将…""好的，我来…"之类的话开头。
    """.trimIndent()

    // ── EXPLORE mode ───────────────────────────────────────────────────────

    /**
     * EXPLORE opening: guest contributes breadth independently.
     * No reference to other guests — explore round 1 is divergence-first.
     */
    fun exploreOpening(room: CouncilRoom, guest: CouncilParticipant): String = """
        议题：${room.objective}
        ${if (room.context.isBlank()) "" else "背景：${room.context}\n"}

        这是发散（Explore）阶段。请作为「${guest.name}」贡献你的视角：
        - 给出 idea（想法）、signal（信号）、question（待解问题）或 possibility（可能性）。
        - 目标是拓宽信息面，发现隐藏问题，不必与其他嘉宾一致。
        - 不要引用或反驳其他嘉宾，先独立贡献广度。
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
        议题：${room.objective}

        已有信号：
        ${priorMessages.joinToString("\n\n") { it.summaryBlock() }}

        作为「${guest.name}」：
        - 可以延续某个信号（idea/signal/question/possibility）并展开。
        - 也可以补充新的角度。避免重复已说过的内容。
        - 保持发散，先不收束。
    """.trimIndent()

    // ── DEBATE mode ────────────────────────────────────────────────────────

    /** DEBATE opening: guest takes a stance (claim). */
    fun debateOpening(room: CouncilRoom, guest: CouncilParticipant): String = """
        议题：${room.objective}
        ${if (room.context.isBlank()) "" else "背景：${room.context}\n"}

        这是辩论（Debate）阶段。请作为「${guest.name}」表明立场：
        - 给出 claim（主张）、counterpoint（反例）、risk（风险）或 evidence（证据）。
        - 立场要清晰，理由要可检验。
        - 第 1 轮先独立表态，不必回应他人。
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
        议题：${room.objective}

        辩论进展：
        ${priorMessages.joinToString("\n\n") { it.summaryBlock() }}

        ${if (referenceMessage != null) {
            "请针对 ${referenceMessage.authorName} 的发言回应（claim/counterpoint/risk/evidence）。" +
                "可在结论处显式指出你同意、部分同意或反对哪一点。"
        } else {
            "请修订或捍卫你的立场，聚焦分歧与缺失的证据。"
        }}

        作为「${guest.name}」发言。
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
        议题：${room.objective}

        你的既往发言：
        ${ownPriorMessages.joinToString("\n\n") { it.summaryBlock() }}

        这是辩论最后一轮。请作为「${guest.name}」给出明确、简洁的最终立场。
    """.trimIndent()

    // ── SYNTHESIZE mode ────────────────────────────────────────────────────

    /**
     * Host synthesis: collect all guest messages, produce structured verdict.
     * Output feeds room.synthesis.
     */
    fun synthesize(room: CouncilRoom): String = """
        议题：${room.objective}
        ${if (room.context.isBlank()) "" else "背景：${room.context}\n"}

        讨论记录（嘉宾发言）：
        ${room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .joinToString("\n\n") { it.summaryBlock(limit = 1_200) }}

        请综合并给出：
        - 共识（consensus）
        - 分歧（conflicts）
        - 最强证据（strongest evidence）
        - 风险（risks）
        - 最终建议（final recommendation）
        综合必须基于已给出的证据，不要引入未在讨论中出现的事实。
    """.trimIndent()

    /**
     * Host's OPENING when a council starts — the host RECEIVES the user's topic,
     * restates/clarifies the real intent, turns it into a clear core proposition,
     * and frames how the members should approach it. This is the "主持承接命题"
     * turn that runs BEFORE any member speaks.
     */
    fun hostOpeningPrompt(room: CouncilRoom): String = """
        议题（来自发起人）：${room.objective}
        ${if (room.context.isBlank()) "" else "背景：${room.context}\n"}
        当前模式：${modeName(room.mode)}
        参与成员：${room.participants
            .filter { it.kind == CouncilParticipantKind.GUEST }
            .joinToString("、") { "${it.name}（${it.role}）" }}

        作为主持人，请用简短几句开场：
        - 复述并澄清这个议题真正要解决的核心问题，把发起人的需求转成一个清晰的核心命题。
        - 点明本轮讨论的重点与边界。
        - 简要说明希望各成员分别从自己的角色切入什么。
        不要替成员下结论，只做承接与框定。
    """.trimIndent()

    // ── host-action turn prompts (the directive the host passes to a guest) ─

    /** Prompt for a guest being directly invited by the host. */
    fun invitedByHostPrompt(
        room: CouncilRoom,
        guest: CouncilParticipant,
        instruction: String,
    ): String = """
        ${if (room.mode == CouncilRoomMode.EXPLORE) exploreOpening(room, guest) else debateOpening(room, guest)}

        主持人额外指令：${instruction.ifBlank { "请按你的角色发言。" }}
    """.trimIndent()

    /** Prompt for a guest following up on a specific seed message. */
    fun followUpPrompt(
        room: CouncilRoom,
        guest: CouncilParticipant,
        seed: CouncilMessage,
    ): String = """
        议题：${room.objective}

        需要回应的发言（来自 ${seed.authorName}）：
        ${seed.summaryBlock(limit = 2_000)}

        作为「${guest.name}」，针对以上发言补充、支持或反驳。
        ${modeGuestGuidance(room.mode)}
    """.trimIndent()

    /**
     * Host's redirect when the user interjects WITHOUT targeting a specific member.
     * The host reads the recent discussion + the user's instruction and produces a
     * short, concrete steer that the next members should follow.
     */
    fun hostInterjectionPrompt(room: CouncilRoom, userInstruction: String): String = """
        议题：${room.objective}

        当前讨论（节选）：
        ${room.messages
            .filter { it.authorId != COUNCIL_ROOM_USER_ID && it.status == CouncilMessageStatus.COMPLETED }
            .takeLast(6)
            .joinToString("\n\n") { it.summaryBlock(limit = 500) }}

        用户中途介入：$userInstruction

        作为主持人，请据此给出一句到几句明确的引导：是否纠偏、聚焦到哪、避免什么、下一步该怎么说。
        简洁、可执行，面向接下来发言的成员；不要替成员下结论。
    """.trimIndent()

    /**
     * FULL mode: host's pre-synthesis RESEARCH turn. Before synthesizing, the host
     * may call read-only tools (search_web / scrape_web) to fill factual gaps or
     * verify claims made during the discussion. The output is a short research
     * summary that gets appended as a host message — which the subsequent
     * synthesis turn then sees as part of the record.
     *
     * Crucially the host decides whether research is even needed: if the
     * discussion is self-contained, it should answer directly without tools.
     * Tool budget is enforced by the caller ([CouncilHostToolProvider]).
     */
    fun hostResearchPrompt(room: CouncilRoom): String = """
        议题：${room.objective}

        已完成的讨论（嘉宾发言节选）：
        ${room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .takeLast(8)
            .joinToString("\n\n") { it.summaryBlock(limit = 500) }}

        你现在要为综合结论做准备。请判断：
        - 讨论中是否存在事实性缺口、过时信息、未经验证的关键主张，或需要最新外部信息（如近期事件、项目现状、最新数据）？
        - 如果是，用 search_web 搜索、或用 scrape_web 抓取相关页面来补全/核验。
        - 如果讨论本身已经充分、自洽、不需要外部信息，就直接输出"无需补充调研"并结束，不要为了用工具而用工具。

        完成调研后，用几句话总结你查到的、对综合有用的关键事实（带来源）；如果没查，就说明为什么不需要。
        保持克制，只补真正缺失的信息。
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
        议题：${room.objective}
        当前：第 $round / $totalRounds 轮（模式：${modeName(room.mode)}）

        本轮嘉宾发言：
        ${room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .takeLast(8)
            .joinToString("\n\n") { it.summaryBlock(limit = 600) }}

        作为主持人，判断这一轮是否需要你的点评与引导：
        - 如果出现了矛盾、错误信息、明显偏离议题、或关键信息缺口，请给出简短点评：指出问题在哪、下一步该聚焦什么、哪些已被证伪可以剔除、哪些需要深化。
        - 如果本轮信息已充分收敛、没有需要纠正或补充的方向，就只输出一行：$NO_COMMENT_SENTINEL（不要输出其他任何内容）。

        你的点评会作为指引传给下一轮成员，帮助他们基于已积累的结论继续深化。简洁、聚焦、可执行。
    """.trimIndent()

    /** Sentinel the host emits when a round needs no commentary. */
    const val NO_COMMENT_SENTINEL = "[no_comment]"

    // ── helpers ────────────────────────────────────────────────────────────

    private fun modeName(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "发散（Explore）"
        CouncilRoomMode.DEBATE -> "辩论（Debate）"
        CouncilRoomMode.SYNTHESIZE -> "综合（Synthesize）"
    }

    private fun modeHostDuty(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "促动者：归类想法，追问，防止跑偏，保持讨论有用。"
        CouncilRoomMode.DEBATE -> "主持人：控制轮次，要求证据，重定向重复，推动收敛。"
        CouncilRoomMode.SYNTHESIZE -> "裁决者：只综合已给出的证据，给出最终建议。"
    }

    private fun modeGuestDefault(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "贡献 idea/signal/question/possibility，拓宽信息面。"
        CouncilRoomMode.DEBATE -> "给出 claim/counterpoint/risk/evidence，立场清晰可检验。"
        CouncilRoomMode.SYNTHESIZE -> "（综合阶段通常不需要嘉宾发言）"
    }

    private fun modeGuestGuidance(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "发散优先：先广度，不急于收束。"
        CouncilRoomMode.DEBATE -> "对抗优先：聚焦分歧、反例、风险与证据。"
        CouncilRoomMode.SYNTHESIZE -> "已进入综合阶段。"
    }
}

/** Render a message as a labeled block for prompt inclusion. */
internal fun CouncilMessage.summaryBlock(limit: Int = 700): String {
    val refs = listOfNotNull(
        replyToMessageId?.let { "回复 #$it" },
        continuesFromMessageId?.let { "延续 #$it" },
    ).joinToString("，").ifBlank { "" }
    val header = listOf(roundLabel(), "$authorName（$role）", refs.ifBlank { "" })
        .filter { it.isNotBlank() }
        .joinToString(" / ")
    val body = (text.ifBlank { error }).take(limit)
    return "$header:\n$body"
}

private fun CouncilMessage.roundLabel(): String = "第 $round 轮"
