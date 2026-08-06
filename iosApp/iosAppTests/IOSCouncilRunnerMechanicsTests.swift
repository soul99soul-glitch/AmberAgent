import XCTest
import SwiftUI
import Observation
@preconcurrency import Shared
@testable import iosApp

/// Council runner mechanics tests. The iOS Room runner is the formal execution
/// path; fake streamers/researchers keep these tests offline and deterministic.
@MainActor
final class IOSCouncilRunnerMechanicsTests: XCTestCase {

    func testAppShellOwnsOneCouncilRuntimeAndOneDestination() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosRoot = testDirectory.deletingLastPathComponent()
        let shell = try String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/AppShell.swift"),
            encoding: .utf8
        )
        let runtime = try String(
            contentsOf: iosRoot.appendingPathComponent("iosApp/CouncilChatRuntimeView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(shell.contains("@State private var councilChatViewModel: CouncilChatViewModel"))
        XCTAssertTrue(shell.contains("councilChatViewModel: councilChatViewModel"))
        XCTAssertTrue(shell.contains("councilChatViewModel.runtimeWillEnterBackground()"))
        XCTAssertFalse(shell.contains("councilChatViewModel.runtimeDidBecomeActive()"))
        XCTAssertEqual(
            shell.components(separatedBy: "viewModel: councilChatViewModel").count - 1,
            1,
            "The single Council route must receive the AppShell-owned runtime."
        )
        XCTAssertFalse(shell.contains("case councilChat"))
        XCTAssertTrue(runtime.contains("viewModel: CouncilChatViewModel? = nil"))
        XCTAssertTrue(runtime.contains("viewModel ?? CouncilChatViewModel("))
    }

    func testCouncilPresentationSessionCoalescesSnapshotsUntilFlushWindow() async throws {
        let probe = CouncilPresentationProbe()
        let didPublish = expectation(description: "Council presentation window flushed")
        let session = IOSCouncilTextPresentationSession(
            flushDelayNanoseconds: 20_000_000,
            snapshotProvider: {
                probe.snapshotCount += 1
                return probe.authoritativeText
            },
            onUpdate: {
                probe.published.append($0)
                didPublish.fulfill()
            }
        )

        probe.authoritativeText = "A"
        session.scheduleFlush()
        probe.authoritativeText = "AB"
        session.scheduleFlush()
        probe.authoritativeText = "ABC"
        session.scheduleFlush()

        XCTAssertEqual(probe.snapshotCount, 0, "Chunk intake must not snapshot before the presentation window flushes.")
        await fulfillment(of: [didPublish], timeout: 1)
        XCTAssertEqual(probe.snapshotCount, 1)
        XCTAssertEqual(probe.published, ["ABC"], "One window should publish only the latest cumulative snapshot.")
    }

    func testCouncilPresentationSessionTerminalFlushIsExactAndCancelsDelayedFlush() async throws {
        let probe = CouncilPresentationProbe(authoritativeText: "partial")
        let session = IOSCouncilTextPresentationSession(
            flushDelayNanoseconds: 1_000_000_000,
            snapshotProvider: {
                probe.snapshotCount += 1
                return probe.authoritativeText
            },
            onUpdate: { probe.published.append($0) }
        )

        session.scheduleFlush()
        probe.authoritativeText = "authoritative final"
        let final = session.flushAndClose()

        XCTAssertEqual(final, "authoritative final")
        XCTAssertEqual(probe.snapshotCount, 1)
        XCTAssertEqual(probe.published, ["authoritative final"])
        try await Task.sleep(nanoseconds: 40_000_000)
        XCTAssertEqual(probe.snapshotCount, 1, "The cancelled delayed flush must not snapshot after terminal close.")
        XCTAssertEqual(probe.published, ["authoritative final"])
    }

    func testCouncilGeneratedTextSnapshotDoesNotExposeInputBeforeAssistantOutput() {
        let inputMessages = [
            UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.system,
                parts: [UIMessagePart.Text(text: "内部主持提示", metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            ),
            UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.user,
                parts: [UIMessagePart.Text(text: "内部议会请求", metadata: nil)],
                annotations: [],
                createdAt: chatNowLocalDateTime(),
                finishedAt: chatNowLocalDateTime(),
                modelId: nil,
                usage: nil,
                translation: nil
            )
        ]
        let accumulator = MessageStreamAccumulator(initialMessages: inputMessages, model: nil)

        XCTAssertEqual(
            IOSCouncilGeneratedTextSnapshot.text(from: accumulator.snapshot()),
            "",
            "Usage-only, empty completion, error, or cancellation before the first assistant chunk must not publish the internal user prompt."
        )

        let assistant = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: "对用户可见的生成结果", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        XCTAssertEqual(
            IOSCouncilGeneratedTextSnapshot.text(from: inputMessages + [assistant]),
            "对用户可见的生成结果"
        )
    }

    func testCouncilStreamerUsesLosslessFIFOAndDefersSnapshotsOutOfProviderCallbacks() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/CouncilRunner.swift"),
            encoding: .utf8
        )
        guard let activeStreamStart = source.range(of: "private final class IOSCouncilActiveTextStream"),
              let streamerStart = source.range(of: "final class IOSCouncilTextStreamer"),
              let streamerEnd = source.range(
                of: "enum IOSCouncilRoomRunnerError",
                range: streamerStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected the concrete council streamer implementation")
        }
        let activeStream = source[activeStreamStart.lowerBound..<streamerStart.lowerBound]
        let streamer = source[streamerStart.lowerBound..<streamerEnd.lowerBound]
        XCTAssertTrue(streamer.contains("AsyncStream<ChatStreamEvent>(bufferingPolicy: .unbounded)"))
        XCTAssertTrue(streamer.contains("eventSink.claim(event)"), "Council chunks need the shared FIFO claim gate.")
        XCTAssertTrue(
            activeStream.contains("eventSink.takePendingChunks()"),
            "The active Council stream must drain accepted FIFO chunks before its exact cancel flush."
        )

        guard let chunkStart = streamer.range(of: "onChunk: { chunk in"),
              let completeStart = streamer.range(of: "onComplete:", range: chunkStart.upperBound..<streamer.endIndex) else {
            return XCTFail("Expected provider chunk and completion callbacks")
        }
        let providerChunkCallback = streamer[chunkStart.upperBound..<completeStart.lowerBound]
        XCTAssertFalse(providerChunkCallback.contains("snapshot()"))
        XCTAssertFalse(providerChunkCallback.contains("Task { @MainActor"))
    }

    func testCouncilMarkdownPresentationMarksOnlyModelOutputAsStreamed() {
        let hostID = UUID()
        let host = CouncilChatMessage(
            id: hostID,
            kind: .host,
            author: "主持人",
            body: "正在生成",
            systemImage: "crown",
            tint: .red,
            subtitle: nil,
            status: .speaking
        )
        let system = CouncilChatMessage(
            kind: .system,
            author: "议会",
            body: "系统消息",
            systemImage: "info.circle",
            tint: .gray,
            subtitle: nil,
            status: .completed
        )

        XCTAssertTrue(host.usesStreamingMarkdown)
        XCTAssertTrue(host.isStreamingMarkdown)
        XCTAssertTrue(host.hasEverStreamedMarkdown)
        XCTAssertEqual(host.markdownRenderCacheNamespace, "council:\(hostID.uuidString)")
        XCTAssertFalse(system.usesStreamingMarkdown)
        XCTAssertFalse(system.isStreamingMarkdown)
        XCTAssertFalse(system.hasEverStreamedMarkdown)
    }

    func testCouncilMeasuredGrowthFollowRequiresActiveUnpausedFollowing() {
        XCTAssertTrue(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 101,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 99,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: false
        ), "Content shrink is handled by terminal settle, not live-growth animation.")
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 120,
            followEnabled: true,
            followPaused: true,
            userDragging: false,
            alreadyAtBottom: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 120,
            followEnabled: true,
            followPaused: false,
            userDragging: true,
            alreadyAtBottom: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 120,
            followEnabled: false,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: false
        ))
    }

    func testCouncilMeasuredGrowthFollowSkipsWhenAlreadyPinnedToBottom() {
        // The `.sizeChanges` anchor already pins content within the same layout
        // transaction; a redundant 0.08s scrollTo(edge:.bottom) command re-creates
        // the "double bottom-scroll command" anti-pattern from the novel-page fix.
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 101,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: true
        ), "Already-at-bottom growth must not re-issue a redundant follow animation.")
        XCTAssertTrue(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 101,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: false
        ))
    }

    func testCouncilViewportShrinkReanchorsOnlyWhileFollowing() {
        XCTAssertTrue(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 500,
            currentVisibleHeight: 600,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: true,
            userDragging: false,
            alreadyAtBottom: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: false,
            userDragging: true,
            alreadyAtBottom: false
        ))
    }

    func testCouncilViewportShrinkSkipsWhenAlreadyPinnedToBottom() {
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: true
        ))
        XCTAssertTrue(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: false,
            userDragging: false,
            alreadyAtBottom: false
        ))
    }

    func testFollowPauseDoesNotFreezeCouncilMarkdown() {
        // `followPaused` only owns automatic scrolling. Council has one bounded,
        // sequential speaking row, so pausing follow must not visibly freeze that
        // row while it is still on screen.
        XCTAssertTrue(
            CouncilTranscriptFollowPolicy.liveRenderingEnabled,
            "A scroll-follow signal is not proof that the speaking row is offscreen."
        )
    }

    func testCouncilPendingLabelsStayOutOfMarkdownBody() {
        for placeholder in ["思考中...", "调研和完善议题中...", "点评中...", "总结中..."] {
            let speaking = CouncilChatMessage(
                kind: .host,
                author: "主持人",
                body: placeholder,
                systemImage: "crown",
                tint: .red,
                subtitle: nil,
                status: .speaking
            )

            XCTAssertNil(
                speaking.streamingMarkdownBody,
                "Pending UI copy must not become the old prefix of the first model output."
            )

            let interrupted = CouncilChatMessage(
                kind: .host,
                author: "主持人",
                body: placeholder,
                systemImage: "crown",
                tint: .red,
                subtitle: nil,
                status: .failed
            )
            XCTAssertNil(interrupted.streamingMarkdownBody)
            XCTAssertEqual(interrupted.displayBody, "未生成内容")
        }

        let output = CouncilChatMessage(
            kind: .guest,
            author: "议员",
            body: "真实模型输出",
            systemImage: "person",
            tint: .blue,
            subtitle: nil,
            status: .speaking
        )
        XCTAssertEqual(output.streamingMarkdownBody, "真实模型输出")
    }

    func testCouncilUserDragResumesFollowWithinNearBottomThreshold() {
        XCTAssertGreaterThan(
            ChatLayout.nearBottomResumeThreshold,
            ChatLayout.bottomStickThreshold,
            "Resume intent must stay distinct from physical true-bottom."
        )
        XCTAssertTrue(CouncilTranscriptFollowPolicy.shouldResumeFollowing(
            distanceToBottom: ChatLayout.nearBottomResumeThreshold
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldResumeFollowing(
            distanceToBottom: ChatLayout.nearBottomResumeThreshold + 0.5
        ))
    }

    func testCouncilNearBottomDragEndCommitsSemanticBottomFollow() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/CouncilChatRuntimeView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("NativeTimelineScrollReturnPolicy.returnedToBottom("))
        XCTAssertTrue(source.contains("if returnedToBottom, followGeneration {"))
    }

    func testCouncilMeasuredGrowthFollowOwnsFullAnimationWindow() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/CouncilChatRuntimeView.swift"),
            encoding: .utf8
        )
        guard let scheduleStart = source.range(of: "private func scheduleMeasuredGrowthFollowToBottom()"),
              let cancelStart = source.range(
                of: "private func cancelPendingMeasuredGrowthFollow()",
                range: scheduleStart.upperBound..<source.endIndex
              ),
              let settleStart = source.range(
                of: "private func scheduleTerminalBottomSettle()",
                range: cancelStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected the Council measured-growth scheduler")
        }
        let schedule = source[scheduleStart.lowerBound..<cancelStart.lowerBound]
        let cancellation = source[cancelStart.lowerBound..<settleStart.lowerBound]
        XCTAssertTrue(schedule.contains("try await Task.sleep"))
        XCTAssertTrue(schedule.contains("liveGrowthAnimationDuration * 1_000_000_000"))
        XCTAssertTrue(
            schedule.contains("measuredGrowthFollowPending = true"),
            "A second Markdown measurement inside the 80ms window must leave one replay request."
        )
        XCTAssertTrue(
            schedule.contains("if shouldReplay"),
            "The current animation owner must replay a coalesced growth after releasing ownership."
        )
        XCTAssertTrue(schedule.contains("let shouldReplay = measuredGrowthFollowPending &&"))
        XCTAssertFalse(schedule.contains("let shouldReplay = !Task.isCancelled &&"))
        XCTAssertFalse(
            cancellation.contains("measuredGrowthFollowTask = nil"),
            "Cancellation must leave ownership with the in-flight task until its defer runs."
        )
        // Pin owns live growth alone; no residual scrollTo dual-write under the pin.
        XCTAssertTrue(source.contains("let sizeChangesPinOwnsGrowth = followGeneration && !isNativeScrollDriverDesired"))
        XCTAssertTrue(source.contains("if shouldFollowViewportShrink {"))
        XCTAssertTrue(
            source.contains("} else if shouldFollowMeasuredGrowth,\n                      !sizeChangesPinOwnsGrowth || isNativeScrollDriverActive {"),
            "Measured growth must not schedule a chase/snap while the sizeChanges pin owns follow."
        )
        XCTAssertFalse(
            source.contains("} else if sizeChangesPinOwnsGrowth {\n                    scrollToTranscriptBottom()"),
            "Residual snap under the pin causes continuous jump/flicker."
        )
    }

    func testDefaultSeatDescriptorsIncludeHostRiskOpponent() {
        // The Android parity core-seats (host/opponent/judge-or-risk) should be
        // represented in the default council roster.
        let seats = CouncilRunner.defaultSeatDescriptorsForTesting()
        let seatIds = Set(seats.map(\.id))
        XCTAssertTrue(seatIds.contains("host"), "host seat must be present")
        XCTAssertTrue(seats.count >= 3, "council should default to at least 3 seats")
        // Each seat must carry a non-empty role description (Android seat.role parity).
        XCTAssertTrue(seats.allSatisfy { !$0.role.isEmpty && !$0.name.isEmpty })
    }

    func testCouncilSeatDescriptorIsEquatable() {
        let a = IOSCouncilSeatDescriptor(id: "x", name: "X", role: "r", modelLabel: "m")
        let b = IOSCouncilSeatDescriptor(id: "x", name: "X", role: "r", modelLabel: "m")
        let c = IOSCouncilSeatDescriptor(id: "y", name: "Y", role: "r", modelLabel: "m")
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, c)
    }

    func testChatCouncilToolUsesPersistedRoomModelsWhenArgumentsDoNotOverrideThem() async {
        let defaults = isolatedDefaults()
        let roomSettingsStore = IOSCouncilRoomSettingsStore(
            userDefaults: defaults,
            storageKey: "tool-room-settings",
            currentModelId: "gpt-main"
        )
        roomSettingsStore.settings = compactRoomSettings(defaultRounds: 1)
        roomSettingsStore.dynamicSeatGeneration = false
        let models = ["gpt-main", "gpt-host", "gpt-engineer", "gpt-risk"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        let currentModel = models[0]
        let baseParams = TextGenerationParams(
            model: currentModel,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("风险发言"),
            .success("主持总结")
        ])
        let runner = CouncilRunner(
            taskStore: IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks"),
            roomSettingsStore: roomSettingsStore,
            roomStreamer: streamer
        )

        _ = await runner.run(
            objective: "验证工具链设置",
            providerSetting: provider,
            currentModel: currentModel,
            baseParams: baseParams
        )

        XCTAssertEqual(streamer.receivedModels.map(\.modelId), [
            "gpt-host", "gpt-engineer", "gpt-risk", "gpt-host"
        ])
    }

    func testCouncilConnectivityTesterShowsUnsupportedConfiguredModelFallback() async throws {
        let models = ["gpt-main", "gpt-host", "gpt-engineer"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        let streamer = ScriptedCouncilStreamer([
            .success("OK"),
            .success("OK"),
            .success("OK")
        ])
        let tester = IOSCouncilModelConnectivityTester(streamer: streamer)

        let results = try await tester.test(
            settings: compactRoomSettings(defaultRounds: 1),
            dynamicSeatGeneration: false,
            providerSetting: provider,
            currentModelId: "gpt-main"
        )

        XCTAssertEqual(results.map(\.configuredModelId), ["gpt-host", "gpt-engineer", "gpt-risk"])
        XCTAssertEqual(results.map(\.effectiveModelId), ["gpt-host", "gpt-engineer", "gpt-main"])
        XCTAssertTrue(results.allSatisfy(\.isReachable))
        XCTAssertTrue(results.last?.detail.contains("实际回退") == true)
        XCTAssertEqual(streamer.receivedModels.map(\.modelId), ["gpt-engineer", "gpt-host", "gpt-main"])
    }

    func testCouncilConnectivityTimeoutCancelsTheBlockedProbe() async throws {
        let models = ["gpt-main"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        let streamer = BlockingCouncilProbeStreamer()
        let tester = IOSCouncilModelConnectivityTester(
            streamer: streamer,
            timeoutNanoseconds: 10_000_000
        )

        let results = try await tester.test(
            settings: compactRoomSettings(defaultRounds: 1),
            dynamicSeatGeneration: true,
            providerSetting: provider,
            currentModelId: "gpt-main"
        )

        XCTAssertEqual(results.count, 1)
        XCTAssertFalse(results[0].isReachable)
        XCTAssertTrue(results[0].detail.contains("没有完成测试"))
        XCTAssertGreaterThan(streamer.cancelCount, 0)
    }

    func testRoomSettingsPersistHostLimitsAndSeatPayload() throws {
        let defaults = isolatedDefaults()
        let store = IOSCouncilRoomSettingsStore(
            userDefaults: defaults,
            storageKey: "settings",
            currentModelId: "gpt-main"
        )

        store.updateHost(modelId: "gpt-host", reasoning: .high, prompt: "主持 prompt")
        store.updateLimits(maxSeats: 6, defaultRounds: 3, seatTimeoutSeconds: 45, outputBudgetCharacters: 16_000)
        store.addOrUpdateSeat(
            IOSCouncilRoomSeatConfig(
                id: "design",
                name: "设计",
                rolePrompt: "看体验",
                modelId: "gpt-seat",
                reasoning: .low,
                prompt: "只说关键判断",
                isDefault: false
            ),
            currentModelId: "gpt-main"
        )

        let reloaded = IOSCouncilRoomSettingsStore(
            userDefaults: defaults,
            storageKey: "settings",
            currentModelId: "gpt-main"
        )
        XCTAssertEqual(reloaded.settings.host.modelId, "gpt-host")
        XCTAssertEqual(reloaded.settings.host.reasoning, .high)
        XCTAssertEqual(reloaded.settings.host.prompt, "主持 prompt")
        XCTAssertEqual(reloaded.settings.limits.maxSeats, 6)
        XCTAssertEqual(reloaded.settings.limits.defaultRounds, 3)
        XCTAssertEqual(reloaded.settings.limits.seatTimeoutSeconds, 45)
        XCTAssertEqual(reloaded.settings.limits.outputBudgetCharacters, 16_000)

        let seat = try XCTUnwrap(reloaded.settings.seats.first { $0.id == "design" })
        XCTAssertEqual(seat.name, "设计")
        XCTAssertEqual(seat.modelId, "gpt-seat")
        XCTAssertEqual(seat.reasoning, .low)
        XCTAssertEqual(seat.prompt, "只说关键判断")
        XCTAssertFalse(seat.isDefault)
    }

    func testPlannedSeatsFromJSONPrefersDistinctAvailableModels() {
        let planned = IOSCouncilRoomRunner.plannedSeatsFromJSON(
            """
            主持人输出：
            ```json
            {"seats":[{"name":"工程","lens":"看实现复杂度"},{"name":"风险","lens":"看安全和失败模式"}]}
            ```
            """,
            maxSeats: 4,
            routes: [
                IOSCouncilModelRouteDescriptor(providerId: "provider-a", modelId: "gpt-alt-a"),
                IOSCouncilModelRouteDescriptor(providerId: "provider-b", modelId: "gpt-alt-b"),
                IOSCouncilModelRouteDescriptor(providerId: "provider-main", modelId: "gpt-main")
            ]
        )
        XCTAssertEqual(planned.map(\.name), ["工程", "风险"])
        XCTAssertEqual(planned.map(\.rolePrompt), ["看实现复杂度", "看安全和失败模式"])
        XCTAssertEqual(planned.map(\.modelId), ["gpt-alt-a", "gpt-alt-b"])
        XCTAssertEqual(planned.map(\.providerId), ["provider-a", "provider-b"])
        XCTAssertFalse(planned.contains { $0.isHost })
    }

    func testPlannedSeatsFromJSONFallsBackToEmptyWhenInsufficientOrInvalid() {
        // 少于 2 个有效席位 → 空（调用方保留已 resolve 的默认席位）
        XCTAssertTrue(IOSCouncilRoomRunner.plannedSeatsFromJSON(
            #"{"seats":[{"name":"只有一个","lens":"数量非法"}]}"#,
            maxSeats: 4,
            routes: [IOSCouncilModelRouteDescriptor(providerId: "provider-main", modelId: "gpt-main")]
        ).isEmpty)
        // 非 JSON 散文 → 空
        XCTAssertTrue(IOSCouncilRoomRunner.plannedSeatsFromJSON(
            "主持人写了一堆散文，没有任何 JSON。",
            maxSeats: 4,
            routes: [IOSCouncilModelRouteDescriptor(providerId: "provider-main", modelId: "gpt-main")]
        ).isEmpty)
        // 超过上限按 maxSeats 截断
        let capped = IOSCouncilRoomRunner.plannedSeatsFromJSON(
            #"{"seats":[{"name":"A","lens":"a"},{"name":"B","lens":"b"},{"name":"C","lens":"c"}]}"#,
            maxSeats: 2,
            routes: [IOSCouncilModelRouteDescriptor(providerId: "provider-main", modelId: "gpt-main")]
        )
        XCTAssertEqual(capped.map(\.name), ["A", "B"])
    }

    // MARK: - 动态组席回退/重试/解析鲁棒性（辩论与自由群聊共用同一条组席路径）

    /// 主持人在真正 JSON 前用了带花括号的列举时，extractJSONObject 会先抓到干扰对象；
    /// 应跳过它，继续扫描到含 seats 的对象，而不是静默回退默认席位。
    func testPlannedSeatsFromJSONSkipsLeadingBraceNoiseToFindSeats() {
        let planned = IOSCouncilRoomRunner.plannedSeatsFromJSON(
            """
            先考虑 {历史, 政治} 两个维度，再给席位：
            {"seats":[{"name":"考据","lens":"核史料"},{"name":"社会","lens":"看民生"}]}
            """,
            maxSeats: 4,
            routes: [IOSCouncilModelRouteDescriptor(providerId: "provider-main", modelId: "gpt-main")]
        )
        XCTAssertEqual(planned.map(\.name), ["考据", "社会"])
    }

    /// 动态组席解析不出 ≥2 席时，必须发一条显式「回退默认」divider，不能静默——
    /// 否则用户看到「已组建…工程、产品、风险」会误以为动态组席成功。
    func testDynamicSeatPlanParseFailureSurfacesFallbackDivider() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let models = ["gpt-main", "gpt-alt", "gpt-engineer", "gpt-risk"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        var scripts: [Result<String, Error>] = [
            .success("最终议题"),
            .success("主持人这次没给 JSON，只写了一段散文。"),
        ]
        scripts.append(contentsOf: Array(repeating: .success("OK"), count: 12))
        let streamer = ScriptedCouncilStreamer(scripts)
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .unavailable,
            providerSetting: provider,
            currentModel: models[0]
        )
        request.dynamicSeatGeneration = true
        var appendedBodies: [String] = []
        _ = await runner.run(request: request, onEvent: { event in
            if case .append(let message) = event { appendedBodies.append(message.body) }
        })

        let surfacedFallback = appendedBodies.contains { body in
            body.contains("动态组席") &&
                (body.contains("默认") || body.contains("沿用") ||
                 body.contains("失败") || body.contains("未成功"))
        }
        XCTAssertTrue(
            surfacedFallback,
            "动态组席解析失败时应发显式回退 divider，实际 append: \(appendedBodies)"
        )
    }

    /// 动态组席调用第一次失败时，应重试一次；重试成功则采用动态席位，而非回退默认。
    func testDynamicSeatPlanRetriesOnceAfterFailure() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let models = ["gpt-main", "gpt-alt", "gpt-engineer", "gpt-risk"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        var scripts: [Result<String, Error>] = [
            .success("最终议题"),
            .failure(CouncilTestError.scriptedFailure),
            .success(#"{"seats":[{"name":"考据","lens":"核史料"},{"name":"社会","lens":"看民生"}]}"#),
        ]
        scripts.append(contentsOf: Array(repeating: .success("OK"), count: 12))
        let streamer = ScriptedCouncilStreamer(scripts)
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .unavailable,
            providerSetting: provider,
            currentModel: models[0]
        )
        request.dynamicSeatGeneration = true
        var rosters: [[IOSCouncilRoomSpeaker]] = []
        let outcome = await runner.run(request: request, onEvent: { event in
            if case .roster(let speakers, _, _) = event { rosters.append(speakers) }
        })

        XCTAssertEqual(outcome.status, .completed)
        let finalSeats = (rosters.last ?? []).filter { !$0.isHost }
        XCTAssertEqual(Set(finalSeats.map(\.name)), Set(["考据", "社会"]))
        // 组席被调用两次：议题之后连续两次 host（首次失败 + 重试）。
        XCTAssertEqual(
            streamer.receivedModels.prefix(3).map(\.modelId),
            ["gpt-main", "gpt-main", "gpt-main"]
        )
    }

    // MARK: - 席位输出伪联网搜索文本清洗（显示兜底）

    func testSanitizeSeatOutputStripsFencedPseudoWebSearchBlock() {
        let raw = """
        我先查一下资料。
        ```html
        web_search
        query 赵匡胤 开国功臣 名将 名臣 名单
        num_results 15
        web_search
        query 李世民 凌烟阁 二十四功臣 人才来源
        num_results 10
        ```
        综上，人才池其实不小。
        """
        let cleaned = IOSCouncilRoomRunner.sanitizeSeatOutput(raw)
        XCTAssertFalse(cleaned.contains("web_search"))
        XCTAssertFalse(cleaned.contains("num_results"))
        XCTAssertFalse(cleaned.contains("```"))
        XCTAssertTrue(cleaned.contains("我先查一下资料。"))
        XCTAssertTrue(cleaned.contains("综上，人才池其实不小。"))
    }

    func testSanitizeSeatOutputStripsBarePseudoWebSearchLines() {
        let raw = """
        让我搜索。
        web_search
        query 朱元璋 淮西集团 开国将相
        num_results 10
        所以结论是 X。
        """
        let cleaned = IOSCouncilRoomRunner.sanitizeSeatOutput(raw)
        XCTAssertFalse(cleaned.contains("web_search"))
        XCTAssertFalse(cleaned.contains("num_results"))
        XCTAssertTrue(cleaned.contains("让我搜索。"))
        XCTAssertTrue(cleaned.contains("所以结论是 X。"))
    }

    func testSanitizeSeatOutputKeepsNormalProseMentioningSearch() {
        let raw = "我认为这个问题不需要联网搜索，从制度成本看即可。"
        XCTAssertEqual(IOSCouncilRoomRunner.sanitizeSeatOutput(raw), raw)
    }

    /// 行首恰为 query 的英文正常发言，在没有 web_search 上下文时不得被误删——
    /// sanitize 无条件运行(与开关无关)，误删会丢用户付费生成的合法内容。
    func testSanitizeSeatOutputKeepsEnglishQueryLineWithoutPseudoSearchContext() {
        let raw = """
        Query the archive for precedents before deciding.
        The conclusion follows from the evidence.
        """
        XCTAssertEqual(IOSCouncilRoomRunner.sanitizeSeatOutput(raw), raw)
    }

    /// web_search 行被一句正常散文隔开后，其后的 query 行已脱离伪搜索块，应保留；
    /// 而 web_search 行本身仍删。验证「query 删除依赖前序 web_search 上下文」的复位逻辑。
    func testSanitizeSeatOutputKeepsQueryLineAfterContextReset() {
        let raw = """
        web_search
        This is a normal intervening line.
        query should be kept now.
        """
        let cleaned = IOSCouncilRoomRunner.sanitizeSeatOutput(raw)
        XCTAssertFalse(cleaned.contains("web_search"))
        XCTAssertTrue(cleaned.contains("This is a normal intervening line."))
        XCTAssertTrue(cleaned.contains("query should be kept now."))
    }

    // MARK: - 席位发言前联网查证（开关 + 尊重全局联网 consent）

    /// 开关开 + consent allowed：主持调研 1 次 + 每席 1 次（2 席）= 3 次；两席发言 prompt
    /// 均注入「本席联网查证」段且含调研标记。researcher 由 runner 注入，主持与席位共用同一实例。
    func testSeatWebSearchRunsPerSeatResearchAndInjectsSummary() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let models = ["gpt-main", "gpt-host", "gpt-engineer", "gpt-risk"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        let researcher = CountingCouncilResearcher()
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("风险发言"),
            .success("主持总结"),
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: researcher,
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .allowed,
            providerSetting: provider,
            currentModel: models[0]
        )
        request.dynamicSeatGeneration = false
        request.seatWebSearch = true

        let outcome = await runner.run(request: request, onEvent: { _ in })

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(researcher.callCount, 3)
        let seatResearchPrompts = streamer.receivedUserPrompts.filter {
            $0.contains("本席联网查证")
        }
        XCTAssertEqual(seatResearchPrompts.count, 2)
        XCTAssertTrue(seatResearchPrompts.allSatisfy { $0.contains("SEAT_RESEARCH_MARKER_42") })
    }

    /// 开关关：席位不调研、不注入；仅主持因 consent=allowed 调研 1 次。
    func testSeatWebSearchOffSkipsPerSeatResearch() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let models = ["gpt-main", "gpt-host", "gpt-engineer", "gpt-risk"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        let researcher = CountingCouncilResearcher()
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("风险发言"),
            .success("主持总结"),
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: researcher,
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .allowed,
            providerSetting: provider,
            currentModel: models[0]
        )
        request.dynamicSeatGeneration = false
        request.seatWebSearch = false

        let outcome = await runner.run(request: request, onEvent: { _ in })

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(researcher.callCount, 1)
        XCTAssertEqual(
            streamer.receivedUserPrompts.filter { $0.contains("本席联网查证") }.count,
            0
        )
    }

    /// 开关开但 consent=unavailable（全局联网关）：主持与席位都不调研、不注入——
    /// 席位联网尊重全局联网开关，与主持一致，避免在用户禁用联网时偷偷发请求。
    func testSeatWebSearchRespectsUnavailableConsent() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let models = ["gpt-main", "gpt-host", "gpt-engineer", "gpt-risk"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        let researcher = CountingCouncilResearcher()
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("风险发言"),
            .success("主持总结"),
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: researcher,
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .unavailable,
            providerSetting: provider,
            currentModel: models[0]
        )
        request.dynamicSeatGeneration = false
        request.seatWebSearch = true

        let outcome = await runner.run(request: request, onEvent: { _ in })

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(researcher.callCount, 0)
        XCTAssertEqual(
            streamer.receivedUserPrompts.filter { $0.contains("本席联网查证") }.count,
            0
        )
    }

    func testDynamicSeatsProbeAssignedModelsAndReplaceUnreachableOnes() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let models = ["gpt-main", "gpt-bad", "gpt-good"].map(makeCouncilModel)
        let provider = makeCouncilProvider(models: models)
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success(#"{"seats":[{"name":"求证","lens":"核对证据"},{"name":"反方","lens":"寻找反例"}]}"#),
            .failure(CouncilTestError.scriptedFailure),
            .success("OK"),
            .success("求证席发言"),
            .success("反方席发言"),
            .success("主持总结")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .unavailable,
            providerSetting: provider,
            currentModel: models[0]
        )
        request.dynamicSeatGeneration = true
        var rosters: [[IOSCouncilRoomSpeaker]] = []

        let outcome = await runner.run(request: request, onEvent: { event in
            if case .roster(let speakers, _, _) = event {
                rosters.append(speakers)
            }
        })

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertTrue(outcome.failedSeats.isEmpty)
        XCTAssertEqual(streamer.receivedModels.prefix(4).map(\.modelId), [
            "gpt-main", "gpt-main", "gpt-bad", "gpt-good"
        ])
        XCTAssertFalse(streamer.receivedModels.dropFirst(4).contains { $0.modelId == "gpt-bad" })
        let finalSeats = try XCTUnwrap(rosters.last).filter { !$0.isHost }
        XCTAssertEqual(finalSeats.count, 2)
        XCTAssertFalse(finalSeats.contains { $0.modelId == "gpt-bad" })
    }

    func testDynamicSeatsCarryAndUseCrossProviderRoutes() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let currentModels = [makeCouncilModel("gpt-main"), makeCouncilModel("gpt-host")]
        let claudeModel = makeCouncilModel("claude-seat")
        let grokModel = makeCouncilModel("grok-seat")
        let currentProvider = makeCouncilProvider(name: "DeepSeek", models: currentModels)
        let claudeProvider = makeCouncilProvider(name: "Anthropic", models: [claudeModel])
        let grokProvider = makeCouncilProvider(name: "xAI", models: [grokModel])
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success(#"{"seats":[{"name":"求证","lens":"核对证据"},{"name":"反方","lens":"寻找反例"}]}"#),
            .success("OK"),
            .success("OK"),
            .success("求证席发言"),
            .success("反方席发言"),
            .success("主持总结")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .unavailable,
            providerSetting: currentProvider,
            providerSettings: [currentProvider, claudeProvider, grokProvider],
            currentModel: currentModels[0]
        )
        request.dynamicSeatGeneration = true
        var rosters: [[IOSCouncilRoomSpeaker]] = []

        let outcome = await runner.run(request: request, onEvent: { event in
            if case .roster(let speakers, _, _) = event {
                rosters.append(speakers)
            }
        })

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(streamer.receivedProviderNames, [
            "DeepSeek", "DeepSeek", "Anthropic", "xAI", "Anthropic", "xAI", "DeepSeek"
        ])
        XCTAssertEqual(streamer.receivedModels.map(\.modelId), [
            "gpt-host", "gpt-host", "claude-seat", "grok-seat", "claude-seat", "grok-seat", "gpt-host"
        ])
        let finalSeats = try XCTUnwrap(rosters.last).filter { !$0.isHost }
        XCTAssertEqual(
            Set(finalSeats.map { "\($0.providerId ?? "")/\($0.modelId)" }),
            Set([
                "\(claudeProvider.id.description())/claude-seat",
                "\(grokProvider.id.description())/grok-seat"
            ])
        )
    }

    func testFreshFreeChatAndDebateUseConfiguredDefaultRounds() async {
        for mode in [IOSCouncilRoomRunMode.freeChat, .debate] {
            let streamer = ScriptedCouncilStreamer([
                .success("最终议题"),
                .success("工程第一轮"),
                .success("风险第一轮"),
                .success("主持点评"),
                .success("工程第二轮"),
                .success("风险第二轮"),
                .success("主持总结")
            ])
            let runner = IOSCouncilRoomRunner(
                streamer: streamer,
                researcher: StaticCouncilResearcher(),
                taskStore: IOSAdvancedTaskStore(userDefaults: isolatedDefaults(), storageKey: "tasks-\(mode.rawValue)")
            )
            var roundDividers: [String] = []
            var seatMessageCount = 0

            let outcome = await runner.run(
                request: roomRequest(
                    mode: mode,
                    settings: compactRoomSettings(defaultRounds: 2),
                    researchConsent: .unavailable
                ),
                onEvent: { event in
                    guard case .append(let message) = event else { return }
                    if message.kind == .divider, message.body.hasPrefix("第 ") {
                        roundDividers.append(message.body)
                    } else if message.kind == .seat {
                        seatMessageCount += 1
                    }
                }
            )

            XCTAssertEqual(outcome.status, .completed, "mode=\(mode.rawValue)")
            XCTAssertEqual(roundDividers, ["第 1 轮", "第 2 轮"], "mode=\(mode.rawValue)")
            XCTAssertEqual(seatMessageCount, 4, "mode=\(mode.rawValue)")
            XCTAssertEqual(streamer.callCount, 7, "mode=\(mode.rawValue)")
        }
    }

    func testRoomRunnerFreeChatPersistsTaskApprovalAndOrderedMessages() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let permissionStore = IOSPermissionStore(
            userDefaults: defaults,
            storageKey: "policies",
            approvalStorageKey: "approvals",
            taskStore: taskStore
        )
        let policiesBefore = permissionStore.policies
        let streamer = ScriptedCouncilStreamer([
            .success("""
            最终议题：把模型议会做成正式 Room。
            席位: 工程 | 看实现路径
            席位: 风险 | 看失败模式
            """),
            .success("工程建议：拆 runner，UI 只消费事件。"),
            .success("风险建议：不要伪装跨 provider。"),
            .success("主持总结：先做当前服务商内多模型 Room。")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore,
            permissionStore: permissionStore
        )
        var events: [IOSCouncilRoomEvent] = []

        let outcome = await runner.run(
            request: roomRequest(
                mode: .freeChat,
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .allowed
            ),
            onEvent: { events.append($0) }
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.seatNames, ["工程", "风险"])
        XCTAssertEqual(outcome.failedSeats, [])
        XCTAssertEqual(outcome.finalAnswer, "主持总结：先做当前服务商内多模型 Room。")
        XCTAssertNil(outcome.failureReason)
        XCTAssertTrue(outcome.transcript.contains("主持总结"))
        XCTAssertEqual(streamer.callCount, 4)
        XCTAssertEqual(permissionStore.policies, policiesBefore, "Room consent must not mutate global permission policy.")

        let messageAuthors = events.compactMap { event -> String? in
            guard case let .append(message) = event,
                  message.kind == .host || message.kind == .seat else { return nil }
            return message.author
        }
        XCTAssertEqual(messageAuthors, ["主持人", "工程", "风险", "主持人"])

        XCTAssertEqual(Set(permissionStore.approvalRecords.map(\.toolName)), ["search_web", "scrape_web"])
        XCTAssertTrue(permissionStore.approvalRecords.allSatisfy { $0.action == .allowed })

        let task = try XCTUnwrap(taskStore.recent(kind: .modelCouncil, limit: 1).first)
        XCTAssertEqual(task.status, .completed)
        XCTAssertEqual(task.toolScope, ["search_web", "scrape_web"])
        XCTAssertEqual(task.metadata["room_mode"], IOSCouncilRoomRunMode.freeChat.rawValue)
    }

    func testRoomRunnerUsesOnlyTheConfiguredCouncilStages() async {
        let runner = IOSCouncilRoomRunner(
            streamer: ScriptedCouncilStreamer([
                .success("最终议题"),
                .success("工程发言"),
                .success("风险发言"),
                .success("主持总结")
            ]),
            researcher: StaticCouncilResearcher(),
            taskStore: IOSAdvancedTaskStore(userDefaults: isolatedDefaults(), storageKey: "tasks")
        )

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .completed)
    }

    func testRoomRunnerTimeoutMeasuresLackOfOutputInsteadOfTotalGenerationTime() async {
        let runner = IOSCouncilRoomRunner(
            streamer: ProgressingFinalCouncilStreamer(),
            researcher: StaticCouncilResearcher(),
            taskStore: IOSAdvancedTaskStore(userDefaults: isolatedDefaults(), storageKey: "tasks"),
            timeoutUnitNanoseconds: 10_000_000
        )
        var settings = compactRoomSettings(defaultRounds: 1)
        settings.limits.seatTimeoutSeconds = 15

        let outcome = await runner.run(
            request: roomRequest(
                settings: settings,
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.finalAnswer, "第一段第二段")
    }

    func testRoomRunnerSeatFailureCompletesWithFailedSeat() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = ScriptedCouncilStreamer([
            .success("""
            最终议题：压测席位失败降级。
            席位: 工程 | 先给实现判断
            席位: 风险 | 再给风险判断
            """),
            .success("工程发言完成。"),
            .failure(CouncilTestError.scriptedFailure),
            .success("主持总结：风险席位缺席，结论保持不确定。")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var events: [IOSCouncilRoomEvent] = []

        let outcome = await runner.run(
            request: roomRequest(
                mode: .debate,
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .denied
            ),
            onEvent: { events.append($0) }
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.seatNames, ["工程", "风险"])
        XCTAssertEqual(outcome.failedSeats, ["风险"])
        XCTAssertEqual(streamer.callCount, 4)
        XCTAssertTrue(outcome.transcript.contains("风险席位缺席"))

        let failedUpdate = events.contains { event in
            guard case let .updateMessage(_, body, status) = event else { return false }
            return status == .failed && body.contains("席位失败")
        }
        XCTAssertTrue(failedUpdate)

        let task = try XCTUnwrap(taskStore.recent(kind: .modelCouncil, limit: 1).first)
        XCTAssertEqual(task.status, .completed)
        XCTAssertTrue(task.retryable)
        XCTAssertTrue(task.error.contains("风险"))
    }

    func testRoomRunnerTreatsEmptySeatOutputAsThatSeatFailure() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success(""),
            .success("风险发言"),
            .success("主持总结")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.failedSeats, ["工程"])
        XCTAssertEqual(outcome.finalAnswer, "主持总结")
        XCTAssertFalse(outcome.transcript.contains("[工程]"))
    }

    func testRoomRunnerFailsWhenFinalTopicIsEmpty() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let runner = IOSCouncilRoomRunner(
            streamer: ScriptedCouncilStreamer([.success("")]),
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .failed)
        XCTAssertTrue(outcome.failureReason?.contains("最终议题") == true)
        XCTAssertTrue(outcome.finalAnswer.isEmpty)
    }

    func testRoomRunnerFailsWhenFinalSynthesisIsEmpty() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let runner = IOSCouncilRoomRunner(
            streamer: ScriptedCouncilStreamer([
                .success("最终议题"),
                .success("工程发言"),
                .success("风险发言"),
                .success("")
            ]),
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .failed)
        XCTAssertTrue(outcome.failureReason?.contains("最终综合") == true)
        XCTAssertTrue(outcome.finalAnswer.isEmpty)
    }

    func testRoomRunnerAllowsLegitimateOutputBeginningWithError() async throws {
        let runner = IOSCouncilRoomRunner(
            streamer: ScriptedCouncilStreamer([
                .success("Error: budgeting assumptions need review"),
                .success("工程发言"),
                .success("风险发言"),
                .success("主持总结")
            ]),
            researcher: StaticCouncilResearcher(),
            taskStore: IOSAdvancedTaskStore(userDefaults: isolatedDefaults(), storageKey: "tasks")
        )

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.finalTopic, "Error: budgeting assumptions need review")
    }

    func testCouncilProviderResolutionPreservesCredentialIdentity() throws {
        let provider = IOSCouncilRoomRunner.makeProviderSetting(
            baseUrl: "https://example.com/v1",
            apiKey: "test-key"
        )

        let resolved = try XCTUnwrap(
            IOSCouncilRoomRunner.resolveProviderSetting(selected: provider)
        )

        XCTAssertTrue(resolved === provider)
    }

    func testCouncilStreamerUsesCodexAndGrokProductionRouting() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/CouncilRunner.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("IOSCodexProviderResolver.resolved(providerSetting)"))
        XCTAssertTrue(source.contains("IOSCodexProviderResolver.augmentParamsForCodex("))
        XCTAssertTrue(source.contains("IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI)"))
        XCTAssertTrue(source.contains("IOSGrokWebClient(providerId: providerId).streamText("))
    }

    func testRoomRunnerPassesTheConfiguredModelInsteadOfSynthesizingOne() async throws {
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("产品发言"),
            .success("主持总结")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: IOSAdvancedTaskStore(userDefaults: isolatedDefaults(), storageKey: "tasks")
        )
        let model = Model(
            modelId: "gpt-main",
            displayName: "Configured Council Model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [ModelAbility.reasoning],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: KotlinInt(value: 32_000),
            providerOverwrite: nil
        )
        var settings = IOSCouncilRoomSettings.defaults(currentModelId: model.modelId)
        settings.limits.maxSeats = 2
        settings.limits.defaultRounds = 1
        var request = roomRequest(settings: settings)
        request.currentModel = model

        let outcome = await runner.run(request: request)

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(streamer.receivedModels.count, 4)
        XCTAssertTrue(streamer.receivedModels.allSatisfy { $0 === model })
    }

    func testRoomRunnerPreservesExactPartialSeatTailWhenStreamFails() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = PartialTailCouncilStreamer()
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var events: [IOSCouncilRoomEvent] = []

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .denied
            ),
            onEvent: { events.append($0) }
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.failedSeats, ["风险"])
        let riskFailure = events.compactMap { event -> String? in
            guard case let .updateMessage(_, body, status) = event,
                  status == .failed else { return nil }
            return body
        }.last
        XCTAssertEqual(
            riskFailure,
            PartialTailCouncilStreamer.partialTail,
            "A failed seat must close streaming state without replacing its exact accepted tail."
        )
    }

    func testRoomRunnerShowsFailureReasonWhenFailedSeatHasNoGeneratedTail() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = PartialTailCouncilStreamer(failedTail: nil)
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var events: [IOSCouncilRoomEvent] = []

        _ = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .denied
            ),
            onEvent: { events.append($0) }
        )

        let failedBody = events.compactMap { event -> String? in
            guard case let .updateMessage(_, body, status) = event,
                  status == .failed else { return nil }
            return body
        }.last
        XCTAssertTrue(failedBody?.contains("席位失败") == true)
        XCTAssertFalse(failedBody == "思考中...")
    }

    func testCancelledRunCannotCancelImmediateReplacementRun() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let firstStreamStarted = expectation(description: "first council stream started")
        let streamer = RestartableCouncilStreamer(firstStreamStarted: firstStreamStarted)
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        let request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .denied
        )

        let firstRun = Task { await runner.run(request: request) }
        await fulfillment(of: [firstStreamStarted], timeout: 1)
        let firstTaskID = try XCTUnwrap(taskStore.recent(kind: .modelCouncil, limit: 1).first?.id)
        runner.cancel()

        let cancelled = await firstRun.value
        // A late terminal callback from the cancelled owner must not mutate its
        // task after cancel has cleared the runner's active task identity.
        runner.markActiveTaskTerminal(
            status: .interrupted,
            summary: "不应改写旧轮",
            retryable: true
        )
        let replacement = await runner.run(request: request)

        XCTAssertEqual(cancelled.status, .cancelled)
        XCTAssertEqual(replacement.status, .completed)
        XCTAssertEqual(
            taskStore.tasks.first(where: { $0.id == firstTaskID })?.status,
            .cancelled,
            "The cancelled run must remain terminal after a late callback."
        )
        XCTAssertEqual(streamer.callCount, 5)
        XCTAssertEqual(streamer.cancelCount, 1)
    }

    func testExplicitContinuationCancelRemainsCancelledInTaskLedger() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let baseTask = taskStore.startTask(
            kind: .modelCouncil,
            title: "既有议会",
            objective: "原始议题"
        )
        _ = taskStore.updateTask(id: baseTask.id, status: .completed, resultSummary: "既有结论")
        let firstStreamStarted = expectation(description: "continuation stream started")
        let runner = IOSCouncilRoomRunner(
            streamer: RestartableCouncilStreamer(firstStreamStarted: firstStreamStarted),
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .unavailable
        )
        request.continuation = IOSCouncilRoomContinuation(
            taskId: baseTask.id,
            originalObjective: "原始议题",
            finalTopic: "既有最终议题",
            priorTranscript: "[主持人] 既有结论",
            speakers: [
                IOSCouncilRoomSpeaker(
                    id: "host",
                    name: "主持人",
                    rolePrompt: "主持与综合",
                    modelId: "gpt-host",
                    reasoning: .off,
                    prompt: "",
                    isHost: true
                ),
                roomSpeaker(id: "engineering", name: "工程", modelId: "gpt-main"),
                roomSpeaker(id: "risk", name: "风险", modelId: "gpt-main")
            ],
            nextRound: 2
        )

        // Before the continuation runner creates its task, the reused task ID
        // still belongs to the completed prior round and must not be cancelled.
        runner.markActiveTaskTerminal(
            taskId: baseTask.id,
            status: .cancelled,
            summary: "不应改写上一轮",
            retryable: true
        )
        XCTAssertEqual(taskStore.tasks.first(where: { $0.id == baseTask.id })?.status, .completed)

        let run = Task { await runner.run(request: request) }
        await fulfillment(of: [firstStreamStarted], timeout: 1)
        runner.markActiveTaskTerminal(
            taskId: baseTask.id,
            status: .cancelled,
            summary: "本轮议会已停止。",
            retryable: true
        )
        runner.cancel()

        let summary = await run.value
        XCTAssertEqual(summary.status, .cancelled)
        XCTAssertEqual(
            taskStore.tasks.first(where: { $0.id == baseTask.id })?.status,
            .cancelled,
            "An explicit continuation cancel must not be rewritten as completed by the catch path."
        )
    }

    func testRunnerKeepsPersistedTerminalStatusAvailableAfterRunReturns() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let runner = IOSCouncilRoomRunner(
            streamer: ScriptedCouncilStreamer([
                .success("最终议题"),
                .success("工程发言"),
                .success("风险发言"),
                .success("主持总结")
            ]),
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )

        let summary = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(summary.status, .completed)
        XCTAssertEqual(runner.taskStatus(taskId: summary.taskId), .completed)
        XCTAssertNil(runner.taskStatus(taskId: "missing-task"))
    }

    func testViewModelCancelClosesStreamingTailAndRejectsOldRunEventsAfterRestart() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let firstStreamStarted = expectation(description: "view model first stream started")
        let streamer = RestartableCouncilStreamer(firstStreamStarted: firstStreamStarted)
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        let settingsStore = SettingsStore(
            userDefaults: defaults,
            storageKey: "legacy-settings",
            apiKeyStore: CouncilTestAPIKeyStore(key: "test-key")
        )
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = IosSettingsMutations.shared.buildOpenAIProvider(
            name: "Council Test Provider",
            apiKey: "test-key",
            baseUrl: "https://example.com/v1",
            modelName: "Council Test Model",
            modelId: "gpt-main"
        )
        _ = sharedSettings.addProvider(provider)
        let model = try XCTUnwrap(
            sharedSettings.availableChatModels().first { $0.providerName == "Council Test Provider" }
        )
        sharedSettings.setCurrentChatModelId(model.id)
        let roomSettings = IOSCouncilRoomSettingsStore(
            userDefaults: defaults,
            storageKey: "room-settings",
            currentModelId: "gpt-main"
        )
        roomSettings.settings = compactRoomSettings(defaultRounds: 1)
        roomSettings.dynamicSeatGeneration = false
        let archiveStore = CouncilRoomArchiveStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("council-vm-test-\(UUID().uuidString)", isDirectory: true)
        )
        let viewModel = CouncilChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            providerRegistry: nil,
            permissionStore: IOSPermissionStore(
                userDefaults: defaults,
                storageKey: "policies",
                approvalStorageKey: "approvals",
                taskStore: taskStore
            ),
            roomSettingsStore: roomSettings,
            runner: runner,
            transcriptDefaults: defaults,
            archiveStore: archiveStore
        )

        viewModel.inputText = "第一轮"
        viewModel.send()
        await fulfillment(of: [firstStreamStarted], timeout: 1)
        viewModel.cancelDiscussion()

        XCTAssertFalse(viewModel.messages.contains { $0.status == .speaking })
        XCTAssertEqual(
            viewModel.messages.first { $0.body == "旧轮精确尾部" }?.status,
            .failed,
            "取消时尚未完成的流式尾部不能伪装成已完成。"
        )

        viewModel.inputText = "第二轮"
        viewModel.send()
        for _ in 0..<200 where viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }

        XCTAssertFalse(viewModel.isRunning)
        XCTAssertFalse(viewModel.messages.contains { $0.status == .speaking })
        XCTAssertFalse(
            viewModel.messages.contains { $0.body == "模型议会已取消。" },
            "The cancelled run's late terminal event must not enter the replacement room."
        )
        XCTAssertEqual(viewModel.messages.last(where: { $0.kind == .host })?.body, "主持总结")
    }

    func testDetachedCouncilRuntimeFinishesWithoutCancellingTheOwner() async throws {
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("风险发言"),
            .success("主持总结")
        ])
        let harness = try makeViewModelHarness(streamer: streamer)

        harness.viewModel.inputText = "离页后继续完成"
        harness.viewModel.send()
        harness.viewModel.runtimeDidDisappear()

        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }

        XCTAssertFalse(harness.viewModel.isRunning)
        XCTAssertEqual(harness.viewModel.messages.last(where: { $0.kind == .host })?.body, "主持总结")
    }

    func testSubmittedCouncilInputIsPersistedBeforeRunnerTaskStarts() throws {
        let harness = try makeViewModelHarness(streamer: ScriptedCouncilStreamer([]))

        harness.viewModel.inputText = "进程启动前也要保留的议题"
        harness.viewModel.send()

        let transcript = try XCTUnwrap(CouncilTranscriptStore.load(defaults: harness.defaults))
        XCTAssertEqual(transcript.messages.last?.body, "进程启动前也要保留的议题")
        XCTAssertTrue(transcript.messages.last?.kind == CouncilMessageKind.user.rawKey)

        // Avoid leaving the test-owned foreground task alive after the synchronous assertion.
        harness.viewModel.cancelDiscussion()
    }

    func testCouncilBackgroundContractStartsKeepAliveWithTheForegroundRunWithoutAnUnreachableAskUserSurface() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let runtime = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/CouncilChatRuntimeView.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(
            runtime.contains("beginBackgroundKeepAlive(for: discussionID)"),
            "Council must submit continued processing when the foreground run starts."
        )
        XCTAssertTrue(
            runtime.contains("private func keepAliveLeaseId(for discussionID: UUID)"),
            "The system task must be bound to this discussion round, not a fixed Council lease."
        )
        XCTAssertTrue(
            runtime.contains("BackgroundGenerationKeepAlive.shared.updateProgress"),
            "Council must report real generation phases to continued processing."
        )
        XCTAssertTrue(
            runtime.contains("runner.markActiveTaskTerminal"),
            "Expiration and cancellation must close the task ledger as a retryable terminal."
        )
        XCTAssertFalse(
            runtime.contains("skipAskUser"),
            "Council no longer exposes an unreachable ask_user pause surface."
        )
        XCTAssertFalse(
            runtime.contains("submitAskUserAnswer"),
            "Council no longer exposes an unreachable ask_user resume surface."
        )
        XCTAssertFalse(runtime.contains("pendingAskUser"))
        XCTAssertFalse(runtime.contains("onAskUser"))
    }

    func testCouncilExpirationCheckpointsBeforeReleasingItsBackgroundLease() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let runtime = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/CouncilChatRuntimeView.swift"),
            encoding: .utf8
        )
        guard let stopStart = runtime.range(of: "private func stopAndCheckpointActiveDiscussion("),
              let stopEnd = runtime.range(of: "\n    private func stopActiveDiscussion", range: stopStart.upperBound..<runtime.endIndex) else {
            return XCTFail("Expected the Council terminal checkpoint path")
        }
        let stopSource = String(runtime[stopStart.lowerBound..<stopEnd.lowerBound])
        let stopOffset = { (needle: String) -> String.Index? in stopSource.range(of: needle)?.lowerBound }

        guard let cancelOffset = stopOffset("stopActiveDiscussion(releaseBackgroundLease: false)"),
              let persistOffset = stopOffset("persistTranscript()"),
              let archiveOffset = stopOffset("archiveCurrentRoom()"),
              let releaseOffset = stopOffset("endBackgroundKeepAlive(for: discussionID)") else {
            return XCTFail("Expected cancel, checkpoint, and lease release in one terminal path")
        }
        XCTAssertLessThan(cancelOffset, persistOffset)
        XCTAssertLessThan(persistOffset, archiveOffset)
        XCTAssertLessThan(archiveOffset, releaseOffset)
    }

    func testSpeakingPartialTailIsCheckpointedByTheExistingThrottle() async throws {
        let partialPublished = expectation(description: "partial seat tail published")
        let streamer = DelayedPartialCouncilStreamer(partialPublished: partialPublished)
        let harness = try makeViewModelHarness(streamer: streamer)

        harness.viewModel.inputText = "中途发言也要保留"
        harness.viewModel.send()
        await fulfillment(of: [partialPublished], timeout: 2)
        try await Task.sleep(nanoseconds: 350_000_000)
        await harness.archiveStore.flushDeferred()

        let taskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )
        let archive = try XCTUnwrap(harness.archiveStore.load(taskId: taskID))
        XCTAssertTrue(
            archive.messages.contains { $0.body == DelayedPartialCouncilStreamer.partialTail },
            "A speaking update must reach the existing throttled archive checkpoint."
        )

        harness.viewModel.cancelDiscussion()
    }

    func testSecondSendContinuesCurrentCouncilAsFollowUpRound() async throws {
        let streamer = ScriptedCouncilStreamer([
            .success("第一场最终议题"),
            .success("第一场工程发言"),
            .success("第一场风险发言"),
            .success("第一场主持总结"),
            .success("第二轮工程发言"),
            .success("第二轮风险发言"),
            .success("第二轮主持总结"),
            .success("不应发生的额外调用")
        ])
        let harness = try makeViewModelHarness(streamer: streamer)

        harness.viewModel.inputText = "第一场用户议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let taskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )

        harness.viewModel.inputText = "补充：请重点解释证据不足的地方"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let tasks = harness.taskStore.recent(kind: .modelCouncil, limit: 2)
        let archive = try XCTUnwrap(harness.archiveStore.load(taskId: taskID))

        XCTAssertEqual(tasks.map(\.id), [taskID], "追问应继续同一议会任务，而不是另开议题。")
        XCTAssertTrue(archive.messages.contains { $0.body == "第一场用户议题" })
        XCTAssertTrue(archive.messages.contains { $0.body == "第一场主持总结" })
        XCTAssertTrue(archive.messages.contains { $0.body == "补充：请重点解释证据不足的地方" })
        XCTAssertTrue(archive.messages.contains { $0.body == "第二轮主持总结" })
        XCTAssertEqual(harness.viewModel.discussionRound, 2)
        XCTAssertEqual(harness.viewModel.messages.last(where: { $0.kind == .host })?.body, "第二轮主持总结")
        let followUpPrompts = streamer.receivedUserPrompts.suffix(3)
        XCTAssertEqual(followUpPrompts.count, 3)
        XCTAssertTrue(followUpPrompts.allSatisfy { $0.contains("第一场主持总结") })
        XCTAssertTrue(followUpPrompts.allSatisfy { $0.contains("补充：请重点解释证据不足的地方") })
        XCTAssertTrue(followUpPrompts.allSatisfy { $0.contains("最终议题：\n第一场最终议题") })
    }

    func testFailedFollowUpKeepsCompletedCouncilEligibleForAnotherRound() async throws {
        let streamer = ScriptedCouncilStreamer([
            .success("首轮最终议题"),
            .success("首轮工程发言"),
            .success("首轮风险发言"),
            .success("首轮主持总结"),
            .success("失败轮工程发言"),
            .success("失败轮风险发言"),
            .failure(CouncilTestError.scriptedFailure),
            .success("第三轮工程发言"),
            .success("第三轮风险发言"),
            .success("第三轮主持总结")
        ])
        let harness = try makeViewModelHarness(streamer: streamer)

        harness.viewModel.inputText = "首轮议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let taskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )

        harness.viewModel.inputText = "第二轮追问"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }

        XCTAssertEqual(
            harness.taskStore.tasks.first { $0.id == taskID }?.status,
            .completed,
            "失败的追加轮不能覆盖此前已经完成的议会终态。"
        )
        XCTAssertEqual(harness.viewModel.composerPlaceholder, "输入补充或追问，再讨论一轮")

        harness.viewModel.inputText = "第三轮继续追问"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }

        XCTAssertEqual(harness.taskStore.recent(kind: .modelCouncil, limit: 2).map(\.id), [taskID])
        XCTAssertEqual(harness.viewModel.discussionRound, 3)
        XCTAssertEqual(harness.viewModel.messages.last(where: { $0.kind == .host })?.body, "第三轮主持总结")
    }

    func testHomeResumeContextRestoresEveryRecoverableContinuationStatus() async throws {
        let harness = try makeViewModelHarness(streamer: ScriptedCouncilStreamer([
            .success("首轮最终议题"),
            .success("首轮工程发言"),
            .success("首轮风险发言"),
            .success("首轮主持总结"),
        ]))

        harness.viewModel.inputText = "首轮议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let taskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )
        for status in [
            IOSAdvancedTaskStatus.failed,
            .cancelled,
            .timedOut,
            .interrupted,
        ] {
            _ = harness.taskStore.updateTask(
                id: taskID,
                status: .completed,
                metadata: ["continuation_status": status.rawValue]
            )

            let context = try XCTUnwrap(harness.viewModel.homeResumeContext)
            XCTAssertEqual(context.status, status)
            XCTAssertTrue(context.canContinue)
        }
    }

    func testHomeResumeContextRejectsUndecodableArchive() async throws {
        let harness = try makeViewModelHarness(streamer: ScriptedCouncilStreamer([
            .success("首轮最终议题"),
            .success("首轮工程发言"),
            .success("首轮风险发言"),
            .success("首轮主持总结"),
        ]))

        harness.viewModel.inputText = "首轮议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let taskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )
        let archiveURL = harness.archiveBaseDirectory
            .appendingPathComponent("council", isDirectory: true)
            .appendingPathComponent("\(taskID).json", isDirectory: false)
        try Data("not-json".utf8).write(to: archiveURL, options: .atomic)

        XCTAssertNil(harness.viewModel.homeResumeContext)
    }

    func testHomeResumeContextRejectsMismatchedArchiveIdentity() async throws {
        let harness = try makeViewModelHarness(streamer: ScriptedCouncilStreamer([
            .success("首轮最终议题"),
            .success("首轮工程发言"),
            .success("首轮风险发言"),
            .success("首轮主持总结"),
        ]))

        harness.viewModel.inputText = "首轮议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let taskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )
        let archiveURL = harness.archiveBaseDirectory
            .appendingPathComponent("council", isDirectory: true)
            .appendingPathComponent("\(taskID).json", isDirectory: false)
        let archiveData = try Data(contentsOf: archiveURL)
        var archive = try XCTUnwrap(
            JSONSerialization.jsonObject(with: archiveData) as? [String: Any]
        )
        archive["taskId"] = "another-task"
        try JSONSerialization.data(withJSONObject: archive)
            .write(to: archiveURL, options: .atomic)

        XCTAssertNil(harness.viewModel.homeResumeContext)
    }

    func testHomeResumeProjectionInvalidatesWhenFirstCouncilStarts() async throws {
        let streamStarted = expectation(description: "first council stream started")
        let harness = try makeViewModelHarness(
            streamer: RestartableCouncilStreamer(firstStreamStarted: streamStarted)
        )
        let projectionChanged = expectation(description: "home resume projection invalidated")
        withObservationTracking {
            XCTAssertNil(harness.viewModel.homeResumeContext)
        } onChange: {
            projectionChanged.fulfill()
        }

        harness.viewModel.inputText = "新议题"
        harness.viewModel.send()
        await fulfillment(of: [projectionChanged, streamStarted], timeout: 1)
        harness.viewModel.cancelDiscussion()
    }

    func testFollowUpRestoresEvictedTaskWithTheSameIdentity() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = ScriptedCouncilStreamer([
            .success("工程续轮发言"),
            .success("风险续轮发言"),
            .success("主持续轮总结")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var request = roomRequest(
            settings: compactRoomSettings(defaultRounds: 1),
            researchConsent: .unavailable
        )
        request.continuation = IOSCouncilRoomContinuation(
            taskId: "evicted-council-task",
            originalObjective: "原始议题",
            finalTopic: "主持完善后的最终议题",
            priorTranscript: "[主持人] 既有总结",
            speakers: [
                IOSCouncilRoomSpeaker(
                    id: "host",
                    name: "主持人",
                    rolePrompt: "主持与综合",
                    modelId: "gpt-main",
                    reasoning: .off,
                    prompt: "",
                    isHost: true
                ),
                IOSCouncilRoomSpeaker(
                    id: "engineering",
                    name: "工程",
                    rolePrompt: "工程实现",
                    modelId: "gpt-main",
                    reasoning: .medium,
                    prompt: "",
                    isHost: false
                ),
                IOSCouncilRoomSpeaker(
                    id: "risk",
                    name: "风险",
                    rolePrompt: "风险审计",
                    modelId: "gpt-main",
                    reasoning: .medium,
                    prompt: "",
                    isHost: false
                )
            ],
            nextRound: 2
        )

        let outcome = await runner.run(request: request)

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.taskId, "evicted-council-task")
        XCTAssertEqual(taskStore.tasks.map(\.id), ["evicted-council-task"])
    }

    func testTopBarCircleButtonsKeepGlyphsAboveNativeGlassAndTintTrailingActions() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let sourceDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let sharedChrome = try String(
            contentsOf: sourceDirectory.appendingPathComponent("PlaceholderViews.swift"),
            encoding: .utf8
        )
        let council = try String(
            contentsOf: sourceDirectory.appendingPathComponent("CouncilChatRuntimeView.swift"),
            encoding: .utf8
        )

        guard let buttonStart = sharedChrome.range(of: "struct AmberGlassCircleButton: View"),
              let sectionStart = sharedChrome.range(
                of: "struct AmberSectionLabel: View",
                range: buttonStart.upperBound..<sharedChrome.endIndex
              ) else {
            return XCTFail("Expected the shared circle button source")
        }
        let buttonSource = String(sharedChrome[buttonStart.lowerBound..<sectionStart.lowerBound])
        let compactSharedChrome = sharedChrome.filter { !$0.isWhitespace }
        let compactCouncil = council.filter { !$0.isWhitespace }

        XCTAssertTrue(buttonSource.contains("var tint: Color = AmberTheme.foreground2"))
        XCTAssertTrue(buttonSource.contains(".foregroundStyle(tint)"))
        XCTAssertTrue(buttonSource.contains(".glassEffect(.regular.interactive(), in: Circle())"))
        XCTAssertFalse(
            buttonSource.contains("private var circleGlass"),
            "The glass effect must stay attached to the glyph label; a sibling glass layer washes the glyph out on device."
        )
        XCTAssertTrue(council.contains("AmberGlassGroup(spacing: 0)"))
        XCTAssertTrue(compactSharedChrome.contains(
            "HomeGlassCircleButton(icon:.gear,accessibilityLabel:\"设置\",size:38,iconSize:20,tint:AmberTheme.fab)"
        ))
        XCTAssertTrue(compactCouncil.contains(
            "accessibilityLabel:\"历史议会\",size:44,symbolSize:18,tint:AmberTheme.accent"
        ))
        XCTAssertTrue(compactCouncil.contains(
            "accessibilityLabel:\"议会设置\",size:44,symbolSize:18,tint:AmberTheme.accent"
        ))
        XCTAssertTrue(compactCouncil.contains(
            "accessibilityLabel:\"返回\",size:44,symbolSize:20,tint:AmberTheme.foreground"
        ))
    }

    func testMidRunCheckpointsThrottleToOffMainThreadWrites() async throws {
        // 旧实现:roster/append/消息完结每个事件都在主线程同步 save(≈11 次 encode+write)。
        // 新实现:中段事件经 300ms 节流合并成离主线程延迟写,终态一次同步写收口。
        // ScriptedCouncilStreamer 无 pacing,事件密集到达 → 节流任务在终态前不会单独触发,
        // 因此写入次数应远低于事件数(这里断言 ≤5,旧实现约 11 会红)。
        let harness = try makeViewModelHarness(streamer: ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("风险发言"),
            .success("主持总结")
        ]))

        harness.viewModel.inputText = "归档节流议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTAssertFalse(harness.viewModel.isRunning)

        let taskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )
        let archive = try XCTUnwrap(harness.archiveStore.load(taskId: taskID))
        XCTAssertTrue(
            archive.messages.contains { $0.body == "主持总结" },
            "终态同步写必须把完成态落盘,load-after-save 要看到最新事实。"
        )
        XCTAssertGreaterThanOrEqual(
            harness.archiveStore.completedWriteCount, 2,
            "至少应有 taskStarted 与终态两次同步写。"
        )
        XCTAssertLessThanOrEqual(
            harness.archiveStore.completedWriteCount, 5,
            "中段检查点应防抖合并,不能每条消息都在主线程同步落盘。"
        )
    }

    func testViewModelDoesNotResearchWhenWebSearchIsDisabled() async throws {
        let researcher = RecordingCouncilResearcher()
        let harness = try makeViewModelHarness(
            streamer: ScriptedCouncilStreamer([
                .success("最终议题"),
                .success("工程发言"),
                .success("风险发言"),
                .success("主持总结")
            ]),
            researcher: researcher
        )
        harness.sharedSettings.setEnableWebSearch(false)

        harness.viewModel.inputText = "不联网议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }

        XCTAssertEqual(researcher.callCount, 0)
        let task = try XCTUnwrap(harness.taskStore.recent(kind: .modelCouncil, limit: 1).first)
        XCTAssertEqual(task.toolScope, [])
        XCTAssertEqual(task.metadata["research_consent"], IOSCouncilResearchConsent.unavailable.rawValue)
    }

    func testChatCouncilToolWaitsForPermissionBeforeStartingRun() async throws {
        let defaults = isolatedDefaults()
        let permissionStore = IOSPermissionStore(userDefaults: defaults, taskStore: nil)
        let localToolExecutor = IOSLocalToolExecutor(
            permissionStore: permissionStore,
            documentStore: DocumentAccessStore(),
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: FileManager.default.temporaryDirectory
                    .appendingPathComponent("council-tool-test-\(UUID().uuidString)")
            )
        )
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: defaults),
            localToolExecutor: localToolExecutor,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
        let provider = IOSCouncilRoomRunner.makeProviderSetting(
            baseUrl: "https://example.com/v1",
            apiKey: "test-key"
        )
        let model = Model(
            modelId: "gpt-main",
            displayName: "Council Test Model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let toolCall = UIMessagePart.Tool(
            toolCallId: "council-approval",
            toolName: "model_council_run",
            input: #"{"objective":"审查发布风险","max_seats":4}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let assistantSeed = UIMessage.companion.assistant(prompt: "")
        let assistant = UIMessage(
            id: assistantSeed.id,
            role: assistantSeed.role,
            parts: [toolCall],
            annotations: assistantSeed.annotations,
            createdAt: assistantSeed.createdAt,
            finishedAt: assistantSeed.finishedAt,
            modelId: assistantSeed.modelId,
            usage: assistantSeed.usage,
            translation: assistantSeed.translation
        )
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: provider,
            params: params,
            runId: "run-council-approval",
            startedAt: 0,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [assistant]
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: context
        )

        guard case .waitingForApproval(.council(let request)) = result else {
            return XCTFail("Council tool must wait for its configured approval policy.")
        }
        XCTAssertEqual(request.objectivePreview, "审查发布风险")
        XCTAssertEqual(request.maxSeats, 4)

        let deniedMessages = await runtime.finishCouncilApproval(
            pending: context,
            allow: false
        )
        let deniedTool = try XCTUnwrap(
            deniedMessages.flatMap(\.parts).compactMap { $0 as? UIMessagePart.Tool }.first
        )
        XCTAssertEqual(
            ChatToolOutputFormatter.failureReason(from: deniedTool.output),
            "用户拒绝启动模型议会。"
        )
        XCTAssertEqual(permissionStore.approvalRecords.first?.action, .denied)
    }

    func testCouncilToolFailureWithoutReasonStillRendersAsFailure() {
        let output: [UIMessagePart] = [
            UIMessagePart.Text(
                text: #"{"ok":false,"status":"failed"}"#,
                metadata: nil
            )
        ]

        XCTAssertEqual(
            ChatToolOutputFormatter.failureReason(from: output),
            "工具执行失败"
        )
    }

    func testOpeningArchiveCheckpointsActiveCouncilTailBeforeReplacingRoom() async throws {
        let firstStreamStarted = expectation(description: "active council stream started before archive switch")
        let harness = try makeViewModelHarness(
            streamer: RestartableCouncilStreamer(firstStreamStarted: firstStreamStarted)
        )
        let targetTaskID = "archived-target"
        harness.archiveStore.save(CouncilPersistedRoom(
            taskId: targetTaskID,
            objective: "历史议题",
            modeRaw: CouncilDiscussionMode.freeChat.rawValue,
            statusRaw: "就绪",
            failedSpeakerIds: [],
            participants: harness.viewModel.participants.map(CouncilPersistedParticipant.init),
            messages: [CouncilPersistedMessage(CouncilChatMessage(
                kind: .system,
                author: "议会",
                body: "历史内容",
                systemImage: "clock",
                tint: .gray,
                subtitle: nil
            ))],
            updatedAtMs: 1
        ))

        harness.viewModel.inputText = "运行中的议题"
        harness.viewModel.send()
        await fulfillment(of: [firstStreamStarted], timeout: 1)
        let activeTaskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )

        harness.viewModel.openArchive(taskId: targetTaskID)

        let checkpoint = try XCTUnwrap(harness.archiveStore.load(taskId: activeTaskID))
        XCTAssertTrue(checkpoint.messages.contains { $0.body == "旧轮精确尾部" })
        XCTAssertFalse(checkpoint.messages.contains { $0.status == "speaking" })
        XCTAssertEqual(checkpoint.statusRaw, "已取消")
        XCTAssertEqual(harness.viewModel.activeReplayTaskId, targetTaskID)
        XCTAssertEqual(harness.viewModel.messages.last?.body, "历史内容")
    }

    func testStartingFreshRoomCheckpointsActiveCouncilTailBeforeClearingRoom() async throws {
        let firstStreamStarted = expectation(description: "active council stream started before room reset")
        let harness = try makeViewModelHarness(
            streamer: RestartableCouncilStreamer(firstStreamStarted: firstStreamStarted)
        )

        harness.viewModel.inputText = "即将重开的议题"
        harness.viewModel.send()
        await fulfillment(of: [firstStreamStarted], timeout: 1)
        let activeTaskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )

        harness.viewModel.startFreshRoom()

        let checkpoint = try XCTUnwrap(harness.archiveStore.load(taskId: activeTaskID))
        XCTAssertTrue(checkpoint.messages.contains { $0.body == "旧轮精确尾部" })
        XCTAssertFalse(checkpoint.messages.contains { $0.status == "speaking" })
        XCTAssertEqual(checkpoint.statusRaw, "已取消")
        XCTAssertFalse(harness.viewModel.isRunning)
        XCTAssertTrue(harness.viewModel.messages.isEmpty)
    }

    func testRoomRunnerMissingAPIKeyFailsBeforeStreaming() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = ScriptedCouncilStreamer([])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(),
                apiKey: "",
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .failed)
        XCTAssertTrue(outcome.failureReason?.contains("API Key") == true)
        XCTAssertEqual(streamer.callCount, 0)
        let task = try XCTUnwrap(taskStore.recent(kind: .modelCouncil, limit: 1).first)
        XCTAssertEqual(task.status, .failed)
        XCTAssertTrue(task.error.contains("API Key"))
    }

    func testStartupRecoveryOnlyInterruptsRunningCouncilTasks() throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let runningCouncil = taskStore.startTask(
            kind: .modelCouncil,
            title: "running council",
            objective: "council"
        )
        let completedCouncil = taskStore.startTask(
            kind: .modelCouncil,
            title: "completed council",
            objective: "done"
        )
        _ = taskStore.updateTask(id: completedCouncil.id, status: .completed)
        let runningContinuation = taskStore.startTask(
            kind: .modelCouncil,
            title: "running follow-up",
            objective: "follow-up",
            metadata: ["continuation_base_completed": "true"]
        )
        let runningSubAgent = taskStore.startTask(
            kind: .subAgent,
            title: "running subagent",
            objective: "subagent"
        )

        let interruptedIDs = taskStore.markInterruptedCouncilTasks()
        let secondPass = taskStore.markInterruptedCouncilTasks()

        XCTAssertEqual(Set(interruptedIDs), Set([runningCouncil.id, runningContinuation.id]))
        XCTAssertTrue(secondPass.isEmpty)
        XCTAssertEqual(taskStore.tasks.first { $0.id == runningCouncil.id }?.status, .interrupted)
        XCTAssertEqual(taskStore.tasks.first { $0.id == runningCouncil.id }?.metadata["interruption_reason"], "process_terminated")
        XCTAssertEqual(taskStore.tasks.first { $0.id == runningContinuation.id }?.status, .completed)
        XCTAssertEqual(
            taskStore.tasks.first { $0.id == runningContinuation.id }?.metadata["continuation_status"],
            IOSAdvancedTaskStatus.interrupted.rawValue
        )
        XCTAssertEqual(taskStore.tasks.first { $0.id == completedCouncil.id }?.status, .completed)
        XCTAssertEqual(taskStore.tasks.first { $0.id == runningSubAgent.id }?.status, .running)
    }

    private func compactRoomSettings(
        currentModelId: String = "gpt-main",
        defaultRounds: Int = 2
    ) -> IOSCouncilRoomSettings {
        IOSCouncilRoomSettings(
            host: IOSCouncilHostConfig(
                modelId: "gpt-host",
                reasoning: .off,
                prompt: "主持人只做议题组织和总结。"
            ),
            seats: [
                IOSCouncilRoomSeatConfig(
                    id: "engineering",
                    name: "工程",
                    rolePrompt: "工程实现",
                    modelId: "gpt-engineer",
                    reasoning: .medium,
                    prompt: "短句",
                    isDefault: true
                ),
                IOSCouncilRoomSeatConfig(
                    id: "risk",
                    name: "风险",
                    rolePrompt: "风险审计",
                    modelId: "gpt-risk",
                    reasoning: .high,
                    prompt: "指出缺口",
                    isDefault: true
                )
            ],
            limits: IOSCouncilRoomLimits(
                maxSeats: 2,
                defaultRounds: defaultRounds,
                seatTimeoutSeconds: 30,
                outputBudgetCharacters: 6_000
            ),
            legacySeatsImported: true
        ).normalized(currentModelId: currentModelId)
    }

    private func makeCouncilModel(_ modelId: String) -> Model {
        makeCouncilModel(modelId, providerOverwrite: nil)
    }

    private func makeCouncilModel(
        _ modelId: String,
        providerOverwrite: ProviderSetting?
    ) -> Model {
        Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [ModelAbility.reasoning],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: providerOverwrite
        )
    }

    private func makeCouncilProvider(
        name: String = "Council Test Provider",
        models: [Model]
    ) -> ProviderSetting {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: name,
            models: models,
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "test-key",
            baseUrl: "https://example.com/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func roomRequest(
        mode: IOSCouncilRoomRunMode = .freeChat,
        settings: IOSCouncilRoomSettings,
        apiKey: String = "test-key",
        researchConsent: IOSCouncilResearchConsent = .unavailable,
        providerSetting: ProviderSetting? = nil,
        providerSettings: [ProviderSetting] = [],
        currentModel: Model? = nil
    ) -> IOSCouncilRoomRunRequest {
        IOSCouncilRoomRunRequest(
            objective: "完善 iOS 模型议会",
            mode: mode,
            settings: settings,
            currentModelId: "gpt-main",
            currentModel: currentModel,
            providerSetting: providerSetting ?? IOSCouncilRoomRunner.makeProviderSetting(
                baseUrl: "https://example.com/v1",
                apiKey: apiKey
            ),
            providerSettings: providerSettings,
            searchSettings: nil,
            researchConsent: researchConsent
        )
    }

    private func roomSpeaker(id: String, name: String, modelId: String) -> IOSCouncilRoomSpeaker {
        IOSCouncilRoomSpeaker(
            id: id,
            name: name,
            rolePrompt: "\(name)职责",
            modelId: modelId,
            reasoning: .medium,
            prompt: "",
            isHost: false
        )
    }

    private func makeViewModelHarness(
        streamer: any IOSCouncilTextStreaming,
        researcher: any IOSCouncilResearching = StaticCouncilResearcher()
    ) throws -> (
        viewModel: CouncilChatViewModel,
        archiveStore: CouncilRoomArchiveStore,
        archiveBaseDirectory: URL,
        taskStore: IOSAdvancedTaskStore,
        sharedSettings: IOSSharedSettingsStore,
        defaults: UserDefaults
    ) {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let permissionStore = IOSPermissionStore(
            userDefaults: defaults,
            storageKey: "policies",
            approvalStorageKey: "approvals",
            taskStore: taskStore
        )
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: researcher,
            taskStore: taskStore,
            permissionStore: permissionStore
        )
        let settingsStore = SettingsStore(
            userDefaults: defaults,
            storageKey: "legacy-settings",
            apiKeyStore: CouncilTestAPIKeyStore(key: "test-key")
        )
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = IosSettingsMutations.shared.buildOpenAIProvider(
            name: "Council Test Provider",
            apiKey: "test-key",
            baseUrl: "https://example.com/v1",
            modelName: "Council Test Model",
            modelId: "gpt-main"
        )
        _ = sharedSettings.addProvider(provider)
        let model = try XCTUnwrap(
            sharedSettings.availableChatModels().first { $0.providerName == "Council Test Provider" }
        )
        sharedSettings.setCurrentChatModelId(model.id)
        let roomSettings = IOSCouncilRoomSettingsStore(
            userDefaults: defaults,
            storageKey: "room-settings",
            currentModelId: "gpt-main"
        )
        roomSettings.settings = compactRoomSettings(defaultRounds: 1)
        roomSettings.dynamicSeatGeneration = false
        let archiveBaseDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("council-vm-test-\(UUID().uuidString)", isDirectory: true)
        let archiveStore = CouncilRoomArchiveStore(baseDirectory: archiveBaseDirectory)
        let viewModel = CouncilChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            providerRegistry: nil,
            permissionStore: permissionStore,
            roomSettingsStore: roomSettings,
            runner: runner,
            transcriptDefaults: defaults,
            archiveStore: archiveStore
        )
        return (
            viewModel,
            archiveStore,
            archiveBaseDirectory,
            taskStore,
            sharedSettings,
            defaults
        )
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.council.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

