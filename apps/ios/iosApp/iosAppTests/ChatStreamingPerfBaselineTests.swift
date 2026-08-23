import XCTest
import SwiftUI
import Shared
@testable import iosApp

/// 长内容流式性能回归契约。
///
/// 以绝对阈值拦截真实回归，并打印 `[PERF]` 指标供修复前后对账。
/// 本文件属于默认 `iosAppTests` target。
///
/// 指标口径:主线程 CPU 时间(`CLOCK_THREAD_CPUTIME_ID`),
/// 排除 Task.detached 解析线程和 runloop 空转睡眠——这正是掉帧的预算来源
/// (120Hz 帧预算 ≈ 8.3ms 主线程时间)。
@MainActor
final class ChatStreamingPerfBaselineTests: XCTestCase {

    // MARK: - Fixture

    /// 约 32KB 的混合 Markdown(标题/段落/列表/表格/代码块,不含 widget 标记),
    /// 模拟一条超长 assistant 回复。CJK 为主,贴近真实会话。
    private static func longMarkdown(targetUTF16: Int) -> String {
        let section = """
        ## 分层架构与机制推演

        这一节讨论**流式渲染**的分层职责:缓冲层负责把 provider chunk 平滑释放,\
        渲染层做增量解析与 partial 容错,滚动层维护三态机,视觉层负责逐词淡入。\
        每一层只有单一所有者,症状必须归层后自底向上修复,拒绝补丁叠加。

        - 缓冲层:`AsyncStream` FIFO,48ms flush,只在必要点取 snapshot
        - 渲染层:块级冻结,前缀块稳定,只有尾块重解析
        - 滚动层:`scrollTo(id:)` 单一定位形态,24ms 合并
        - 视觉层:CADisplayLink glyph fade,不参与 SwiftUI 事务

        | 层 | 职责 | 所有者 | 复杂度 |
        |----|------|--------|--------|
        | L1 | 缓冲与节流 | 协调器 | O(delta) |
        | L2 | 增量解析 | 控制器 | O(尾块) |
        | L3 | 滚动跟随 | 列表 | O(1) |
        | L4 | 动画视觉 | vendor | O(可见) |

        ```swift
        func follow(_ geometry: Geometry) {
            guard mode == .followingBottom else { return }
            anchor(.bottom)
        }
        ```

        > 引用:读代码只产生嫌疑,运行时证据才能定罪。参数需要魔法数才收敛,\
        说明坐标系已经错了。

        """
        var out = ""
        var index = 0
        while out.utf16.count < targetUTF16 {
            index += 1
            out += "\n# 第 \(index) 章\n\n" + section
        }
        return out
    }

    private static func mainThreadCPUNanos() -> UInt64 {
        clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID)
    }

    // MARK: - 端到端:真实流式气泡的每 delta 主线程成本

    private final class StreamModel: ObservableObject {
        @Published var markdown: String = ""
        let messageID = KotlinUuid.companion.random()
        let createdAt = chatNowLocalDateTime()

        var message: UIMessage {
            UIMessage(
                id: messageID,
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: markdown, metadata: nil)],
                annotations: [],
                createdAt: createdAt,
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }
    }

    private struct StreamingBubbleHarness: View {
        @ObservedObject var model: StreamModel
        let workspaceStore: IOSWorkspaceStore

        var body: some View {
            content
        }

        private var content: some View {
            ScrollView {
                MessageBubbleView(
                    message: model.message,
                    messageIndex: 0,
                    isGenerating: true,
                    isLastMessage: true,
                    hasEverStreamed: true,
                    liveMarkdownRenderingEnabled: true,
                    frozenMarkdownSnapshot: nil
                )
                .environment(workspaceStore)
                .padding(.horizontal, ChatLayout.contentHorizontalInset)
            }
            .frame(width: 393, height: 852)
        }
    }

    /// 每 delta 之间泵 30ms wall-clock，让节流解析的发布/布局
    /// 成本落进测量窗。指标 = 主线程 CPU 每 delta 平均毫秒数。
    func testPerf_endToEnd_streamingBubbleDeltas() {
        let table24 = runStreamingHarness(base: Self.longMarkdown(targetUTF16: 24_000))
        // 宽松回归门:当前基线约 76-82ms，保留 2× 以上机器余量，但必须拦住
        // config 失效时已复现的约 317ms 旧回归。
        XCTAssertLessThan(table24, 200)
    }
    private func runStreamingHarness(base: String) -> Double {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatStreamingPerfBaselineTests-\(UUID().uuidString)", isDirectory: true)
        let workspaceStore = IOSWorkspaceStore(baseDirectory: tempDir)

        let model = StreamModel()
        model.markdown = base

        let host = UIHostingController(
            rootView: StreamingBubbleHarness(
                model: model,
                workspaceStore: workspaceStore
            )
        )
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        } else {
            window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        }
        window.rootViewController = host
        window.makeKeyAndVisible()
        window.layoutIfNeeded()

        // 预热:让初始解析/布局完成,不计入测量。
        pump(seconds: 2.0)

        let deltas = 60
        let start = Self.mainThreadCPUNanos()
        for _ in 0..<deltas {
            model.markdown += "接着上一句继续补充:机制层的所有权在任一时刻只能有一个写入者,"
            pump(seconds: 0.03)
        }
        // 收尾泵:让最后一次节流解析落地。
        pump(seconds: 0.6)
        let elapsed = Self.mainThreadCPUNanos() - start

        let perDeltaMs = Double(elapsed) / Double(deltas) / 1_000_000
        print(String(
            format: "[PERF] endToEnd[tables24KB] deltas=%d mainThreadTotal=%.0fms per-delta=%.2fms",
            deltas, Double(elapsed) / 1_000_000, perDeltaMs
        ))
        window.isHidden = true
        window.rootViewController = nil
        return perDeltaMs
    }

    private func pump(seconds: TimeInterval) {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
        }
    }

}
