import Foundation
import Shared

/// 行摘要:把"这一行有什么变化"拆成两个显式契约的哈希。
/// - layout:任何可能改变行渲染高度的输入。高度缓存以它为键;它变化 → 高度失效。
/// - presentation:只改变行内呈现、不改变高度的输入(如 index 驱动的 action 路由)。
/// 两者任一变化 → 行需要 reconfigure。
/// 契约由 ChatRowDigestTests 逐输入枚举保护;新增 MessageBubbleView 输入时必须
/// 归类进其中一侧并补测试。
struct ChatRowDigest: Equatable, Hashable {
    let layout: String
    let presentation: String
}

enum ChatRowDigests {
    static func digest(
        row: ChatMessageRowModel,
        renderState: ChatRenderState,
        contentHash: Int,
        isGenerationActive: Bool,
        displaySettingSignature: String,
        generativeUiSettingSignature: String,
        hasMultipleVariants: Bool,
        reasoningLevelLabel: String?
    ) -> ChatRowDigest {
        var layout = Hasher()
        layout.combine(row.messageId)
        layout.combine(String(describing: row.role))
        layout.combine(contentHash)
        layout.combine(row.isLast)
        layout.combine(row.isStreaming)
        layout.combine(row.hasEverStreamed)
        // isGenerating 经由思考胶囊显隐、reasoning 卡自动折叠、widget 流式占位
        // 三条路径真实影响高度,v1 保守归入 layout;精细化(按 part 组成拆位)
        // 待夹具实测后再收紧。
        layout.combine(row.isLast && isGenerationActive)
        layout.combine(renderState.rendererMode.rawValue)
        layout.combine(renderState.liveRenderingEnabled)
        layout.combine(displaySettingSignature)
        layout.combine(generativeUiSettingSignature)
        // variantSwitcher 是整行内嵌控件,单变体→多变体切换会让整行多出/少出
        // 一整条控件,直接改变行高;不能只留在 presentation 侧。
        layout.combine(hasMultipleVariants)
        // reasoning 卡标题文案(如 "Auto"/"High")长度不同会导致折行,进而改变
        // 卡片(以及整行)高度,必须纳入 layout。
        layout.combine(reasoningLevelLabel)

        var presentation = Hasher()
        // index 只影响 action 路由(variantInfo 按 index 解析),不影响高度。
        presentation.combine(row.index)

        return ChatRowDigest(
            layout: String(layout.finalize()),
            presentation: String(presentation.finalize())
        )
    }
}
