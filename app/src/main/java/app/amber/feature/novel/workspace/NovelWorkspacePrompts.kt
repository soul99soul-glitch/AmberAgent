package app.amber.feature.novel.workspace

import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceJointReviewResult
import java.util.Locale

/**
 * Slim prompt catalog for workspace-native turns. The heavy injection planning of the
 * legacy engine is gone: the agent reads what it needs through the five workspace tools.
 */
object NovelWorkspacePrompts {

    private val ENGLISH_WORKSPACE_DISCIPLINE = """
        You are collaborating in a novel's file workspace. The book is made of markdown files; use only these five tools:
        novel_workspace_list, novel_workspace_read, novel_workspace_grep, novel_workspace_status, novel_workspace_write.
        Rules:
        - Whenever a file must be written, actually call novel_workspace_write; saying that you will write without calling it is a failure, and files do not appear by themselves.
        - Before acting, use list/read/grep to understand the current state; do not guess.
        - The host injects a "workspace state brief" (plot state, unresolved foreshadowing, confirmed decisions, and chapter-related nodes). It is the current canon; do not contradict it. If a name, place, or term is uncertain, grep the relevant node file first.
        - When prose changes a character state, relationship, or plot, update the corresponding setting card fields and plot/current.md in the same turn.
        - Create a node under plot/foreshadowing/ with front matter status: open for new foreshadowing; set the matching node to status: resolved when it is paid off.
        - Writes under setting/, plan/, inbox/, and drafts/ are saved immediately. Changes under branches/*/chapters/ and branches/*/plot/ create an approval card; after submitting one, wrap up the turn.
        - Write only the file body; do not invent front matter. Chapter identity comes from the path and host.
        - Do not invent new novel_* actions; express everything through the five workspace tools.
        - Write in the app language. Respect existing prose, style, and facts; do not rewrite unrequested text.
    """.trimIndent()

    private fun isChinese(locale: Locale): Boolean = locale.language.equals("zh", ignoreCase = true)

    private fun discipline(locale: Locale): String =
        if (isChinese(locale)) WORKSPACE_DISCIPLINE else ENGLISH_WORKSPACE_DISCIPLINE

    /** Shared discipline preamble prepended to every workspace turn. */
    val WORKSPACE_DISCIPLINE: String = """
        你在一部小说的文件工作区里协作。书就是 markdown 文件，你只用五个工具读写它：
        novel_workspace_list、novel_workspace_read、novel_workspace_grep、novel_workspace_status、novel_workspace_write。
        规则：
        - 需要写任何文件时，必须实际调用 novel_workspace_write 工具完成写入；只在回复里「说要写」而不调用工具是失败，文件不会凭空出现。
        - 动手前先 list/read/grep 了解现状，不要凭空猜测文件内容。
        - 系统会注入「工作区状态简报」（剧情状态、未回收伏笔、已确认决定、本章相关节点）。它是当前正史，你写的内容不得与之矛盾；对要写到的人名/地名/术语拿不准时，先 grep 相应节点文件核对。
        - 写正文时如果角色状态、人物关系或剧情推进发生变化，必须同步更新对应节点文件（setting/ 下卡片的 status/relations 字段）与 plot/current.md，并和正文在同一轮提交。
        - 埋设伏笔时在 plot/foreshadowing/ 建一个节点文件（front matter 含 status: open）；回收伏笔时把对应文件改为 status: resolved。
        - setting/、plan/、inbox/、drafts/ 调用 write 工具后立即保存，无需作者确认；只有已收录章节（branches/*/chapters/）与剧情（branches/*/plot/）的修改会生成审批卡等作者确认，提交这类修改后请收尾本轮。
        - 写文件只给正文本身，不要编造 front matter；章节身份由路径和宿主保持。
        - 不要发明新的 novel_* 动作；需要的一切表达都通过读写文件完成。
        - 中文创作，尊重已有正文的文风与事实，不要改写未要求修改的内容。
    """.trimIndent()

