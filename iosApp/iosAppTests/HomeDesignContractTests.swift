import Foundation
import Shared
import UIKit
import XCTest
@testable import iosApp

final class HomeDesignContractTests: XCTestCase {
    /// 设计硬约束：首页图标统一 Phosphor fill（路径数据与定稿原型内嵌 symbol 同源）。
    /// 每个字形必须能被解析器完整消化：非空、整体落在 256 viewBox 内（含贝塞尔控制点余量）。
    func testHomePhosphorGlyphsParse() {
        XCTAssertEqual(HomePhosphor.allCases.count, 21, "首页 Phosphor 字形表意外增删")
        for icon in HomePhosphor.allCases {
            let path = HomeSVGPathParser.parse(icon.pathData)
            let box = path.boundingRect
            XCTAssertFalse(box.isNull || box.isEmpty, "\(icon) 解析结果为空")
            XCTAssertGreaterThan(box.width, 100, "\(icon) 解析后宽度异常：\(box)")
            XCTAssertGreaterThan(box.height, 100, "\(icon) 解析后高度异常：\(box)")
            XCTAssertLessThan(box.minX, 256, "\(icon) 越出 viewBox 右缘：\(box)")
            XCTAssertLessThan(box.minY, 256, "\(icon) 越出 viewBox 下缘：\(box)")
            XCTAssertGreaterThan(box.maxX, 0, "\(icon) 越出 viewBox 左缘：\(box)")
            XCTAssertGreaterThan(box.maxY, 0, "\(icon) 越出 viewBox 上缘：\(box)")
        }
    }

    func testIconMapperMappings() {
        let expectations = [
            ("赵光义晚年有怀念赵匡胤的记录吗", HomePhosphor.moon),
            ("今晚的红酒品鉴笔记", .wine),
            ("赵匡胤的打仗风格是什么样的？", .sword),
            ("皇帝成长计划复盘", .crown),
            ("赵匡胤在位 16 年也不短了，但是为什么", .crown),
            ("明朝十六帝的顺序。", .list),
            ("巫师三的经典 BGM 都有哪些", .musicNotes),
            ("中国各朝各代的都城都在哪", .mapPin),
            ("痛风药影响精子质量吗", .pill),
            ("秦皇汉武，唐宗宋祖，到底谁应该被排第一", .scales),
            ("梁圣和牢梁的区别是什么", .chatCircle),
        ]

        for (title, expectedSymbol) in expectations {
            XCTAssertEqual(
                HomeConversationIcon.icon(forTitle: title, isPinned: false),
                expectedSymbol,
                "标题“\(title)”应使用语义化的实心图标 \(expectedSymbol)"
            )
        }
        XCTAssertEqual(
            HomeConversationIcon.icon(forTitle: "任何会话标题", isPinned: true),
            .pushPin,
            "置顶会话必须优先显示实心图钉"
        )
        XCTAssertEqual(HomeConversationIcon.fallback, .chatCircle, "无匹配标题不能产生空头像")
    }

    func testPaletteDesignValues() {
        XCTAssertEqual(AmberTheme.neutralLight.background, 0xECE8E4, "暖灰画布必须是设计实测值")
        XCTAssertEqual(AmberTheme.neutralLight.surface, 0xF6F5F3, "暖灰卡片必须比画布亮一档")
        XCTAssertEqual(AmberTheme.neutralLight.surface2, 0xEDEBE7, "闲置头像底色必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.neutralLight.foreground, 0x161514, "主墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.neutralLight.foreground2, 0x55524D, "节标题墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.neutralLight.muted, 0x716D67, "次级墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.neutralLight.muted2, 0x8F8B85, "闲置头像墨必须匹配设计令牌")

        XCTAssertEqual(AmberTheme.paperLight.background, 0xEFE7D6, "暖纸画布必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.paperLight.surface, 0xFFFDF7, "暖纸卡片必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.paperLight.surface2, 0xF0EBE2, "暖纸次表面必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.paperLight.foreground, 0x1B1813, "暖纸主墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.paperLight.foreground2, 0x5B5449, "暖纸节标题墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.paperLight.muted, 0x746D62, "暖纸次级墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.paperLight.muted2, 0x918A80, "暖纸弱化墨必须匹配设计令牌")

        XCTAssertEqual(AmberTheme.whiteLight.background, 0xF5F5F4, "中性白画布必须是冷中性灰白")
        XCTAssertEqual(AmberTheme.whiteLight.surface, 0xFFFFFF, "中性白卡片必须为真白")
        XCTAssertEqual(AmberTheme.whiteLight.surface2, 0xEEEEED, "中性白次表面必须与画布/卡片分层")
        XCTAssertNotEqual(
            AmberTheme.whiteLight.background,
            AmberTheme.whiteLight.surface,
            "中性白 background 与 surface 不得同值"
        )

        XCTAssertEqual(AmberTheme.darkPalette.background, 0x0E0D10, "深色画布必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.darkPalette.surface, 0x1F1D23, "深色卡片必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.darkPalette.surface2, 0x2B2930, "深色次表面必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.darkPalette.foreground, 0xF4F1ED, "深色主墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.darkPalette.foreground2, 0xC3BEC5, "深色节标题墨必须匹配设计令牌")
        XCTAssertEqual(AmberTheme.darkPalette.muted, 0xAAA5AD, "深色次级墨必须匹配设计令牌")

        XCTAssertEqual(AmberThemeRuntime.Paper.paper.displayName, "暖纸")
        XCTAssertEqual(AmberThemeRuntime.Paper.neutral.displayName, "暖灰")
        XCTAssertEqual(AmberThemeRuntime.Paper.white.displayName, "中性白")
        XCTAssertFalse(AmberThemeRuntime.Paper.white.isImmersive)
    }

