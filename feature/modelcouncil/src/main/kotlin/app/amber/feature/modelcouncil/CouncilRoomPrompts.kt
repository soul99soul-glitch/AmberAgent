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
        你是 AmberAgent Council Room 的主持人（Host）。
        当前议题：${room.objective}
        ${backgroundSection(room)}
        当前模式：${modeName(room.mode)}
        参与者：${room.participants.joinToString("、") { "${it.name}（${it.role}）" }}

        你的职责：
        - ${modeHostDuty(room.mode)}
        - 邀请合适的参与者发言，传递上下文，追问或收束。
        - 不替嘉宾下结论；综合时只基于已给出的证据。

        硬性边界：
        ${if (hasTools) """
        - 你可以使用以下工具：search_web（联网搜索）、scrape_web（抓取网页）、time（获取当前时间）。
        - 对时效性/事实性议题（新发布、最新数据、项目现状、近期事件）请主动调用搜索/抓取工具获取准确信息，不要凭记忆作答。
        - 其余未列出的工具一律不可用；不要声称检查了未实际通过工具获取的文件/网页/私有数据。
        """.trimIndent() else """
        - 你没有工具。
        - 不要声称检查了文件/网页/私有数据，除非证据已在讨论中给出。
        """.trimIndent()}
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
        ${backgroundSection(room)}

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
        ${backgroundSection(room)}

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
     * Host synthesis: the host wraps up the council as the moderator — restating
     * the proposition, weighing each member's contribution, and delivering a
     * decisive closing statement (共识/分歧/最强证据/风险/最终建议). Written in
     * the host's first-person moderator voice so it reads as the 主持人收尾,
     * not a neutral report. Output feeds room.synthesis.
     */
    fun synthesize(room: CouncilRoom): String = """
        议题：${room.objective}
        ${backgroundSection(room)}

        讨论记录（嘉宾发言）：
        ${room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .joinToString("\n\n") { it.summaryBlock(limit = 1_200) }}

        现在请你以「主持人」的身份做这场讨论的结案陈词。你是这场会议的主持，不是中立的报告生成器：
        - 用第一人称（"我"）收尾，语气像一个真正在主持会议、听过所有人发言后做总结的主持人。
        - 先简短回顾议题与各成员的核心立场，体现你确实"听过"了讨论。
        - 然后给出你的主持人判断，结构如下：
          - 共识（consensus）：各方达成一致的地方
          - 分歧（conflicts）：仍未对齐的关键分歧
          - 最强证据（strongest evidence）：支撑结论的最有力依据
          - 风险（risks）：采纳该结论需要注意的风险
          - 最终建议（final recommendation）：你作为主持人给出的明确结论
        - 结案陈词必须基于讨论中已给出的证据，不要引入未在讨论中出现的事实。
    """.trimIndent()

    /**
     * Host's OPENING when a council starts — the host RECEIVES the user's topic,
     * restates/clarifies the real intent, turns it into a clear core proposition,
     * and frames how the members should approach it. This is the "主持承接命题"
     * turn that runs BEFORE any member speaks.
     */
    fun hostOpeningPrompt(room: CouncilRoom): String = """
        议题（来自发起人）：${room.objective}
        ${backgroundSection(room)}
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
        议题（来自发起人）：${room.objective}

        你是多模型议会的主持人。在指挥成员讨论之前，请先为全员建立一个共同的事实底座。

        判断这个议题是否需要外部事实：
        - 如果涉及近期事件、新发布、具体数据、项目现状、最新评测结果（例如新模型发布、最新 benchmark、最新产品能力、近期新闻），你必须用 search_web 搜索、用 scrape_web 抓取相关页面来获取准确、最新的信息。
        - 议题里的成员模型（包括你自己）训练数据往往不包含最新内容，所以时效性/事实性议题务必先查，不要凭记忆作答。
        - 只有纯主观/创作/抽象议题才可能不需要查。

        如何检索（重要）：
        - 多维度、多关键词：复杂议题不要只搜一次。按议题的关键维度分别检索（例如评价一个新模型，应分别搜索：发布与概览、benchmark/评测、代码与推理能力、中文/特定语言能力、价格与上下文窗口等），每个维度用聚焦的关键词单独搜一次。
        - 不要人为限制结果数：调用 search_web 时不要传过小的 max_results，让搜索服务返回足够的候选（默认配置已设好），你需要从较多结果里挑选最相关的，必要时用 scrape_web 抓取详情页核对。
        - 深度优先：对每个维度，优先看是否有权威来源、具体数据；摘要不够就 scrape 详情页。

        完成检索后，输出一份 200-400 字的关键事实摘要，作为后续全员讨论的共同事实底座：
        - 按维度组织，只陈述查到的客观事实（能力数据、发布信息、关键评测结果等），带来源。
        - 不要加入你的评价或结论——那是后续成员和综合阶段的事。
        - 如果确实无需外部事实，就用一句话说明，并简述原因。

        这份摘要会被写入"背景"，供全体成员、命题、综合共同参考。
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
        - 如果发现需要向用户澄清的关键问题（议题目标本身有歧义、用户的偏好/约束/取舍需要明确、继续讨论缺少一个用户才知道的前提），输出一行以 $ASK_USER_SENTINEL 开头，紧跟你要问用户的问题（一句话，直接可答）。此时议会会暂停，等用户回答后再继续。
        - 如果本轮信息已充分收敛、没有需要纠正或补充的方向，就只输出一行：$NO_COMMENT_SENTINEL（不要输出其他任何内容）。

        你的点评会作为指引传给下一轮成员，帮助他们基于已积累的结论继续深化。简洁、聚焦、可执行。
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
        if (room.context.isBlank()) "" else "背景：${room.context}\n"

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
