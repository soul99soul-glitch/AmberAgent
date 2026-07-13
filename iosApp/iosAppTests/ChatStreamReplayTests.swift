import XCTest
import UIKit
import SwiftUI
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
/// 见 `ChatStreamRecorder`)录制一次真实 UI 发布序列,把
/// `Library/Caches/stream-recordings/<runId>.jsonl` 拷贝进
/// `realRecordingsDir`,`testRecordedStreamReplays()` 就会用真实 UI 发布节奏
/// 重放并接受同样的硬断言检查。
@MainActor
final class ChatStreamReplayTests: XCTestCase {

    private static let screenSize = CGSize(width: 393, height: 852)
    private var previousScrollArbiterFlag: Any?

    /// `ChatStreamRecorder` 写在设备/模拟器的 `Library/Caches/stream-recordings/`
    /// 下;这里是人工(或未来自动化脚本)把真实录制拷贝进来供本测试回放的
    /// 约定位置,与 `ChatMessageWidthOverflowTests` 的 scratchpad 路径同一个目录树。
    private static let realRecordingsDir =
        "/private/tmp/claude-501/-Users-mi-Downloads-AI-AmberAgent-iOS/75dbf062-21b6-4935-a9b8-7fc5177e47eb/scratchpad/stream-recordings"

    override func setUp() {
        super.setUp()
        previousScrollArbiterFlag = UserDefaults.standard.object(forKey: ChatScrollArbiterFeatureFlags.userDefaultsKey)
        UserDefaults.standard.set(true, forKey: ChatScrollArbiterFeatureFlags.userDefaultsKey)
    }

    override func tearDown() {
        if let previousScrollArbiterFlag {
            UserDefaults.standard.set(previousScrollArbiterFlag, forKey: ChatScrollArbiterFeatureFlags.userDefaultsKey)
        } else {
            UserDefaults.standard.removeObject(forKey: ChatScrollArbiterFeatureFlags.userDefaultsKey)
        }
        previousScrollArbiterFlag = nil
        super.tearDown()
    }

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

    func testArbiterStreamingDragTakeoverStopsAutoFollow() {
        let frames = Self.cumulativeFrames(fromDeltas: Self.chunkIntoDeltas(Self.scrollableEmbeddedFixture, chunkCount: 60))
        let takeoverStep = max(10, frames.count * 3 / 4)
        let metrics = runReplay(
            cumulativeFrames: frames,
            label: "arbiter-drag-takeover",
            completeGeneration: false,
            interaction: .dragTakeover(step: takeoverStep),
            historyTurnCount: 3
        )

        XCTAssertNotNil(metrics.takeoverDistanceToBottom, "拖拽抢占步骤未执行")
        XCTAssertGreaterThan(
            metrics.finalDistanceToBottom,
            ChatLayout.bottomStickThreshold,
            "用户上滑抢占后仍被程序性滚动拉回底部"
        )
        XCTAssertGreaterThan(
            metrics.minimumDistanceToBottomAfterTakeover,
            ChatLayout.bottomStickThreshold,
            "拖拽抢占后的后续流式 delta 不应重新贴底"
        )
    }

    func testArbiterKeyboardSettleDoesNotOverrideDragTakeover() {
        let frames = Self.cumulativeFrames(fromDeltas: Self.chunkIntoDeltas(Self.scrollableEmbeddedFixture, chunkCount: 60))
        let takeoverStep = max(10, frames.count * 3 / 4)
        let metrics = runReplay(
            cumulativeFrames: frames,
            label: "arbiter-keyboard-drag-takeover",
            completeGeneration: false,
            interaction: .keyboardSettleThenDrag(step: takeoverStep),
            historyTurnCount: 3
        )

        XCTAssertNotNil(metrics.takeoverDistanceToBottom, "键盘 settle race 的拖拽抢占步骤未执行")
        XCTAssertGreaterThan(
            metrics.finalDistanceToBottom,
            ChatLayout.bottomStickThreshold,
            "键盘延迟 settle 不应在用户释放后把列表重新拉回底部"
        )
    }

    func testArbiterGenerationCompletionSnapsToBottom() {
        let frames = Self.cumulativeFrames(fromDeltas: Self.chunkIntoDeltas(Self.scrollableEmbeddedFixture, chunkCount: 60))
        let metrics = runReplay(
            cumulativeFrames: frames,
            label: "arbiter-completion-snap",
            preCompletionDistanceFromBottom: 180,
            historyTurnCount: 3
        )
        XCTAssertGreaterThan(
            metrics.preCompletionDistanceToBottom ?? 0,
            ChatLayout.bottomStickThreshold,
            "测试前置条件失效: completion 前必须先离开底部,否则无法证明收尾 snap 生效"
        )
        XCTAssertLessThanOrEqual(
            metrics.finalDistanceToBottom,
            2,
            "generationCompleted 后仲裁者应收尾 snap 到底部"
        )
    }

    func testArbiterConversationEntryAnchorsToBottom() {
        let text = Self.embeddedFixture + "\n\n" + Self.embeddedFixture
        let distance = runLoadedConversationAnchorReplay(assistantText: text, label: "arbiter-entry-anchor")
        XCTAssertLessThanOrEqual(
            distance,
            2,
            "进入长会话后仲裁者应收敛锚定到底部"
        )
    }

    /// 纯诊断插桩(零断言、零生产代码改动):症状 4/5(生成结束后最新内容在画面外、
    /// 划不上来)的头号嫌疑是 freeze/unfreeze 渲染态链路——didEndDisplaying →
    /// renderStateStore.freeze(messageID:latestText:) 存下离屏时刻的冻结快照;流式继续
    /// 时真实文本增长而快照停留在离屏时刻;回显时按冻结短内容渲染+测量;解冻依赖
    /// willDisplay 里 isLastAssistantItem 分支的 async refreshLastAssistantRenderState()。
    ///
    /// 实验组:回放至约 1/3 拖离底部(尾行完全离屏),流式喂满剩余帧,触发
    /// generationCompleted,再模拟滚回底部;三个采样点(回底前/回底后立即/追加 pump 后)
    /// dump [FREEZE-DIAG] 指标。对照组见 `testDiagnoseAlwaysAtBottomFreezeControl`。
    func testDiagnoseOffscreenTailFreezeAtGenerationEnd() {
        let frames = Self.cumulativeFrames(fromDeltas: Self.chunkIntoDeltas(Self.embeddedFixture, chunkCount: 60))
        runFreezeDiagnosisReplay(
            cumulativeFrames: frames,
            label: "freeze-exp",
            dragStep: max(10, frames.count / 3),
            dragDistance: 1400
        )
    }

    /// 对照组:同样的回放与 generationCompleted → 回底流程,但从不拖离底部,
    /// dump 同一组 [FREEZE-DIAG] 指标供与实验组逐项对比。
    func testDiagnoseAlwaysAtBottomFreezeControl() {
        let frames = Self.cumulativeFrames(fromDeltas: Self.chunkIntoDeltas(Self.embeddedFixture, chunkCount: 60))
        runFreezeDiagnosisReplay(
            cumulativeFrames: frames,
            label: "freeze-ctrl",
            dragStep: nil,
            dragDistance: 0
        )
    }

    /// 硬断言回归门:曾经流式过的尾行在"generationCompleted 贴底 → 滚离
    /// 1400pt(尾行完全离屏,cell 被回收) → 滚回底部(cell 重新实现)"的往返中,
    /// 高度与 contentSize 都不应该再发生排版跳变。
    ///
    /// 回归点:视图层必须服从 projection 层的 hasEverStreamed 记忆,回收后的可见
    /// 完成态继续使用 SwiftStreamingMarkdown;同时异步 parse 完成后要触发 cell
    /// self-sizing 失效,不能把首帧/占位高度钉进 ChatLayout。
    func testStreamedRowSurvivesCellRecycleRoundtrip() {
        let frames = Self.cumulativeFrames(fromDeltas: Self.chunkIntoDeltas(Self.embeddedFixture, chunkCount: 60))
        let measurement = runCellRecycleRoundtripMeasurement(cumulativeFrames: frames, label: "recycle-roundtrip")

        print(
            "[RECYCLE-ROUNDTRIP] H0=\(measurement.tailHeightBefore) H1=\(measurement.tailHeightAfter) "
                + "heightDelta=\(measurement.tailHeightAfter - measurement.tailHeightBefore) "
                + "S0=\(measurement.contentSizeBefore) S1=\(measurement.contentSizeAfter) "
                + "contentSizeDelta=\(measurement.contentSizeAfter - measurement.contentSizeBefore)"
        )

        XCTAssertLessThan(
            abs(measurement.tailHeightAfter - measurement.tailHeightBefore),
            2,
            "尾行滚离→滚回后高度不应跳变: H0=\(measurement.tailHeightBefore) H1=\(measurement.tailHeightAfter)"
        )
        XCTAssertLessThan(
            abs(measurement.contentSizeAfter - measurement.contentSizeBefore),
            4,
            "滚离→滚回后 contentSize 不应跳变: S0=\(measurement.contentSizeBefore) S1=\(measurement.contentSizeAfter)"
        )
    }