    func testAccentDesignValues() {
        XCTAssertEqual(AmberAccentOption.allCases.first, .amberGold, "默认 accent 必须是琥珀金")
        XCTAssertEqual(AmberAccentOption.amberGold.accentHex, 0xB9863A, "琥珀金色值必须精确匹配设计")
        XCTAssertEqual(AmberAccentOption.amberGold.inkHex, 0x231602, "FAB 前景墨色必须精确匹配设计")
        // fab / focus 必须跟随 runtime accent，不得再钉死琥珀金常量。
        let runtime = AmberThemeRuntime.shared
        let previous = (runtime.accentHex, runtime.accentInkHex)
        runtime.apply(.mistBlue)
        XCTAssertEqual(runtime.accentHex, AmberAccentOption.mistBlue.accentHex)
        // 读一次以保证观察链路；颜色本身是动态的，此处校验 runtime 写入。
        XCTAssertEqual(runtime.accentInkHex, AmberAccentOption.mistBlue.inkHex)
        runtime.accentHex = previous.0
        runtime.accentInkHex = previous.1
    }

    func testContinueModelHidesWhenNoResumableWork() {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        XCTAssertNil(
            HomeContinueCardModel.resolve(now: now),
            "没有真实待继续任务时应收起首行，避免与下方功能入口重复"
        )
    }

    func testContinueModelChoosesLatestValidNovelDraft() throws {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        let olderID = NovelProjectID(UUID(uuidString: "00000000-0000-0000-0000-000000000001")!)
        let newerID = NovelProjectID(UUID(uuidString: "00000000-0000-0000-0000-000000000002")!)
        let older = HomeNovelProjectRef(
            id: olderID,
            name: "旧项目",
            updatedAt: now.addingTimeInterval(-3600),
            isDegraded: false
        )
        let newer = HomeNovelProjectRef(
            id: newerID,
            name: "新项目",
            updatedAt: now.addingTimeInterval(-60),
            isDegraded: false
        )
        let resumed = try XCTUnwrap(HomeContinueCardModel.resolve(
            novelProjects: [older, newer],
            now: now
        ))
        XCTAssertEqual(resumed.title, "小说创作", "首行标题应保持为当前任务所属功能")
        XCTAssertEqual(resumed.ctaTitle, "继续", "有项目时 CTA 应为继续")
        XCTAssertEqual(resumed.destination, .resumeProject(newerID), "应选择最近更新的有效项目")
        XCTAssertTrue(resumed.meta.contains("《"), "继续说明必须使用书名号")
        XCTAssertTrue(resumed.meta.contains(newer.name), "继续说明必须包含最近项目的名称")

        let degraded = HomeNovelProjectRef(
            id: NovelProjectID(UUID(uuidString: "00000000-0000-0000-0000-000000000003")!),
            name: "损坏项目",
            updatedAt: now.addingTimeInterval(60),
            isDegraded: true
        )
        XCTAssertNil(
            HomeContinueCardModel.resolve(novelProjects: [degraded], now: now),
            "只有损坏项目时不应伪造可继续任务"
        )

        let mixed = try XCTUnwrap(HomeContinueCardModel.resolve(
            novelProjects: [older, degraded],
            now: now
        ))
        XCTAssertEqual(mixed.destination, .resumeProject(olderID), "更新更晚的损坏项目不能遮蔽有效项目")
        XCTAssertTrue(mixed.meta.contains(older.name), "混合项目时说明必须指向有效项目")
    }