@MainActor
private final class CouncilPresentationProbe {
    var authoritativeText: String
    var snapshotCount = 0
    var published: [String] = []

    init(authoritativeText: String = "") {
        self.authoritativeText = authoritativeText
    }
}

@MainActor
private final class ScriptedCouncilStreamer: IOSCouncilTextStreaming {
    private var outputs: [Result<String, Error>]
    private(set) var callCount = 0
    private(set) var cancelCount = 0
    private(set) var receivedModels: [Model] = []
    private(set) var receivedProviderNames: [String] = []
    private(set) var receivedUserPrompts: [String] = []

    init(_ outputs: [Result<String, Error>]) {
        self.outputs = outputs
    }

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        callCount += 1
        receivedModels.append(params.model)
        receivedProviderNames.append(providerSetting.name)
        receivedUserPrompts.append(
            messages.last?.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined() ?? ""
        )
        guard !outputs.isEmpty else { throw CouncilTestError.unexpectedCall }
        let next = outputs.removeFirst()
        switch next {
        case .success(let text):
            onUpdate(text)
            return text
        case .failure(let error):
            throw error
        }
    }

    func cancel() {
        cancelCount += 1
    }
}

@MainActor
private final class PartialTailCouncilStreamer: IOSCouncilTextStreaming {
    static let partialTail = "风险判断已经生成到这里。"
    private let failedTail: String?
    private(set) var callCount = 0

