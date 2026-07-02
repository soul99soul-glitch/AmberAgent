import XCTest
import Shared
@testable import iosApp

/// 流式回放夹具(M0)。
///
/// 把一段 markdown(标题 + 表格 + 列表)切成约 60 条"新增后缀"增量,以 30ms
/// 间隔依次喂给真实的 `ChatCollectionViewController`(与生产环境完全相同的
/// 控制器 / `CollectionViewChatLayout` / diffable data source 路径,只是不
/// 经过网络),模拟一次真实助手消息的流式渲染,然后在回放过程中采样两类
/// 关键几何指标:
///
/// 1. 可见 cell 是否发生行重叠(相邻行 frame 在竖直方向压线 / 交叠);
/// 2. `contentOffset` 轨迹在跟随态下是否出现异常的大幅向上回跳。
///
/// 这不是一次性回归测试,而是一套可复用的回放夹具:任何人发现真实设备上
/// 的流式渲染异常,只要打开 `chat.stream.recording.enabled`(DEBUG 构建,
/// 见 `ChatStreamRecorder`)录制一次真实会话,把
/// `Library/Caches/stream-recordings/<runId>.jsonl` 拷贝进
/// `realRecordingsDir`,`testRecordedStreamReplays()` 就会用真实的流式节奏
/// 重放并接受同样的硬断言检查。
@MainActor
final class ChatStreamReplayTests: XCTestCase {

    private static let screenSize = CGSize(width: 393, height: 852)

    /// `ChatStreamRecorder` 写在设备/模拟器的 `Library/Caches/stream-recordings/`
    /// 下;这里是人工(或未来自动化脚本)把真实录制拷贝进来供本测试回放的
    /// 约定位置,与 `ChatMessageWidthOverflowTests` 的 scratchpad 路径同一个目录树。
    private static let realRecordingsDir =
        "/private/tmp/claude-501/-Users-mi-Downloads-AI-AmberAgent-iOS/75dbf062-21b6-4935-a9b8-7fc5177e47eb/scratchpad/stream-recordings"

    // MARK: - Tests

    /// 内嵌合成录制:任何机器都能跑,不依赖真机录制是否存在。
    func testSyntheticStreamReplayKeepsLayoutCoherent() {
        let deltas = Self.chunkIntoDeltas(Self.embeddedFixture, chunkCount: 60)
        XCTAssertGreaterThan(deltas.count, 30, "合成夹具切片数量过少,回放粒度不足以模拟真实流式节奏")
        let frames = Self.cumulativeFrames(fromDeltas: deltas)
        let metrics = runReplay(cumulativeFrames: frames, label: "synthetic")
        assertLayoutCoherent(metrics, label: "synthetic")
    }

    /// 真实录制回放:目录下没有 .jsonl 时跳过(M0 阶段尚无真机录制属预期)。
    func testRecordedStreamReplays() throws {
        guard let frames = Self.loadRealRecordingCumulativeFrames(), !frames.isEmpty else {
            throw XCTSkip("未发现真实录制(\(Self.realRecordingsDir) 下无 .jsonl),跳过真实回放。")
        }
        let metrics = runReplay(cumulativeFrames: frames, label: "recorded")
        assertLayoutCoherent(metrics, label: "recorded")
    }

    // MARK: - Replay engine

    private struct ReplayMetrics {
        var overlapViolations: [String] = []
        var backJumpViolations: [String] = []
        var offsetTrack: [CGFloat] = []
        var frameIntervalsMs: [Double] = []
        var finalDistanceToBottom: CGFloat = .greatestFiniteMagnitude
        var stepCount: Int = 0
    }

