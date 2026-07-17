import XCTest
import SwiftUI
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
        XCTAssertTrue(shell.contains("councilChatViewModel.runtimeDidBecomeActive()"))
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
            userDragging: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 99,
            followEnabled: true,
            followPaused: false,
            userDragging: false
        ), "Content shrink is handled by terminal settle, not live-growth animation.")
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 120,
            followEnabled: true,
            followPaused: true,
            userDragging: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 120,
            followEnabled: true,
            followPaused: false,
            userDragging: true
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
            previousContentHeight: 100,
            currentContentHeight: 120,
            followEnabled: false,
            followPaused: false,
            userDragging: false
        ))
    }

    func testCouncilViewportShrinkReanchorsOnlyWhileFollowing() {
        XCTAssertTrue(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: false,
            userDragging: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 500,
            currentVisibleHeight: 600,
            followEnabled: true,
            followPaused: false,
            userDragging: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: true,
            userDragging: false
        ))
        XCTAssertFalse(CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
            previousVisibleHeight: 600,
            currentVisibleHeight: 500,
            followEnabled: true,
            followPaused: false,
            userDragging: true
        ))
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

    func testPlannedSeatsFromJSONParsesDynamicSeatsAllOnHostModel() {
        let planned = IOSCouncilRoomRunner.plannedSeatsFromJSON(
            """
            主持人输出：
            ```json
            {"seats":[{"name":"工程","lens":"看实现复杂度"},{"name":"风险","lens":"看安全和失败模式"}]}
            ```
            """,
            maxSeats: 4,
            modelId: "gpt-main"
        )
        XCTAssertEqual(planned.map(\.name), ["工程", "风险"])
        XCTAssertEqual(planned.map(\.rolePrompt), ["看实现复杂度", "看安全和失败模式"])
        // 所有动态席位都跑在主持人的工作模型上（修复 gpt-4o Not supported 的根因）。
        XCTAssertEqual(planned.map(\.modelId), ["gpt-main", "gpt-main"])
        XCTAssertFalse(planned.contains { $0.isHost })
    }

    func testPlannedSeatsFromJSONFallsBackToEmptyWhenInsufficientOrInvalid() {
        // 少于 2 个有效席位 → 空（调用方保留已 resolve 的默认席位）
        XCTAssertTrue(IOSCouncilRoomRunner.plannedSeatsFromJSON(
            #"{"seats":[{"name":"只有一个","lens":"数量非法"}]}"#,
            maxSeats: 4, modelId: "gpt-main"
        ).isEmpty)
        // 非 JSON 散文 → 空
        XCTAssertTrue(IOSCouncilRoomRunner.plannedSeatsFromJSON(
            "主持人写了一堆散文，没有任何 JSON。", maxSeats: 4, modelId: "gpt-main"
        ).isEmpty)
        // 超过上限按 maxSeats 截断
        let capped = IOSCouncilRoomRunner.plannedSeatsFromJSON(
            #"{"seats":[{"name":"A","lens":"a"},{"name":"B","lens":"b"},{"name":"C","lens":"c"}]}"#,
            maxSeats: 2, modelId: "gpt-main"
        )
        XCTAssertEqual(capped.map(\.name), ["A", "B"])
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
                settings: compactRoomSettings(),
                researchConsent: .allowed
            ),
            onEvent: { events.append($0) }
        )

        XCTAssertEqual(outcome.status, .completed)
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
        runner.cancel()

        let replacement = await runner.run(request: request)
        let cancelled = await firstRun.value

        XCTAssertEqual(cancelled.status, .cancelled)
        XCTAssertEqual(replacement.status, .completed)
        XCTAssertEqual(streamer.callCount, 5)
        XCTAssertEqual(streamer.cancelCount, 1)
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
            apiKey: IOSCredentialRedactor.mask,
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
            if viewModel.pendingAskUser != nil {
                viewModel.skipAskUser()
            }
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

    func testDetachedCouncilRuntimeSkipsMandatoryAskAndFinishesInBackground() async throws {
        let streamer = ScriptedCouncilStreamer([
            .success("最终议题"),
            .success("工程发言"),
            .success("风险发言"),
            .success("主持总结")
        ])
        let harness = try makeViewModelHarness(streamer: streamer)

        harness.viewModel.runtimeDidAppear()
        harness.viewModel.inputText = "离页后继续完成"
        harness.viewModel.send()
        harness.viewModel.runtimeDidDisappear()

        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }

        XCTAssertFalse(harness.viewModel.isRunning)
        XCTAssertNil(harness.viewModel.pendingAskUser)
        XCTAssertEqual(harness.viewModel.messages.last(where: { $0.kind == .host })?.body, "主持总结")
    }

    func testSecondSendStartsIsolatedCouncilRoomAndArchive() async throws {
        let harness = try makeViewModelHarness(streamer: ScriptedCouncilStreamer([
            .success("第一场最终议题"),
            .success("第一场工程发言"),
            .success("第一场风险发言"),
            .success("第一场主持总结"),
            .success("第二场最终议题"),
            .success("第二场工程发言"),
            .success("第二场风险发言"),
            .success("第二场主持总结")
        ]))

        harness.viewModel.inputText = "第一场用户议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let firstTaskID = try XCTUnwrap(
            harness.taskStore.recent(kind: .modelCouncil, limit: 1).first?.id
        )

        harness.viewModel.inputText = "第二场用户议题"
        harness.viewModel.send()
        for _ in 0..<200 where harness.viewModel.isRunning {
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        let taskIDs = harness.taskStore.recent(kind: .modelCouncil, limit: 2).map(\.id)
        let secondTaskID = try XCTUnwrap(taskIDs.first { $0 != firstTaskID })
        let firstArchive = try XCTUnwrap(harness.archiveStore.load(taskId: firstTaskID))
        let secondArchive = try XCTUnwrap(harness.archiveStore.load(taskId: secondTaskID))

        XCTAssertTrue(firstArchive.messages.contains { $0.body == "第一场用户议题" })
        XCTAssertFalse(firstArchive.messages.contains { $0.body == "第二场用户议题" })
        XCTAssertTrue(secondArchive.messages.contains { $0.body == "第二场用户议题" })
        XCTAssertFalse(secondArchive.messages.contains { $0.body == "第一场用户议题" })
        XCTAssertFalse(harness.viewModel.messages.contains { $0.body == "第一场用户议题" })
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
        let runningSubAgent = taskStore.startTask(
            kind: .subAgent,
            title: "running subagent",
            objective: "subagent"
        )

        let interruptedIDs = taskStore.markInterruptedCouncilTasks()
        let secondPass = taskStore.markInterruptedCouncilTasks()

        XCTAssertEqual(interruptedIDs, [runningCouncil.id])
        XCTAssertTrue(secondPass.isEmpty)
        XCTAssertEqual(taskStore.tasks.first { $0.id == runningCouncil.id }?.status, .interrupted)
        XCTAssertEqual(taskStore.tasks.first { $0.id == runningCouncil.id }?.metadata["interruption_reason"], "process_terminated")
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

    private func roomRequest(
        mode: IOSCouncilRoomRunMode = .freeChat,
        settings: IOSCouncilRoomSettings,
        apiKey: String = "test-key",
        researchConsent: IOSCouncilResearchConsent = .unavailable
    ) -> IOSCouncilRoomRunRequest {
        IOSCouncilRoomRunRequest(
            objective: "完善 iOS 模型议会",
            mode: mode,
            settings: settings,
            currentModelId: "gpt-main",
            providerSetting: IOSCouncilRoomRunner.makeProviderSetting(
                baseUrl: "https://example.com/v1",
                apiKey: apiKey
            ),
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
        taskStore: IOSAdvancedTaskStore,
        sharedSettings: IOSSharedSettingsStore
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
            apiKey: IOSCredentialRedactor.mask,
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
            permissionStore: permissionStore,
            roomSettingsStore: roomSettings,
            runner: runner,
            transcriptDefaults: defaults,
            archiveStore: archiveStore
        )
        return (viewModel, archiveStore, taskStore, sharedSettings)
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