    /// 纯诊断(零断言):分体裁对照两个 assistant markdown 渲染器的高度。
    ///
    /// 背景:同一 embeddedFixture 文本,流式渲染器(SwiftStreamingMarkdown,完成态可见行
    /// latch 保持)排出 1316.67pt,同步渲染器(AmberMarkdownView,cell 重建后走此路径)排出
    /// 778.33pt,差 538pt(70%)。本测试把夹具按体裁拆解逐段测量,定位差值集中在哪种体裁;
    /// 并用"行数/段数梯度法"判定矮的一方是内容截断还是排版更紧凑、高的一方是否在灌空气
    /// (对内容增量的高度增长率是判断内容完整性的渲染器无关证据)。
    ///
    /// 两条渲染路径都经由生产组件 ChatAssistantMarkdownView:
    /// - streaming=true → SwiftStreamingMarkdown.MarkdownView(animate=true,与完成态可见行一致)
    /// - streaming=false(新 view 实例 latch=false)→ AmberMarkdownView
    func testRendererHeightComparisonByGenre() {
        let heading = "# 流式回放测试固定素材 · 创作规划草稿"
        let paragraph = "下面是一份**创作规划**草稿,用于流式回放夹具的合成 markdown 素材,包含\n标题、表格与列表,足以在 60 步左右的增量喂入过程中反复触发自撑高度行\n的重新布局。"
        let tableRows = [
            "| 名称 | 示例条目一号 |",
            "| 类型 | 示例条目二号 |",
            "| 来源 | 示例条目三号 |",
            "| 用途 | 示例条目四号 |",
            "| 备注 | 示例条目五号,内容稍长一些用来撑开表格列宽 |"
        ]
        func table(rows: Int) -> String {
            (["| 要素 | 内容 |", "|------|------|"] + tableRows.prefix(rows)).joined(separator: "\n")
        }
        let listItems = [
            "1. 开篇引入,交代背景与核心冲突",
            "2. 主角登场,展示核心能力与限制",
            "3. 第一次转折,揭示隐藏设定的一角",
            "4. 中段发展,矛盾逐步升级、盟友登场",
            "5. 高潮对决,能力全面爆发、代价显现",
            "6. 结局收束,埋下后续系列的伏笔"
        ]
        func orderedList(items: Int) -> String {
            listItems.prefix(items).joined(separator: "\n")
        }
        // 与 embeddedFixture 的无序列表段一致;单独成体裁以量化 bullet 结构差异
        // (vendor 为 Image bullet + 22pt 固定缩进,紧凑基准为 Text("•") + spacing 8)。
        let unorderedList = [
            "- 保持节奏紧凑,避免大段注水描写",
            "- 每一章都设置至少一个小钩子",
            "- 世界观设定分批揭示,不要一次性倾倒给读者",
            "- 主角性格前后要有可信的弧光变化",
            "- 关键道具 / 能力需要提前埋下伏笔,不要临时起意"
        ].joined(separator: "\n")

        let fragments: [(genre: String, markdown: String)] = [
            ("baseline-one-line", "单行纯文本基线。"),
            // 块间距探针:两个单行段落,与 baseline 的高度差即"段间 spacing + 一行"。
            ("two-one-line-paragraphs", "甲段基线。\n\n乙段基线。"),
            ("heading-h1", heading),
            ("heading-h2", "## 一、核心设定速查表"),
            ("paragraph-x1", paragraph),
            ("paragraph-x3", Array(repeating: paragraph, count: 3).joined(separator: "\n\n")),
            ("table-2rows", table(rows: 2)),
            ("table-5rows", table(rows: 5)),
            ("ordered-list-2", orderedList(items: 2)),
            ("ordered-list-6", orderedList(items: 6)),
            ("unordered-list-5", unorderedList),
            ("full-fixture", Self.embeddedFixture)
        ]

        // 生产同构主表:每个体裁经真实 ChatCollectionViewController 渲染成一条
        // assistant 行,量 cell 实际 self-sizing 高度。高度含固定 chrome(agent 名等),
        // 对两渲染器一致,delta 只反映 markdown 渲染差异——它就是尾行"流式→回收重建"
        // 高度跳变(testStreamedRowSurvivesCellRecycleRoundtrip)的逐体裁分解。
        for fragment in fragments {
            let stream = measureAssistantMarkdownRender(markdown: fragment.markdown, streaming: true)
            let sync = measureAssistantMarkdownRender(markdown: fragment.markdown, streaming: false)
            print(
                "[RENDERER-DIFF] genre=\(fragment.genre) "
                    + "streamH=\(String(format: "%.1f", stream.height)) "
                    + "syncH=\(String(format: "%.1f", sync.height)) "
                    + "delta=\(String(format: "%.1f", stream.height - sync.height)) "
                    + "streamTextLen=\(stream.accessibleTextLength) syncTextLen=\(sync.accessibleTextLength) "
                    + "streamTextNodes=\(stream.textNodeCount) syncTextNodes=\(sync.textNodeCount)"
            )
        }
    }