    /// 构造一个真实的 `ChatCollectionViewController`,挂进带 windowScene 的
    /// UIWindow(裸 `UIWindow(frame:)` 在测试进程里不会真正 composite 内容,
    /// 参见 `ChatMessageWidthOverflowTests` 的同款说明),然后逐帧喂入
    /// `cumulativeFrames` 里的累计文本,每步之间泵 30ms run loop,采样几何。
    @MainActor
    private func runReplay(cumulativeFrames: [String], label: String) -> ReplayMetrics {
        var metrics = ReplayMetrics()
        metrics.stepCount = cumulativeFrames.count

        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "ChatStreamReplayTests-\(label)-\(UUID().uuidString)")!
        )
        let displaySetting = sharedSettings.displaySetting
        let generativeUiSetting = sharedSettings.agentRuntime.generativeUi

        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("ChatStreamReplayTests-\(label)-\(UUID().uuidString)", isDirectory: true)
        )

        var messages: [UIMessage] = []
        let controller = ChatCollectionViewController()
        controller.messagesProvider = { messages }
        controller.variantInfoProvider = { _ in nil }
        controller.onAction = { _ in }
        controller.onViewportStateChange = { _ in }

        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: .zero)
        }
        window.frame = CGRect(origin: .zero, size: Self.screenSize)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        window.layoutIfNeeded()

        guard let collectionView = Self.findCollectionView(in: controller.view) else {
            XCTFail("[\(label)] 未能在视图树中找到 UICollectionView,回放无法继续")
            return metrics
        }

        var revision = 0
        func applyUpdate(reason: ChatMessageUpdateReason, isGenerationActive: Bool) {
            revision += 1
            controller.update(
                signal: ChatMessageUpdateSignal(revision: revision, reason: reason),
                configurationIssue: nil,
                isGenerationActive: isGenerationActive,
                isLoading: false,
                isRecognizingImages: false,
                contextCompactState: .idle,
                followGeneration: true,
                displaySetting: displaySetting,
                generativeUiSetting: generativeUiSetting,
                reasoningLevelLabel: nil,
                workspaceStore: workspaceStore,
                scrollToBottomTrigger: 0
            )
        }

        func pump(_ seconds: TimeInterval) {
            let deadline = Date().addingTimeInterval(seconds)
            repeat {
                RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.005))
            } while Date() < deadline
        }

        let userMessageId = KotlinUuid.companion.random()
        let assistantMessageId = KotlinUuid.companion.random()
        let userMessage = UIMessage(
            id: userMessageId,
            role: MessageRole.user,
            parts: [UIMessagePart.Text(text: "请给我一份创作规划", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )

        func makeAssistantMessage(text: String, finished: Bool) -> UIMessage {
            UIMessage(
                id: assistantMessageId,
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: text, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: finished ? chatNowLocalDateTime() : nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }

        // 建立会话:先以空列表触发 initialLoad,再追加用户消息(与真实
        // ChatView 在进入会话/发送消息时的两次驱动一致)。
        messages = []
        applyUpdate(reason: .initialLoad, isGenerationActive: false)
        pump(0.03)

        messages = [userMessage]
        applyUpdate(reason: .userAppend, isGenerationActive: false)
        pump(0.03)

        var lastOffsetY: CGFloat?
        let displayLink = DisplayLinkSampler()
        displayLink.start()

        for (index, text) in cumulativeFrames.enumerated() {
            messages = [userMessage, makeAssistantMessage(text: text, finished: false)]
            applyUpdate(reason: .streamDelta, isGenerationActive: true)
            pump(0.030)

            collectionView.layoutIfNeeded()

            let cells = collectionView.visibleCells.sorted { $0.frame.minY < $1.frame.minY }
            if cells.count >= 2 {
                for i in 1..<cells.count {
                    let prev = cells[i - 1].frame
                    let next = cells[i].frame
                    if next.minY < prev.maxY - 1 {
                        metrics.overlapViolations.append(
                            "step=\(index) prevFrame=\(prev) nextFrame=\(next)"
                        )
                    }
                }
            }

            let offsetY = collectionView.contentOffset.y
            metrics.offsetTrack.append(offsetY)
            if let last = lastOffsetY {
                let delta = offsetY - last
                if delta < -200 {
                    metrics.backJumpViolations.append(
                        "step=\(index) offsetY: \(last) -> \(offsetY) (Δ=\(delta))"
                    )
                }
            }
            lastOffsetY = offsetY
        }

        displayLink.stop()
        metrics.frameIntervalsMs = displayLink.intervalsMs

        // 终态锚定:再驱动一次 generationCompleted,泵 0.5s,校验收敛到底部。
        messages = [userMessage, makeAssistantMessage(text: cumulativeFrames.last ?? "", finished: true)]
        applyUpdate(reason: .generationCompleted, isGenerationActive: false)
        pump(0.5)
        collectionView.layoutIfNeeded()

        let visibleHeight = collectionView.bounds.height
            - collectionView.adjustedContentInset.top
            - collectionView.adjustedContentInset.bottom
        let visibleMaxY = collectionView.contentOffset.y
            + collectionView.adjustedContentInset.top
            + visibleHeight
        metrics.finalDistanceToBottom = collectionView.contentSize.height - visibleMaxY

        window.isHidden = true
        window.rootViewController = nil

        return metrics
    }

    // MARK: - Assertions

    private func assertLayoutCoherent(_ metrics: ReplayMetrics, label: String) {
        let sortedIntervals = metrics.frameIntervalsMs.sorted()
        let maxInterval = sortedIntervals.last ?? 0
        let p95Interval: Double
        if sortedIntervals.isEmpty {
            p95Interval = 0
        } else {
            let rank = Int((Double(sortedIntervals.count) * 0.95).rounded(.up))
            let p95Index = min(sortedIntervals.count - 1, max(0, rank - 1))
            p95Interval = sortedIntervals[p95Index]
        }
        print(String(
            format: "REPLAY-METRIC: frameMax=%.2f frameP95=%.2f steps=%d",
            maxInterval, p95Interval, metrics.stepCount
        ))

        if !metrics.overlapViolations.isEmpty {
            print("OVERLAP[\(label)]: \(metrics.overlapViolations.count) violation(s)")
            for violation in metrics.overlapViolations.prefix(20) {
                print("OVERLAP[\(label)]: \(violation)")
            }
        } else {
            print("OVERLAP[\(label)]: none")
        }

        if !metrics.backJumpViolations.isEmpty {
            print("BACKJUMP[\(label)]: \(metrics.backJumpViolations.count) violation(s)")
            for violation in metrics.backJumpViolations.prefix(20) {
                print("BACKJUMP[\(label)]: \(violation)")
            }
        } else {
            print("BACKJUMP[\(label)]: none")
        }

        print("OFFSET-TRACK[\(label)]: \(metrics.offsetTrack.map { Int($0) })")
        print("FINAL-DISTANCE[\(label)]: \(metrics.finalDistanceToBottom)")

        XCTAssertTrue(
            metrics.overlapViolations.isEmpty,
            "[\(label)] 回放期间检测到 \(metrics.overlapViolations.count) 处行重叠(样例:"
                + "\(metrics.overlapViolations.prefix(3).joined(separator: "; ")))"
        )
        XCTAssertTrue(
            metrics.backJumpViolations.isEmpty,
            "[\(label)] 回放期间检测到 \(metrics.backJumpViolations.count) 次跟随态下 >200pt 向上回跳"
                + "(样例:\(metrics.backJumpViolations.prefix(3).joined(separator: "; ")))"
        )
        XCTAssertLessThanOrEqual(
            metrics.finalDistanceToBottom, 2,
            "[\(label)] 回放结束后距底 \(metrics.finalDistanceToBottom)pt,超过 2pt 收敛阈值"
        )
    }

    // MARK: - View tree helpers

    private static func findCollectionView(in view: UIView) -> UICollectionView? {
        if let collectionView = view as? UICollectionView { return collectionView }
        for subview in view.subviews {
            if let found = findCollectionView(in: subview) { return found }
        }
        return nil
    }

    // MARK: - Fixture chunking

    /// 把 `text` 按 Character(而非 UTF-8 byte,避免切断多字节/CJK 字符)
    /// 均分成最多 `chunkCount` 段"新增后缀"增量。
    private static func chunkIntoDeltas(_ text: String, chunkCount: Int) -> [String] {
        let characters = Array(text)
        guard !characters.isEmpty, chunkCount > 0 else { return [] }
        let base = characters.count / chunkCount
        let remainder = characters.count % chunkCount
        var deltas: [String] = []
        var index = 0
        for i in 0..<chunkCount {
            let size = base + (i < remainder ? 1 : 0)
            guard size > 0 else { continue }
            let end = min(characters.count, index + size)
            guard end > index else { continue }
            deltas.append(String(characters[index..<end]))
            index = end
        }
        return deltas
    }

    private static func cumulativeFrames(fromDeltas deltas: [String]) -> [String] {
        var cumulative = ""
        return deltas.map { delta in
            cumulative += delta
            return cumulative
        }
    }

    // MARK: - Real recording loader

    /// 从 `realRecordingsDir` 里挑第一个 `.jsonl`,把它的 `d`/`reset` 事件流
    /// 折算成累计文本序列(与 `chunkIntoDeltas`/`cumulativeFrames` 的合成
    /// 路径产出同一种形状),供 `runReplay` 统一消费。`meta` 行被忽略。
    private static func loadRealRecordingCumulativeFrames() -> [String]? {
        let fileManager = FileManager.default
        guard let entries = try? fileManager.contentsOfDirectory(atPath: realRecordingsDir) else { return nil }
        let jsonlFiles = entries.filter { $0.hasSuffix(".jsonl") }.sorted()
        guard let first = jsonlFiles.first else { return nil }
        let path = (realRecordingsDir as NSString).appendingPathComponent(first)
        guard let data = fileManager.contents(atPath: path),
              let text = String(data: data, encoding: .utf8) else { return nil }

        var cumulative = ""
        var frames: [String] = []
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: true) {
            guard let lineData = String(rawLine).data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: lineData) as? [String: Any] else { continue }
            if let delta = obj["d"] as? String {
                cumulative += delta
                frames.append(cumulative)
            } else if let reset = obj["reset"] as? String {
                cumulative = reset
                frames.append(cumulative)
            }
            // "meta" 行(录制起点元信息)与本回放无关,忽略。
        }
        return frames
    }

    // MARK: - Embedded synthetic fixture

    /// 合成录制素材:标题 + 表格 + 有序/无序列表,足够触发多行、自撑高度的
    /// UICollectionView cell 在流式增长过程中反复重新布局——这正是真实场景
    /// 里最容易暴露"行重叠 / 回跳"问题的内容形状。
    private static let embeddedFixture = """
    # 流式回放测试固定素材 · 创作规划草稿

    下面是一份**创作规划**草稿,用于流式回放夹具的合成 markdown 素材,包含
    标题、表格与列表,足以在 60 步左右的增量喂入过程中反复触发自撑高度行
    的重新布局。

    ## 一、核心设定速查表

    | 要素 | 内容 |
    |------|------|
    | 名称 | 示例条目一号 |
    | 类型 | 示例条目二号 |
    | 来源 | 示例条目三号 |
    | 用途 | 示例条目四号 |
    | 备注 | 示例条目五号,内容稍长一些用来撑开表格列宽 |

    ## 二、章节大纲

    1. 开篇引入,交代背景与核心冲突
    2. 主角登场,展示核心能力与限制
    3. 第一次转折,揭示隐藏设定的一角
    4. 中段发展,矛盾逐步升级、盟友登场
    5. 高潮对决,能力全面爆发、代价显现
    6. 结局收束,埋下后续系列的伏笔

    ## 三、写作要点清单

    - 保持节奏紧凑,避免大段注水描写
    - 每一章都设置至少一个小钩子
    - 世界观设定分批揭示,不要一次性倾倒给读者
    - 主角性格前后要有可信的弧光变化
    - 关键道具 / 能力需要提前埋下伏笔,不要临时起意

    ### 补充说明

    以上仅为草稿框架,后续可以根据具体设定继续扩展每个章节的细纲,也可以
    针对每个角色单独列出小传,方便后续写作时保持人设前后一致、不跑偏。
    """

    /// 采样 CADisplayLink 帧间隔,只用于打印基线数据,不参与硬断言。
    private final class DisplayLinkSampler: NSObject {
        private var displayLink: CADisplayLink?
        private var lastTimestamp: CFTimeInterval?
        private(set) var intervalsMs: [Double] = []

        func start() {
            let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
            link.add(to: .main, forMode: .common)
            displayLink = link
        }

        @objc private func tick(_ link: CADisplayLink) {
            if let last = lastTimestamp {
                intervalsMs.append((link.timestamp - last) * 1000)
            }
            lastTimestamp = link.timestamp
        }

        func stop() {
            displayLink?.invalidate()
            displayLink = nil
        }
    }
}
