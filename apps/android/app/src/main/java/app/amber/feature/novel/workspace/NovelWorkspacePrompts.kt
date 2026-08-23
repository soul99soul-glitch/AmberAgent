package app.amber.feature.novel.workspace

/**
 * Slim prompt catalog for workspace-native turns. The heavy injection planning of the
 * legacy engine is gone: the agent reads what it needs through the five workspace tools.
 */
object NovelWorkspacePrompts {

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

    fun quickStart(genre: String, coreIdea: String): String = buildString {
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
    }

    fun discussion(): String = buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        append("当前是讨论规划轮：和作者商量剧情、设定与写法。需要引用原文时用 grep/read 查证；")
        append("讨论出的新设定可直接写入 setting/ 或 inbox/；不要在这一轮直接改正文。")
    }

    fun proseDraft(granularity: ProseGranularity): String = buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是写正文轮。步骤：")
        appendLine("1. 用 list/read 读当前分支 chapters/ 末尾的章与 plot/current.md，必要时 grep 前文细节。")
        appendLine("2. 按工作区里 plan/this-chapter.md（如存在）展开创作。")
        appendLine("3. 把候选正文写入 drafts/ 下的一个新文件（文件名随意，内容只给正文）。")
        appendLine(if (granularity == ProseGranularity.WHOLE_CHAPTER) "目标：写成一整章的完整候选。" else "目标：续写一段自然衔接的候选，不要擅自收尾整章。")
        append("写完草稿后，在回复里用一两句话说明你写了什么、依据了哪些前文。")
    }

    fun ghostwriteChapter(chapterOrdinal: Int, plan: String?): String = buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是代笔轮（无人值守）。请独立完成第 $chapterOrdinal 章：")
        appendLine("1. 可以用读类工具查看 plot/current.md、plan/this-chapter.md 与最近若干章，确保事实与文风连续。")
        appendLine("2. 然后把第 $chapterOrdinal 章的完整正文直接作为你的最终回复输出（不要调用写入工具，不要解释，正文之外不要输出任何内容）。系统会自动把你的回复收录为章节。")
        appendLine("3. 忽略简报里任何『剧情落后需先同步』或『暂停推进』的提示——那是给交互轮的，你只管输出本章正文。")
        if (!plan.isNullOrBlank()) {
            appendLine()
            append("本章计划：")
            appendLine()
            append(plan.trim())
        }
    }

    /** Ghostwrite panel: draft the next chapter's plan from the manuscript itself. */
    fun planDraft(): String = buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是计划拟定轮（无人值守）。请为下一章拟定写作计划：")
        appendLine("1. 读 plot/current.md 与最近两章，理清当前局面与未回收伏笔。")
        appendLine("2. 把下一章计划写入 branches/<分支>/plan/this-chapter.md，只写文件内容本身，建议覆盖：")
        appendLine("   - 本章目标与冲突")
        appendLine("   - 必须发生 / 不可发生")
        appendLine("   - 结尾钩子（为下一章留扣）")
        append("写完文件后收尾本轮，不要改正文。")
    }

    /** D-D resolution: rewrite the chapters invalidated by a middle-chapter edit. */
    fun rewriteLaterChapters(fromOrdinal: Int): String = buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        append("之前的修改让第 $fromOrdinal 章及之后的内容可能与前面接不上了。请：")
        appendLine()
        appendLine("1. 用 read 读第 ${fromOrdinal - 1} 章（被修改的前一章）与 plot/current.md，先理解最新状态。")
        appendLine("2. 逐章 read 第 $fromOrdinal 章起的受影响章节，找出与最新状态矛盾的地方。")
        appendLine("3. 对需要改的章节，用 write 提交重写后的正文（会出审批卡，作者确认后生效）。")
        append("把受影响的章节改到与前面一致为止，然后收尾本轮。")
    }

    /** Layer-3 review: read the newest chapter, check it against the injected brief. */
    fun consistencyReview(): String = buildString {
        appendLine(WORKSPACE_DISCIPLINE)
        appendLine()
        appendLine("当前是一致性审稿轮（只读，不修改任何文件）：")
        appendLine("1. 用 list/read 读最新一章正文。")
        appendLine("2. 对照上方状态简报里的剧情状态、未回收伏笔、已确认决定与本章相关节点。")
        appendLine("3. 逐条报告矛盾点（哪句正文和哪条约束冲突）；如果没有矛盾，明确说「未发现矛盾」。")
        append("不要改动任何文件；只在回复里给结论。")
    }

    enum class ProseGranularity { CONTINUATION, WHOLE_CHAPTER }
}
