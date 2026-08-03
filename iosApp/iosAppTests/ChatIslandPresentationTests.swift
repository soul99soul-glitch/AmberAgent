import XCTest
@testable import iosApp

/// 活动岛重设计（安静的星核）定点测试：映射、辉光 spec、截断、presentation reducer。
final class ChatIslandPresentationTests: XCTestCase {

    // MARK: - helpers

    private func makeState(
        _ kind: ChatActivityIslandState.Kind,
        title: String = "标题",
        detail: String? = nil,
        systemImage: String = "sparkles",
        tint: ChatActivityIslandTint = .amber,
        toolID: String? = nil
    ) -> ChatActivityIslandState {
        if kind == .title {
            return .conversationTitle(title)
        }
        return .activity(
            kind: kind,
            title: title,
            detail: detail,
            systemImage: systemImage,
            tint: tint,
            toolID: toolID
        )
    }

    // MARK: - orb 六态映射

    func testOrbMappingKeepsExistingThreeStates() {
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .waiting, systemImage: "sparkles"), .listening)
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .thinking, systemImage: "brain"), .working)
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .generating, systemImage: "text.bubble"), .composing)
    }

    func testOrbMappingAssignsIdleStatesToToolSemantics() {
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .tool, systemImage: "magnifyingglass"), .searching)
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .tool, systemImage: "globe"), .searching)
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .tool, systemImage: "photo.on.rectangle"), .shaping)
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .tool, systemImage: "wrench"), .solving)
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .image, systemImage: "viewfinder"), .shaping)
        XCTAssertEqual(ChatActivityIslandMapping.orbState(kind: .awaitingUser, systemImage: "checkmark.circle"), .listening)
    }

    func testOrbMappingTitleHasNoOrb() {
        XCTAssertNil(ChatActivityIslandMapping.orbState(kind: .title, systemImage: "text.bubble"))
    }

    // MARK: - 辉光 spec 映射

    func testGlowSpectralPeriodsPerAIState() {
        let waiting = ChatActivityIslandMapping.glowSpec(for: makeState(.waiting), terminalHold: false)
        XCTAssertEqual(waiting?.rotationPeriod, 8)
        XCTAssertFalse(waiting?.breathing ?? true)
        XCTAssertEqual(waiting?.stops.count, 4, "光谱循环：amber→cyan→violet→amber")
        XCTAssertEqual(waiting?.stops.first?.hex, waiting?.stops.last?.hex, "光谱必须闭环")

        let thinking = ChatActivityIslandMapping.glowSpec(for: makeState(.thinking), terminalHold: false)
        XCTAssertEqual(thinking?.rotationPeriod, 14)

        let generating = ChatActivityIslandMapping.glowSpec(for: makeState(.generating), terminalHold: false)
        XCTAssertEqual(generating?.rotationPeriod, 20)
        XCTAssertTrue(generating?.breathing ?? false, "生成中唯一带呼吸")
    }

    func testGlowToolUsesStaticHueFromTint() {
        let spec = ChatActivityIslandMapping.glowSpec(
            for: makeState(.tool, tint: .cyan),
            terminalHold: false
        )
        XCTAssertEqual(spec?.rotationPeriod, 0, "工具态常亮不转")
        XCTAssertFalse(spec?.breathing ?? true)
        XCTAssertEqual(spec?.stops.count, 3)
        XCTAssertEqual(spec?.stops.first?.hex, 0x2AA0BC)
    }

    func testGlowAwaitingUserIsStaticAmber() {
        let spec = ChatActivityIslandMapping.glowSpec(for: makeState(.awaitingUser), terminalHold: false)
        XCTAssertEqual(spec?.rotationPeriod, 0)
        XCTAssertEqual(spec?.stops.first?.hex, 0xD98324)
    }

    func testGlowTerminalHoldOverridesToStaticRed() {
        let spec = ChatActivityIslandMapping.glowSpec(
            for: makeState(.generating),
            terminalHold: true
        )
        XCTAssertEqual(spec?.rotationPeriod, 0, "终态边光永不旋转")
        XCTAssertEqual(spec?.stops.count, 1)
        XCTAssertEqual(spec?.stops.first?.hex, 0xC8402F)
    }

    func testGlowTitleHasNone() {
        XCTAssertNil(ChatActivityIslandMapping.glowSpec(for: makeState(.title), terminalHold: false))
    }

    func testStrokeLadderFadesOutward() {
        let ladder = IslandGlowSpec.strokeLadder
        XCTAssertEqual(ladder.first, IslandGlowStroke(width: 1.0, opacity: 0.85))
        for index in 1..<ladder.count {
            XCTAssertGreaterThan(ladder[index].width, ladder[index - 1].width, "越往外越宽")
            XCTAssertLessThan(ladder[index].opacity, ladder[index - 1].opacity, "越往外越淡")
        }
    }

    // MARK: - 词边界截断

    func testCompactTextKeepsShortInput() {
        XCTAssertEqual(ChatActivityIslandMapping.compactText("短标题", limit: 14), "短标题")
        XCTAssertEqual(ChatActivityIslandMapping.compactText("两行\n合一", limit: 14), "两行 合一")
    }

    func testCompactTextCutsAtWordBoundary() {
        // limit=20 → 边界下限 12：位置 8 的空格不命中，位置 12/19 的空格命中，
        // 在最后一个命中边界（“design” 后）截断。
        let raw = "Reading the design document carefully"
        let result = ChatActivityIslandMapping.compactText(raw, limit: 20)
        XCTAssertEqual(result, "Reading the design", "应在 ≥60% 的最后空格处截断而不是切半词")
    }

    func testCompactTextTrimsTrailingPunctuation() {
        let raw = "正在处理：第一批次，第二批次，第三批次"
        let result = ChatActivityIslandMapping.compactText(raw, limit: 12)
        XCTAssertFalse(result.hasSuffix("，"), "截断处不留尾随标点")
        XCTAssertFalse(result.hasSuffix("："))
    }

    func testCompactTextIgnoresBoundaryBelowFloor() {
        // limit=20 → 边界下限 12：空格位于位置 6，低于下限，硬切。
        let raw = "深度研究 Apple Intelligence 辉光设计语言"
        let result = ChatActivityIslandMapping.compactText(raw, limit: 20)
        XCTAssertEqual(result.count, 20, "低于 60% 下限的边界不采用，维持硬切")
    }

    func testCompactTextHardCutsWhenNoBoundaryPastFloor() {
        let raw = "超级无敌长的没有任何分隔的一段标题文本内容"
        let result = ChatActivityIslandMapping.compactText(raw, limit: 10)
        XCTAssertEqual(result.count, 10, "无可用边界时维持硬切（原行为）")
    }

    // MARK: - Reducer：settle 与 terminalHold

    func testActiveToIdleSettlesBrieflyWhenOrbPresent() {
        let now: TimeInterval = 100
        let active = ChatIslandPresentation.active(makeState(.generating))
        let title = makeState(.title, title: "会话")
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: active, next: title, failedToolID: nil, now: now, reduceMotion: false
        )
        guard case .settling(let held, let fallback, let until) = next else {
            return XCTFail("生成→标题应 settle 0.4s 安静退场，得到 \(next)")
        }
        XCTAssertEqual(held, makeState(.generating))
        XCTAssertEqual(fallback, title)
        XCTAssertEqual(until, now + ChatIslandPresentationReducer.settleDuration)
    }

    func testFailedToolTransitionsToTerminalHold() {
        let now: TimeInterval = 100
        let tool = makeState(.tool, title: "正在搜索", systemImage: "magnifyingglass", toolID: "tool-1")
        let title = makeState(.title)
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: .active(tool), next: title, failedToolID: "tool-1", now: now, reduceMotion: false
        )
        guard case .terminalHold(let held, _, let until) = next else {
            return XCTFail("失败工具应 terminalHold 2s，得到 \(next)")
        }
        XCTAssertEqual(held.title, "未完成", "单行活动岛必须把失败事实放在可见标题中")
        XCTAssertEqual(held.detail, "正在搜索", "原工具标题只保留给无障碍摘要")
        XCTAssertEqual(held.tint, .red)
        XCTAssertEqual(until, now + ChatIslandPresentationReducer.terminalHoldDuration)
    }

    func testMismatchedFailedToolIDDoesNotHold() {
        let now: TimeInterval = 100
        let tool = makeState(.tool, systemImage: "wrench", toolID: "tool-1")
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: .active(tool), next: makeState(.title),
            failedToolID: "tool-other", now: now, reduceMotion: false
        )
        XCTAssertFalse(next.isTerminalHold, "id 不匹配不得亮红")
        XCTAssertTrue(next.isSettling, "普通完成走 settle")
    }

    func testFailedImageToolTransitionsToTerminalHold() {
        let now: TimeInterval = 100
        let imageTool = makeState(
            .image, title: "生成图片", systemImage: "photo.on.rectangle", toolID: "img-1"
        )
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: .active(imageTool), next: makeState(.title),
            failedToolID: "img-1", now: now, reduceMotion: false
        )
        XCTAssertTrue(next.isTerminalHold, "图片类工具失败同样亮红（review P2 修复）")
    }

    func testImageKindWithoutToolIDDoesNotHold() {
        let now: TimeInterval = 100
        // 图片识别路径的 .image 态 toolID 为 nil，不参与失败匹配。
        let imageState = makeState(.image, title: "识别图片", systemImage: "viewfinder")
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: .active(imageState), next: makeState(.title),
            failedToolID: "img-1", now: now, reduceMotion: false
        )
        XCTAssertFalse(next.isTerminalHold)
        XCTAssertTrue(next.isSettling)
    }

    func testNewActivityInterruptsSettleImmediately() {
        let now: TimeInterval = 100
        let settling = ChatIslandPresentation.settling(
            active: makeState(.generating), fallback: makeState(.title), until: now + 0.4
        )
        let revived = makeState(.thinking)
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: settling, next: revived, failedToolID: nil, now: now + 0.1, reduceMotion: false
        )
        XCTAssertEqual(next, .active(revived), "新活跃态打断 settle，不叠加第二段动画")
    }

    func testTitleRefreshDuringSettleOnlyUpdatesFallback() {
        let now: TimeInterval = 100
        let until = now + 0.4
        let settling = ChatIslandPresentation.settling(
            active: makeState(.generating), fallback: makeState(.title, title: "旧"), until: until
        )
        let refreshed = makeState(.title, title: "新")
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: settling, next: refreshed, failedToolID: nil, now: now + 0.1, reduceMotion: false
        )
        guard case .settling(_, let fallback, let keptUntil) = next else {
            return XCTFail("停留期标题刷新不得取消 settle")
        }
        XCTAssertEqual(fallback, refreshed)
        XCTAssertEqual(keptUntil, until, "截止时刻不变，不延长停留")
    }

    func testReduceMotionSkipsSettle() {
        let now: TimeInterval = 100
        let next = ChatIslandPresentationReducer.stateChanged(
            prev: .active(makeState(.generating)), next: makeState(.title),
            failedToolID: nil, now: now, reduceMotion: true
        )
        XCTAssertEqual(next, .idle(makeState(.title)), "减少动态：直落 idle，无 settle")
    }

    // MARK: - Reducer：timeout

    func testTimeoutAfterDeadlineFallsBackToIdle() {
        let title = makeState(.title, title: "会话")
        let settling = ChatIslandPresentation.settling(
            active: makeState(.generating), fallback: title, until: 100
        )
        XCTAssertEqual(
            ChatIslandPresentationReducer.timeout(prev: settling, now: 100.01),
            .idle(title)
        )
        XCTAssertEqual(
            ChatIslandPresentationReducer.timeout(prev: settling, now: 99.9),
            settling,
            "未到截止时刻保持不变"
        )
    }

    func testTimeoutTerminalHoldFallsBackToIdle() {
        let title = makeState(.title)
        let hold = ChatIslandPresentation.terminalHold(
            active: makeState(.tool, detail: "未完成", tint: .red, toolID: "tool-1"),
            fallback: title, until: 100
        )
        XCTAssertEqual(ChatIslandPresentationReducer.timeout(prev: hold, now: 101), .idle(title))
    }

    func testTimeoutLeavesActiveUntouched() {
        let active = ChatIslandPresentation.active(makeState(.thinking))
        XCTAssertEqual(ChatIslandPresentationReducer.timeout(prev: active, now: 500), active)
    }

    // MARK: - Presentation 访问器

    func testPresentationAccessors() {
        let held = makeState(.tool, detail: "未完成", tint: .red, toolID: "tool-1")
        let hold = ChatIslandPresentation.terminalHold(active: held, fallback: makeState(.title), until: 42)
        XCTAssertEqual(hold.displayedState, held, "停留期渲染被持有的活跃态")
        XCTAssertEqual(hold.holdDeadline, 42)
        XCTAssertTrue(hold.isFrozen, "停留期 orb 冻结")
        XCTAssertNil(ChatIslandPresentation.idle(makeState(.title)).holdDeadline)
        XCTAssertFalse(ChatIslandPresentation.active(makeState(.waiting)).isFrozen)
    }
}