    fun quickStart(genre: String, coreIdea: String, locale: Locale = Locale.CHINESE): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("这是新书的第一轮快速开始。请生成一组可确认的初始设定：")
        appendLine("- 主要角色（每人一个文件写入 setting/characters/，含别名与一句话定位）")
        appendLine("- 世界观要点（setting/world/）")
        appendLine("- 总剧情大纲（setting/outline/）")
        appendLine("- 写作要求（setting/writing/）")
        appendLine("题材：${genre.trim()}")
        appendLine("核心想法：${coreIdea.trim()}")
        appendLine()
        append("硬性要求：本轮必须用 novel_workspace_write 实际写出这些设定文件——只浏览不写即失败。")
        append("最多粗看一两个文件了解现状，然后立刻开始逐个写入设定。")
        append("设定数量克制（角色一般 3–6 个）。写完后在回复里概述你生成了哪些文件。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is the first quick-start turn for a new book. Create a reviewable initial setting:")
        appendLine("- Main characters (one file per character under setting/characters/, with aliases and a one-line role)")
        appendLine("- World-building notes (setting/world/)")
        appendLine("- Overall outline (setting/outline/)")
        appendLine("- Writing preferences (setting/writing/)")
        appendLine("Genre: ${genre.trim()}")
        appendLine("Core idea: ${coreIdea.trim()}")
        appendLine()
        appendLine("This turn must actually write those setting files with novel_workspace_write; browsing without writing is a failure.")
        appendLine("Briefly inspect at most one or two files, then write the setting files one by one.")
        append("Keep the number of settings concise (usually 3–6 characters). Summarize the files you created when done.")
    }

    fun discussion(locale: Locale = Locale.CHINESE): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        append("当前是讨论规划轮：和作者商量剧情、设定与写法。需要引用原文时用 grep/read 查证；")
        append("讨论出的新设定可直接写入 setting/ 或 inbox/；不要在这一轮直接改正文。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        append("This is a discussion and planning turn: work with the author on plot, settings, and writing. Use grep/read to verify quoted source text; ")
        append("new settings may be written to setting/ or inbox/, but do not edit the manuscript in this turn.")
    }

    fun proseDraft(granularity: ProseGranularity, locale: Locale = Locale.CHINESE): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是写正文轮。步骤：")
        appendLine("1. 用 list/read 读当前分支 chapters/ 末尾的章与 plot/current.md，必要时 grep 前文细节。")
        appendLine("2. 按工作区里 plan/this-chapter.md（如存在）展开创作。")
        appendLine("3. 把候选正文写入 drafts/ 下的一个新文件（文件名随意，内容只给正文）。")
        appendLine(if (granularity == ProseGranularity.WHOLE_CHAPTER) "目标：写成一整章的完整候选。" else "目标：续写一段自然衔接的候选，不要擅自收尾整章。")
        append("写完草稿后，在回复里用一两句话说明你写了什么、依据了哪些前文。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is a prose-writing turn:")
        appendLine("1. Use list/read to inspect the latest chapters in the current branch and plot/current.md; use grep for details when needed.")
        appendLine("2. Develop the prose from plan/this-chapter.md when it exists.")
        appendLine("3. Write the candidate prose to one new file under drafts/ (any filename; body only).")
        appendLine(if (granularity == ProseGranularity.WHOLE_CHAPTER) "Goal: write a complete candidate chapter." else "Goal: continue naturally without prematurely closing the chapter.")
        append("After writing the draft, briefly state what you wrote and which earlier material it follows.")
    }

    fun ghostwriteChapter(chapterOrdinal: Int, plan: String?, locale: Locale = Locale.CHINESE): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是代笔轮（无人值守）。请独立完成第 $chapterOrdinal 章：")
        appendLine("1. 可以用读类工具查看 plot/current.md、plan/this-chapter.md 与最近若干章，确保事实与文风连续。")
        appendLine("2. 然后把第 $chapterOrdinal 章的完整正文直接作为你的最终回复输出（不要调用写入工具，不要解释，正文之外不要输出任何内容）。系统会自动把你的回复收录为章节。")
        appendLine("3. 本批次启动前宿主已校验剧情状态；若『剧情落后』仅由本批次刚完成的上一章产生，继续本章。不得忽略『中间章被修改/暂停推进』提示。")
        if (!plan.isNullOrBlank()) {
            appendLine()
            append("本章计划：")
            appendLine()
            append(plan.trim())
        }
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is an unattended ghostwrite turn. Complete chapter $chapterOrdinal independently:")
        appendLine("1. Use read tools for plot/current.md, plan/this-chapter.md, and recent chapters to preserve facts and voice.")
        appendLine("2. Output the complete chapter $chapterOrdinal as your final response (do not call a write tool, explain, or output anything beyond the prose). The host will collect it automatically.")
        appendLine("3. The host checked plot state before this batch. If plot lag was caused only by the preceding chapter in this batch, continue; do not ignore a middle-chapter edit or paused-progress warning.")
        if (!plan.isNullOrBlank()) {
            appendLine()
            appendLine("Chapter plan:")
            appendLine(plan.trim())
        }
    }

    /** Ghostwrite panel: draft the next chapter's plan from the manuscript itself. */
    fun planDraft(locale: Locale = Locale.CHINESE): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是计划拟定轮（无人值守）。请为下一章拟定写作计划：")
        appendLine("1. 读 plot/current.md 与最近两章，理清当前局面与未回收伏笔。")
        appendLine("2. 把下一章计划写入 branches/<分支>/plan/this-chapter.md，只写文件内容本身，建议覆盖：")
        appendLine("   - 本章目标与冲突")
        appendLine("   - 必须发生 / 不可发生")
        appendLine("   - 结尾钩子（为下一章留扣）")
        append("写完文件后收尾本轮，不要改正文。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is an unattended planning turn. Draft the plan for the next chapter:")
        appendLine("1. Read plot/current.md and the latest two chapters to understand the current situation and unresolved foreshadowing.")
        appendLine("2. Write the next-chapter plan to branches/<branch>/plan/this-chapter.md, body only, covering:")
        appendLine("   - Chapter goal and conflict")
        appendLine("   - Must happen / must not happen")
        appendLine("   - Ending hook for the following chapter")
        append("After writing the file, wrap up the turn; do not edit the manuscript.")
    }

    /** D-D resolution: rewrite the chapters invalidated by a middle-chapter edit. */
    fun rewriteLaterChapters(fromOrdinal: Int, locale: Locale = Locale.CHINESE): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        append("之前的修改让第 $fromOrdinal 章及之后的内容可能与前面接不上了。请：")
        appendLine()
        appendLine("1. 用 read 读第 ${fromOrdinal - 1} 章（被修改的前一章）与 plot/current.md，先理解最新状态。")
        appendLine("2. 逐章 read 第 $fromOrdinal 章起的受影响章节，找出与最新状态矛盾的地方。")
        appendLine("3. 对需要改的章节，用 write 提交重写后的正文（会出审批卡，作者确认后生效）。")
        append("把受影响的章节改到与前面一致为止，然后收尾本轮。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        append("An earlier edit may have disconnected chapter $fromOrdinal and later chapters from the preceding story. ")
        appendLine("Do the following:")
        appendLine("1. Read chapter ${fromOrdinal - 1} and plot/current.md to understand the latest state.")
        appendLine("2. Read each affected chapter from $fromOrdinal onward and find contradictions with the latest state.")
        appendLine("3. Use write for each chapter that needs a rewrite (an approval card will be created and the author must confirm it).")
        append("Rewrite affected chapters until they are consistent, then wrap up the turn.")
    }

    /**
     * Regenerate one chapter in place: the host pastes the current body, the chapter
     * plan (if any) and the writing preference, and the model writes the full
     * replacement back to the same chapter file. chapters/ is a protected path, so the
     * write lands in the existing proposal gate — no new approval mechanism.
     */
    fun regenerateChapter(
        chapterOrdinal: Int,
        chapterTitle: String,
        chapterPath: String,
        chapterBody: String,
        plan: String?,
        writingPreference: String?,
        locale: Locale = Locale.CHINESE,
    ): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是重写本章轮：请重写第 $chapterOrdinal 章「$chapterTitle」，产出整章替换稿。")
        appendLine()
        appendLine("## 当前章正文（$chapterPath）")
        appendLine(chapterBody.trim().ifEmpty { "（空章节）" })
        if (!plan.isNullOrBlank()) {
            appendLine()
            appendLine("## 本章计划（plan/this-chapter.md）")
            appendLine(plan.trim())
        }
        if (!writingPreference.isNullOrBlank()) {
            appendLine()
            appendLine("## 写作偏好（setting/writing）")
            appendLine(writingPreference.trim())
        }
        appendLine()
        appendLine("步骤：")
        appendLine("1. 对照上方注入简报（剧情状态、未回收伏笔、已确认决定）检查当前章需要改进的地方；必要时 read/grep 相邻章节保证衔接。")
        appendLine("2. 保持本章在全书中的定位与事件结果，把重写后的完整正文用 novel_workspace_write 写回 $chapterPath（会生成审批卡，作者确认后整章替换）。")
        appendLine("3. 只写这一个文件，不改其他章节；写完后收尾本轮。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is a chapter rewrite turn. Rewrite chapter $chapterOrdinal (\"$chapterTitle\") as a complete replacement.")
        appendLine()
        appendLine("## Current chapter ($chapterPath)")
        appendLine(chapterBody.trim().ifEmpty { "(empty chapter)" })
        if (!plan.isNullOrBlank()) {
            appendLine()
            appendLine("## Chapter plan (plan/this-chapter.md)")
            appendLine(plan.trim())
        }
        if (!writingPreference.isNullOrBlank()) {
            appendLine()
            appendLine("## Writing preferences (setting/writing)")
            appendLine(writingPreference.trim())
        }
        appendLine()
        appendLine("Steps:")
        appendLine("1. Check the injected brief (plot state, unresolved foreshadowing, and confirmed decisions); use read/grep on nearby chapters when needed.")
        appendLine("2. Preserve this chapter's role and event outcomes, then write the complete replacement to $chapterPath with novel_workspace_write (an approval card will be created).")
        append("3. Write only this file; do not change other chapters. Wrap up after the write.")
    }

    /**
     * Character proposal: the host gives the name, a one-line sketch, the existing
     * character card paths, and the host-computed target file. setting/ is a free-write
     * path, so the card lands directly (no approval card) with the story-graph metadata
     * and a fixed section shape.
     */
    fun characterProposal(
        characterName: String,
        sketch: String,
        existingCharacters: List<String>,
        targetPath: String,
        locale: Locale = Locale.CHINESE,
    ): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是角色提案轮：请为新角色建一张人物卡。")
        appendLine("角色名：${characterName.trim()}")
        appendLine("一句话设想：${sketch.trim()}")
        if (existingCharacters.isNotEmpty()) {
            appendLine()
            appendLine("现有角色卡片（setting/characters/，供避免重复与定位冲突，可用 read 查看细节）：")
            existingCharacters.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("要求：")
        appendLine("1. 遵守上方注入简报里的已确认决定与既有角色关系，不与之矛盾；拿不准先 read/grep 核对。")
        appendLine("2. 用 novel_workspace_write 把完整人物卡写入 $targetPath。本轮是新建节点，作为「不编造 front matter」规则的明确例外，必须包含以下 front matter：")
        appendLine("   ---")
        appendLine("   kind: material")
        appendLine("   materialKind: character")
        appendLine("   title: ${characterName.trim()}")
        appendLine("   ---")
        appendLine("   front matter 后按以下小节组织正文：## 身份 / ## 动机 / ## 外形 / ## 口癖 / ## 与既有角色的关系 / ## 备注")
        append("3. 与既有角色定位重复时，在「备注」里写清差异，不要改动别人的卡片。写完文件后收尾本轮。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is a character-proposal turn. Create a character card for a new character.")
        appendLine("Name: ${characterName.trim()}")
        appendLine("One-line idea: ${sketch.trim()}")
        if (existingCharacters.isNotEmpty()) {
            appendLine()
            appendLine("Existing character cards (under setting/characters/; use read to avoid duplication and conflicts):")
            existingCharacters.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Requirements:")
        appendLine("1. Follow confirmed decisions and existing relationships in the injected brief; use read/grep when uncertain.")
        appendLine("2. Use novel_workspace_write to write the complete card to $targetPath. This new node is the explicit exception to the no-invented-front-matter rule; include exactly this front matter:")
        appendLine("   ---")
        appendLine("   kind: material")
        appendLine("   materialKind: character")
        appendLine("   title: ${characterName.trim()}")
        appendLine("   ---")
        appendLine("   After the front matter, use these sections: ## Identity / ## Motivation / ## Appearance / ## Speech / ## Relationships / ## Notes")
        append("3. If the role duplicates an existing character, explain the difference in Notes and do not modify other cards. Wrap up after writing.")
    }

    /**
     * Batch polish (unattended): the host pastes the current chapter body plus the
     * writing preference (same setting/writing source as 重写本章), and the model writes
     * the full polished chapter back to the SAME chapter file. The turn runs on the
     * ghostwrite canon path (autoApproveCanon), so the write tool is host-locked to
     * exactly this path. Polish must not move the story: facts, character states and
     * the timeline stay byte-for-byte equivalent in meaning — only prose improves —
     * and the host pairs the commit with a 「剧情指针」 commit afterwards.
     */
    fun polishChapter(
        chapterOrdinal: Int,
        chapterPath: String,
        chapterBody: String,
        writingPreference: String?,
        locale: Locale = Locale.CHINESE,
    ): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是批量润色轮（无人值守）：请润色第 $chapterOrdinal 章。只改善文笔，绝不改变故事。")
        appendLine()
        appendLine("## 当前章正文（$chapterPath）")
        appendLine(chapterBody.trim().ifEmpty { "（空章节）" })
        if (!writingPreference.isNullOrBlank()) {
            appendLine()
            appendLine("## 写作偏好（setting/writing）")
            appendLine(writingPreference.trim())
        }
        appendLine()
        appendLine("要求：")
        appendLine("1. 情节事实、人物状态、人物关系、时间线必须与当前章完全一致：不增删事件，不改动任何结果与因果，不提前透露后文。")
        appendLine("2. 只优化文字表达（对白、描写、节奏、去冗余），篇幅与当前章大致持平（±20%），不要续写新情节。")
        appendLine("3. 把润色后的整章正文用 novel_workspace_write 写回 $chapterPath；除这一个文件外不要写任何文件（包括 plot/ 与其他章节）。")
        append("4. 写完后收尾本轮，不要在回复里复述正文。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is an unattended batch-polish turn. Polish chapter $chapterOrdinal. Improve prose only; do not change the story.")
        appendLine()
        appendLine("## Current chapter ($chapterPath)")
        appendLine(chapterBody.trim().ifEmpty { "(empty chapter)" })
        if (!writingPreference.isNullOrBlank()) {
            appendLine()
            appendLine("## Writing preferences (setting/writing)")
            appendLine(writingPreference.trim())
        }
        appendLine()
        appendLine("Requirements:")
        appendLine("1. Keep plot facts, character states, relationships, and timeline exactly equivalent: add or remove no events, outcomes, or causes, and do not reveal later material.")
        appendLine("2. Improve only wording (dialogue, description, pacing, and redundancy), keeping length roughly stable (±20%); do not continue the story.")
        appendLine("3. Use novel_workspace_write to write the polished full chapter back to $chapterPath; write no other file, including plot/ or other chapters.")
        append("4. Wrap up after writing; do not repeat the prose in the response.")
    }

    /** Layer-3 review: read the newest chapter, check it against the injected brief. */
    fun consistencyReview(locale: Locale = Locale.CHINESE): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是一致性审稿轮（只读，不修改任何文件）：")
        appendLine("1. 用 list/read 读最新一章正文。")
        appendLine("2. 对照上方状态简报里的剧情状态、未回收伏笔、已确认决定与本章相关节点。")
        appendLine("3. 逐条报告矛盾点（哪句正文和哪条约束冲突）；如果没有矛盾，明确说「未发现矛盾」。")
        append("不要改动任何文件；只在回复里给结论。")
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is a read-only consistency-review turn; do not modify any files:")
        appendLine("1. Use list/read to inspect the newest chapter.")
        appendLine("2. Compare it with the injected brief: plot state, unresolved foreshadowing, confirmed decisions, and chapter-related nodes.")
        appendLine("3. Report each contradiction with the conflicting prose and constraint; if none exist, explicitly say \"No contradictions found.\"")
        append("Do not change files; give the conclusion in the response only.")
    }

    /** One candidate-bound review replaces separate consistency, plot-sync and planning calls. */
    fun jointGhostwriteReview(
        candidate: NovelWorkspaceGhostwriteCandidate,
        confirmedPlan: String,
        requiresNextPlan: Boolean = false,
        locale: Locale = Locale.CHINESE,
    ): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是无人值守联合审核轮。只读，不得调用写工具；最终回复必须是一个原始 JSON 对象，不能有代码围栏或解释文字。")
        appendLine("只有以下三类证据可令 blocking=true：缺失计划明确要求的必写项、违反计划明确禁写项、可定位到当前候选章的连续性硬伤。不确定问题、复读提示、可选 upcoming arc 一律 non_blocking。")
        appendLine("可修复问题使用 rewriteRequired=true 并给出精确 repairInstructions；blocking 与 rewriteRequired 不得同时为 true。审核通过时两者都为 false，并给出完整的章后剧情/人物状态摘要与一行本章事件。")
        appendLine(
            if (requiresNextPlan) {
                "这不是批次最终章；审核通过时 nextPlan 必须给出下一章的非空写作计划正文，不含 front matter。"
            } else {
                "这是批次最终章；nextPlan 必须为 null。"
            },
        )
        appendLine()
        appendLine("严格 JSON 字段：")
        appendLine(if (requiresNextPlan) REVIEW_JSON_WITH_NEXT_PLAN else REVIEW_JSON_FINAL)
        appendLine()
        appendLine("candidateId: ${candidate.id}")
        appendLine("chapterOrdinal: ${candidate.chapterOrdinal}")
        appendLine("planId: ${candidate.planId}")
        appendLine("planDigest: ${candidate.planDigest}")
        appendLine()
        appendLine("## 已确认本章计划")
        appendLine(confirmedPlan.trim())
        appendLine()
        appendLine("## 候选标题")
        appendLine(candidate.title)
        appendLine()
        appendLine("## 候选正文")
        append(candidate.body.trim())
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is an unattended joint-review turn. It is read-only: do not call the write tool. The final response must be one raw JSON object with no code fence or prose.")
        appendLine("Only three evidence classes may set blocking=true: a missing explicitly required plan item, an explicit forbidden-plan violation, or a hard continuity error located in this candidate. Uncertainty, repeated prompt advice, and an optional upcoming arc are non_blocking.")
        appendLine("Use rewriteRequired=true with precise repairInstructions for repairable issues. blocking and rewriteRequired cannot both be true. On pass, both are false and plotState/chapterHighlight describe the post-chapter state.")
        appendLine(
            if (requiresNextPlan) {
                "This is not the batch's final chapter. On pass, nextPlan must contain a non-empty next-chapter plan body without front matter."
            } else {
                "This is the batch's final chapter. nextPlan must be null."
            },
        )
        appendLine()
        appendLine("Exact JSON fields:")
        appendLine(if (requiresNextPlan) REVIEW_JSON_WITH_NEXT_PLAN else REVIEW_JSON_FINAL)
        appendLine()
        appendLine("candidateId: ${candidate.id}")
        appendLine("chapterOrdinal: ${candidate.chapterOrdinal}")
        appendLine("planId: ${candidate.planId}")
        appendLine("planDigest: ${candidate.planDigest}")
        appendLine()
        appendLine("## Confirmed chapter plan")
        appendLine(confirmedPlan.trim())
        appendLine()
        appendLine("## Candidate title")
        appendLine(candidate.title)
        appendLine()
        appendLine("## Candidate prose")
        append(candidate.body.trim())
    }

    /** Read-only fallback used only when a passing non-final review omitted nextPlan. */
    fun nextGhostwritePlan(
        candidate: NovelWorkspaceGhostwriteCandidate,
        review: NovelWorkspaceJointReviewResult,
        locale: Locale = Locale.CHINESE,
    ): String = if (isChinese(locale)) buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是无人值守的下一章规划轮。只读，不得调用写工具；最终回复只能是一个原始 JSON 对象：{\"nextPlan\":\"...\"}，不能有代码围栏或解释文字。")
        appendLine("可读 plot/current.md、最近章节与可选的 plan/upcoming.md；当前候选尚未进入正史，应以上述联合审核后的状态和下方候选为准。")
        appendLine("nextPlan 必须是第 ${candidate.chapterOrdinal + 1} 章的非空计划正文，不含 front matter；写清目标与冲突、必须发生、不可发生和结尾钩子。")
        appendLine()
        appendLine("## 联合审核后的剧情状态")
        appendLine(review.plotState.trim())
        appendLine()
        appendLine("## 本章事件")
        appendLine(review.chapterHighlight.trim())
        appendLine()
        appendLine("## 当前候选")
        append(candidate.body.trim())
    } else buildString {
        appendLine(discipline(locale))
        appendLine()
        appendLine("This is an unattended next-chapter planning turn. It is read-only: do not call write tools. Return exactly one raw JSON object, {\"nextPlan\":\"...\"}, with no code fence or prose.")
        appendLine("You may read plot/current.md, recent chapters, and optional plan/upcoming.md. The current candidate is not canon yet, so use the reviewed post-chapter state and candidate below as the source of truth.")
        appendLine("nextPlan must be a non-empty body for chapter ${candidate.chapterOrdinal + 1}, without front matter, covering the goal/conflict, must happen, must not happen, and ending hook.")
        appendLine()
        appendLine("## Post-review plot state")
        appendLine(review.plotState.trim())
        appendLine()
        appendLine("## Chapter event")
        appendLine(review.chapterHighlight.trim())
        appendLine()
        appendLine("## Current candidate")
        append(candidate.body.trim())
    }

    private const val REVIEW_JSON_FINAL = "{\"candidateId\":\"...\",\"chapterOrdinal\":1,\"planId\":\"...\",\"planDigest\":\"...\",\"blocking\":false,\"rewriteRequired\":false,\"repairInstructions\":[],\"findings\":[{\"kind\":\"missing_required|forbidden_violation|hard_continuity|non_blocking\",\"message\":\"...\",\"candidateEvidence\":\"...\",\"planEvidence\":null}],\"plotState\":\"...\",\"chapterHighlight\":\"...\",\"nextPlan\":null}"

    private const val REVIEW_JSON_WITH_NEXT_PLAN = "{\"candidateId\":\"...\",\"chapterOrdinal\":1,\"planId\":\"...\",\"planDigest\":\"...\",\"blocking\":false,\"rewriteRequired\":false,\"repairInstructions\":[],\"findings\":[{\"kind\":\"missing_required|forbidden_violation|hard_continuity|non_blocking\",\"message\":\"...\",\"candidateEvidence\":\"...\",\"planEvidence\":null}],\"plotState\":\"...\",\"chapterHighlight\":\"...\",\"nextPlan\":\"下一章计划正文\"}"

    enum class ProseGranularity { CONTINUATION, WHOLE_CHAPTER }
}
