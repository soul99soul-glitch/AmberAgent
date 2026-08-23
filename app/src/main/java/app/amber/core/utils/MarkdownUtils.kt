package app.amber.core.utils

/**
 * 移除字符串中的Markdown格式
 * @return 移除Markdown格式后的纯文本
 */
fun String.stripMarkdown(): String {
    return this
        // 移除代码块 (```...``` 和 `...`)
        .replace(Regex("```[\\s\\S]*?```|`[^`]*?`"), "")
        // 移除图片和链接，但保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        // 移除下划线
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        // 移除删除线
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 移除标题标记 (多行模式)
        .replace(Regex("(?m)^#+\\s*"), "")
        // 移除列表标记 (多行模式)
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // 移除引用标记 (多行模式)
        .replace(Regex("(?m)^>\\s*"), "")
        // 移除水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 将多个换行符压缩，以保留段落
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * 思考框（reasoning）专用的轻量标记剥离：与 [stripMarkdown] 不同，
 * 代码围栏只去掉围栏行本身、内容保留——思考里的代码草稿是 prose 的一部分，
 * 不应整块消失。标题/加粗/链接等文档级标记全部剥掉，只留文字。
 */
fun String.stripReasoningMarkdown(): String {
    return this
        // 代码围栏行（含语言标注）去掉，内容保留
        .replace(Regex("(?m)^\\s*```[^\\n]*$"), "")
        .replace(Regex("(?m)^\\s*~~~[^\\n]*$"), "")
        // 行内代码标记
        .replace(Regex("`([^`\\n]+?)`"), "$1")
        // 图片/链接保留文字
        .replace(Regex("!\\[([^\\]]*)\\]\\([^\\)]*\\)"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 粗体/斜体/下划线/删除线
        .replace(Regex("\\*\\*\\*([^*]+?)\\*\\*\\*"), "$1")
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*\\n]+?)\\*"), "$1")
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_\\n]+?)_"), "$1")
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 标题/列表/引用标记（保留嵌套缩进——bullet 去掉但层级信息保留）
        .replace(Regex("(?m)^#{1,6}(?:[ \\t]+)"), "")
        .replace(Regex("(?m)^(\\s*)[-*+][ \\t]+"), "$1")
        .replace(Regex("(?m)^(\\s*)\\d+[.)][ \\t]+"), "$1")
        .replace(Regex("(?m)^>\\s?"), "")
        // setext 标题下划线（=== / ---）：连前导换行一起移除，保留行间分隔
        .replace(Regex("\\n?={2,}[ \\t]*(?=\\n|$)"), "")
        // 水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 压缩多余空行
        .replace(Regex("\n{3,}"), "\n\n")
}

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()

        // 检查是否为加粗格式且独占一整行
        val boldPattern = Regex("^\\*\\*(.+?)\\*\\*$")
        val match = boldPattern.find(line)

        if (match != null) {
            // 返回加粗标记内的文本内容
            return match.groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}