    init(failedTail: String? = partialTail) {
        self.failedTail = failedTail
    }

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        callCount += 1
        switch callCount {
        case 1:
            return publish("最终议题", through: onUpdate)
        case 2:
            return publish("工程发言", through: onUpdate)
        case 3:
            onUpdate(failedTail ?? "")
            throw CouncilTestError.scriptedFailure
        case 4:
            return publish("主持总结", through: onUpdate)
        default:
            throw CouncilTestError.unexpectedCall
        }
    }

    func cancel() {}

    private func publish(
        _ text: String,
        through onUpdate: @MainActor (String) -> Void
    ) -> String {
        onUpdate(text)
        return text
    }
}

private final class CouncilTestAPIKeyStore: SettingsAPIKeyStore {
    private var key: String?

    init(key: String?) {
        self.key = key
    }

    func loadApiKey() -> String? {
        key
    }

    func saveApiKey(_ key: String) -> Bool {
        self.key = key
        return true
    }
}

@MainActor
private final class ProgressingFinalCouncilStreamer: IOSCouncilTextStreaming {
    private var immediateOutputs = ["最终议题", "工程发言", "风险发言"]

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        if !immediateOutputs.isEmpty {
            let text = immediateOutputs.removeFirst()
            onUpdate(text)
            return text
        }
        onUpdate("第一段")
        try await Task.sleep(nanoseconds: 100_000_000)
        onUpdate("第一段第二段")
        try await Task.sleep(nanoseconds: 100_000_000)
        return "第一段第二段"
    }

    func cancel() {}
}