    func testContinueModelUsesStableCrossFeaturePriority() throws {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        let novelID = NovelProjectID(UUID(uuidString: "00000000-0000-0000-0000-000000000010")!)
        let novel = HomeNovelProjectRef(
            id: novelID,
            name: "雾城",
            updatedAt: now,
            isDegraded: false
        )
        let recoverableRead = HomeDeepReadTaskRef(
            id: "read-failed",
            title: "Agent 趋势",
            status: .failed,
            updatedAt: now.addingTimeInterval(-30),
            workspaceSyncFailed: nil
        )
        let runningCouncil = HomeCouncilTaskRef(
            id: "council-running",
            title: "品牌定位讨论",
            status: .running,
            updatedAt: now.addingTimeInterval(-60),
            canContinue: true
        )
        let syncBlockedRead = HomeDeepReadTaskRef(
            id: "read-sync",
            title: "年度报告",
            status: .succeeded,
            updatedAt: now.addingTimeInterval(-120),
            workspaceSyncFailed: "Workspace 写入失败"
        )

        let selected = try XCTUnwrap(HomeContinueCardModel.resolve(
            novelProjects: [novel],
            councilTask: runningCouncil,
            deepReadTasks: [recoverableRead, syncBlockedRead],
            now: now
        ))

        XCTAssertEqual(selected.destination, .deepReadTask("read-sync"))
        XCTAssertEqual(selected.title, "深度阅读")
        XCTAssertEqual(selected.ctaTitle, "处理", "等待用户处理的任务必须高于运行、重试和草稿")
        XCTAssertTrue(selected.meta.contains("同步失败"))
    }

    func testCompletedImageResultBeatsDraftButNotRecoverableWork() throws {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        let image = HomeImageGenerationRef(
            id: "conversation|message|tool",
            conversationID: "conversation",
            messageID: "message",
            toolCallID: "tool",
            prompt: "雨夜里的未来城市",
            state: .completed,
            updatedAt: now
        )
        let novel = HomeNovelProjectRef(
            id: NovelProjectID(UUID(uuidString: "00000000-0000-0000-0000-000000000011")!),
            name: "雾城",
            updatedAt: now.addingTimeInterval(60),
            isDegraded: false
        )
        let completedSelected = try XCTUnwrap(HomeContinueCardModel.resolve(
            novelProjects: [novel],
            imageGeneration: image,
            now: now
        ))

        XCTAssertEqual(completedSelected.feature, .imageGeneration)
        XCTAssertEqual(completedSelected.feature.icon, .imageSquare)
        XCTAssertEqual(completedSelected.ctaTitle, "查看图片")
        XCTAssertTrue(completedSelected.meta.hasPrefix("图片已生成 · "))
        XCTAssertEqual(
            completedSelected.destination,
            .generatedImage(
                ChatMessageAnchor(
                    conversationID: "conversation",
                    messageID: "message",
                    toolCallID: "tool"
                )
            )
        )

        let recoverableRead = HomeDeepReadTaskRef(
            id: "read-failed",
            title: "Agent 趋势",
            status: .failed,
            updatedAt: now.addingTimeInterval(-60),
            workspaceSyncFailed: nil
        )
        let recoverableSelected = try XCTUnwrap(HomeContinueCardModel.resolve(
            novelProjects: [novel],
            deepReadTasks: [recoverableRead],
            imageGeneration: image,
            now: now
        ))
        XCTAssertEqual(recoverableSelected.destination, .deepReadTask("read-failed"))
    }

