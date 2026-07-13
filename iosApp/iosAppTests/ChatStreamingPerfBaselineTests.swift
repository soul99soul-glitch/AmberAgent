import XCTest
import SwiftUI
import Shared
@testable import iosApp

/// 长内容流式性能基线(诊断型)。
///
/// 打印 `[PERF]` 指标行,不做紧断言——用途是修复前后的可对账对比,
/// 阈值断言只设"荒谬上限"防止极端回归,避免 CI 机器差异造成 flaky。
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

    private func report(_ name: String, iterations: Int, totalNanos: UInt64) {
        let totalMs = Double(totalNanos) / 1_000_000
        let perCallMicros = Double(totalNanos) / Double(iterations) / 1_000
        print(String(
            format: "[PERF] %@ iterations=%d total=%.1fms per-call=%.1fµs",
            name, iterations, totalMs, perCallMicros
        ))
    }

    // MARK: - 微基准:每 delta 主线程调用图中的 O(n) 嫌疑项

    /// 嫌疑 1:`mayContainWidgetPayload` 在流式尾行每次 body 求值执行,
    /// 对全文做多次 case-insensitive 子串搜索。
    func testPerf_widgetPayloadScan_longContent() {
        let text = Self.longMarkdown(targetUTF16: 32_000)
        let iterations = 200
        var hits = 0
        let start = Self.mainThreadCPUNanos()
        for _ in 0..<iterations {
            if IOSGenerativeWidgetParser.mayContainWidgetPayload(text) {
                hits += 1
            }
        }
        let elapsed = Self.mainThreadCPUNanos() - start
        report("widgetPayloadScan(32KB)", iterations: iterations, totalNanos: elapsed)
        XCTAssertEqual(hits, 0, "夹具不应含 widget 标记")
    }

    /// 嫌疑 2:`ChatTimelinePlanner.build` 每 delta 对全部消息重建 rows,
    /// 且尾行 renderToken 里的 `text.count` 是 O(n) 字素簇遍历。
    func testPerf_timelinePlannerBuild_longSession() {
        let tail = Self.longMarkdown(targetUTF16: 32_000)
        var messages: [UIMessage] = []
        for turn in 0..<30 {
            messages.append(UIMessage.companion.user(prompt: "问题 \(turn):请继续展开上一节的机制细节。"))
            messages.append(UIMessage.companion.assistant(prompt: "回答 \(turn):这一段是普通长度的历史回复,包含若干句子成本可忽略。"))
        }
        messages.append(UIMessage.companion.assistant(prompt: tail))

        let iterations = 200
        var entryCount = 0
        let start = Self.mainThreadCPUNanos()
        for _ in 0..<iterations {
            let plan = ChatTimelinePlanner.build(
                messages: messages,
                event: .assistantStreamDelta,
                streamedMessageIDs: [],
                includePendingAssistant: false
            )
            entryCount = plan.entries.count
        }
        let elapsed = Self.mainThreadCPUNanos() - start
        report("timelinePlannerBuild(61msgs+32KBtail)", iterations: iterations, totalNanos: elapsed)
        XCTAssertGreaterThan(entryCount, 60)
    }

    /// 嫌疑 3:`ChatStableStreamingMarkdownController.renderable(for:)` 每次 body
    /// 求值对全文做缓存键 Hasher + 最多 12 次 hasPrefix。用 DEBUG TestSupport
    /// 探针近似(同一条静态缓存查询路径)。
    func testPerf_renderableCacheProbe_longContent() {
        ChatStableStreamingMarkdownCacheTestSupport.reset()
        let text = Self.longMarkdown(targetUTF16: 32_000)
        // 预置一个短前缀条目,迫使查询走"精确 miss → 前缀回退扫描"全路径。
        ChatStableStreamingMarkdownCacheTestSupport.store(
            text: String(text.prefix(2_000)),
            animate: true
        )
        let iterations = 200
        var hitCount = 0
        let start = Self.mainThreadCPUNanos()
        for _ in 0..<iterations {
            if ChatStableStreamingMarkdownCacheTestSupport.hasCachedRenderable(text: text, animate: true) {
                hitCount += 1
            }
        }
        let elapsed = Self.mainThreadCPUNanos() - start
        report("renderableCacheProbe(32KB)", iterations: iterations, totalNanos: elapsed)
        XCTAssertEqual(hitCount, iterations, "前缀回退应命中预置条目")
    }

    /// 嫌疑 4:行级 digest 里每可见行两次 `String(describing:)` 反射
    /// (displaySetting / generativeUiSetting 签名,对所有行相同却逐行重算)。
    /// 用 KMP 桥接对象近似反射成本量级(与 DisplaySetting 同为 Kotlin 数据类桥)。
    func testPerf_rowDigestSignatureReflection() {
        let iterations = 2_000
        let kmpObject = UIMessage.companion.assistant(prompt: "反射成本采样对象")
        let start = Self.mainThreadCPUNanos()
        var totalLength = 0
        for _ in 0..<iterations {
            totalLength += String(describing: kmpObject).count
        }
        let elapsed = Self.mainThreadCPUNanos() - start
        report("kmpObjectDescribing", iterations: iterations, totalNanos: elapsed)
        XCTAssertGreaterThan(totalLength, 0)
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
        var isGenerating = true

        var body: some View {
            content
        }

        private var content: some View {
            ScrollView {
                MessageBubbleView(
                    message: model.message,
                    messageIndex: 0,
                    isGenerating: isGenerating,
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

    /// 纯散文变体(无表格/代码块):隔离 vendor 表格重测量与 block 渲染路径,
    /// 用缩放矩阵(表格/散文 × 24KB/6KB)定位每 delta 主线程成本的来源与量级。
    private static func proseMarkdown(targetUTF16: Int) -> String {
        let section = """
        ## 机制推演续篇

        这一节继续讨论流式渲染的分层职责。缓冲层负责把 provider chunk 平滑释放,\
        渲染层做增量解析与 partial 容错,滚动层维护三态机,视觉层负责逐词淡入。\
        每一层只有单一所有者,症状必须归层之后自底向上修复,拒绝补丁叠加。\
        读代码只产生嫌疑,运行时证据才能定罪;参数需要魔法数才收敛,说明坐标系错了。

        对照实验是最锋利的刀。怀疑改动打破了测试,就把改动临时翻回去单独跑;\
        实验组异常时先跑一个不做该操作的对照组;切换方案前先写下可证伪预言,\
        预言不中就回炉,不硬凹。数字要能对账,对不上账的残差就是还没找到的因素。

        """
        var out = ""
        var index = 0
        while out.utf16.count < targetUTF16 {
            index += 1
            out += "\n# 第 \(index) 篇\n\n" + section
        }
        return out
    }

    /// 端到端缩放矩阵:每 delta 之间泵 30ms wall-clock,让节流解析的发布/布局
    /// 成本落进测量窗。指标 = 主线程 CPU 每 delta 平均毫秒数。
    func testPerf_endToEnd_streamingBubbleDeltas() {
        let table24 = runStreamingHarness(
            label: "tables24KB",
            base: Self.longMarkdown(targetUTF16: 24_000),
            deltas: 60
        )
        let prose24 = runStreamingHarness(
            label: "prose24KB",
            base: Self.proseMarkdown(targetUTF16: 24_000),
            deltas: 60
        )
        let table6 = runStreamingHarness(
            label: "tables6KB",
            base: Self.longMarkdown(targetUTF16: 6_000),
            deltas: 60
        )
        let prose6 = runStreamingHarness(
            label: "prose6KB",
            base: Self.proseMarkdown(targetUTF16: 6_000),
            deltas: 60
        )
        // 对照 A:同一表格夹具,强制单文档路径(块渲染开关关),区分
        // 「块路径结构成本」vs「vendor 表格重测量成本」。
        let blockKey = IOSDisplayPreferenceKeys.streamingBlockMarkdown
        let previousBlockFlag = UserDefaults.standard.object(forKey: blockKey)
        UserDefaults.standard.set(false, forKey: blockKey)
        let tableMono = runStreamingHarness(
            label: "tables24KB-monolith",
            base: Self.longMarkdown(targetUTF16: 24_000),
            deltas: 60
        )
        if let previousBlockFlag {
            UserDefaults.standard.set(previousBlockFlag, forKey: blockKey)
        } else {
            UserDefaults.standard.removeObject(forKey: blockKey)
        }
        // 对照 B:零 delta 静置,量常驻成本(display link/动画/定时器),
        // 换算成同口径的"每 30ms 窗口毫秒数"。
        let tableIdle = runStreamingHarness(
            label: "tables24KB-idle",
            base: Self.longMarkdown(targetUTF16: 24_000),
            deltas: 0
        )
        // 对照 C:isGenerating=false 静置——隔离「流式活跃态」的常驻消耗
        // (思考卡 shimmer/计时、animate config、display link 等)。
        let tableIdleDone = runStreamingHarness(
            label: "tables24KB-idle-done",
            base: Self.longMarkdown(targetUTF16: 24_000),
            deltas: 0,
            isGenerating: false
        )
        // 对照 D:纯散文静置——表格相关性。
        let proseIdle = runStreamingHarness(
            label: "prose24KB-idle",
            base: Self.proseMarkdown(targetUTF16: 24_000),
            deltas: 0
        )
        // 对照 E:极小内容静置——harness 自身(runloop 泵/宿主)的地板。
        let bareIdle = runStreamingHarness(
            label: "bare-idle",
            base: "短内容。",
            deltas: 0
        )
        // 对照 F:静置衰减曲线——区分「永动动画」(恒定)与「超长尾 fade」(衰减)。
        runIdleDecayProbe(label: "tables24", base: Self.longMarkdown(targetUTF16: 24_000))
        // 对照 G:夹具二分——纯表格 vs 纯代码块,锁定永动动画的宿主。
        var tableOnly = ""
        var codeOnly = ""
        for index in 1...20 {
            tableOnly += """

            # 表 \(index)

            | 层 | 职责 | 所有者 | 复杂度 |
            |----|------|--------|--------|
            | L1 | 缓冲与节流机制描述 | 协调器 | O(delta) |
            | L2 | 增量解析容错说明 | 控制器 | O(尾块) |
            | L3 | 滚动跟随三态机 | 列表 | O(1) |

            """
            codeOnly += """

            # 段 \(index)

            ```swift
            func follow(_ geometry: Geometry) {
                guard mode == .followingBottom else { return }
                anchor(.bottom)
            }
            ```

            """
        }
        runIdleDecayProbe(label: "tableOnly", base: tableOnly)
        runIdleDecayProbe(label: "codeOnly", base: codeOnly)
        // 对照 H:裸挂 ChatAssistantMarkdownView(无气泡壳=无思考卡 symbolEffect
        // 等常驻指示动画)——若烧钱归零,定罪「气泡级永动动画 × 大显示列表」。
        runIdleDecayProbe(
            label: "tables24-bareMarkdown",
            base: Self.longMarkdown(targetUTF16: 24_000),
            bareMarkdown: true
        )
        print(String(
            format: "[PERF] endToEndMatrix tables24=%.1f prose24=%.1f tables6=%.1f prose6=%.1f tablesMono=%.1f tablesIdle=%.1f tablesIdleDone=%.1f proseIdle=%.1f bareIdle=%.1f (ms/窗口)",
            table24, prose24, table6, prose6, tableMono, tableIdle, tableIdleDone, proseIdle, bareIdle
        ))
        // 宽松回归门:当前基线约 76-82ms，保留 2× 以上机器余量，但必须拦住
        // config 失效时已复现的约 317ms 旧回归。
        XCTAssertLessThan(table24, 200)
    }

    func testPerf_growingSingleTableRows() {
        var table = """
        | 序号 | 流式表格内容 | 状态 |
        | --- | --- | --- |

        """
        for index in 0..<80 {
            table += "| \(index) | 已稳定的长表格内容，用于验证追加行不会重做无关主线程转换 | stable |\n"
        }

        let perRowMs = runStreamingHarness(
            label: "growingSingleTable",
            base: table,
            deltas: 20,
            deltaInterval: 0.35,
            chunk: { index in
                "| \(80 + index) | 新追加的流式表格行，列宽保持不变 | live |\n"
            }
        )

        XCTAssertLessThan(perRowMs, 200)
    }

    @discardableResult
    private func runStreamingHarness(
        label: String,
        base: String,
        deltas: Int,
        isGenerating: Bool = true,
        deltaInterval: TimeInterval = 0.03,
        chunk: (Int) -> String = { _ in
            "接着上一句继续补充:机制层的所有权在任一时刻只能有一个写入者,"
        }
    ) -> Double {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatStreamingPerfBaselineTests-\(UUID().uuidString)", isDirectory: true)
        let workspaceStore = IOSWorkspaceStore(baseDirectory: tempDir)

        let model = StreamModel()
        model.markdown = base

        let host = UIHostingController(
            rootView: StreamingBubbleHarness(
                model: model,
                workspaceStore: workspaceStore,
                isGenerating: isGenerating
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

        let effectiveWindows = max(deltas, 20)
        let start = Self.mainThreadCPUNanos()
        if deltas > 0 {
            for index in 0..<deltas {
                model.markdown += chunk(index)
                pump(seconds: deltaInterval)
            }
        } else {
            // 静置对照:同样的 30ms 窗口节奏,但不喂任何 delta。
            for _ in 0..<effectiveWindows {
                pump(seconds: 0.03)
            }
        }
        // 收尾泵:让最后一次节流解析落地。
        pump(seconds: 0.6)
        let elapsed = Self.mainThreadCPUNanos() - start

        let perDeltaMs = Double(elapsed) / Double(effectiveWindows) / 1_000_000
        print(String(
            format: "[PERF] endToEnd[%@] deltas=%d mainThreadTotal=%.0fms per-delta=%.2fms",
            label, deltas, Double(elapsed) / 1_000_000, perDeltaMs
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

    private struct BareMarkdownHarness: View {
        @ObservedObject var model: StreamModel

        var body: some View {
            ScrollView {
                ChatAssistantMarkdownView(
                    markdown: model.markdown,
                    isStreaming: true,
                    hasEverStreamed: true,
                    liveRenderingEnabled: true
                )
                .padding(.horizontal, ChatLayout.contentHorizontalInset)
            }
            .frame(width: 393, height: 852)
        }
    }

    private func runIdleDecayProbe(label: String, base: String, bareMarkdown: Bool = false) {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatStreamingPerfDecay-\(UUID().uuidString)", isDirectory: true)
        let workspaceStore = IOSWorkspaceStore(baseDirectory: tempDir)
        let model = StreamModel()
        model.markdown = base
        let host: UIHostingController<AnyView>
        if bareMarkdown {
            host = UIHostingController(rootView: AnyView(BareMarkdownHarness(model: model)))
        } else {
            host = UIHostingController(rootView: AnyView(StreamingBubbleHarness(
                model: model,
                workspaceStore: workspaceStore,
                isGenerating: true
            )))
        }
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
        var readings: [String] = []
        for second in 1...8 {
            let start = Self.mainThreadCPUNanos()
            pump(seconds: 1.0)
            let cpuMs = Double(Self.mainThreadCPUNanos() - start) / 1_000_000
            readings.append(String(format: "t%d=%.0f", second, cpuMs))
        }
        print("[PERF] idleDecay[\(label)] \(readings.joined(separator: " ")) (ms CPU/秒)")
        window.isHidden = true
        window.rootViewController = nil
    }

    /// 采样器专用:静置持有 20s,供外部 `sample` 命令抓主线程热栈。
    /// 默认跳过,需 TEST_RUNNER_AA_PERF_HOLD=1。
    func testPerf_idleProfileHold() throws {
        guard ProcessInfo.processInfo.environment["AA_PERF_HOLD"] == "1" else {
            throw XCTSkip("仅采样时手动开启")
        }
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatStreamingPerfHold-\(UUID().uuidString)", isDirectory: true)
        let workspaceStore = IOSWorkspaceStore(baseDirectory: tempDir)
        let model = StreamModel()
        model.markdown = Self.longMarkdown(targetUTF16: 24_000)
        let host = UIHostingController(
            rootView: StreamingBubbleHarness(model: model, workspaceStore: workspaceStore, isGenerating: true)
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
        print("[PERF] idle-hold begin pid=\(ProcessInfo.processInfo.processIdentifier)")
        // 预热后持续喂 delta 20s,供采样器抓「流式更新期」的主线程热栈。
        pump(seconds: 3)
        let chunk = "接着上一句继续补充:机制层的所有权在任一时刻只能有一个写入者,"
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            model.markdown += chunk
            pump(seconds: 0.03)
        }
        print("[PERF] idle-hold end")
    }
}