@MainActor
private final class DelayedPartialCouncilStreamer: IOSCouncilTextStreaming {
    static let partialTail = "工程发言已生成到这里。"
    private let partialPublished: XCTestExpectation
    private var callCount = 0

    init(partialPublished: XCTestExpectation) {
        self.partialPublished = partialPublished
    }

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        callCount += 1
        switch callCount {
        case 1:
            onUpdate("最终议题")
            return "最终议题"
        case 2:
            // Let the placeholder's first checkpoint land, then publish a speaking
            // tail and hold long enough for a second throttled checkpoint.
            try await Task.sleep(nanoseconds: 450_000_000)
            onUpdate(Self.partialTail)
            partialPublished.fulfill()
            try await Task.sleep(nanoseconds: 450_000_000)
            return Self.partialTail
        case 3:
            onUpdate("风险发言")
            return "风险发言"
        case 4:
            onUpdate("主持总结")
            return "主持总结"
        default:
            throw CouncilTestError.unexpectedCall
        }
    }

    func cancel() {}
}

@MainActor
private final class BlockingCouncilProbeStreamer: IOSCouncilTextStreaming {
    private var continuation: CheckedContinuation<String, Error>?
    private(set) var cancelCount = 0

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
        }
    }

    func cancel() {
        cancelCount += 1
        let continuation = continuation
        self.continuation = nil
        continuation?.resume(throwing: CancellationError())
    }
}