    func testImageGenerationResumeProjectionUsesOwningMessageAndRejectsFalseSuccess() throws {
        let running = imageToolMessage(
            toolCallID: "tool-running",
            prompt: "山谷",
            output: [],
            finished: false
        )
        XCTAssertNil(ChatImageGenerationResumeProjection.latest(
            in: [running],
            conversationID: "conversation-running",
            isGenerationActive: false
        ))
        let runningContext = try XCTUnwrap(ChatImageGenerationResumeProjection.latest(
            in: [running],
            conversationID: "conversation-running",
            isGenerationActive: true
        ))
        XCTAssertEqual(runningContext.state, .running)
        XCTAssertEqual(runningContext.messageID, ChatMessageProjector.messageId(for: running))

        let completed = imageToolMessage(
            toolCallID: "tool-completed",
            prompt: "雨夜里的未来城市",
            output: [UIMessagePart.Image(url: "amber-image-generation://result.png", metadata: nil)],
            finished: true
        )
        let completedContext = try XCTUnwrap(ChatImageGenerationResumeProjection.latest(
            in: [completed],
            conversationID: "conversation-completed",
            isGenerationActive: false
        ))
        XCTAssertEqual(completedContext.state, .completed)
        XCTAssertEqual(completedContext.prompt, "雨夜里的未来城市")
        XCTAssertEqual(completedContext.toolCallID, "tool-completed")
        XCTAssertEqual(completedContext.messageID, ChatMessageProjector.messageId(for: completed))

        let falseSuccess = imageToolMessage(
            toolCallID: "tool-false-success",
            prompt: "没有图片",
            output: [UIMessagePart.Text(
                text: #"{"status":"ok","source":"generate_image","files":[]}"#,
                metadata: nil
            )],
            finished: true
        )
        XCTAssertNil(ChatImageGenerationResumeProjection.latest(
            in: [falseSuccess],
            conversationID: "conversation-false-success",
            isGenerationActive: false
        ))

        let newerCompleted = ChatImageGenerationResumeContext(
            id: "completed",
            conversationID: "conversation-completed",
            messageID: "message-completed",
            toolCallID: "tool-completed",
            prompt: "已完成",
            state: .completed,
            updatedAt: Date(timeIntervalSinceReferenceDate: 2_000)
        )
        let olderRunning = ChatImageGenerationResumeContext(
            id: "running",
            conversationID: "conversation-running",
            messageID: "message-running",
            toolCallID: "tool-running",
            prompt: "生成中",
            state: .running,
            updatedAt: Date(timeIntervalSinceReferenceDate: 1_000)
        )
        XCTAssertEqual(
            ChatImageGenerationResumeProjection.preferred(
                in: [newerCompleted, olderRunning]
            ),
            olderRunning,
            "运行中的生图必须高于较新的已完成结果"
        )

        let matched = try XCTUnwrap(ChatImageGenerationResumeProjection.matching(
            in: [completed],
            conversationID: "conversation-completed",
            messageID: completedContext.messageID,
            toolCallID: completedContext.toolCallID,
            isGenerationActive: false
        ))
        XCTAssertEqual(matched, completedContext)

        let suiteName = "HomeImageResume-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        XCTAssertNil(ChatImageGenerationResumeConsumption.markViewedIfCompleted(
            anchor: ChatMessageAnchor(
                conversationID: "conversation-running",
                messageID: runningContext.messageID,
                toolCallID: runningContext.toolCallID
            ),
            messages: [running],
            isGenerationActive: true,
            userDefaults: defaults
        ))
        XCTAssertEqual(ChatImageGenerationResumeConsumption.markViewedIfCompleted(
            anchor: ChatMessageAnchor(
                conversationID: "conversation-completed",
                messageID: completedContext.messageID,
                toolCallID: completedContext.toolCallID
            ),
            messages: [completed],
            isGenerationActive: false,
            userDefaults: defaults
        ), completedContext.id)
        XCTAssertEqual(
            defaults.string(forKey: ChatImageGenerationResumeConsumption.viewedCompletionIDKey),
            completedContext.id
        )
    }

    func testContinueModelUsesNewestCandidateWithinSamePriority() throws {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        let older = HomeDeepReadTaskRef(
            id: "read-older",
            title: "旧阅读",
            status: .running,
            updatedAt: now.addingTimeInterval(-120),
            workspaceSyncFailed: nil
        )
        let newer = HomeDeepReadTaskRef(
            id: "read-newer",
            title: "新阅读",
            status: .queued,
            updatedAt: now.addingTimeInterval(-30),
            workspaceSyncFailed: nil
        )

        let selected = try XCTUnwrap(HomeContinueCardModel.resolve(
            deepReadTasks: [older, newer],
            now: now
        ))

        XCTAssertEqual(selected.destination, .deepReadTask("read-newer"))
        XCTAssertEqual(selected.ctaTitle, "查看")
    }

    func testContinueModelOnlySurfacesTruthfulMiniAppAndCouncilWork() throws {
        let now = Date(timeIntervalSinceReferenceDate: 1_000_000)
        let alreadyOpened = HomeMiniAppRef(
            id: "app-opened",
            title: "已打开应用",
            latestVersionCreatedAt: now.addingTimeInterval(-120),
            lastRunAt: now.addingTimeInterval(-60)
        )
        let newVersion = HomeMiniAppRef(
            id: "app-new-version",
            title: "财务计算器",
            latestVersionCreatedAt: now.addingTimeInterval(-30),
            lastRunAt: now.addingTimeInterval(-90)
        )
        let completedCouncil = HomeCouncilTaskRef(
            id: "council-complete",
            title: "已完成讨论",
            status: .completed,
            updatedAt: now,
            canContinue: true
        )

        let selected = try XCTUnwrap(HomeContinueCardModel.resolve(
            councilTask: completedCouncil,
            miniApps: [alreadyOpened, newVersion],
            now: now
        ))

        XCTAssertEqual(selected.destination, .miniAppRunner("app-new-version"))
        XCTAssertEqual(selected.title, "小应用")
        XCTAssertEqual(selected.ctaTitle, "打开")
        XCTAssertTrue(selected.meta.contains("新版本尚未打开"))

        XCTAssertNil(
            HomeContinueCardModel.resolve(
                councilTask: completedCouncil,
                miniApps: [alreadyOpened],
                now: now
            ),
            "已完成议会和已经运行过的最新小应用版本都不能冒充未完成任务"
        )
    }

    func testNovelSummaryCarriesRunningStateIntoHomeCandidate() throws {
        var document = try NovelTestFixtures.document()
        let branch = try XCTUnwrap(document.branches.first)
        let session = try XCTUnwrap(document.sessions.first)
        document.activeRuns.append(NovelActiveRunRecord(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            requestPayloadSHA256: NovelTestFixtures.hashA,
            branchID: branch.id,
            sessionID: session.id,
            kind: .discussion,
            mode: .discussPlan,
            granularity: nil,
            userMessageID: NovelMessageID(),
            messageID: NovelMessageID(),
            candidateID: nil,
            sourceChapterVersionID: nil,
            contextualCharacterMention: nil,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .running,
            partialContent: "",
            receiptID: NovelReceiptID(),
            startedAt: document.project.updatedAt,
            terminalAt: nil,
            interruptionReason: nil,
            terminalFailure: nil,
            chapterPlanDigest: nil
        ))

        let summary = NovelProjectSummary(document: document)
        let selected = try XCTUnwrap(HomeContinueCardModel.resolve(
            novelProjects: [HomeNovelProjectRef(summary)],
            now: document.project.updatedAt
        ))

        XCTAssertTrue(summary.hasRunningRun)
        XCTAssertEqual(selected.destination, .resumeProject(document.project.id))
        XCTAssertEqual(selected.ctaTitle, "查看")
        XCTAssertTrue(selected.meta.contains("生成中"))
    }

    func testHomeAdaptiveTypeAndMotionContracts() throws {
        let testsDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testsDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/PlaceholderViews.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("@ScaledMetric(relativeTo: .caption2) private var shortcutLabelSize: CGFloat = 11"))
        XCTAssertTrue(source.contains("@ScaledMetric(relativeTo: .subheadline) private var continueTitleSize: CGFloat = 15"))
        XCTAssertTrue(source.contains("@ScaledMetric(relativeTo: .body) private var conversationTitleSize: CGFloat = 16"))
        XCTAssertTrue(source.contains(".frame(maxWidth: .infinity, minHeight: 44)"))
        XCTAssertTrue(source.contains(".frame(minHeight: 72)"))
        XCTAssertTrue(source.contains(".lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)"))
        XCTAssertTrue(source.contains("if dynamicTypeSize.isAccessibilitySize"))
        XCTAssertTrue(source.contains("ScrollView(.horizontal)"))
        XCTAssertTrue(source.contains(".homeCascade(delay: 0.18, enabled: !cascadeComplete)"))
        let ringStart = try XCTUnwrap(source.range(of: "private struct ConversationGeneratingRing"))
        let ringEnd = try XCTUnwrap(source.range(of: "struct SearchView", range: ringStart.upperBound..<source.endIndex))
        let ring = String(source[ringStart.lowerBound..<ringEnd.lowerBound])
        XCTAssertTrue(ring.contains("@Environment(\\.accessibilityReduceMotion) private var reduceMotion"))
        XCTAssertTrue(ring.contains("if reduceMotion"))
        XCTAssertTrue(source.contains("UIAccessibility.post(notification: .announcement"))
        XCTAssertTrue(source.contains("@AccessibilityFocusState private var deepReadShortcutFocused: Bool"))
        // E 版当前会话头像呼吸光晕：只挂 isCurrent，Reduce Motion 关闭动画。
        XCTAssertTrue(source.contains("CurrentConversationAvatarGlow()"))
        XCTAssertTrue(source.contains("enum HomeCurrentAvatarBreath"))
        XCTAssertTrue(source.contains("static var activeAvatarGlow"))
    }

    func testCurrentAvatarBreathIntensityMatchesPrototypeTiming() {
        // 原型：delay 1.6s，period 3.4s，ease-in-out 0 → 1 → 0
        XCTAssertEqual(HomeCurrentAvatarBreath.delaySeconds, 1.6, accuracy: 0.001)
        XCTAssertEqual(HomeCurrentAvatarBreath.periodSeconds, 3.4, accuracy: 0.001)
        XCTAssertEqual(HomeCurrentAvatarBreath.intensity(elapsed: 0, reduceMotion: false), 0, accuracy: 0.001)
        XCTAssertEqual(HomeCurrentAvatarBreath.intensity(elapsed: 1.59, reduceMotion: false), 0, accuracy: 0.001)
        XCTAssertEqual(
            HomeCurrentAvatarBreath.intensity(elapsed: 1.6 + 3.4 * 0.5, reduceMotion: false),
            1.0,
            accuracy: 0.001,
            "半周期应到峰值光晕"
        )
        XCTAssertEqual(
            HomeCurrentAvatarBreath.intensity(elapsed: 1.6 + 3.4, reduceMotion: false),
            0,
            accuracy: 0.001,
            "整周期应回到零"
        )
        XCTAssertEqual(
            HomeCurrentAvatarBreath.intensity(elapsed: 100, reduceMotion: true),
            0,
            accuracy: 0.001,
            "Reduce Motion 必须关掉呼吸（初始关键无光晕）"
        )
    }

    @MainActor
    func testListPreviewSanitizeAndWiringContracts() throws {
        XCTAssertEqual(
            ChatViewModel.sanitizeListPreview("  \"已整理好宋史相关段落，要看看吗？\"  \n第二行"),
            "已整理好宋史相关段落，要看看吗？"
        )
        let long = String(repeating: "摘要", count: 20)
        let truncated = ChatViewModel.sanitizeListPreview("- \(long)")
        XCTAssertEqual(truncated.count, 28, "浓缩预览必须限长 28")
        XCTAssertEqual(ChatViewModel.sanitizeListPreview(""), "")

        let testsDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let chatVM = try String(
            contentsOf: testsDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/ChatViewModel.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(chatVM.contains("generateConversationListPreview()"))
        XCTAssertTrue(chatVM.contains("ConversationListPreviewGenerator.schedule"))

        let generator = try String(
            contentsOf: testsDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/ConversationListPreviewGenerator.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(generator.contains("requestTokens"))
        XCTAssertTrue(generator.contains("titleModelId"))

        let bg = try String(
            contentsOf: testsDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/IOSChatBackgroundGenerationCoordinator.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(
            bg.contains("ConversationListPreviewGenerator.schedule"),
            "后台成功完成必须挂 list preview，避免仅 FG generationSucceeded"
        )

        let store = try String(
            contentsOf: testsDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/IOSConversationStore.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(store.contains("listPreviewsByConversationId"))
        XCTAssertTrue(store.contains("list-previews.json"))
        XCTAssertTrue(store.contains("deletedConversationIds.contains(key)"))

        let home = try String(
            contentsOf: testsDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/PlaceholderViews.swift"),
            encoding: .utf8
        )
        XCTAssertTrue(home.contains("listPreview: conversationStore.listPreview(for: summary.id)"))
        XCTAssertTrue(home.contains("restartMetaCycleIfNeeded"))
        XCTAssertTrue(home.contains("showingListPreview"))
        XCTAssertTrue(home.contains("scaleAnchor: .leading"))
        XCTAssertTrue(home.contains("duration: 0.4"))
    }

    private func imageToolMessage(
        toolCallID: String,
        prompt: String,
        output: [UIMessagePart],
        finished: Bool
    ) -> UIMessage {
        let seed = UIMessage.companion.assistant(prompt: "")
        return UIMessage(
            id: seed.id,
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: toolCallID,
                toolName: "generate_image",
                input: #"{"prompt":"\#(prompt)"}"#,
                output: output,
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: finished ? chatNowLocalDateTime() : nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }
}