    /// 单片段单渲染器测量:与生产完全同构——真实 ChatCollectionViewController +
    /// 真实 renderModel/MessageBubbleView/UIHostingConfiguration 链路,单条 assistant
    /// 消息成行。streaming=true 时消息未完成 + isGenerationActive(尾行 isStreaming
    /// =true → SwiftStreamingMarkdown);streaming=false 时消息已完成(latch=false →
    /// AmberMarkdownView)。
    ///
    /// 测高口径 = cell 实际 self-sizing 高度(layoutAttributesForItem)。经 roundtrip
    /// harness 实证,它与 UIHostingConfiguration content view 的 sizeThatFits 一致
    /// (SwiftUI 真实布局高度);而 systemLayoutSizeFitting(hostingFitH)对纯 SwiftUI
    /// 内容会高估 ~20%,对 UITextView 内容才准,不能用作两渲染器的对比口径。
    /// 高度含固定 chrome(agent 名等),对两渲染器一致,不影响 delta。
    @MainActor
    private func measureAssistantMarkdownRender(
        markdown: String,
        streaming: Bool
    ) -> (height: CGFloat, accessibleTextLength: Int, textNodeCount: Int) {
        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "RendererDiff-\(UUID().uuidString)")!
        )
        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("RendererDiff-\(UUID().uuidString)", isDirectory: true)
        )

        let assistantMessageId = KotlinUuid.companion.random()
        func makeAssistantMessage(finished: Bool) -> UIMessage {
            UIMessage(
                id: assistantMessageId,
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: markdown, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: finished ? chatNowLocalDateTime() : nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }
        var messages: [UIMessage] = [makeAssistantMessage(finished: !streaming)]

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
                displaySetting: sharedSettings.displaySetting,
                generativeUiSetting: sharedSettings.agentRuntime.generativeUi,
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

        applyUpdate(reason: .initialLoad, isGenerationActive: streaming)
        pump(0.1)

        if streaming {
            // 与 roundtrip 的 H0 状态同构:先流式(latch 置位 → SwiftStreamingMarkdown),
            // 再 generationCompleted(生成态 chrome 消失,渲染器因 latch 保持流式)。
            applyUpdate(reason: .streamDelta, isGenerationActive: true)
            pump(0.1)
            messages = [makeAssistantMessage(finished: true)]
            applyUpdate(reason: .generationCompleted, isGenerationActive: false)
            pump(0.1)
        }
        guard let collectionView = Self.findCollectionView(in: controller.view) else {
            XCTFail("RendererDiff 未找到 UICollectionView")
            return (-1, 0, 0)
        }

        let itemID = "message-\(String(describing: assistantMessageId))"
        func measuredCell() -> UICollectionViewCell? {
            collectionView.layoutIfNeeded()
            return collectionView.visibleCells.first { $0.accessibilityIdentifier == itemID }
        }

        func currentHeight() -> CGFloat {
            // 异步渲染器 parse 完成后不会自己触发 self-sizing 重新询问(真实流式
            // 场景由持续的 streamDelta reconfigure 驱动);这里显式 invalidate 让
            // preferred attributes 以当前 SwiftUI 内容重新计算。
            collectionView.collectionViewLayout.invalidateLayout()
            collectionView.setNeedsLayout()
            collectionView.layoutIfNeeded()
            return measuredCell()?.bounds.height ?? -1
        }

        // 保底先 pump:异步渲染器(SwiftStreamingMarkdown)的 .task parse 完成前,
        // empty document 也会给出一个稳定的非零高度,不能靠"两次采样一致"提前退出。
        pump(0.4)
        // 再 pump 到两次采样一致(最多 ~2.4s 防挂死)。
        var height = currentHeight()
        for _ in 0..<10 {
            pump(0.2)
            let next = currentHeight()
            if abs(next - height) < 0.5, next > 0 {
                height = next
                break
            }
            height = next
        }

        var textLength = 0
        var textNodes = 0
        var visited = Set<ObjectIdentifier>()
        func collectText(_ element: Any) {
            guard let object = element as? NSObject else { return }
            let id = ObjectIdentifier(object)
            guard !visited.contains(id) else { return }
            visited.insert(id)
            if let label = object as? UILabel, let text = label.text, !text.isEmpty {
                textLength += text.count
                textNodes += 1
            } else if let textView = object as? UITextView, !textView.text.isEmpty {
                textLength += textView.text.count
                textNodes += 1
            } else if let accessibilityLabel = object.accessibilityLabel, !accessibilityLabel.isEmpty {
                textLength += accessibilityLabel.count
                textNodes += 1
            }
            // SwiftUI 原生 Text 的可访问文本不在 UIView 层级里,而在惰性生成的
            // accessibilityElements 中,两条通道都要遍历。
            (object.accessibilityElements ?? []).forEach(collectText)
            if let view = object as? UIView {
                view.subviews.forEach(collectText)
            }
        }
        if let cell = measuredCell() {
            collectText(cell as Any)
        }

        window.isHidden = true
        window.rootViewController = nil
        return (ceil(height), textLength, textNodes)
    }

    func testTallUserMessageDoesNotPaintIntoAssistantRow() {
        let violations = runTallUserMessageOverlapReplay(label: "tall-user-then-thinking")
        if !violations.isEmpty {
            print("INK-OVERLAP[tall-user-then-thinking]: \(violations.count) violation(s)")
            for violation in violations.prefix(20) {
                print("INK-OVERLAP[tall-user-then-thinking]: \(violation)")
            }
        } else {
            print("INK-OVERLAP[tall-user-then-thinking]: none")
        }
        XCTAssertTrue(
            violations.isEmpty,
            "长 user 气泡不应把可绘制内容画出自身 cell 并侵入下一条 assistant/思考行: "
                + "\(violations.prefix(3).joined(separator: "; "))"
        )
    }

    func testTallUserMessageWithVariantsDoesNotPaintIntoAssistantRow() {
        let firstText = Array(
            repeating: "第一段用户设定很长,需要撑开气泡高度,不能让下一条 agent 思考卡片盖上来。",
            count: 24
        ).joined(separator: "\n")
        let secondText = Array(
            repeating: "第二段也是独立 text part,编辑分支和多段输入都必须走同一套高度保护。",
            count: 18
        ).joined(separator: "\n")
        let violations = runTallUserMessageOverlapReplay(
            label: "tall-user-variants",
            userParts: [
                UIMessagePart.Text(text: firstText, metadata: nil),
                UIMessagePart.Text(text: secondText, metadata: nil)
            ],
            userVariantInfo: IOSConversationStore.VariantInfo(variantCount: 3, selectedIndex: 1)
        )
        if !violations.isEmpty {
            print("INK-OVERLAP[tall-user-variants]: \(violations.count) violation(s)")
            for violation in violations.prefix(20) {
                print("INK-OVERLAP[tall-user-variants]: \(violation)")
            }
        } else {
            print("INK-OVERLAP[tall-user-variants]: none")
        }
        XCTAssertTrue(
            violations.isEmpty,
            "多 text part/variant user 气泡不应把可绘制内容画出自身 cell 并侵入下一条 assistant/思考行: "
                + "\(violations.prefix(3).joined(separator: "; "))"
        )
    }

    func testSingleParagraphVariantUserMessageDoesNotOverlapStreamingAssistant() {
        let prompt = "想写一本都市灵异小说，主角是九天应元雷声普化天尊转世，也就是道教的雷祖，对于妖魔鬼怪天生克制，大杀四方的爽文"
        let assistantText = """
        # 三、世界观架构
        ## 三界格局
        - 天庭：千年前天庭未知大劫陷入沉寂，大神沉睡或消失，雷部曾显赫一时，如今只剩零星符箓。
        - 人间：灵气复苏，妖魔鬼怪从缝隙中涌入都市。
        - 地府：鄷都失联，轮回紊乱，孤魂野鬼无处可去。

        ## 妖魔势力
        | 势力 | 首领 |
        | --- | --- |
        | 血月集团 | 千年僵尸王 |
        | 百鬼堂 | 九尾狐 |
        | 幽冥商会 | 未知 |
        | 深渊教廷 | 堕天使 |

        ## 人类势力
        - 守夜人：国家秘密部门，负责处理灵异事件。
        - 道门残余：龙虎机、茅山等古老道门的末代传人。
        - 散修联盟：民间修行者自发组织，良莠不齐。
        """
        let violations = runTallUserMessageOverlapReplay(
            label: "single-paragraph-variant-user-streaming-assistant",
            userParts: [UIMessagePart.Text(text: prompt, metadata: nil)],
            assistantParts: [UIMessagePart.Text(text: assistantText, metadata: nil)],
            userVariantInfo: IOSConversationStore.VariantInfo(variantCount: 8, selectedIndex: 7)
        )
        if !violations.isEmpty {
            print("INK-OVERLAP[single-paragraph-variant]: \(violations.count) violation(s)")
            for violation in violations.prefix(20) {
                print("INK-OVERLAP[single-paragraph-variant]: \(violation)")
            }
        } else {
            print("INK-OVERLAP[single-paragraph-variant]: none")
        }
        XCTAssertTrue(
            violations.isEmpty,
            "单段 CJK + variant user 气泡不应覆盖流式 assistant 正文: "
                + "\(violations.prefix(3).joined(separator: "; "))"
        )
    }

    func testTallUserMessageAtLargeSerifFontDoesNotPaintIntoAssistantRow() {
        let defaults = UserDefaults.standard
        let previousScale = defaults.object(forKey: IOSDisplayPreferenceKeys.fontScale)
        let previousFont = defaults.object(forKey: IOSDisplayPreferenceKeys.chatFont)
        defaults.set(1.25, forKey: IOSDisplayPreferenceKeys.fontScale)
        defaults.set(IOSChatFont.serif.rawValue, forKey: IOSDisplayPreferenceKeys.chatFont)
        defer {
            if let previousScale {
                defaults.set(previousScale, forKey: IOSDisplayPreferenceKeys.fontScale)
            } else {
                defaults.removeObject(forKey: IOSDisplayPreferenceKeys.fontScale)
            }
            if let previousFont {
                defaults.set(previousFont, forKey: IOSDisplayPreferenceKeys.chatFont)
            } else {
                defaults.removeObject(forKey: IOSDisplayPreferenceKeys.chatFont)
            }
        }

        let violations = runTallUserMessageOverlapReplay(label: "tall-user-large-serif")
        if !violations.isEmpty {
            print("INK-OVERLAP[tall-user-large-serif]: \(violations.count) violation(s)")
            for violation in violations.prefix(20) {
                print("INK-OVERLAP[tall-user-large-serif]: \(violation)")
            }
        } else {
            print("INK-OVERLAP[tall-user-large-serif]: none")
        }
        XCTAssertTrue(
            violations.isEmpty,
            "大字号 serif user 气泡不应把可绘制内容画出自身 cell 并侵入下一条 assistant/思考行: "
                + "\(violations.prefix(3).joined(separator: "; "))"
        )
    }

    /// 常驻守护测试(硬性回归门):在流式跟随态下以细粒度采样
    /// `contentOffset.y` / `contentSize.height` / `maxValidOffset` 三条轨迹,
    /// 守住"消息块整体上下抖动"的根因不再复发——曾经是 `ChatLayout` 的
    /// `keepContentOffsetAtBottomOnBatchUpdates`(批量更新时的瞬时贴底补偿)
    /// 与 `ChatScrollArbiter` 的 display-link 逐帧 `followTail()`(独立于
    /// RunLoop pump 节奏的指数趋近 `writeOffset`)在同一个 `contentOffset`
    /// 上双写竞争,导致 offset 回退。修复后(仲裁者启用时关闭
    /// `keepContentOffsetAtBottomOnBatchUpdates`)"offset 回退次数为 0"必须
    /// 保持翻绿;contentSize 回缩是另一个已记账的独立小问题,这里只做
    /// sanity 上界断言,不阻塞本次修复。`[JITTER-DIAG]` 摘要用于后续排查。
    func testStreamingFollowOffsetStabilityDiagnostics() {
        let deltas = Self.chunkIntoDeltas(Self.embeddedFixture, chunkCount: 60)
        XCTAssertGreaterThan(deltas.count, 30, "合成夹具切片数量过少,回放粒度不足以模拟真实流式节奏")
        let frames = Self.cumulativeFrames(fromDeltas: deltas)

        let diagnostics = runFineGrainedFollowDiagnosticsReplay(
            cumulativeFrames: frames,
            label: "follow-jitter-diagnostics"
        )

        // 回退事件:相邻细粒度样本之间 offset 下降 > 0.5pt。
        var regressionCount = 0
        var maxRegressionMagnitude: CGFloat = 0
        var maxRegressionSampleIndex = -1
        var regressionWithContentSizeChangeCount = 0
        let regressionExampleLimit = 20
        var regressionExamples: [String] = []
        var regressionExemptExamples: [String] = []

        // 超界事件:offset > maxValidOffset + 0.5。
        var overshootCount = 0
        var maxOvershootMagnitude: CGFloat = 0

        // contentSize 回缩事件:相邻样本 contentSize 下降 > 0.5pt。
        var contentSizeShrinkCount = 0
        var maxContentSizeShrinkMagnitude: CGFloat = 0

        let samples = diagnostics.samples
        for i in 1..<max(1, samples.count) where samples.count > 1 {
            let prev = samples[i - 1]
            let cur = samples[i]

            let offsetDelta = cur.offsetY - prev.offsetY
            if offsetDelta < -0.5 {
                let contentSizeDelta = cur.contentSizeHeight - prev.contentSizeHeight
                // 合法的内容回缩豁免:同一样本内 contentSize 也在收缩(例如思考块
                // 折叠)会连带把贴底的 offset 往回带,这不是仲裁者/补偿之间的锯齿,
                // 是内容真值变化的正常结果,不计入回退。
                if contentSizeDelta > -0.5 {
                    regressionCount += 1
                    let magnitude = -offsetDelta
                    let sizeChanged = abs(contentSizeDelta) > 0.5
                    if sizeChanged {
                        regressionWithContentSizeChangeCount += 1
                    }
                    if magnitude > maxRegressionMagnitude {
                        maxRegressionMagnitude = magnitude
                        maxRegressionSampleIndex = i
                    }
                    if regressionExamples.count < regressionExampleLimit {
                        regressionExamples.append(
                            String(
                                format: "sample=%d step=%d offsetY: %.2f -> %.2f (Δ=%.2f) contentSizeΔ=%.2f",
                                i, cur.step, prev.offsetY, cur.offsetY, offsetDelta, contentSizeDelta
                            )
                        )
                    }
                } else if regressionExemptExamples.count < regressionExampleLimit {
                    regressionExemptExamples.append(
                        String(
                            format: "sample=%d step=%d offsetY: %.2f -> %.2f (Δ=%.2f) contentSizeΔ=%.2f",
                            i, cur.step, prev.offsetY, cur.offsetY, offsetDelta, contentSizeDelta
                        )
                    )
                }
            }

            let sizeDelta = cur.contentSizeHeight - prev.contentSizeHeight
            if sizeDelta < -0.5 {
                contentSizeShrinkCount += 1
                maxContentSizeShrinkMagnitude = max(maxContentSizeShrinkMagnitude, -sizeDelta)
            }

            let overshoot = cur.offsetY - (cur.maxValidOffset + 0.5)
            if overshoot > 0 {
                overshootCount += 1
                maxOvershootMagnitude = max(maxOvershootMagnitude, overshoot)
            }
        }

        let firstOffset = samples.first?.offsetY ?? .nan
        let lastOffset = samples.last?.offsetY ?? .nan

        print("[JITTER-DIAG] samples=\(samples.count) steps=\(diagnostics.stepCount) "
            + "offsetFirst=\(String(format: "%.2f", firstOffset)) offsetLast=\(String(format: "%.2f", lastOffset))")
        print(String(
            format: "[JITTER-DIAG] regressions=%d maxMagnitude=%.2fpt atSample=%d withContentSizeChange=%d",
            regressionCount, maxRegressionMagnitude, maxRegressionSampleIndex, regressionWithContentSizeChangeCount
        ))
        for example in regressionExamples {
            print("[JITTER-DIAG] regression-detail: \(example)")
        }
        for example in regressionExemptExamples {
            print("[JITTER-DIAG] regression-exempt: \(example)")
        }
        print(String(
            format: "[JITTER-DIAG] overshoots=%d maxMagnitude=%.2fpt",
            overshootCount, maxOvershootMagnitude
        ))
        print(String(
            format: "[JITTER-DIAG] contentSizeShrinks=%d maxMagnitude=%.2fpt",
            contentSizeShrinkCount, maxContentSizeShrinkMagnitude
        ))

        XCTAssertEqual(
            regressionCount, 0,
            "流式跟随期间 offset 不应回退,实测 \(regressionCount) 次,最大幅度 \(maxRegressionMagnitude)pt"
        )
        // contentSize 回缩是另一个已记账的独立小问题(不在本次 offset 回退修复范围内),
        // 这里只做 sanity 上界断言,避免其无限恶化;[JITTER-DIAG] 打印仍保留供排查。
        XCTAssertLessThanOrEqual(
            maxContentSizeShrinkMagnitude, 16.0,
            "流式跟随期间 contentSize 回缩幅度超出 sanity 上界,实测 \(contentSizeShrinkCount) 次,最大幅度 \(maxContentSizeShrinkMagnitude)pt"
        )
    }

    func testTallUserMessageWithImageDoesNotPaintIntoAssistantRow() {
        let text = Array(
            repeating: "带图片的用户长消息也要撑开 cell,不能只修纯文本普通路径。",
            count: 10
        ).joined(separator: "\n")
        let violations = runTallUserMessageOverlapReplay(
            label: "tall-user-image",
            userParts: [
                UIMessagePart.Text(text: text, metadata: nil),
                UIMessagePart.Image(url: Self.pngDataURL(size: CGSize(width: 40, height: 200)), metadata: nil)
            ]
        )
        if !violations.isEmpty {
            print("INK-OVERLAP[tall-user-image]: \(violations.count) violation(s)")
            for violation in violations.prefix(20) {
                print("INK-OVERLAP[tall-user-image]: \(violation)")
            }
        } else {
            print("INK-OVERLAP[tall-user-image]: none")
        }
        XCTAssertTrue(
            violations.isEmpty,
            "text+image user 气泡不应把可绘制内容画出自身 cell 并侵入下一条 assistant/思考行: "
                + "\(violations.prefix(3).joined(separator: "; "))"
        )
    }

    func testVisibleTallUserMessageRelayoutsAfterFontChange() {
        let defaults = UserDefaults.standard
        let previousScale = defaults.object(forKey: IOSDisplayPreferenceKeys.fontScale)
        let previousFont = defaults.object(forKey: IOSDisplayPreferenceKeys.chatFont)
        defaults.set(0.88, forKey: IOSDisplayPreferenceKeys.fontScale)
        defaults.set(IOSChatFont.default.rawValue, forKey: IOSDisplayPreferenceKeys.chatFont)
        defer {
            if let previousScale {
                defaults.set(previousScale, forKey: IOSDisplayPreferenceKeys.fontScale)
            } else {
                defaults.removeObject(forKey: IOSDisplayPreferenceKeys.fontScale)
            }
            if let previousFont {
                defaults.set(previousFont, forKey: IOSDisplayPreferenceKeys.chatFont)
            } else {
                defaults.removeObject(forKey: IOSDisplayPreferenceKeys.chatFont)
            }
        }

        let violations = runTallUserMessageOverlapReplay(
            label: "tall-user-font-change",
            afterInitialLayout: {
                defaults.set(1.25, forKey: IOSDisplayPreferenceKeys.fontScale)
                defaults.set(IOSChatFont.serif.rawValue, forKey: IOSDisplayPreferenceKeys.chatFont)
                NotificationCenter.default.post(name: UserDefaults.didChangeNotification, object: defaults)
            }
        )
        if !violations.isEmpty {
            print("INK-OVERLAP[tall-user-font-change]: \(violations.count) violation(s)")
            for violation in violations.prefix(20) {
                print("INK-OVERLAP[tall-user-font-change]: \(violation)")
            }
        } else {
            print("INK-OVERLAP[tall-user-font-change]: none")
        }
        XCTAssertTrue(
            violations.isEmpty,
            "可见长 user 气泡在字号/字体变化后必须重新撑开 cell,不得侵入下一条 assistant/思考行: "
                + "\(violations.prefix(3).joined(separator: "; "))"
        )
    }

    // MARK: - Replay engine

    private enum ReplayInteraction {
        case none
        case dragTakeover(step: Int)
        case keyboardSettleThenDrag(step: Int)
    }

    private struct ReplayMetrics {
        var overlapViolations: [String] = []
        var backJumpViolations: [String] = []
        var offsetTrack: [CGFloat] = []
        var frameIntervalsMs: [Double] = []
        var finalDistanceToBottom: CGFloat = .greatestFiniteMagnitude
        var takeoverDistanceToBottom: CGFloat?
        var preCompletionDistanceToBottom: CGFloat?
        var minimumDistanceToBottomAfterTakeover: CGFloat = .greatestFiniteMagnitude
        var stepCount: Int = 0
    }

    /// 构造一个真实的 `ChatCollectionViewController`,挂进带 windowScene 的
    /// UIWindow(裸 `UIWindow(frame:)` 在测试进程里不会真正 composite 内容,
    /// 参见 `ChatMessageWidthOverflowTests` 的同款说明),然后逐帧喂入
    /// `cumulativeFrames` 里的累计文本,每步之间泵 30ms run loop,采样几何。
    @MainActor
    private func runReplay(
        cumulativeFrames: [String],
        label: String,
        completeGeneration: Bool = true,
        interaction: ReplayInteraction = .none,
        preCompletionDistanceFromBottom: CGFloat? = nil,
        historyTurnCount: Int = 0
    ) -> ReplayMetrics {
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

        func distanceToBottom() -> CGFloat {
            let visibleHeight = collectionView.bounds.height
                - collectionView.adjustedContentInset.top
                - collectionView.adjustedContentInset.bottom
            let visibleMaxY = collectionView.contentOffset.y
                + collectionView.adjustedContentInset.top
                + visibleHeight
            return max(0, collectionView.contentSize.height - visibleMaxY)
        }

        func bottomTargetOffsetY() -> CGFloat {
            let minimumY = -collectionView.adjustedContentInset.top
            let bottomTarget = collectionView.contentSize.height
                - collectionView.bounds.height
                + collectionView.adjustedContentInset.bottom
            return max(minimumY, bottomTarget)
        }

        var didTakeover = false
        func simulateDragTakeover() {
            collectionView.delegate?.scrollViewWillBeginDragging?(collectionView)
            let minimumY = -collectionView.adjustedContentInset.top
            let pulledOffsetY = max(minimumY, collectionView.contentOffset.y - 260)
            collectionView.setContentOffset(
                CGPoint(x: collectionView.contentOffset.x, y: pulledOffsetY),
                animated: false
            )
            collectionView.delegate?.scrollViewDidScroll?(collectionView)
            collectionView.delegate?.scrollViewDidEndDragging?(collectionView, willDecelerate: false)
            didTakeover = true
            metrics.takeoverDistanceToBottom = distanceToBottom()
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

        var historyMessages: [UIMessage] = []
        if historyTurnCount > 0 {
            for round in 0..<historyTurnCount {
                historyMessages.append(UIMessage(
                    id: KotlinUuid.companion.random(),
                    role: MessageRole.user,
                    parts: [UIMessagePart.Text(text: "第\(round + 1)轮历史提问:继续扩展设定。", metadata: nil)],
                    annotations: [],
                    createdAt: chatNowLocalDateTime(),
                    finishedAt: chatNowLocalDateTime(),
                    modelId: nil,
                    usage: nil,
                    translation: nil
                ))
                historyMessages.append(UIMessage(
                    id: KotlinUuid.companion.random(),
                    role: MessageRole.assistant,
                    parts: [UIMessagePart.Text(text: Self.embeddedFixture, metadata: nil)],
                    annotations: [],
                    createdAt: chatNowLocalDateTime(),
                    finishedAt: chatNowLocalDateTime(),
                    modelId: nil,
                    usage: nil,
                    translation: nil
                ))
            }
        }

        // 建立会话:先以空列表触发 initialLoad,再追加用户消息(与真实
        // ChatView 在进入会话/发送消息时的两次驱动一致)。
        messages = []
        applyUpdate(reason: .initialLoad, isGenerationActive: false)
        pump(0.03)

        messages = historyMessages + [userMessage]
        applyUpdate(reason: .userAppend, isGenerationActive: false)
        pump(0.03)

        var lastOffsetY: CGFloat?
        let displayLink = DisplayLinkSampler()
        displayLink.start()

        for (index, text) in cumulativeFrames.enumerated() {
            messages = historyMessages + [userMessage, makeAssistantMessage(text: text, finished: false)]
            applyUpdate(reason: .streamDelta, isGenerationActive: true)
            pump(0.030)

            collectionView.layoutIfNeeded()

            switch interaction {
            case let .dragTakeover(step) where index == step:
                simulateDragTakeover()
            case let .keyboardSettleThenDrag(step) where index == step:
                let keyboardFrame = CGRect(
                    x: 0,
                    y: Self.screenSize.height - 320,
                    width: Self.screenSize.width,
                    height: 320
                )
                NotificationCenter.default.post(
                    name: UIResponder.keyboardDidChangeFrameNotification,
                    object: nil,
                    userInfo: [UIResponder.keyboardFrameEndUserInfoKey: NSValue(cgRect: keyboardFrame)]
                )
                pump(0.02)
                simulateDragTakeover()
                pump(0.16)
            default:
                break
            }

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

            if didTakeover {
                metrics.minimumDistanceToBottomAfterTakeover = min(
                    metrics.minimumDistanceToBottomAfterTakeover,
                    distanceToBottom()
                )
            }
        }

        displayLink.stop()
        metrics.frameIntervalsMs = displayLink.intervalsMs

        if completeGeneration {
            // 终态锚定:再驱动一次 generationCompleted,泵 0.5s,校验收敛到底部。
            if let preCompletionDistanceFromBottom {
                collectionView.setContentOffset(
                    CGPoint(
                        x: collectionView.contentOffset.x,
                        y: max(
                            -collectionView.adjustedContentInset.top,
                            bottomTargetOffsetY() - preCompletionDistanceFromBottom
                        )
                    ),
                    animated: false
                )
                collectionView.delegate?.scrollViewDidScroll?(collectionView)
                metrics.preCompletionDistanceToBottom = distanceToBottom()
            }
            messages = historyMessages + [userMessage, makeAssistantMessage(text: cumulativeFrames.last ?? "", finished: true)]
            applyUpdate(reason: .generationCompleted, isGenerationActive: false)
            pump(0.5)
        } else {
            pump(0.2)
        }
        collectionView.layoutIfNeeded()

        metrics.finalDistanceToBottom = distanceToBottom()

        window.isHidden = true
        window.rootViewController = nil

        return metrics
    }

    private struct CellRecycleRoundtripMeasurement {
        var tailHeightBefore: CGFloat
        var contentSizeBefore: CGFloat
        var tailHeightAfter: CGFloat
        var contentSizeAfter: CGFloat
    }

    /// 硬断言用的精简回放 runner:与 `runFreezeDiagnosisReplay` 共享同一套"铺垫多屏
    /// 历史消息 → 流式喂入尾行 → generationCompleted"骨架,但流程收敛到用户描述的
    /// 单次往返——贴底记录 (H0, S0) → 拖离 1400pt(尾行完全离屏,cell 被回收)→
    /// 滚回底部并 pump 到稳定 → 记录 (H1, S1)。不做 `runFreezeDiagnosisReplay` 里
    /// D1/D2 那几轮额外诊断实验,只服务于本测试的硬断言。
    @MainActor
    private func runCellRecycleRoundtripMeasurement(
        cumulativeFrames: [String],
        label: String
    ) -> CellRecycleRoundtripMeasurement {
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
            return CellRecycleRoundtripMeasurement(tailHeightBefore: -1, contentSizeBefore: -1, tailHeightAfter: -1, contentSizeAfter: -1)
        }

        var revision = 0
        var scrollToBottomTrigger = 0
        func applyUpdate(reason: ChatMessageUpdateReason, isGenerationActive: Bool, bumpRevision: Bool = true) {
            if bumpRevision {
                revision += 1
            }
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
                scrollToBottomTrigger: scrollToBottomTrigger
            )
        }

        func pump(_ seconds: TimeInterval) {
            let deadline = Date().addingTimeInterval(seconds)
            repeat {
                RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.005))
            } while Date() < deadline
        }

        var historyMessages: [UIMessage] = []
        for round in 0..<6 {
            let userText = "第\(round + 1)轮提问:请继续扩展设定与情节。"
            historyMessages.append(UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.user,
                parts: [UIMessagePart.Text(text: userText, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            ))
            historyMessages.append(UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: Self.embeddedFixture, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            ))
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

        let tailItemID = "message-\(String(describing: assistantMessageId))"

        func tailCell() -> UICollectionViewCell? {
            collectionView.visibleCells.first { $0.accessibilityIdentifier == tailItemID }
        }

        func tailHeight() -> CGFloat {
            let itemCount = collectionView.numberOfItems(inSection: 0)
            let tailIndexPath = IndexPath(item: max(0, itemCount - 2), section: 0)
            return collectionView.layoutAttributesForItem(at: tailIndexPath)?.frame.height ?? -1
        }

        // 建立会话:先 initialLoad,再把历史消息 + 首条用户消息一次性铺上去。
        messages = []
        applyUpdate(reason: .initialLoad, isGenerationActive: false)
        pump(0.03)

        messages = historyMessages + [userMessage]
        applyUpdate(reason: .userAppend, isGenerationActive: false)
        pump(0.05)
        collectionView.layoutIfNeeded()

        XCTAssertGreaterThan(
            collectionView.contentSize.height,
            collectionView.bounds.height * 2,
            "[\(label)] 测试前置条件失效: 历史消息铺垫的内容不足以撑出可滚动的多屏空间"
        )

        // 预热:从顶到底逐屏滚一遍,让所有历史行完成首次 realize(estimated →
        // 真实 self-sizing 高度的一次性修正)。这属于 estimated-sizing 列表的
        // 固有行为,与本测试要守护的"尾行回收重建排版跳变"无关,预热将其排除,
        // 否则滚离 1400pt 恰好触发上方行首次 realize,contentSize 的良性增长会
        // 污染 S0/S1 断言。
        var warmupOffsetY = -collectionView.adjustedContentInset.top
        while warmupOffsetY < collectionView.contentSize.height {
            collectionView.setContentOffset(CGPoint(x: 0, y: warmupOffsetY), animated: false)
            collectionView.layoutIfNeeded()
            pump(0.02)
            warmupOffsetY += collectionView.bounds.height * 0.8
        }
        // 预热完回到底部(流式跟随的起点)。
        let warmupBottomY = max(
            -collectionView.adjustedContentInset.top,
            collectionView.contentSize.height - collectionView.bounds.height + collectionView.adjustedContentInset.bottom
        )
        collectionView.setContentOffset(CGPoint(x: 0, y: warmupBottomY), animated: false)
        pump(0.1)
        collectionView.layoutIfNeeded()

        // 流式喂入全部帧。
        for text in cumulativeFrames {
            messages = historyMessages + [userMessage, makeAssistantMessage(text: text, finished: false)]
            applyUpdate(reason: .streamDelta, isGenerationActive: true)
            pump(0.030)
            collectionView.layoutIfNeeded()
        }

        // 生成结束:最后一帧 finished = true,pump 到稳定并贴底记录 H0/S0。
        messages = historyMessages + [userMessage, makeAssistantMessage(text: cumulativeFrames.last ?? "", finished: true)]
        applyUpdate(reason: .generationCompleted, isGenerationActive: false)
        pump(0.5)
        collectionView.layoutIfNeeded()

        scrollToBottomTrigger += 1
        applyUpdate(reason: .generationCompleted, isGenerationActive: false, bumpRevision: false)
        pump(0.8)
        collectionView.layoutIfNeeded()
        pump(0.4)
        collectionView.layoutIfNeeded()

        let tailHeightBefore = tailHeight()
        let contentSizeBefore = collectionView.contentSize.height

        // 滚离 1400pt:尾行完全离屏,didEndDisplaying(freeze)触发、cell 被回收。
        collectionView.delegate?.scrollViewWillBeginDragging?(collectionView)
        collectionView.setContentOffset(
            CGPoint(
                x: collectionView.contentOffset.x,
                y: max(-collectionView.adjustedContentInset.top, collectionView.contentOffset.y - 1400)
            ),
            animated: false
        )
        collectionView.delegate?.scrollViewDidScroll?(collectionView)
        collectionView.delegate?.scrollViewDidEndDragging?(collectionView, willDecelerate: false)
        collectionView.layoutIfNeeded()
        pump(0.3)

        // 滚回底部:willDisplay(unfreeze)重新实现尾行 cell,新 view 实例
        // hasUsedStreamingMarkdownRenderer latch 重置为 false → 走 AmberMarkdownView。
        scrollToBottomTrigger += 1
        applyUpdate(reason: .generationCompleted, isGenerationActive: false, bumpRevision: false)
        pump(0.8)
        collectionView.layoutIfNeeded()
        pump(0.4)
        collectionView.layoutIfNeeded()

        let tailHeightAfter = tailHeight()
        let contentSizeAfter = collectionView.contentSize.height

        window.isHidden = true
        window.rootViewController = nil

        return CellRecycleRoundtripMeasurement(
            tailHeightBefore: tailHeightBefore,
            contentSizeBefore: contentSizeBefore,
            tailHeightAfter: tailHeightAfter,
            contentSizeAfter: contentSizeAfter
        )
    }

    /// freeze/unfreeze 链路诊断 runner(纯插桩、零断言修复):
    /// 铺垫多屏历史消息 → 流式喂入尾行 → (实验组)在 `dragStep` 拖离底部并把 offset
    /// 钉住一段时间让尾行完全离屏 → 喂满剩余帧 → generationCompleted → 模拟点击
    /// "回到底部" → 三个采样点 dump [FREEZE-DIAG] 指标。
    /// `dragStep == nil` 为对照组:全程贴底,其余流程完全相同。
    ///
    /// renderStateStore / viewportState 都是 private,诊断用 Mirror 反射读原始集合
    /// (frozenMessageIDs / visibleMessageIDs / frozenMarkdownByMessageID),
    /// 不复现 stateForRow 的推导逻辑,保证 dump 的是事实层数据。
    @MainActor
    private func runFreezeDiagnosisReplay(
        cumulativeFrames: [String],
        label: String,
        dragStep: Int?,
        dragDistance: CGFloat
    ) {
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
            return
        }

        var revision = 0
        var scrollToBottomTrigger = 0
        var lastReason: ChatMessageUpdateReason = .initialLoad
        func applyUpdate(reason: ChatMessageUpdateReason, isGenerationActive: Bool, bumpRevision: Bool = true) {
            if bumpRevision {
                revision += 1
                lastReason = reason
            }
            controller.update(
                signal: ChatMessageUpdateSignal(revision: revision, reason: bumpRevision ? reason : lastReason),
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
                scrollToBottomTrigger: scrollToBottomTrigger
            )
        }

        func pump(_ seconds: TimeInterval) {
            let deadline = Date().addingTimeInterval(seconds)
            repeat {
                RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.005))
            } while Date() < deadline
        }

        func distanceToBottom() -> CGFloat {
            let visibleHeight = collectionView.bounds.height
                - collectionView.adjustedContentInset.top
                - collectionView.adjustedContentInset.bottom
            let visibleMaxY = collectionView.contentOffset.y
                + collectionView.adjustedContentInset.top
                + visibleHeight
            return max(0, collectionView.contentSize.height - visibleMaxY)
        }

        // 铺垫历史消息:每条 assistant 回复都是多段落长文本,足以撑出远超一屏的可滚动
        // 内容,这样"拖走 400pt"才有意义让尾行完全离开可视区,而不是把仅有的一两条
        // 消息一起拖出画面。
        var historyMessages: [UIMessage] = []
        for round in 0..<6 {
            let userText = "第\(round + 1)轮提问:请继续扩展设定与情节。"
            historyMessages.append(UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.user,
                parts: [UIMessagePart.Text(text: userText, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            ))
            // 与尾行终态完全相同的文本:历史行(从未流式,staticMarkdown 渲染路径)与
            // 流式尾行渲染同一内容,高度可直接对比——用来分辨"重建后塌陷"是流式渲染器
            // 停摆,还是两种渲染器对同一文本的高度本来就不一致。
            let assistantText = Self.embeddedFixture
            historyMessages.append(UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: assistantText, metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            ))
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

        let tailItemID = "message-\(String(describing: assistantMessageId))"

        func tailCell() -> UICollectionViewCell? {
            collectionView.visibleCells.first { $0.accessibilityIdentifier == tailItemID }
        }

        func tailOffscreen() -> Bool {
            // "完全离屏"既包含滚动到尾行上方看不见(视口在尾行之前),也包含尾行虽然
            // 存在于 visibleCells 但完全在可视 bounds 之外(异常拉伸场景)。这里以
            // "找不到该 cell 或其 frame 与可视 bounds 不相交"为准。
            guard let cell = tailCell() else { return true }
            let visibleRect = CGRect(origin: collectionView.contentOffset, size: collectionView.bounds.size)
            return !cell.frame.intersects(visibleRect)
        }

        // ---- [FREEZE-DIAG] 插桩:Mirror 反射读 private 渲染态 ----
        func reflectChild(_ any: Any, _ name: String) -> Any? {
            Mirror(reflecting: any).children.first { $0.label == name }?.value
        }
        let storeObject = reflectChild(controller, "store")
        let renderStateStoreObject = storeObject.flatMap { reflectChild($0, "renderStateStore") }
        let tailMessageID = String(describing: assistantMessageId)

        func dumpFreezeDiag(_ phase: String) {
            let frozenIDs = (renderStateStoreObject.flatMap { reflectChild($0, "frozenMessageIDs") } as? Set<String>) ?? []
            let visibleIDs = (renderStateStoreObject.flatMap { reflectChild($0, "visibleMessageIDs") } as? Set<String>) ?? []
            let snapshots = (renderStateStoreObject.flatMap { reflectChild($0, "frozenMarkdownByMessageID") } as? [String: String]) ?? [:]
            let viewportState = storeObject.flatMap { reflectChild($0, "viewportState") } as? ChatViewportState
            // digest 追踪:layout 哈希已包含 rendererMode(ChatRowDigest.swift:37),
            // 各采样点对比它是否翻转,可判定解冻后 reconfigure 管线是否真的跑过。
            let digests = (storeObject.flatMap { reflectChild($0, "digests") } as? [String: ChatRowDigest]) ?? [:]
            let tailDigestLayout = digests["message-\(tailMessageID)"]?.layout ?? "nil"

            let latestTextLen = messages.last?.parts
                .compactMap { ($0 as? UIMessagePart.Text)?.text }
                .first?.count ?? -1
            let snapshotLen = snapshots[tailMessageID]?.count ?? -1

            let contentSizeH = collectionView.contentSize.height
            let boundsH = collectionView.bounds.height
            let insetTop = collectionView.adjustedContentInset.top
            let insetBottom = collectionView.adjustedContentInset.bottom
            let offsetY = collectionView.contentOffset.y
            let bottomTargetY = max(-insetTop, contentSizeH - boundsH + insetBottom)
            let viewportBottomY = offsetY + boundsH

            let cell = tailCell()
            let cellH = cell?.bounds.height ?? -1
            let tailFrameDescription = cell.map { String(describing: $0.frame) } ?? "nil(offscreen)"
            // 尾行的 layoutAttributes 不依赖 cell 是否可见:items 结构固定为
            // [message...] + bottomSpacer,尾行 = 倒数第二个 item。
            let itemCount = collectionView.numberOfItems(inSection: 0)
            let tailIndexPath = IndexPath(item: max(0, itemCount - 2), section: 0)
            let attrFrame = collectionView.layoutAttributesForItem(at: tailIndexPath)?.frame
            let attrH = attrFrame?.height ?? -1
            let hostingFitH: CGFloat = cell.map { cell in
                cell.contentView.systemLayoutSizeFitting(
                    CGSize(width: cell.bounds.width, height: UIView.layoutFittingCompressedSize.height),
                    withHorizontalFittingPriority: .required,
                    verticalFittingPriority: .fittingSizeLevel
                ).height
            } ?? -1
            let tailMaxY = attrFrame?.maxY ?? -1
            let tailReachable = tailMaxY >= 0 && tailMaxY <= viewportBottomY + 1
            // 同文本对照:item 1 = 第一条历史 assistant 行(从未流式,文本与尾行终态相同)。
            let histAttrH = collectionView.layoutAttributesForItem(at: IndexPath(item: 1, section: 0))?.frame.height ?? -1

            print(
                "[FREEZE-DIAG][\(label)][\(phase)] frozen=\(frozenIDs.contains(tailMessageID)) "
                    + "visibleSetHasTail=\(visibleIDs.contains(tailMessageID)) "
                    + "snapshotLen=\(snapshotLen) latestTextLen=\(latestTextLen) "
                    + "tailDigestLayout=\(tailDigestLayout)"
            )
            print(
                "[FREEZE-DIAG][\(label)][\(phase)] farFromBottom=\(viewportState?.liveRenderingFarFromBottom.description ?? "nil") "
                    + "followPaused=\(viewportState?.followPaused.description ?? "nil") "
                    + "isAtBottom=\(viewportState?.isAtBottom.description ?? "nil") "
                    + "userDragging=\(viewportState?.userDragging.description ?? "nil")"
            )
            print(
                "[FREEZE-DIAG][\(label)][\(phase)] cellBoundsH=\(cellH) attrH=\(attrH) "
                    + "hostingFitH=\(hostingFitH) histAttrH=\(histAttrH) tailCellFrame=\(tailFrameDescription)"
            )
            print(
                "[FREEZE-DIAG][\(label)][\(phase)] contentSizeH=\(contentSizeH) offsetY=\(offsetY) "
                    + "bottomTargetY=\(bottomTargetY) viewportBottomY=\(viewportBottomY) "
                    + "distanceToBottom=\(distanceToBottom()) "
                    + "tailAttrMaxY=\(tailMaxY) tailReachable=\(tailReachable) "
                    + "tailOffscreen=\(tailOffscreen())"
            )
        }

        // 建立会话:先 initialLoad,再把历史消息 + 首条用户消息一次性铺上去。
        messages = []
        applyUpdate(reason: .initialLoad, isGenerationActive: false)
        pump(0.03)

        messages = historyMessages + [userMessage]
        applyUpdate(reason: .userAppend, isGenerationActive: false)
        pump(0.05)
        collectionView.layoutIfNeeded()

        XCTAssertGreaterThan(
            collectionView.contentSize.height,
            collectionView.bounds.height * 2,
            "[\(label)] 测试前置条件失效: 历史消息铺垫的内容不足以撑出可滚动的多屏空间"
        )

        var didTakeover = false
        func simulateDragTakeover(distance: CGFloat) {
            collectionView.delegate?.scrollViewWillBeginDragging?(collectionView)
            let minimumY = -collectionView.adjustedContentInset.top
            let pulledOffsetY = max(minimumY, collectionView.contentOffset.y - distance)
            collectionView.setContentOffset(
                CGPoint(x: collectionView.contentOffset.x, y: pulledOffsetY),
                animated: false
            )
            collectionView.delegate?.scrollViewDidScroll?(collectionView)
            collectionView.delegate?.scrollViewDidEndDragging?(collectionView, willDecelerate: false)
            didTakeover = true
        }

        // 用户手指持续按在同一个绝对 offsetY 不放(而不是拖一下就撒手,也不是
        // "离(不断上涨的)底部固定距离"——后者会随尾行变高而跟着下移,永远追着
        // 尾行底部,尾行就不可能离屏)。ChatLayout 的批量更新补偿
        // (keepContentOffsetAtBottomOnBatchUpdates)会在每次 reconfigure 后把 offset
        // 往下顶、抵消用户的上拖(参见 CollectionViewChatLayout.swift 的
        // offsetCompensation 逻辑),仅松手一次不足以让尾行持续离屏。每帧都重新钉回
        // 拖拽落点的绝对 offsetY,才能模拟"用户按住不放、尾行在可视区外持续增长"
        // 的真实场景。
        func pinAbsoluteOffset(_ offsetY: CGFloat) {
            let minimumY = -collectionView.adjustedContentInset.top
            let pinnedOffsetY = max(minimumY, offsetY)
            collectionView.setContentOffset(
                CGPoint(x: collectionView.contentOffset.x, y: pinnedOffsetY),
                animated: false
            )
            collectionView.delegate?.scrollViewDidScroll?(collectionView)
        }

        // 松手前(dragStep...releaseStep)持续把 offset 钉在拖拽落点的绝对 offsetY,
        // 模拟手指按住不放;releaseStep 之后才真正撒手,让后续流式与批量补偿按
        // 正常路径继续(与真实的"上拖后停留一段时间,再自然停止交互"一致)。
        // 对照组(dragStep == nil)整段跳过,全程贴底。
        let releaseStep = dragStep.map { min(cumulativeFrames.count - 1, $0 + max(5, cumulativeFrames.count / 6)) }
        var pinnedOffsetY: CGFloat?
        var offscreenStepCount = 0

        for (index, text) in cumulativeFrames.enumerated() {
            messages = historyMessages + [userMessage, makeAssistantMessage(text: text, finished: false)]
            applyUpdate(reason: .streamDelta, isGenerationActive: true)
            pump(0.030)
            collectionView.layoutIfNeeded()

            if let dragStep, index == dragStep {
                simulateDragTakeover(distance: dragDistance)
                collectionView.layoutIfNeeded()
                pinnedOffsetY = collectionView.contentOffset.y
            } else if didTakeover, let releaseStep, index < releaseStep, let pinnedOffsetY {
                pinAbsoluteOffset(pinnedOffsetY)
                collectionView.layoutIfNeeded()
            }
            if didTakeover, tailOffscreen() {
                offscreenStepCount += 1
            }
        }

        print(
            "[FREEZE-DIAG][\(label)] streaming done: steps=\(cumulativeFrames.count) "
                + "didTakeover=\(didTakeover) dragStep=\(dragStep.map(String.init) ?? "nil(control)") "
                + "releaseStep=\(releaseStep.map(String.init) ?? "nil") "
                + "offscreenStepCount=\(offscreenStepCount)"
        )

        // 生成结束:最后一帧 finished = true。
        messages = historyMessages + [userMessage, makeAssistantMessage(text: cumulativeFrames.last ?? "", finished: true)]
        applyUpdate(reason: .generationCompleted, isGenerationActive: false)
        pump(0.5)
        collectionView.layoutIfNeeded()

        dumpFreezeDiag("A-after-completion-before-return")

        // 模拟点击"回到底部"按钮:生产路径是 scrollToBottomTrigger 变化、signal 不变
        // (revision 不递增,快照防重放会跳过 apply,只走 trigger → snapToBottom 分支,
        // 与真实 ChatView 里点击按钮的驱动方式一致)。
        scrollToBottomTrigger += 1
        applyUpdate(reason: .generationCompleted, isGenerationActive: false, bumpRevision: false)
        pump(0.8)
        collectionView.layoutIfNeeded()

        dumpFreezeDiag("B-after-return-immediate")

        // 追加多轮 pump:给 willDisplay 里 isLastAssistantItem 分支的
        // DispatchQueue.main.async refreshLastAssistantRenderState() 充分的执行机会,
        // 对比 B/C 两个采样点可判断异步解冻是否真的发生。
        pump(0.6)
        collectionView.layoutIfNeeded()
        pump(0.4)
        collectionView.layoutIfNeeded()

        dumpFreezeDiag("C-after-return-settled")

        // ---- 判定实验 D1:手动全局 invalidateLayout ----
        // 若高度恢复 → 测量数据其实已就绪,只是解冻后缺一次 layout invalidation;
        // 若不恢复 → 断点在 hosting 测量/preferred-attributes 层,invalidation 不够。
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.setNeedsLayout()
        collectionView.layoutIfNeeded()
        pump(0.3)
        collectionView.layoutIfNeeded()
        dumpFreezeDiag("D1-after-manual-invalidate")

        // ---- 判定实验 D2:第二轮滚离→滚回 ----
        // 让尾行再经历一次完整的 didEndDisplaying(freeze)→willDisplay(unfreeze)循环。
        // 若这一轮能恢复 → 首轮是一次性时序竞争(reconfigure 时 SwiftUI 内容未就绪);
        // 若仍停在旧高度 → 冻结/解冻循环本身收敛到错误高度,结构性问题。
        collectionView.delegate?.scrollViewWillBeginDragging?(collectionView)
        collectionView.setContentOffset(
            CGPoint(
                x: collectionView.contentOffset.x,
                y: max(-collectionView.adjustedContentInset.top, collectionView.contentOffset.y - 1400)
            ),
            animated: false
        )
        collectionView.delegate?.scrollViewDidScroll?(collectionView)
        collectionView.delegate?.scrollViewDidEndDragging?(collectionView, willDecelerate: false)
        collectionView.layoutIfNeeded()
        pump(0.3)
        scrollToBottomTrigger += 1
        applyUpdate(reason: .generationCompleted, isGenerationActive: false, bumpRevision: false)
        pump(0.8)
        collectionView.layoutIfNeeded()
        pump(0.4)
        collectionView.layoutIfNeeded()
        dumpFreezeDiag("D2-after-second-roundtrip")

        window.isHidden = true
        window.rootViewController = nil
    }

    private struct FollowOffsetSample {
        var step: Int
        var offsetY: CGFloat
        var contentSizeHeight: CGFloat
        var maxValidOffset: CGFloat
    }

    private struct FollowOffsetDiagnostics {
        var samples: [FollowOffsetSample] = []
        var stepCount: Int = 0
    }

    /// 专用于 `testStreamingFollowOffsetStabilityDiagnostics` 的细粒度回放
    /// runner:与 `runReplay` 共享同一套建立会话 / 累计帧喂入的骨架,但把
    /// 每一步的 `pump(0.03)` 拆成多个 `pump(0.008)` 小片段,每个小片段后都
    /// 采样一次 `contentOffset.y` / `contentSize.height` / `maxValidOffset`,
    /// 以便捕捉 display-link 逐帧 `followTail()` 与批量更新贴底补偿之间的
    /// 竞争细节(这些细节在 `runReplay` 现有的"每步一次"采样粒度下会被
    /// 平均掉,不会显现为回退)。不影响、不共享 `runReplay` 的行为。
    @MainActor
    private func runFineGrainedFollowDiagnosticsReplay(
        cumulativeFrames: [String],
        label: String
    ) -> FollowOffsetDiagnostics {
        var diagnostics = FollowOffsetDiagnostics()
        diagnostics.stepCount = cumulativeFrames.count

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
            return diagnostics
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

        func sample(step: Int) {
            let maxValidOffset = collectionView.contentSize.height
                + collectionView.adjustedContentInset.bottom
                - collectionView.bounds.height
            diagnostics.samples.append(
                FollowOffsetSample(
                    step: step,
                    offsetY: collectionView.contentOffset.y,
                    contentSizeHeight: collectionView.contentSize.height,
                    maxValidOffset: maxValidOffset
                )
            )
        }

        // 每步 pump 拆成多次细粒度 pump,每次之后都采样一条记录,
        // 用来捕捉 display-link 逐帧 writeOffset 与批量更新贴底补偿
        // 之间的竞争细节(总时长与 runReplay 的 pump(0.030) 对齐)。
        func pumpAndSampleFinely(step: Int) {
            let sliceCount = 4
            let sliceSeconds: TimeInterval = 0.008
            for _ in 0..<sliceCount {
                pump(sliceSeconds)
                sample(step: step)
            }
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

        // 建立会话:先以空列表触发 initialLoad,再追加用户消息(与 runReplay 一致)。
        messages = []
        applyUpdate(reason: .initialLoad, isGenerationActive: false)
        pump(0.03)

        messages = [userMessage]
        applyUpdate(reason: .userAppend, isGenerationActive: false)
        pump(0.03)

        for (index, text) in cumulativeFrames.enumerated() {
            messages = [userMessage, makeAssistantMessage(text: text, finished: false)]
            applyUpdate(reason: .streamDelta, isGenerationActive: true)
            pumpAndSampleFinely(step: index)
            collectionView.layoutIfNeeded()
            sample(step: index)
        }

        // 全程处于流式跟随:不提前触发 generationCompleted,不做用户交互。
        pump(0.05)
        collectionView.layoutIfNeeded()
        sample(step: cumulativeFrames.count)

        window.isHidden = true
        window.rootViewController = nil

        return diagnostics
    }

    @MainActor
    private func runLoadedConversationAnchorReplay(assistantText: String, label: String) -> CGFloat {
        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "ChatStreamReplayTests-\(label)-\(UUID().uuidString)")!
        )
        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("ChatStreamReplayTests-\(label)-\(UUID().uuidString)", isDirectory: true)
        )

        let userMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Text(text: "打开已有长会话", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let assistantMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: assistantText, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let messages = [userMessage, assistantMessage]
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
            return .greatestFiniteMagnitude
        }

        controller.update(
            signal: ChatMessageUpdateSignal(revision: 1, reason: .conversationSwitch),
            configurationIssue: nil,
            isGenerationActive: false,
            isLoading: false,
            isRecognizingImages: false,
            contextCompactState: .idle,
            followGeneration: true,
            displaySetting: sharedSettings.displaySetting,
            generativeUiSetting: sharedSettings.agentRuntime.generativeUi,
            reasoningLevelLabel: nil,
            workspaceStore: workspaceStore,
            scrollToBottomTrigger: 0
        )

        let deadline = Date().addingTimeInterval(0.8)
        repeat {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.005))
            collectionView.layoutIfNeeded()
        } while Date() < deadline

        let visibleHeight = collectionView.bounds.height
            - collectionView.adjustedContentInset.top
            - collectionView.adjustedContentInset.bottom
        let visibleMaxY = collectionView.contentOffset.y
            + collectionView.adjustedContentInset.top
            + visibleHeight
        let distance = collectionView.contentSize.height - visibleMaxY
        XCTAssertGreaterThan(
            collectionView.contentSize.height,
            visibleHeight + ChatLayout.bottomStickThreshold,
            "[\(label)] 测试前置条件失效: 内容不足一屏,无法证明进会话锚定到底部"
        )

        window.isHidden = true
        window.rootViewController = nil

        return max(0, distance)
    }

    @MainActor
    private func runTallUserMessageOverlapReplay(
        label: String,
        userParts explicitUserParts: [UIMessagePart]? = nil,
        assistantParts explicitAssistantParts: [UIMessagePart]? = nil,
        userVariantInfo: IOSConversationStore.VariantInfo? = nil,
        afterInitialLayout: (() -> Void)? = nil
    ) -> [String] {
        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "ChatStreamReplayTests-\(label)-\(UUID().uuidString)")!
        )
        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("ChatStreamReplayTests-\(label)-\(UUID().uuidString)", isDirectory: true)
        )

        let tallPrompt = Array(
            repeating: "九天应元雷声普化天尊转世,都市灵异爽文,主角觉醒后逐层解锁雷法和封印。",
            count: 42
        ).joined(separator: "\n")
        let userParts = explicitUserParts ?? [UIMessagePart.Text(text: tallPrompt, metadata: nil)]
        let userMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: userParts,
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let assistantMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: explicitAssistantParts
                ?? [UIMessagePart.Text(text: "# 一、核心设定\n主角身份与觉醒机制正在展开。", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let messages = [userMessage, assistantMessage]
        let controller = ChatCollectionViewController()
        controller.messagesProvider = { messages }
        controller.variantInfoProvider = { index in
            index == 0 ? userVariantInfo : nil
        }
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
            return []
        }

        controller.update(
            signal: ChatMessageUpdateSignal(revision: 1, reason: .streamDelta),
            configurationIssue: nil,
            isGenerationActive: true,
            isLoading: false,
            isRecognizingImages: false,
            contextCompactState: .idle,
            followGeneration: true,
            displaySetting: sharedSettings.displaySetting,
            generativeUiSetting: sharedSettings.agentRuntime.generativeUi,
            reasoningLevelLabel: "Auto",
            workspaceStore: workspaceStore,
            scrollToBottomTrigger: 0
        )

        let deadline = Date().addingTimeInterval(0.8)
        repeat {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.005))
            collectionView.layoutIfNeeded()
        } while Date() < deadline

        if let afterInitialLayout {
            afterInitialLayout()
            let relayoutDeadline = Date().addingTimeInterval(0.3)
            repeat {
                RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.005))
                collectionView.layoutIfNeeded()
            } while Date() < relayoutDeadline
        }

        let userItemID = "message-\(ChatMessageProjector.messageId(for: userMessage))"
        let assistantItemID = "message-\(ChatMessageProjector.messageId(for: assistantMessage))"
        Self.revealMessageBoundary(
            in: collectionView,
            firstIndexPath: IndexPath(item: 0, section: 0),
            secondIndexPath: IndexPath(item: 1, section: 0)
        )
        let violations = Self.firstVisibleCellPaintOverflowViolations(
            in: collectionView,
            label: label,
            expectedFirstItemID: userItemID,
            expectedSecondItemID: assistantItemID
        )
        window.isHidden = true
        window.rootViewController = nil
        return violations
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

    private static func firstVisibleCellPaintOverflowViolations(
        in collectionView: UICollectionView,
        label: String,
        expectedFirstItemID: String,
        expectedSecondItemID: String
    ) -> [String] {
        let visibleRect = CGRect(origin: collectionView.contentOffset, size: collectionView.bounds.size)
        let cells = collectionView.visibleCells.sorted { $0.frame.minY < $1.frame.minY }
        guard cells.count >= 2 else {
            return ["\(label) expected at least 2 visible cells, got \(cells.count)"]
        }

        var violations: [String] = []
        let cell = cells[0]
        let secondCell = cells[1]
        if cell.accessibilityIdentifier != expectedFirstItemID ||
            secondCell.accessibilityIdentifier != expectedSecondItemID {
            violations.append(
                "\(label) expected visible pair \(expectedFirstItemID) -> \(expectedSecondItemID), "
                    + "got \(cell.accessibilityIdentifier ?? "nil") -> "
                    + "\(secondCell.accessibilityIdentifier ?? "nil")"
            )
        }
        let nextCellMinY = cells[1].frame.minY
        let cellFrame = cell.frame
        func walk(_ layer: CALayer, path: [String]) {
            guard !layer.isHidden, layer.opacity > 0.01, layer.bounds.width > 1, layer.bounds.height > 1 else { return }
            let frame = layer.convert(layer.bounds, to: collectionView.layer)
            let topOverflow = frame.minY < cellFrame.minY - 4
            let bottomOverflow = frame.maxY > cellFrame.maxY + 4 && frame.maxY > nextCellMinY + 1
            if frame.intersects(visibleRect), topOverflow || bottomOverflow {
                let direction = topOverflow ? "top" : "bottom"
                violations.append(
                    "\(label) direction=\(direction) cell=\(type(of: cell)) cellFrame=\(cellFrame) layer=\(type(of: layer)) "
                        + "layerFrame=\(frame) path=\(path.suffix(5).joined(separator: "->"))"
                )
            }
            for sublayer in layer.sublayers ?? [] {
                walk(sublayer, path: path + ["\(type(of: layer))"])
            }
        }
        walk(cell.layer, path: [])
        return violations
    }

    private static func revealMessageBoundary(
        in collectionView: UICollectionView,
        firstIndexPath: IndexPath,
        secondIndexPath: IndexPath
    ) {
        guard let firstAttributes = collectionView.layoutAttributesForItem(at: firstIndexPath),
              let secondAttributes = collectionView.layoutAttributesForItem(at: secondIndexPath) else { return }
        positionBoundary(
            between: firstAttributes.frame,
            and: secondAttributes.frame,
            in: collectionView
        )
    }

    private static func positionBoundary(
        between firstFrame: CGRect,
        and secondFrame: CGRect,
        in collectionView: UICollectionView
    ) {
        let boundaryY = (firstFrame.maxY + secondFrame.minY) / 2
        let visibleHeight = collectionView.bounds.height
        let minimumY = -collectionView.adjustedContentInset.top
        let maximumY = max(
            minimumY,
            collectionView.contentSize.height + collectionView.adjustedContentInset.bottom - visibleHeight
        )
        let targetY = min(maximumY, max(minimumY, boundaryY - visibleHeight / 2))
        collectionView.layer.removeAllAnimations()
        collectionView.setContentOffset(CGPoint(x: collectionView.contentOffset.x, y: targetY), animated: false)
        collectionView.layoutIfNeeded()
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

    private static func pngDataURL(size: CGSize) -> String {
        let image = UIGraphicsImageRenderer(size: size).image { context in
            UIColor.systemOrange.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        let data = image.pngData() ?? Data()
        return "data:image/png;base64,\(data.base64EncodedString())"
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

    private static let scrollableEmbeddedFixture = [
        embeddedFixture,
        embeddedFixture,
        embeddedFixture
    ].joined(separator: "\n\n---\n\n")

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