@MainActor
private final class RestartableCouncilStreamer: IOSCouncilTextStreaming {
    private let firstStreamStarted: XCTestExpectation
    private var firstContinuation: CheckedContinuation<String, Never>?
    private var firstUpdate: (@MainActor (String) -> Void)?
    private var replacementOutputs = ["最终议题", "工程发言", "风险发言", "主持总结"]
    private(set) var callCount = 0
    private(set) var cancelCount = 0

    init(firstStreamStarted: XCTestExpectation) {
        self.firstStreamStarted = firstStreamStarted
    }

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        callCount += 1
        if callCount == 1 {
            firstUpdate = onUpdate
            firstStreamStarted.fulfill()
            return await withCheckedContinuation { continuation in
                firstContinuation = continuation
            }
        }
        guard !replacementOutputs.isEmpty else {
            throw CouncilTestError.unexpectedCall
        }
        let text = replacementOutputs.removeFirst()
        onUpdate(text)
        return text
    }

    func cancel() {
        cancelCount += 1
        guard let continuation = firstContinuation else { return }
        firstUpdate?("旧轮精确尾部")
        firstContinuation = nil
        firstUpdate = nil
        continuation.resume(returning: "旧轮精确尾部")
    }
}

@MainActor
private final class CountingCouncilResearcher: IOSCouncilResearching {
    private(set) var callCount = 0
    private(set) var receivedObjectives: [String] = []
    let marker: String
    init(marker: String = "SEAT_RESEARCH_MARKER_42") { self.marker = marker }
    func research(
        objective: String,
        settings: Settings?,
        maxSearches: Int,
        maxScrapes: Int
    ) async -> IOSCouncilResearchBundle {
        callCount += 1
        receivedObjectives.append(objective)
        return IOSCouncilResearchBundle(
            searches: [],
            scrapedPages: [IOSCouncilScrapedPage(url: "seat://research", content: marker)],
            failures: []
        )
    }
}

private final class StaticCouncilResearcher: IOSCouncilResearching {
    func research(
        objective: String,
        settings: Settings?,
        maxSearches: Int,
        maxScrapes: Int
    ) async -> IOSCouncilResearchBundle {
        IOSCouncilResearchBundle(
            searches: [],
            scrapedPages: [
                IOSCouncilScrapedPage(
                    url: "https://example.com/current",
                    content: "Fresh context for \(objective)."
                )
            ],
            failures: []
        )
    }
}

@MainActor
private final class RecordingCouncilResearcher: IOSCouncilResearching {
    private(set) var callCount = 0

    func research(
        objective: String,
        settings: Settings?,
        maxSearches: Int,
        maxScrapes: Int
    ) async -> IOSCouncilResearchBundle {
        callCount += 1
        return IOSCouncilResearchBundle(searches: [], scrapedPages: [], failures: [])
    }
}

private enum CouncilTestError: LocalizedError {
    case scriptedFailure
    case unexpectedCall

    var errorDescription: String? {
        switch self {
        case .scriptedFailure: "scripted seat failure"
        case .unexpectedCall: "unexpected streamer call"
        }
    }
}
