import SwiftUI
import XCTest
@testable import iosApp

@MainActor
final class NovelSessionViewModelTests: XCTestCase {
    func testDiscussionArchiveDistillsWithoutPersistingThenCommitsEditedDecisions() async throws {
        var document = try NovelTestFixtures.document()
        let omittedProse = String(repeating: "这段正文候选绝不能进入讨论蒸馏。", count: 80)
        document.sessions[0].messages = [
            NovelSessionMessageRecord(
                id: NovelMessageID(), sequence: 0, role: .user, mode: .discussPlan,
                kind: .userInput, content: "主角应在何时揭示身世？",
                createdAt: Date(timeIntervalSince1970: 1_700_000_001),
                runID: nil, candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: NovelMessageID(), sequence: 1, role: .assistant, mode: .discussPlan,
                kind: .discussion, content: "建议第三章末揭示。",
                createdAt: Date(timeIntervalSince1970: 1_700_000_002),
                runID: nil, candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: NovelMessageID(), sequence: 2, role: .assistant, mode: .writeProse,
                kind: .proseCandidate, content: omittedProse,
                createdAt: Date(timeIntervalSince1970: 1_700_000_003),
                runID: nil, candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: NovelMessageID(), sequence: 3, role: .user, mode: .discussPlan,
                kind: .userInput, content: "就定在第三章末。",
                createdAt: Date(timeIntervalSince1970: 1_700_000_004),
                runID: nil, candidateID: nil
            ),
        ]
        document.sessions[0].revision = 4
        try NovelDocumentValidator.validate(document)
        let archiveJSON = """
        {"schemaVersion":1,"decisions":[{"topic":"身世揭示","decision":"第三章末揭示。","relatedMaterialID":null}],"summary":"已确定身世揭示时点。"}
        """
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta(archiveJSON), .complete])]
        )

        let distilled = await harness.session.distillDiscussionArchive(chapterID: nil)
        let draft = try XCTUnwrap(distilled)
        XCTAssertEqual(draft.throughSequence, 3)
        XCTAssertEqual(draft.decisions.map(\.topic), ["身世揭示"])
        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertFalse(request.messages.map(\.content).joined().contains(omittedProse))

        var persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertNil(persisted.sessions[0].archiveCursor)
        XCTAssertFalse(persisted.materials.contains { $0.kind == .decisionLog })

        let rejected = await harness.session.confirmDiscussionArchive(
            draft,
            decisions: [],
            summary: draft.summary
        )
        XCTAssertFalse(rejected)
        persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertNil(persisted.sessions[0].archiveCursor)

        var edited = draft.decisions
        edited[0].decision = "第五章开场揭示。"
        let confirmed = await harness.session.confirmDiscussionArchive(
            draft,
            decisions: edited,
            summary: "确认在第五章开场揭示身世。"
        )
        XCTAssertTrue(confirmed)

        persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(persisted.sessions[0].archiveCursor, .through(sequence: 3))
        XCTAssertEqual(persisted.sessions[0].discussionArchives?.last?.summary, "确认在第五章开场揭示身世。")
        let decisionMaterial = try XCTUnwrap(
            persisted.materials.first { $0.kind == .decisionLog }
        )
        XCTAssertEqual(
            persisted.materialRevisions.first { $0.materialID == decisionMaterial.id }?.content,
            "第五章开场揭示。"
        )
    }

    func testStartingRunProjectsUserPromptBeforeProviderConnects() async throws {
        var document = try NovelTestFixtures.document()
        document.sessions[0].messages = [
            NovelSessionMessageRecord(
                id: NovelMessageID(),
                sequence: 0,
                role: .user,
                mode: .discussPlan,
                kind: .userInput,
                content: "上一轮问题",
                createdAt: Date(timeIntervalSince1970: 1_700_000_000),
                runID: nil,
                candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: NovelMessageID(),
                sequence: 1,
                role: .assistant,
                mode: .discussPlan,
                kind: .discussion,
                content: String(repeating: "上一轮回答仍应留在视口布局中。", count: 80),
                createdAt: Date(timeIntervalSince1970: 1_700_000_001),
                runID: nil,
                candidateID: nil
            ),
        ]
        document.sessions[0].revision = 2
        try NovelDocumentValidator.validate(document)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])],
            usesAttachGate: true
        )
        let gate = try XCTUnwrap(harness.attachGate)
        await gate.blockNextStart()

        let sendTask = Task { @MainActor in
            await harness.session.send(text: "这一轮问题必须立即可见")
        }
        let startBlocked = await eventually {
            await gate.startIsBlocked()
        }
        XCTAssertTrue(startBlocked)

        let project = try XCTUnwrap(harness.workspace.projectSnapshot)
        let branch = try XCTUnwrap(harness.workspace.branchSnapshot)
        let listModel = try XCTUnwrap(
            harness.session.projectedListModel(project: project, branch: branch)
        )
        let rows = listModel.rows
        XCTAssertEqual(rows.map(\.role), [.user, .assistant, .user, .assistant])
        XCTAssertEqual(rows[0].content, "上一轮问题")
        XCTAssertEqual(rows[2].content, "这一轮问题必须立即可见")
        XCTAssertEqual(rows.last?.transientPhase, .waitingForFirstToken)
        XCTAssertEqual(listModel.historicalRows.map(\.role), [.user, .assistant])
        XCTAssertEqual(listModel.activeRunRows.map(\.role), [.user, .assistant])
        let startingUserDigest = rows[2].digest

        await gate.resumeBlockedStart()
        let didStart = await sendTask.value
        XCTAssertTrue(didStart)
        let durablePromptPublished = await eventually {
            harness.session.durableMessages.contains {
                $0.content == "这一轮问题必须立即可见"
            }
        }
        XCTAssertTrue(durablePromptPublished)
        let refreshedProject = try XCTUnwrap(harness.workspace.projectSnapshot)
        let refreshedBranch = try XCTUnwrap(harness.workspace.branchSnapshot)
        let refreshedRows = try XCTUnwrap(
            harness.session.projectedListModel(project: refreshedProject, branch: refreshedBranch)
        ).rows
        XCTAssertEqual(
            refreshedRows.filter { $0.content == "这一轮问题必须立即可见" }.count,
            1
        )
        XCTAssertEqual(refreshedRows[2].digest, startingUserDigest)
        await harness.session.stop()
    }

    func testSessionInitializesFromAnAlreadyCompleteWorkspaceSelection() async throws {
        let repository = InMemoryNovelProjectRepository()
        let document = try NovelTestFixtures.document()
        _ = try await repository.createProject(document)
        let workspace = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        let didSelect = await workspace.selectProject(document.project.id)
        XCTAssertTrue(didSelect)

        let session = NovelSessionViewModel(workspace: workspace)

        XCTAssertEqual(session.binding?.projectID, document.project.id)
        XCTAssertEqual(session.binding?.branchID, document.branches.first?.id)
        XCTAssertEqual(session.durableMessages, document.sessions.first?.messages)
    }

    func testStartingRunKeepsLongSessionLayoutResponsive() async throws {
        var document = try NovelTestFixtures.document()
        let longMarkdown = "# 第一章\n\n" + String(repeating: "破庙里的风裹着雨气，众人压低声音商议下一步。\n\n", count: 180)
        document.sessions[0].messages = (0..<8).map { index in
            NovelSessionMessageRecord(
                id: NovelMessageID(),
                sequence: Int64(index),
                role: index.isMultiple(of: 2) ? .user : .assistant,
                mode: index.isMultiple(of: 2) ? .discussPlan : .writeProse,
                kind: index.isMultiple(of: 2) ? .userInput : .discussion,
                content: index.isMultiple(of: 2) ? "继续" : longMarkdown,
                createdAt: Date(timeIntervalSince1970: 1_700_000_000 + Double(index)),
                runID: nil,
                candidateID: nil
            )
        }
        document.sessions[0].revision = Int64(document.sessions[0].messages.count)
        try NovelDocumentValidator.validate(document)

        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let settings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "NovelSessionLayout-\(UUID().uuidString)")!
        )
        let host = UIHostingController(rootView: NovelSessionLayoutHarness(
            workspace: harness.workspace,
            session: harness.session,
            settings: settings
        ))
        let window = makeWindow(rootViewController: host)
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        window.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(100))

        let startedAt = ContinuousClock.now
        let didStart = await harness.session.send(text: "继续写下一段")
        window.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(100))
        let elapsed = startedAt.duration(to: .now)

        XCTAssertTrue(didStart)
        XCTAssertLessThan(
            elapsed,
            .seconds(2),
            "Adding the live tail must not force the long lazy history through a watchdog-scale layout pass."
        )
        await harness.session.stop()
    }

    func testDiscussionCompletesAsOneDurableAssistantBubble() async throws {
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta("零散"),
            .replacement("完整讨论建议"),
            .complete,
        ])])
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "下一步该怎么规划？")
        XCTAssertTrue(didStart)
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)

        XCTAssertEqual(harness.session.durableMessages.map(\.kind), [.userInput, .discussion])
        XCTAssertEqual(harness.session.durableMessages.last?.content, "完整讨论建议")
        XCTAssertNil(harness.session.durableMessages.last?.candidateID)
        XCTAssertNil(harness.session.transientTail)
        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.purpose, .discussion)
    }

    func testTerminalTailLingersUnlockedThroughQuietWindowThenRetires() async throws {
        // B' 的核心状态:完成后输入区立即解锁(terminalAwaitingRefresh 立刻清除),但
        // transient tail 在静窗内保留,避免 durable 正文接管瞬间整屏「跳一下」。旧实现
        // 「解锁」与「tail 还在」不能共存(清 tail 与解锁错时),完成瞬间会闪烁。
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .replacement("完成的讨论建议"),
            .complete,
        ])], terminalQuietDelay: 1.0)
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "下一步该怎么规划？")
        XCTAssertTrue(didStart)
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)

        let lingerUnlocked = await eventually {
            harness.session.canSend && harness.session.transientTail != nil
        }
        XCTAssertTrue(lingerUnlocked, "静窗内 tail 应保留,同时输入区已解锁")

        let tailID = try XCTUnwrap(harness.session.transientTail?.messageID)
        let quietWindowModel = try XCTUnwrap(harness.session.projectedListModel(
            project: try XCTUnwrap(harness.workspace.projectSnapshot),
            branch: try XCTUnwrap(harness.workspace.branchSnapshot)
        ))
        XCTAssertEqual(
            quietWindowModel.rows.first(where: { $0.id == tailID })?.transientPhase,
            .terminalAwaitingRefresh,
            "Durable refresh must not bypass the quiet window and replace the visible tail immediately."
        )

        // 静窗过后 tail 退役清空。
        let didRetire = await eventually(timeout: 3) { harness.session.transientTail == nil }
        XCTAssertTrue(didRetire, "静窗过后终态 tail 应退役清空")
    }

    func testNewRunWithinQuietWindowCancelsPreviousTailRetirement() async throws {
        // 静窗内开新 run:installTail 应取消上一场的退役任务,新 run 的 tail 不被上一场
        // 退役任务误清,且整条链路能正确跑到第二场完成。
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.replacement("第一场完成"), .complete]),
            NovelModelScript(steps: [.replacement("第二场完成"), .complete]),
        ], terminalQuietDelay: 0.4)
        harness.session.mode = .discussPlan

        _ = await harness.session.send(text: "第一场")
        let firstDone = await eventually { !harness.session.isRunning }
        XCTAssertTrue(firstDone)

        // 静窗内输入区已解锁,可立即开第二场。
        let secondStarted = await eventually { harness.session.canSend }
        XCTAssertTrue(secondStarted, "静窗内输入区应解锁,允许立即开新 run")
        // 判别点:B' 延迟退役下,解锁时第一场的 tail 仍在静窗里保留(unlocked+tail 共存);
        // 旧「完成即清空」实现解锁时 tail 已被清空,此断言会红。
        XCTAssertNotNil(harness.session.transientTail, "第一场 tail 应在静窗内保留,而非立即退役")
        _ = await harness.session.send(text: "第二场")
        let secondDone = await eventually(timeout: 3) { !harness.session.isRunning }
        XCTAssertTrue(secondDone)

        // 第二场结果正确落盘;tail 最终由第二场自己的静窗退役,而非被第一场任务提前清掉。
        let retired = await eventually(timeout: 3) { harness.session.transientTail == nil }
        XCTAssertTrue(retired)
        XCTAssertEqual(harness.session.durableMessages.last?.content, "第二场完成")
    }

    func testFailedStartWithinQuietWindowRestoresPreviousTailRetirement() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            scripts: [NovelModelScript(steps: [.replacement("第一场完成"), .complete])],
            terminalQuietDelay: 0.2
        )
        harness.session.mode = .discussPlan

        let firstStarted = await harness.session.send(text: "第一场")
        XCTAssertTrue(firstStarted)
        let firstFinished = await eventually {
            !harness.session.isRunning && harness.session.canSend
        }
        XCTAssertTrue(firstFinished)
        let firstTailID = try XCTUnwrap(harness.session.transientTail?.messageID)

        await repository.failNextCommits(1)
        let secondStarted = await harness.session.send(text: "启动会失败的第二场")
        XCTAssertFalse(secondStarted)
        XCTAssertEqual(harness.session.transientTail?.messageID, firstTailID)

        let retired = await eventually(timeout: 2) { harness.session.transientTail == nil }
        XCTAssertTrue(
            retired,
            "Restoring the old tail must also restore its cancelled quiet-window retirement task."
        )
    }

    func testAskUserAnswerStartsTheNextDiscussionTurn() async throws {
        let prompt = NovelAskUserPrompt(
            question: "他此刻更害怕失去谁？",
            options: ["家人", "同伴"]
        )
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.askUser(prompt, preface: "先确认人物动机。")]),
            NovelModelScript(steps: [.replacement("那就先强化他保护家人的选择。"), .complete]),
        ])
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "帮我梳理人物动机")
        XCTAssertTrue(didStart)
        let didAsk = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didAsk)
        let promptMessage = try XCTUnwrap(harness.session.durableMessages.last)
        XCTAssertEqual(promptMessage.interaction, .askUser(prompt))

        let didAnswer = await harness.session.answerAskUser(
            promptMessageID: promptMessage.id,
            answer: "家人"
        )
        XCTAssertTrue(didAnswer)
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)
        XCTAssertEqual(harness.session.durableMessages.count, 4)
        XCTAssertEqual(harness.session.durableMessages[2].content, "家人")
        XCTAssertEqual(harness.session.durableMessages[3].content, "那就先强化他保护家人的选择。")
    }

    func testWholeChapterUsesOneMonotonicTransientTailThenPersistsCandidate() async throws {
        let longBody = String(repeating: "长章正文。", count: 2_000)
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta(longBody),
            .pause,
            .delta("结尾。"),
            .complete,
        ])])
        harness.session.mode = .writeProse
        harness.session.granularity = .wholeChapter

        let didStart = await harness.session.send(text: "生成这一整章")
        XCTAssertTrue(didStart)
        // Presentation is paced: a ~10k-char provider burst drains over many 48ms ticks.
        // First prove the tail is monotonic and mid-drain (prefix only), then wait for catch-up.
        let sawPacedPrefix = await eventually {
            guard let content = harness.session.transientTail?.content else { return false }
            return !content.isEmpty
                && content.count < longBody.count
                && longBody.hasPrefix(content)
        }
        XCTAssertTrue(
            sawPacedPrefix,
            "Long-chapter burst must publish a paced prefix before the full body."
        )
        let sawLongTail = await eventually(timeout: 15) {
            harness.session.transientTail?.content == longBody
        }
        XCTAssertTrue(sawLongTail)
        XCTAssertFalse(harness.workspace.canMutate)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let firstRevision = try XCTUnwrap(harness.session.transientTail?.renderRevision)
        XCTAssertEqual(harness.session.durableMessages.count, 1)
        XCTAssertEqual(harness.session.transientTail?.kind, .proseCandidate)
        // Multi-line burst must take more than one paced publication to fully reveal.
        XCTAssertGreaterThan(
            firstRevision,
            1,
            "Long-chapter backlog should surface through multiple presentation ticks."
        )

        await harness.adapter.resume(runID: runID)
        let expectedFinal = longBody + "结尾。"
        let didFinish = await eventually(timeout: 15) {
            !harness.session.isRunning
                && harness.session.transientTail == nil
                && harness.session.availableProseCandidates.first?.content == expectedFinal
        }
        XCTAssertTrue(didFinish)
        XCTAssertEqual(harness.session.availableProseCandidates.first?.content, expectedFinal)
        XCTAssertNil(harness.session.transientTail)
        XCTAssertTrue(harness.workspace.canMutate)
        XCTAssertGreaterThan(firstRevision, 0)
        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.purpose, .prose)
        // 2026-07-25 契约变更(用户明确裁决):四类生成任务不再人为设置输出上限。
        // 原断言锁的是整章 8_192,现改为断言「不设限」——人为上限会被推理模型的
        // 思考 token 吃掉并触发假失败(见 NovelGenerationLifecycle.modelParameters 注释)。
        XCTAssertNil(request.parameters.maxOutputTokens)
    }

    func testWholeChapterBurstCoalescesUIPublicationsWithoutChangingDurableFinalText() async throws {
        let deltaCount = 240
        let fragment = "雾"
        let expected = String(repeating: fragment, count: deltaCount)
        let harness = try await makeHarness(scripts: [NovelModelScript(
            steps: Array(repeating: .delta(fragment), count: deltaCount) + [.pause, .complete]
        )])
        harness.session.mode = .writeProse
        harness.session.granularity = .wholeChapter

        let didStart = await harness.session.send(text: "生成一段连续的雾景")
        XCTAssertTrue(didStart)
        let caughtUp = await eventually {
            harness.session.transientTail?.content == expected
        }
        XCTAssertTrue(caughtUp)
        let presentationRevision = try XCTUnwrap(harness.session.transientTail?.renderRevision)
        XCTAssertLessThan(
            presentationRevision,
            UInt64(deltaCount / 2),
            "Provider chunk count must not directly determine SwiftUI publication count."
        )

        let runID = try XCTUnwrap(harness.session.activeRunID)
        await harness.adapter.resume(runID: runID)
        // isRunning 在终态 presentation(terminalAwaitingRefresh 置位)即转假,早于 durable
        // 刷新落盘;只等 !isRunning 会撞上「run 已结束但 assistant 消息尚未落盘」的竞态
        // (此刻 durableMessages.last 暂时还是用户那条)。等到 durable 末条真正落盘再断言——
        // 与本文件 prose 用例的 durable 等待一致,放宽容忍窗口但不放宽断言本身。
        let didFinish = await eventually(timeout: 5) {
            !harness.session.isRunning && harness.session.durableMessages.last?.content == expected
        }
        XCTAssertTrue(didFinish)
        XCTAssertEqual(harness.session.durableMessages.last?.content, expected)
    }

    func testStreamingTailRevisionReusesDurableProjection() async throws {
        let longBody = String(repeating: "长章投影。", count: 2_000)
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta(longBody),
            .pause,
        ])])
        harness.session.mode = .writeProse
        harness.session.granularity = .wholeChapter

        let didStart = await harness.session.send(text: "验证投影缓存")
        XCTAssertTrue(didStart)
        let sawFirstPacedFrame = await eventually {
            guard let tail = harness.session.transientTail else { return false }
            return !tail.content.isEmpty && tail.content.count < longBody.count
        }
        XCTAssertTrue(sawFirstPacedFrame)

        let project = try XCTUnwrap(harness.workspace.projectSnapshot)
        let branch = try XCTUnwrap(harness.workspace.branchSnapshot)
        let firstRevision = try XCTUnwrap(harness.session.transientTail?.renderRevision)
        _ = try XCTUnwrap(harness.session.projectedListModel(project: project, branch: branch))
        let fullBuildsAfterFirstFrame = harness.session.fullProjectionBuildCountForTesting

        let advanced = await eventually {
            (harness.session.transientTail?.renderRevision ?? 0) > firstRevision
        }
        XCTAssertTrue(advanced)
        let updated = try XCTUnwrap(
            harness.session.projectedListModel(project: project, branch: branch)
        )

        XCTAssertEqual(
            harness.session.fullProjectionBuildCountForTesting,
            fullBuildsAfterFirstFrame,
            "A content-only tail revision must update one row without rebuilding every durable row."
        )
        XCTAssertEqual(updated.rows.last?.content, harness.session.transientTail?.content)
        await harness.session.stop()
    }

    func testBufferedReplacementSupersedesUnpublishedDeltasAndKeepsFollowingText() async throws {
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta("应被替换"),
            .replacement("最终前缀"),
            .delta("与结尾"),
            .pause,
            .complete,
        ])])
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "测试替换式输出")
        XCTAssertTrue(didStart)
        let sawReplacement = await eventually {
            harness.session.transientTail?.content == "最终前缀与结尾"
        }
        XCTAssertTrue(sawReplacement)

        let runID = try XCTUnwrap(harness.session.activeRunID)
        await harness.adapter.resume(runID: runID)
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)
        XCTAssertEqual(harness.session.durableMessages.last?.content, "最终前缀与结尾")
    }

    func testDetachedWorkspaceConsumerDoesNotCancelAndRunStillPersistsCompletion() async throws {
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta("离开页面前的正文"),
            .pause,
            .delta("，后台继续完成。"),
            .complete,
        ])])
        harness.session.mode = .writeProse
        harness.session.granularity = .continuation

        let didStart = await harness.session.send(text: "开始生成后离开页面")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually {
            harness.session.transientTail?.content == "离开页面前的正文"
        }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)

        harness.session.detachConsumer()
        let beforeResume = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(beforeResume.activeRuns.first { $0.id == runID }?.status, .running)
        let cancellationsBeforeResume = await harness.adapter.cancelledRunIDs
        XCTAssertFalse(cancellationsBeforeResume.contains(runID))

        await harness.adapter.resume(runID: runID)
        let persisted = await eventually {
            let document = try? await harness.repository.loadProject(id: harness.projectID).document
            return document?.activeRuns.first { $0.id == runID }?.status == .completed
        }
        XCTAssertTrue(persisted)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.candidates.first?.content, "离开页面前的正文，后台继续完成。")
        let finalCancellations = await harness.adapter.cancelledRunIDs
        XCTAssertFalse(finalCancellations.contains(runID))
    }

    func testAppBackgroundExpirationInterruptsRunAfterWorkspaceSelectsAnotherProject() async throws {
        let harness = try await makeHarness(scripts: [NovelModelScript(steps: [
            .delta("应保存的后台片段"),
            .pause,
        ])])
        harness.session.mode = .writeProse

        let didStart = await harness.session.send(text: "离开项目后继续生成")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually {
            harness.session.transientTail?.content == "应保存的后台片段"
        }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        harness.session.detachConsumer()

        let otherProject = try NovelTestFixtures.document()
        _ = try await harness.repository.createProject(otherProject)
        await harness.workspace.loadProjects(selecting: otherProject.project.id)
        XCTAssertEqual(harness.workspace.selectedProjectID, otherProject.project.id)

        await harness.workspace.interruptSessionForBackground(deadline: .distantPast)

        let didPersistInterruption = await eventually {
            let document = try? await harness.repository.loadProject(id: harness.projectID).document
            return document?.activeRuns.first { $0.id == runID }?.status == .interrupted
        }
        XCTAssertTrue(didPersistInterruption)
        let original = try await harness.repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(original.activeRuns.first { $0.id == runID })
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .expiration)
        XCTAssertEqual(run.partialContent, "应保存的后台片段")
        XCTAssertEqual(original.sessions[0].messages.last?.kind, .interruptedDraft)
    }

    func testConcurrentRebindsKeepOneConsumerAndApplyEachDeltaOnce() async throws {
        let harness = try await makeHarness(
            scripts: [NovelModelScript(steps: [
                .pause,
                .delta("只追加一次"),
                .pause,
            ])],
            usesAttachGate: true
        )
        harness.session.mode = .discussPlan
        let started = await harness.session.send(text: "建立可恢复订阅")
        XCTAssertTrue(started)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let durable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(durable)
        harness.session.detachConsumer()
        let gate = try XCTUnwrap(harness.attachGate)
        await gate.blockNextStart()

        let firstBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        let attachBlocked = await eventually { await gate.startIsBlocked() }
        XCTAssertTrue(attachBlocked)
        let secondBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        await secondBind.value
        await gate.resumeBlockedStart()
        await firstBind.value

        await harness.adapter.resume(runID: runID)
        let received = await eventually {
            harness.session.transientTail?.content == "只追加一次"
        }
        XCTAssertTrue(received)
        try? await Task.sleep(for: .milliseconds(30))
        XCTAssertEqual(harness.session.transientTail?.content, "只追加一次")
        await harness.session.stop()
    }

    func testStaleAttachCannotOverwriteANewerBranchBinding() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])],
            usesAttachGate: true
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: document.branches[0].headCheckpointID,
            name: "新分支"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan
        let started = await harness.session.send(text: "旧分支运行")
        XCTAssertTrue(started)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let durable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(durable)
        harness.session.detachConsumer()
        let gate = try XCTUnwrap(harness.attachGate)
        await gate.blockNextStart()

        let staleAttach = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        let attachBlocked = await eventually { await gate.startIsBlocked() }
        XCTAssertTrue(attachBlocked)
        await harness.workspace.selectBranch(destinationBranchID)
        await harness.session.bindToCurrentSelection()
        await gate.resumeBlockedStart()
        await staleAttach.value

        XCTAssertEqual(harness.session.binding?.branchID, destinationBranchID)
        XCTAssertNil(harness.session.transientTail)
        XCTAssertNil(harness.session.refreshErrorMessage)
    }

    func testRebindCanonicalizesMultipleInjectionOverridesWithoutChangingRunIdentity() async throws {
        let fixture = try documentWithMaterials()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        harness.session.mode = .discussPlan
        let overrides = NovelInjectionOverrides(
            forceIncludeMaterialIDs: [fixture.materialIDs[1], fixture.materialIDs[0], fixture.materialIDs[1]],
            forceExcludeMaterialIDs: []
        )

        let didStart = await harness.session.send(
            text: "比较两份设定",
            injectionOverrides: overrides
        )
        XCTAssertTrue(didStart)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let refreshedReceipt = await eventually {
            harness.workspace.projectSnapshot?.injectionReceipts.contains(where: {
                $0.runID == runID
            }) == true
        }
        XCTAssertTrue(refreshedReceipt)
        let receipt = try XCTUnwrap(harness.workspace.projectSnapshot?.injectionReceipts.last)
        XCTAssertEqual(receipt.forceIncludeMaterialIDs, fixture.materialIDs.sorted {
            $0.description < $1.description
        })

        harness.session.detachConsumer()
        await harness.session.bindToCurrentSelection()
        let reattached = await eventually {
            harness.session.activeRunID == runID && !harness.session.hasRefreshError
        }
        XCTAssertTrue(reattached)
        XCTAssertNil(harness.session.refreshErrorMessage)
        await harness.session.stop()
    }

    func testExplicitStopPersistsPartialAndRouteExitDoesNotDependOnConsumerCancellation() async throws {
        let harness = try await makeHarness(scripts: [NovelModelScript(
            steps: [.delta("保留的半段正文"), .pause, .delta("迟到内容"), .complete],
            ignoresCancellation: true
        )])
        harness.session.mode = .writeProse
        harness.session.granularity = .continuation

        let didStart = await harness.session.send(text: "先写一小段")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually {
            harness.session.transientTail?.content == "保留的半段正文"
        }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        await harness.session.interruptForRouteExit()

        let document = try await harness.repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(document.activeRuns.first { $0.id == runID })
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .routeExit)
        XCTAssertEqual(run.partialContent, "保留的半段正文")
        XCTAssertEqual(document.sessions[0].messages.last?.kind, .interruptedDraft)
        XCTAssertEqual(document.candidates.first?.status, .interrupted)
        XCTAssertEqual(document.candidates.first?.content, run.partialContent)
        let cancelledRunIDs = await harness.adapter.cancelledRunIDs
        XCTAssertTrue(cancelledRunIDs.contains(runID))
    }

    func testInterruptedProseCanBeCollectedThenUndoneWithoutLeavingRetryAction() async throws {
        let partial = "Mara opened the archive.\n\nShe found a map."
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.delta(partial), .pause]),
            NovelModelScript(steps: [.delta(validRebuildJSON), .pause, .complete]),
        ])
        harness.session.mode = .writeProse
        harness.session.granularity = .wholeChapter

        let didStart = await harness.session.send(text: "写完整一章")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually {
            harness.session.transientTail?.content == partial
        }
        XCTAssertTrue(sawPartial)
        await harness.session.stop()
        let persistedInterruption = await eventually {
            harness.workspace.projectSnapshot?.candidates.contains {
                $0.kind == .prose && $0.status == .interrupted
            } == true
        }
        XCTAssertTrue(persistedInterruption)

        let candidate = try XCTUnwrap(
            harness.workspace.projectSnapshot?.candidates.first {
                $0.kind == .prose && $0.status == .interrupted
            }
        )
        XCTAssertEqual(candidate.content, partial)
        let paragraphs = harness.session.paragraphs(candidateID: candidate.id)
        let collected = await harness.session.collectCandidate(
            candidate.id,
            selection: NovelParagraphSelection(paragraphIDs: paragraphs.map(\.id)),
            target: .createNextChapter(chapterID: NovelChapterID(), title: "新章")
        )
        XCTAssertTrue(collected)

        let collectedProject = try await harness.repository.loadProject(
            id: harness.projectID
        ).document
        XCTAssertEqual(
            collectedProject.candidates.first { $0.id == candidate.id }?.status,
            .collected
        )
        XCTAssertEqual(collectedProject.chapterVersions.last?.content, partial)
        let projected = try XCTUnwrap(harness.session.projectedListModel(
            project: try XCTUnwrap(harness.workspace.projectSnapshot),
            branch: try XCTUnwrap(harness.workspace.branchSnapshot)
        ))
        XCTAssertFalse(projected.rows.flatMap(\.actions).contains {
            if case .retryGeneration = $0.action { return true }
            return false
        })

        let syncStarted = await eventually { await harness.adapter.requests.count == 2 }
        XCTAssertTrue(syncStarted)
        let requests = await harness.adapter.requests
        let syncRunID = try XCTUnwrap(requests.last?.runID)
        await harness.adapter.resume(runID: syncRunID)
        let syncCompleted = await eventually(timeout: 5) {
            harness.workspace.branchSnapshot?.branch.syncStatus == .synchronized &&
                !harness.workspace.isPerforming
        }
        XCTAssertTrue(syncCompleted, harness.workspace.errorMessage ?? "自动剧情同步未完成")

        await harness.workspace.undoBranchHead()
        await harness.session.bindToCurrentSelection()
        let undone = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(undone.branches[0].headCheckpointID, candidate.baseCheckpointID)
    }

    func testSelectBranchTerminatesOldDurableRunBeforeRebinding() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("旧分支内容"), .pause])]
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "另一条线"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        XCTAssertNotEqual(destinationBranchID, sourceBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "继续旧分支")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually { harness.session.transientTail?.content == "旧分支内容" }
        XCTAssertTrue(sawPartial)
        let oldRunID = try XCTUnwrap(harness.session.activeRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == oldRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)

        await harness.workspace.selectBranch(destinationBranchID)
        await harness.session.bindToCurrentSelection()

        XCTAssertEqual(harness.session.binding?.branchID, destinationBranchID)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        let oldRun = try XCTUnwrap(final.activeRuns.first { $0.id == oldRunID })
        XCTAssertEqual(oldRun.status, .interrupted)
        XCTAssertEqual(oldRun.interruptionReason, .routeExit)
    }

    func testSelectBranchPreflightFailureLeavesOldDurableRunRunning() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.delta("继续生成"), .pause])],
            usesSnapshotGate: true
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "读取失败目标"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "不要误停旧分支")
        XCTAssertTrue(didStart)
        let receivedPartial = await eventually {
            harness.session.transientTail?.content == "继续生成"
        }
        XCTAssertTrue(receivedPartial)
        let oldRunID = try XCTUnwrap(harness.session.activeRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == oldRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)

        await harness.snapshotGate?.failNextBranchSnapshots(1)
        await harness.workspace.selectBranch(destinationBranchID)

        XCTAssertEqual(harness.workspace.selectedBranchID, sourceBranchID)
        XCTAssertEqual(harness.workspace.branchSnapshot?.branch.id, sourceBranchID)
        XCTAssertNotNil(harness.workspace.errorMessage)
        XCTAssertFalse(harness.workspace.isPerforming)
        let unchanged = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(unchanged.activeRuns.first(where: { $0.id == oldRunID })?.status, .running)
        let cancelledRunIDs = await harness.adapter.cancelledRunIDs
        XCTAssertFalse(cancelledRunIDs.contains(oldRunID))

        let interrupted = await harness.session.interruptForRouteExit()
        XCTAssertTrue(interrupted)
    }

    func testPostInterruptRefreshFailureRestoresCoherentSourceSelection() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "加载失败目标"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "先停下再切分支")
        XCTAssertTrue(didStart)
        let oldRunID = try XCTUnwrap(harness.session.activeRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == oldRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)
        harness.session.detachConsumer()
        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.blockInterruptReturn()
        let switchTask = Task { @MainActor in
            await harness.workspace.selectBranch(destinationBranchID)
        }
        let interruptBlocked = await eventually {
            await gate.interruptReturnIsBlocked()
        }
        XCTAssertTrue(interruptBlocked)
        await gate.failNextProjectSnapshots(1)
        await gate.resumeBlockedInterruptReturn()
        await switchTask.value

        XCTAssertEqual(harness.workspace.selectedBranchID, sourceBranchID)
        XCTAssertEqual(harness.workspace.branchSnapshot?.branch.id, sourceBranchID)
        XCTAssertNil(harness.workspace.branchSnapshot?.branch.activeRunID)
        XCTAssertEqual(
            harness.workspace.projectSnapshot?.activeRuns.first(where: { $0.id == oldRunID })?.status,
            .interrupted
        )
        XCTAssertNotNil(harness.workspace.errorMessage)
        XCTAssertFalse(harness.workspace.isPerforming)
        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.binding?.branchID, sourceBranchID)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        let oldRun = try XCTUnwrap(final.activeRuns.first { $0.id == oldRunID })
        XCTAssertEqual(oldRun.status, .interrupted)
        XCTAssertEqual(oldRun.interruptionReason, .routeExit)
    }

    func testSelectBranchDoesNotReReadValidatedTargetAfterInterrupt() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "已预检目标"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "预检后切换")
        XCTAssertTrue(didStart)
        let oldRunID = try XCTUnwrap(harness.session.activeRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == oldRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)
        harness.session.detachConsumer()

        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.blockInterruptReturn()
        let switchTask = Task { @MainActor in
            await harness.workspace.selectBranch(destinationBranchID)
        }
        let interruptBlocked = await eventually {
            await gate.interruptReturnIsBlocked()
        }
        XCTAssertTrue(interruptBlocked)
        await gate.failNextBranchSnapshots(1)
        await gate.resumeBlockedInterruptReturn()
        await switchTask.value

        XCTAssertEqual(harness.workspace.selectedBranchID, destinationBranchID)
        XCTAssertEqual(harness.workspace.branchSnapshot?.branch.id, destinationBranchID)
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertFalse(harness.workspace.isPerforming)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.activeRuns.first(where: { $0.id == oldRunID })?.status, .interrupted)
    }

    func testSelectBranchRevalidatesSelectionOwnershipBeforeInterrupt() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "被并发切换的目标"
        )
        let destinationBranchID = try XCTUnwrap(harness.workspace.selectedBranchID)
        await harness.workspace.selectBranch(sourceBranchID)
        await harness.session.bindToCurrentSelection()
        harness.session.mode = .discussPlan

        let didStart = await harness.session.send(text: "保持旧项目生成")
        XCTAssertTrue(didStart)
        let oldRunID = try XCTUnwrap(harness.session.activeRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == oldRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)
        harness.session.detachConsumer()

        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.blockNextSnapshot()
        let switchTask = Task { @MainActor in
            await harness.workspace.selectBranch(destinationBranchID)
        }
        let preflightBlocked = await eventually {
            await gate.snapshotIsBlocked()
        }
        XCTAssertTrue(preflightBlocked)
        await gate.blockNextProjectSnapshot()
        let projectSelectionTask = Task { @MainActor in
            await harness.workspace.selectProject(harness.projectID)
        }
        let projectSelectionBlocked = await eventually {
            await gate.projectSnapshotIsBlocked()
        }
        XCTAssertTrue(projectSelectionBlocked)
        await gate.resumeBlockedSnapshot()
        await switchTask.value
        await gate.resumeBlockedProjectSnapshot()
        let didSelectProject = await projectSelectionTask.value

        XCTAssertTrue(didSelectProject)
        XCTAssertEqual(harness.workspace.selectedProjectID, harness.projectID)
        XCTAssertEqual(harness.workspace.selectedBranchID, sourceBranchID)
        XCTAssertFalse(harness.workspace.isPerforming)
        let unchanged = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(unchanged.activeRuns.first(where: { $0.id == oldRunID })?.status, .running)
        let cancelledRunIDs = await harness.adapter.cancelledRunIDs
        XCTAssertFalse(cancelledRunIDs.contains(oldRunID))

        try? await harness.workspace.interruptSessionRun(NovelCancelRunCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: nil,
                expectedConfigRevision: nil,
                expectedBranchHeadRevision: nil
            ),
            projectID: harness.projectID,
            runID: oldRunID,
            reason: .user
        ))
    }

    func testCommittedForkWithRefreshFailureKeepsCoherentSelectionWithoutOfferingReplay() async throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranchID = document.branches[0].id
        let checkpointID = try XCTUnwrap(document.checkpoints.last?.id)
        let harness = try await makeHarness(
            document: document,
            scripts: [],
            usesSnapshotGate: true
        )
        await harness.snapshotGate?.failNextBranchSnapshots(1)

        let forkedBranchID = await harness.workspace.forkBranch(
            from: sourceBranchID,
            checkpointID: checkpointID,
            name: "已提交但待重载"
        )

        XCTAssertNil(forkedBranchID)
        XCTAssertEqual(harness.workspace.selectedBranchID, sourceBranchID)
        XCTAssertEqual(harness.workspace.branchSnapshot?.branch.id, sourceBranchID)
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertNotNil(harness.workspace.reloadNoticeMessage)
        XCTAssertTrue(harness.workspace.requiresReload)
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(persisted.branches.filter { $0.lifecycle == .active }.count, 2)
        XCTAssertEqual(persisted.appliedOperations.filter { $0.kind == .forkBranch }.count, 1)

        let otherProject = try NovelTestFixtures.document()
        _ = try await harness.repository.createProject(otherProject)
        await harness.workspace.loadProjects(selecting: otherProject.project.id)
        XCTAssertEqual(harness.workspace.selectedProjectID, otherProject.project.id)
        XCTAssertFalse(harness.workspace.requiresReload)
        XCTAssertTrue(harness.workspace.canMutate)
        XCTAssertTrue(harness.workspace.hasReloadRequirement)

        await harness.workspace.retryCommittedMutationReload()
        XCTAssertFalse(harness.workspace.hasReloadRequirement)
        XCTAssertEqual(harness.workspace.selectedProjectID, otherProject.project.id)
        let reloaded = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(reloaded.branches.filter { $0.lifecycle == .active }.count, 2)
        XCTAssertEqual(reloaded.appliedOperations.filter { $0.kind == .forkBranch }.count, 1)
    }

    func testCommittedKeepBothImportWithRefreshFailureDoesNotReportOldSelectionAsSuccess() async throws {
        let source = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            document: source,
            scripts: [],
            usesSnapshotGate: true
        )
        let package = try NovelProjectPackageCodec.encode(source)
        await harness.snapshotGate?.failNextProjectSnapshots(1)

        let result = await harness.workspace.importProject(package.data, choice: .keepBoth)

        guard case .committedNeedsReload(let destinationID) = result else {
            return XCTFail("Expected committedNeedsReload, got \(String(describing: result))")
        }
        XCTAssertNotEqual(destinationID, source.project.id)
        XCTAssertEqual(harness.workspace.selectedProjectID, source.project.id)
        XCTAssertEqual(harness.workspace.projectSnapshot?.project.id, source.project.id)
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertTrue(harness.workspace.hasReloadRequirement)
        XCTAssertFalse(harness.workspace.requiresReload)
        let projects = try await harness.repository.listProjects()
        XCTAssertEqual(projects.count, 2)
    }

    func testFreshProjectSelectionKeepsAnotherProjectsScopedReloadRequirement() async throws {
        let repository = InMemoryNovelProjectRepository()
        let first = try NovelTestFixtures.documentWithForkableCheckpoint()
        let second = try NovelTestFixtures.document()
        let harness = try await makeHarness(
            repository: repository,
            document: first,
            scripts: [],
            usesSnapshotGate: true
        )
        _ = try await repository.createProject(second)
        await harness.snapshotGate?.failNextBranchSnapshots(1)
        await harness.workspace.forkBranch(
            from: first.branches[0].id,
            checkpointID: first.branches[0].headCheckpointID,
            name: "待重载分支"
        )
        XCTAssertTrue(harness.workspace.requiresReload)

        await harness.workspace.selectProject(second.project.id)

        XCTAssertEqual(harness.workspace.selectedProjectID, second.project.id)
        XCTAssertEqual(harness.workspace.projectSnapshot?.project.id, second.project.id)
        XCTAssertFalse(harness.workspace.requiresReload)
        XCTAssertNotNil(harness.workspace.reloadNoticeMessage)
        XCTAssertTrue(harness.workspace.hasReloadRequirement)
        XCTAssertTrue(harness.workspace.canMutate)
    }

    func testLoadProjectsKeepsCompleteSelectionWhenBranchRefreshFailsForRetry() async throws {
        let harness = try await makeHarness(
            scripts: [],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.failNextBranchSnapshots(1)

        await harness.workspace.loadProjects(selecting: harness.projectID)

        XCTAssertEqual(harness.workspace.selectedProjectID, harness.projectID)
        XCTAssertEqual(harness.workspace.projectSnapshot?.project.id, harness.projectID)
        XCTAssertEqual(harness.workspace.branchSnapshot?.projectID, harness.projectID)
        XCTAssertNotNil(harness.workspace.errorMessage)

        await harness.workspace.loadProjects(selecting: harness.projectID)
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertNotNil(harness.workspace.branchSnapshot)
    }

    func testRouteExitDuringSuspendedStartClosesCrossedDurableRun() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        harness.session.mode = .discussPlan
        await repository.blockNextCommit()

        let sendTask = Task { @MainActor in
            await harness.session.send(text: "在落盘前取消")
        }
        let startSuspended = await eventually {
            await repository.commitIsBlocked() && harness.session.isStarting
        }
        XCTAssertTrue(startSuspended)
        XCTAssertTrue(harness.session.canStop)

        let exitTask = Task { @MainActor in
            await harness.session.interruptForRouteExit()
        }
        let cancellationStarted = await eventually {
            harness.session.isPerformingAction
        }
        XCTAssertTrue(cancellationStarted)
        await repository.resumeBlockedCommit()

        let mayExit = await exitTask.value
        let didStart = await sendTask.value
        XCTAssertTrue(mayExit)
        XCTAssertFalse(didStart)
        XCTAssertFalse(harness.session.isStarting)
        XCTAssertFalse(harness.workspace.isPerforming)
        XCTAssertNil(harness.session.transientTail)

        let final = try await repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(final.activeRuns.first)
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .routeExit)
        XCTAssertNil(final.branches[0].activeRunID)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testBackgroundDuringSuspendedStartClosesCrossedDurableRun() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            scripts: [NovelModelScript(steps: [.pause])]
        )
        harness.session.mode = .discussPlan
        await repository.blockNextCommit()

        let sendTask = Task { @MainActor in
            await harness.session.send(text: "切到后台前仍在落盘")
        }
        let startSuspended = await eventually {
            await repository.commitIsBlocked() && harness.session.isStarting
        }
        XCTAssertTrue(startSuspended)

        let backgroundTask = Task { @MainActor in
            await harness.session.interruptForBackground(
                deadline: Date().addingTimeInterval(2)
            )
        }
        let cancellationStarted = await eventually {
            harness.session.isPerformingAction
        }
        XCTAssertTrue(cancellationStarted)
        await repository.resumeBlockedCommit()
        await backgroundTask.value
        let didStart = await sendTask.value
        XCTAssertFalse(didStart)

        let final = try await repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(final.activeRuns.first)
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .background)
        XCTAssertNil(final.branches[0].activeRunID)
        XCTAssertNil(harness.session.transientTail)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testBackgroundBeforeActorStartCannotLeaveAHiddenRun() async throws {
        let harness = try await makeHarness(
            scripts: [NovelModelScript(steps: [.pause])],
            usesAttachGate: true
        )
        let gate = try XCTUnwrap(harness.attachGate)
        await gate.blockNextStart()
        let sendTask = Task { @MainActor in
            await harness.session.send(text: "在 actor 接收前切到后台")
        }
        let startBlocked = await eventually {
            await gate.startIsBlocked() && harness.session.isStarting
        }
        XCTAssertTrue(startBlocked)

        await harness.session.interruptForBackground(
            deadline: Date().addingTimeInterval(2)
        )
        await gate.resumeBlockedStart()
        let didStart = await sendTask.value

        XCTAssertFalse(didStart)
        XCTAssertFalse(harness.workspace.isPerforming)
        XCTAssertNil(harness.session.transientTail)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertTrue(final.activeRuns.isEmpty)
        XCTAssertNil(final.branches[0].activeRunID)
        XCTAssertTrue(final.sessions[0].messages.isEmpty)
        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
    }

    func testPersistenceBlockedTailRetriesTheSameRun() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            scripts: [NovelModelScript(steps: [.delta("等待落盘"), .pause, .complete])]
        )
        harness.session.mode = .discussPlan
        let didStart = await harness.session.send(text: "给我一个建议")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually { harness.session.transientTail?.content == "等待落盘" }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)

        await repository.failNextCommits(3)
        await harness.adapter.resume(runID: runID)
        let sawBlockedTerminal = await eventually { harness.session.canRetryPendingTerminal }
        XCTAssertTrue(sawBlockedTerminal)
        XCTAssertFalse(harness.session.canSend)
        let blockedTail = harness.session.transientTail
        let secondStart = await harness.session.send(text: "不应覆盖待保存回复")
        XCTAssertFalse(secondStart)
        XCTAssertEqual(harness.session.transientTail, blockedTail)
        let blockedError = harness.session.operationErrorMessage
        await harness.session.interruptForRouteExit()
        XCTAssertEqual(harness.session.operationErrorMessage, blockedError)
        guard case .persistenceBlocked = harness.session.transientTail?.phase else {
            return XCTFail("Route exit must preserve the persistence-blocked terminal claim.")
        }
        await harness.session.retryPendingTerminal()
        let didFinish = await eventually { !harness.session.isRunning }
        XCTAssertTrue(didFinish)
        XCTAssertTrue(harness.session.canSend)

        let final = try await repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.activeRuns.first { $0.id == runID }?.status, .completed)
        XCTAssertEqual(final.sessions[0].messages.last?.content, "等待落盘")
    }

    func testQuickStartTerminalBubbleRetriesThroughWorkspaceFlow() async throws {
        let retryableFailure = NovelModelFailure(
            code: "quick_start_failed",
            message: "快速开始暂时失败",
            isRetryable: true
        )
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [
                NovelModelScript(steps: [.fail(retryableFailure)]),
                NovelModelScript(steps: [.delta(quickStartSuggestionsJSON), .complete]),
            ]
        )

        await harness.workspace.startQuickStartSuggestions()
        let firstFailed = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.last?.status == .failed
        }
        XCTAssertTrue(firstFailed)
        let failedRunID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)
        await harness.session.bindToCurrentSelection()

        let retried = await harness.session.retryGeneration(runID: failedRunID)
        XCTAssertTrue(retried)
        let completed = await eventually {
            harness.workspace.projectSnapshot?.settingProposals.count == 4 &&
                harness.workspace.projectSnapshot?.activeRuns.last?.status == .completed
        }
        XCTAssertTrue(completed)
        XCTAssertNotEqual(harness.workspace.projectSnapshot?.activeRuns.last?.id, failedRunID)
    }

    func testInitialPausedQuickStartRefreshesAndAttachesSession() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])]
        )

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        await harness.session.bindToCurrentSelection()

        XCTAssertEqual(harness.session.activeRunID, runID)
        XCTAssertTrue(harness.session.canStop)
        XCTAssertNotNil(harness.session.transientTail)
        await harness.session.stop()
    }

    func testQuickStartPlaceholderHandsOffToDurableStreamingConsumer() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [
                .pause,
                .delta(quickStartSuggestionsJSON),
                .pause,
                .complete,
            ])]
        )

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.activeRunID, runID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true && harness.workspace.quickStartStartingRun == nil
        }
        XCTAssertTrue(becameDurable, "Quick Start must cross the durable start boundary before replaying deltas.")
        harness.session.detachConsumer()
        let firstBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        let secondBind = Task { @MainActor in
            await harness.session.bindToCurrentSelection()
        }
        await firstBind.value
        await secondBind.value

        await harness.adapter.resume(runID: runID)
        let receivedDelta = await eventually {
            harness.session.transientTail?.phase == .streaming
        }
        XCTAssertTrue(receivedDelta)
        XCTAssertEqual(harness.session.transientTail?.content, "")
        await harness.session.stop()
    }

    func testQuickStartHiddenDeltasPublishStreamingPhaseOnlyOnce() async throws {
        let hiddenDeltas = quickStartSuggestionsJSON
            .components(separatedBy: "\n")
            .map { NovelModelScriptStep.delta($0 + "\n") }
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause] + hiddenDeltas + [.pause, .complete])]
        )

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        await harness.session.bindToCurrentSelection()
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true && harness.workspace.quickStartStartingRun == nil
        }
        XCTAssertTrue(becameDurable)
        await harness.session.bindToCurrentSelection()

        await harness.adapter.resume(runID: runID)
        let receivedHiddenOutput = await eventually {
            harness.session.transientTail?.phase == .streaming
        }
        XCTAssertTrue(receivedHiddenOutput, "The first hidden delta must publish the streaming phase.")
        try? await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(harness.session.transientTail?.content, "")
        XCTAssertEqual(
            harness.session.transientTail?.renderRevision,
            1,
            "Hidden structured output should not invalidate the empty Quick Start bubble per chunk."
        )
        await harness.session.stop()
    }

    func testPreBindRouteExitCancelsSuspendedQuickStart() async throws {
        let repository = NovelSessionFailingRepository()
        let harness = try await makeHarness(
            repository: repository,
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])]
        )
        let unboundSession = NovelSessionViewModel(workspace: harness.workspace)
        await repository.blockNextCommit()
        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let commitBlocked = await eventually { await repository.commitIsBlocked() }
        XCTAssertTrue(commitBlocked)

        let exitTask = Task { @MainActor in
            await unboundSession.interruptForRouteExit()
        }
        let cancellationStarted = await eventually { unboundSession.isPerformingAction }
        XCTAssertTrue(cancellationStarted)
        await repository.resumeBlockedCommit()

        let mayExit = await exitTask.value
        XCTAssertTrue(mayExit)
        let final = try await repository.loadProject(id: harness.projectID).document
        let run = try XCTUnwrap(final.activeRuns.first { $0.id == runID })
        XCTAssertEqual(run.status, .interrupted)
        XCTAssertEqual(run.interruptionReason, .routeExit)
        XCTAssertNil(final.branches[0].activeRunID)
        XCTAssertNil(harness.workspace.quickStartStartingRun)
    }

    func testQuickStartPreDurableFailureClearsSessionPlaceholder() async throws {
        let failure = NovelModelFailure(
            code: "resolve_failed",
            message: "模型暂时不可用",
            isRetryable: true
        )
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [],
            resolutionFailure: failure
        )

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.activeRunID, runID)
        let failed = await eventually {
            if case .failed = harness.workspace.quickStartStatus { return true }
            return false
        }
        XCTAssertTrue(failed)
        await harness.session.bindToCurrentSelection()
        XCTAssertNil(harness.session.transientTail)
        XCTAssertFalse(harness.session.isRunning)
        XCTAssertTrue(harness.workspace.projectSnapshot?.activeRuns.isEmpty == true)
    }

    func testQuickStartReloadAfterStartRefreshFailureCanAttachPausedRun() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.failNextSnapshots(2)

        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let refreshFailed = await eventually {
            if case .refreshFailed = harness.workspace.quickStartStatus { return true }
            return false
        }
        XCTAssertTrue(refreshFailed)
        XCTAssertFalse(harness.workspace.projectSnapshot?.activeRuns.contains(where: {
            $0.id == runID && $0.status == .running
        }) == true)

        await harness.workspace.reloadQuickStartProject()
        await harness.session.bindToCurrentSelection()
        XCTAssertEqual(harness.session.activeRunID, runID)
        XCTAssertTrue(harness.session.canStop)
        XCTAssertNotNil(harness.session.transientTail)
        await harness.session.stop()
    }

    func testQuickStartStopReleasesBusyStateWhenTerminalRefreshFails() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [.pause])],
            usesSnapshotGate: true
        )
        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)
        await harness.session.bindToCurrentSelection()
        let gate = try XCTUnwrap(harness.snapshotGate)
        await gate.failNextSnapshots(10)

        await harness.session.stop()
        XCTAssertFalse(harness.workspace.isPerforming)
        XCTAssertNil(harness.workspace.quickStartStartingRun)
        guard case .refreshFailed = harness.workspace.quickStartStatus else {
            return XCTFail("A failed terminal refresh must leave reload reachable.")
        }
    }

    func testStaleQuickStartInterruptReconcileCannotClearANewerOwner() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [
                NovelModelScript(steps: [.pause]),
                NovelModelScript(steps: [
                    .delta(quickStartSuggestionsJSON),
                    .pause,
                    .complete,
                ]),
            ],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        let firstStartedRunID = await harness.workspace.startQuickStartSuggestions()
        let firstRunID = try XCTUnwrap(firstStartedRunID)
        let firstBecameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == firstRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(firstBecameDurable)

        await gate.blockInterruptReturn()
        let oldInterrupt = Task { @MainActor () -> Error? in
            do {
                try await harness.workspace.interruptSessionRun(NovelCancelRunCommand(
                    context: NovelMutationContext(
                        operationID: NovelOperationID(),
                        expectedProjectRevision: nil,
                        expectedConfigRevision: nil,
                        expectedBranchHeadRevision: nil
                    ),
                    projectID: harness.projectID,
                    runID: firstRunID,
                    reason: .user
                ))
                return nil
            } catch {
                return error
            }
        }
        let interruptReturnedFromBase = await eventually {
            await gate.interruptReturnIsBlocked()
        }
        XCTAssertTrue(interruptReturnedFromBase)
        try await harness.workspace.refreshCurrentSelection(projectID: harness.projectID)
        XCTAssertTrue(harness.workspace.projectSnapshot?.activeRuns.contains(where: {
            $0.id == firstRunID && $0.status == .interrupted
        }) == true)

        await gate.blockNextSnapshot()
        await gate.resumeBlockedInterruptReturn()
        let oldRefreshBlocked = await eventually {
            await gate.snapshotIsBlocked()
        }
        XCTAssertTrue(oldRefreshBlocked)

        let secondStartedRunID = await harness.workspace.startQuickStartSuggestions()
        let secondRunID = try XCTUnwrap(secondStartedRunID)
        let secondBecameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == secondRunID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(secondBecameDurable)

        await gate.resumeBlockedSnapshot()
        let oldInterruptError = await oldInterrupt.value
        XCTAssertNil(oldInterruptError)
        XCTAssertEqual(harness.workspace.projectSnapshot?.branches.first?.activeRunID, secondRunID)
        guard case .generating(let ownerID) = harness.workspace.quickStartStatus else {
            return XCTFail("The newer Quick Start must remain the active owner.")
        }
        XCTAssertEqual(ownerID, secondRunID)

        await harness.adapter.resume(runID: secondRunID)
        let secondCompleted = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == secondRunID && $0.status == .completed
            }) == true && harness.workspace.projectSnapshot?.settingProposals.count == 4
        }
        XCTAssertTrue(secondCompleted)
    }

    func testQuickStartTerminalCleanupDoesNotReleaseAnotherMutationBusyState() async throws {
        let harness = try await makeHarness(
            document: try quickStartDocument(),
            scripts: [NovelModelScript(steps: [
                .delta(quickStartSuggestionsJSON),
                .pause,
                .complete,
            ])],
            usesSnapshotGate: true
        )
        let gate = try XCTUnwrap(harness.snapshotGate)
        let startedRunID = await harness.workspace.startQuickStartSuggestions()
        let runID = try XCTUnwrap(startedRunID)
        let becameDurable = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .running
            }) == true
        }
        XCTAssertTrue(becameDurable)

        await gate.blockNextSnapshot()
        await harness.adapter.resume(runID: runID)
        let terminalRefreshBlocked = await eventually {
            await gate.snapshotIsBlocked()
        }
        XCTAssertTrue(terminalRefreshBlocked)

        await gate.blockNextPerform()
        let renameTask = Task { @MainActor in
            await harness.workspace.renameProject("并行改名")
        }
        let renameBlocked = await eventually {
            await gate.performIsBlocked()
        }
        XCTAssertTrue(renameBlocked)
        XCTAssertTrue(harness.workspace.isPerforming)

        await gate.resumeBlockedSnapshot()
        let quickStartFinished = await eventually {
            harness.workspace.projectSnapshot?.activeRuns.contains(where: {
                $0.id == runID && $0.status == .completed
            }) == true
        }
        XCTAssertTrue(quickStartFinished)
        XCTAssertTrue(
            harness.workspace.isPerforming,
            "Quick Start cleanup must not release a newer mutation's busy state."
        )

        await gate.resumeBlockedPerform()
        await renameTask.value
        XCTAssertFalse(harness.workspace.isPerforming)
    }

    func testTerminalRefreshFailureKeepsFinalContentWithoutPretendingToStream() async throws {
        let harness = try await makeHarness(
            scripts: [NovelModelScript(steps: [.delta("已经完成的正文"), .pause, .complete])],
            usesSnapshotGate: true
        )
        harness.session.mode = .writeProse
        let didStart = await harness.session.send(text: "写一个片段")
        XCTAssertTrue(didStart)
        let sawPartial = await eventually { harness.session.transientTail?.content == "已经完成的正文" }
        XCTAssertTrue(sawPartial)
        let runID = try XCTUnwrap(harness.session.activeRunID)
        let gate = try XCTUnwrap(harness.snapshotGate)

        await gate.failNextSnapshots(1)
        await harness.adapter.resume(runID: runID)
        let keptTerminalTail = await eventually {
            harness.session.refreshErrorMessage != nil && !harness.session.isRunning
        }
        XCTAssertTrue(keptTerminalTail)
        XCTAssertEqual(harness.session.transientTail?.content, "已经完成的正文")
        XCTAssertEqual(harness.session.transientTail?.phase, .terminalAwaitingRefresh)
        XCTAssertNil(harness.session.activeRunID)
        XCTAssertFalse(harness.session.canStop)

        let terminalProject = try XCTUnwrap(harness.workspace.projectSnapshot)
        let terminalBranch = try XCTUnwrap(harness.workspace.branchSnapshot)
        let terminalList = try XCTUnwrap(
            harness.session.projectedListModel(project: terminalProject, branch: terminalBranch)
        )
        let terminalRunRowIDs = terminalList.activeRunRows.map(\.id)
        XCTAssertEqual(terminalRunRowIDs.count, 2)

        let didRefresh = await harness.session.refresh()
        XCTAssertTrue(didRefresh)
        XCTAssertNil(harness.session.transientTail)
        XCTAssertEqual(harness.session.durableMessages.last?.content, "已经完成的正文")
        let durableProject = try XCTUnwrap(harness.workspace.projectSnapshot)
        let durableBranch = try XCTUnwrap(harness.workspace.branchSnapshot)
        let durableList = try XCTUnwrap(
            harness.session.projectedListModel(project: durableProject, branch: durableBranch)
        )
        XCTAssertNil(durableList.activeTailID)
        XCTAssertTrue(durableList.activeRunRows.isEmpty)
        XCTAssertTrue(Set(terminalRunRowIDs).isSubset(of: Set(durableList.historicalRows.map(\.id))))
    }

    func testSelectedStableParagraphCollectsAndCommitsFacts() async throws {
        let prose = "Mara opened the archive.\n\nShe found a map."
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.delta(prose), .complete]),
            NovelModelScript(steps: [.delta(validRebuildJSON), .pause, .complete]),
        ])
        harness.session.mode = .writeProse
        harness.session.granularity = .continuation
        let didStart = await harness.session.send(text: "续写档案馆")
        XCTAssertTrue(didStart)
        let sawCandidate = await eventually { !harness.session.availableProseCandidates.isEmpty }
        XCTAssertTrue(sawCandidate)
        let candidate = try XCTUnwrap(harness.session.availableProseCandidates.first)
        let paragraphs = harness.session.paragraphs(candidateID: candidate.id)
        XCTAssertEqual(paragraphs.count, 2)

        let collected = await harness.session.collectCandidate(
            candidate.id,
            selection: NovelParagraphSelection(paragraphIDs: [paragraphs[0].id]),
            target: .createNextChapter(chapterID: NovelChapterID(), title: "第一章")
        )
        XCTAssertTrue(collected)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.chapterVersions.last?.content, paragraphs[0].text)
        XCTAssertEqual(final.candidates.first { $0.id == candidate.id }?.status, .collected)
        XCTAssertTrue(final.pendingOperations.isEmpty)
        XCTAssertEqual(final.checkpoints.last?.kind, .collection)
        XCTAssertEqual(final.branches[0].syncStatus, .needsSync)

        let syncStarted = await eventually {
            await harness.adapter.requests.count == 2
        }
        XCTAssertTrue(syncStarted)
        XCTAssertFalse(harness.session.canSend)
        let collectionRequests = await harness.adapter.requests
        let syncRequest = try XCTUnwrap(collectionRequests.last)
        await harness.adapter.resume(runID: syncRequest.runID)
        let syncCompleted = await eventually {
            let document = try? await harness.repository.loadProject(id: harness.projectID).document
            return document?.branches[0].syncStatus == .synchronized &&
                harness.workspace.branchSnapshot?.branch.syncStatus == .synchronized &&
                !harness.workspace.isPerforming
        }
        XCTAssertTrue(syncCompleted)

        // Undo skips the technical state-sync checkpoint together with its collection.
        await harness.workspace.undoBranchHead()
        await harness.session.bindToCurrentSelection()
        let undone = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(undone.branches[0].headCheckpointID, candidate.baseCheckpointID)
        XCTAssertEqual(
            undone.candidates.first { $0.id == candidate.id }?.status,
            .collected
        )
        let clonedCandidateID = await harness.session.cloneCollectedProse(candidate.id)
        let clonedID = try XCTUnwrap(clonedCandidateID)
        var clonedDocument = try await harness.repository.loadProject(id: harness.projectID).document
        clonedDocument.project.lastGenerationGranularity = .wholeChapter
        harness.workspace.projectSnapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: clonedDocument,
            access: .readWrite
        ))

        XCTAssertEqual(harness.session.collectionGranularity(for: clonedID), .continuation)
    }

    func testManualRewriteSchedulesAutomaticStateSyncAfterSaving() async throws {
        let fixture = try documentWithChapter()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [
                .delta(validRebuildJSON),
                .pause,
                .complete,
            ])]
        )

        let saved = await harness.workspace.saveManualRewrite(
            chapterID: fixture.chapterID,
            title: "第一章",
            content: "Mara opened the archive."
        )
        XCTAssertTrue(saved)

        let syncStarted = await eventually {
            await harness.adapter.requests.count == 1
        }
        XCTAssertTrue(syncStarted)
        let progressPublished = await eventually(timeout: 3) {
            harness.workspace.stateSyncActivity?.requestStartedAt != nil
        }
        XCTAssertTrue(progressPublished)
        let duringSync = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(duringSync.branches[0].syncStatus, .needsSync)
        XCTAssertEqual(duringSync.pendingOperations.first?.kind, .manualSync)
        XCTAssertTrue(harness.session.retryableBranchPendingOperations.isEmpty)

        let rewriteRequests = await harness.adapter.requests
        let syncRequest = try XCTUnwrap(rewriteRequests.first)
        await harness.adapter.resume(runID: syncRequest.runID)
        let syncCompleted = await eventually {
            let document = try? await harness.repository.loadProject(id: harness.projectID).document
            return document?.branches[0].syncStatus == .synchronized
        }
        XCTAssertTrue(syncCompleted)
    }

    func testPersistedNeedsSyncWaitsForWorkspaceAppearanceBeforeAutomaticStateSync() async throws {
        let fixture = try documentWithChapter()
        let branch = fixture.document.branches[0]
        let document = try NovelReducer.apply(.saveManualEdit(NovelSaveManualEditCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: fixture.document.project.revision,
                expectedConfigRevision: fixture.document.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: fixture.document.project.id,
            branchID: branch.id,
            chapterID: fixture.chapterID,
            versionID: NovelChapterVersionID(),
            title: "第一章",
            content: "Mara opened the archive.",
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.workingRevision
        )), to: fixture.document).document
        let harness = try await makeHarness(
            document: document,
            scripts: [NovelModelScript(steps: [
                .delta(validRebuildJSON),
                .pause,
                .complete,
            ])]
        )

        try? await Task.sleep(for: .milliseconds(350))
        let requestsBeforeAppearance = await harness.adapter.requests
        XCTAssertEqual(requestsBeforeAppearance.count, 0)

        harness.workspace.scheduleAutomaticStateSyncIfNeeded()
        let syncStarted = await eventually {
            await harness.adapter.requests.count == 1
        }
        XCTAssertTrue(syncStarted)
        let requests = await harness.adapter.requests
        let syncRequest = try XCTUnwrap(requests.first)
        await harness.adapter.resume(runID: syncRequest.runID)

        let syncCompleted = await eventually {
            let project = try? await harness.repository.loadProject(id: harness.projectID).document
            return project?.branches[0].syncStatus == .synchronized
        }
        XCTAssertTrue(syncCompleted)
    }

    func testWorkspaceAppearanceResumesPersistedPendingManualSync() async throws {
        try await assertPersistedManualSyncResumes(status: .pending)
    }

    func testWorkspaceAppearanceLeavesRetryableManualSyncForExplicitRetry() async throws {
        let fixture = try persistedManualSync(status: .retryable)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [
                .delta(validRebuildJSON),
                .complete,
            ])]
        )

        harness.workspace.scheduleAutomaticStateSyncIfNeeded()
        try? await Task.sleep(for: .milliseconds(400))

        let requests = await harness.adapter.requests
        XCTAssertTrue(requests.isEmpty)
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(persisted.pendingOperations.first?.status, .retryable)
        XCTAssertEqual(persisted.pendingOperations.first?.lastError, "上一次状态同步超时")
        XCTAssertFalse(harness.workspace.isPerforming)
    }

    func testRetryableManualSyncDoesNotBlockGeneratingANewProseCandidate() async throws {
        let fixture = try persistedManualSync(status: .retryable)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [
                .delta("Mara pushed open the archive door."),
                .complete,
            ])]
        )
        harness.session.mode = .writeProse
        harness.session.granularity = .continuation

        XCTAssertTrue(harness.session.canSend)
        let didStart = await harness.session.send(text: "继续写她进入档案馆")
        XCTAssertTrue(didStart)
        let completed = await eventually {
            !harness.session.availableProseCandidates.isEmpty && !harness.session.isRunning
        }
        XCTAssertTrue(completed)

        let candidate = try XCTUnwrap(harness.session.availableProseCandidates.first)
        let paragraphs = harness.session.paragraphs(candidateID: candidate.id)
        let collected = await harness.session.collectCandidate(
            candidate.id,
            selection: NovelParagraphSelection(paragraphIDs: paragraphs.map(\.id)),
            target: .appendToChapter(try XCTUnwrap(fixture.document.chapters.first?.id))
        )
        XCTAssertFalse(collected)

        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(persisted.pendingOperations.first?.id, fixture.pendingID)
        XCTAssertEqual(persisted.pendingOperations.first?.status, .retryable)
        XCTAssertEqual(persisted.candidates.first?.status, .available)
    }

    func testRetryableManualSyncCandidateCanBeCollectedAfterSuccessfulRetry() async throws {
        let fixture = try persistedManualSync(status: .retryable)
        let prose = "Mara pushed open the archive door."
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [.delta(prose), .complete]),
                NovelModelScript(steps: [.delta(validRebuildJSON), .complete]),
                NovelModelScript(steps: [.delta(validRebuildJSON), .complete]),
            ]
        )
        harness.session.mode = .writeProse
        harness.session.granularity = .continuation

        let didStart = await harness.session.send(text: "继续写她进入档案馆")
        XCTAssertTrue(didStart)
        let generated = await eventually {
            !harness.session.availableProseCandidates.isEmpty && !harness.session.isRunning
        }
        XCTAssertTrue(generated)
        let candidate = try XCTUnwrap(harness.session.availableProseCandidates.first)

        await harness.workspace.retryPending(fixture.pendingID)
        let synchronized = await eventually {
            harness.workspace.projectSnapshot?.pendingOperations.isEmpty == true &&
                harness.workspace.branchSnapshot?.branch.syncStatus == .synchronized
        }
        XCTAssertTrue(synchronized)

        let project = try XCTUnwrap(harness.workspace.projectSnapshot)
        let branch = try XCTUnwrap(harness.workspace.branchSnapshot)
        let collectAction = harness.session.projectedListModel(
            project: project,
            branch: branch
        )?.rows.flatMap(\.actions).first {
            $0.action == .collectProse(candidate.id)
        }
        XCTAssertNil(collectAction?.blocker)

        let paragraphs = harness.session.paragraphs(candidateID: candidate.id)
        let collected = await harness.session.collectCandidate(
            candidate.id,
            selection: NovelParagraphSelection(paragraphIDs: paragraphs.map(\.id)),
            target: .appendToChapter(try XCTUnwrap(fixture.document.chapters.first?.id))
        )
        XCTAssertTrue(collected)

        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(
            persisted.candidates.first { $0.id == candidate.id }?.status,
            .collected
        )
        XCTAssertTrue(persisted.chapterVersions.contains {
            $0.sourceCandidateID == candidate.id && $0.content.contains(prose)
        })

        let collectionSynchronized = await eventually(timeout: 5) {
            harness.workspace.projectSnapshot?.pendingOperations.isEmpty == true &&
                harness.workspace.branchSnapshot?.branch.syncStatus == .synchronized &&
                harness.workspace.projectSnapshot?.checkpoints.first(where: {
                    $0.id == harness.workspace.branchSnapshot?.branch.headCheckpointID
                })?.kind == .manualSync
        }
        XCTAssertTrue(collectionSynchronized)

        let synchronizedProject = try XCTUnwrap(harness.workspace.projectSnapshot)
        let synchronizedBranch = try XCTUnwrap(harness.workspace.branchSnapshot)
        let rawHeadID = synchronizedBranch.branch.headCheckpointID
        let undoAction = harness.session.projectedListModel(
            project: synchronizedProject,
            branch: synchronizedBranch
        )?.rows.flatMap(\.actions).first { action in
            guard case .undoCommittedChange(let checkpointID, .prose) = action.action else {
                return false
            }
            return checkpointID == rawHeadID
        }
        XCTAssertNil(undoAction?.blocker)

        await harness.workspace.undoBranchHead()
        await harness.session.bindToCurrentSelection()
        let clonedCandidateID = await harness.session.cloneCollectedProse(candidate.id)
        let clonedID = try XCTUnwrap(clonedCandidateID)
        let clonedParagraphs = harness.session.paragraphs(candidateID: clonedID)
        let recollected = await harness.session.collectCandidate(
            clonedID,
            selection: NovelParagraphSelection(paragraphIDs: clonedParagraphs.map(\.id)),
            target: .appendToChapter(try XCTUnwrap(fixture.document.chapters.first?.id))
        )
        XCTAssertTrue(recollected)
    }

    func testExplicitManualSyncRetryPublishesDurableProgressUntilTerminal() async throws {
        let fixture = try persistedManualSync(status: .retryable)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [
                .delta(validRebuildJSON),
                .pause,
                .complete,
            ])]
        )
        let chapters = try NovelFactTransactionReducer.decodeManualPayload(
            try XCTUnwrap(fixture.document.pendingOperations.first?.selectedText)
        )
        let expectedCharacterCount = NovelFactTransactionReducer
            .manualRebuildManuscript(chapters)
            .count

        let retryTask = Task { @MainActor in
            await harness.workspace.retryPending(fixture.pendingID)
        }

        let progressPublished = await eventually(timeout: 3) {
            guard let activity = harness.workspace.stateSyncActivity else { return false }
            return activity.phase == .analyzing &&
                activity.pendingID == fixture.pendingID &&
                activity.completedCharacters == 0 &&
                activity.totalCharacters == expectedCharacterCount &&
                activity.completionFraction == 0 &&
                activity.requestStartedAt != nil
        }
        XCTAssertTrue(progressPublished)

        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        await harness.adapter.resume(runID: request.runID)
        await retryTask.value

        XCTAssertNil(harness.workspace.stateSyncActivity)
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertTrue(persisted.pendingOperations.isEmpty)
        XCTAssertEqual(persisted.branches[0].syncStatus, .synchronized)
    }

    /// The session view model's `retryPending` is the entry point the "重试" button in
    /// `NovelSessionView` actually calls (not `workspace.retryPending`). It must publish the
    /// same durable `stateSyncActivity` progress as the already-wired workspace-level retry.
    func testSessionRetryPendingPublishesStateSyncActivityForManualSync() async throws {
        let fixture = try persistedManualSync(status: .retryable)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [
                .delta(validRebuildJSON),
                .pause,
                .complete,
            ])]
        )
        let chapters = try NovelFactTransactionReducer.decodeManualPayload(
            try XCTUnwrap(fixture.document.pendingOperations.first?.selectedText)
        )
        let expectedCharacterCount = NovelFactTransactionReducer
            .manualRebuildManuscript(chapters)
            .count

        let retryTask = Task { @MainActor in
            await harness.session.retryPending(fixture.pendingID)
        }

        let progressPublished = await eventually(timeout: 3) {
            guard let activity = harness.workspace.stateSyncActivity else { return false }
            return activity.phase == .analyzing &&
                activity.pendingID == fixture.pendingID &&
                activity.completedCharacters == 0 &&
                activity.totalCharacters == expectedCharacterCount &&
                activity.completionFraction == 0 &&
                activity.requestStartedAt != nil
        }
        XCTAssertTrue(progressPublished)

        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        await harness.adapter.resume(runID: request.runID)
        await retryTask.value

        XCTAssertNil(harness.workspace.stateSyncActivity)
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertTrue(persisted.pendingOperations.isEmpty)
        XCTAssertEqual(persisted.branches[0].syncStatus, .synchronized)
    }

    /// `retryPending` is a generic retry entry point: pending operations can also be the
    /// `.collection` kind (legacy collection recovery), which never runs a state-sync model
    /// call. Retrying one of those must not publish a `stateSyncActivity`.
    func testSessionRetryPendingDoesNotPublishStateSyncActivityForNonManualSyncKind() async throws {
        let fixture = try documentWithChapter()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.complete])]
        )
        let branch = try XCTUnwrap(harness.workspace.branchSnapshot?.branch)
        let collectionPendingID = NovelPendingOperationID()
        let collectionPending = NovelPendingOperationRecord(
            id: collectionPendingID,
            kind: .collection,
            status: .retryable,
            branchID: branch.id,
            operationID: NovelOperationID(),
            payloadSHA256: "0000000000000000000000000000000000000000000000000000000000000",
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            candidateID: nil,
            collectionTarget: nil,
            selectedText: "A collected paragraph awaiting legacy extraction.",
            proposedChapterVersion: nil,
            createdAt: Date(timeIntervalSince1970: 1_700_800_100),
            lastError: "上一次收集提取失败"
        )
        var seededDocument = try await harness.repository.loadProject(id: harness.projectID).document
        seededDocument.pendingOperations.append(collectionPending)
        harness.workspace.projectSnapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: seededDocument,
            access: .readWrite
        ))

        await harness.session.retryPending(collectionPendingID)

        XCTAssertNil(harness.workspace.stateSyncActivity)
        // Give any (incorrectly) started polling task a chance to publish before asserting again.
        try? await Task.sleep(for: .milliseconds(150))
        XCTAssertNil(harness.workspace.stateSyncActivity)
    }

    /// Automatic background sync may already be publishing progress when the user taps retry.
    /// The single-owner mechanism (`stateSyncActivityOwnerID` gated behind `operationOwnerID`)
    /// must reject the concurrent manual retry rather than let it reset or clear the activity
    /// that automatic sync owns.
    func testConcurrentSessionRetryDoesNotStompAutomaticStateSyncOwnership() async throws {
        let fixture = try documentWithChapter()
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [
                .delta(validRebuildJSON),
                .pause,
                .complete,
            ])]
        )

        let saved = await harness.workspace.saveManualRewrite(
            chapterID: fixture.chapterID,
            title: "第一章",
            content: "Mara opened the archive."
        )
        XCTAssertTrue(saved)

        let progressPublished = await eventually(timeout: 3) {
            harness.workspace.stateSyncActivity?.requestStartedAt != nil
        }
        XCTAssertTrue(progressPublished)
        let activityBeforeRetryTap = try XCTUnwrap(harness.workspace.stateSyncActivity)
        let pendingID = activityBeforeRetryTap.pendingID

        // `workspace.projectSnapshot` only reloads once the in-flight automatic sync's
        // `perform(_:)` call returns, so it does not yet contain the pending that automatic
        // sync already committed. Bring it up to date with the live persisted document so the
        // manual retry's own guards can see the pending and actually reach
        // `acquireSessionOperation`, exercising the real ownership mutex rather than an
        // unrelated stale-snapshot guard.
        let liveDocument = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertTrue(liveDocument.pendingOperations.contains { $0.id == pendingID })
        harness.workspace.projectSnapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: liveDocument,
            access: .readWrite
        ))

        // The automatic sync still owns the in-flight operation, so this concurrent manual
        // retry must be rejected by `acquireSessionOperation` and must not touch the activity.
        await harness.session.retryPending(pendingID)

        XCTAssertEqual(harness.workspace.stateSyncActivity, activityBeforeRetryTap)

        let requests = await harness.adapter.requests
        let request = try XCTUnwrap(requests.first)
        await harness.adapter.resume(runID: request.runID)
        let syncCompleted = await eventually {
            let document = try? await harness.repository.loadProject(id: harness.projectID).document
            return document?.branches[0].syncStatus == .synchronized
        }
        XCTAssertTrue(syncCompleted)
        XCTAssertNil(harness.workspace.stateSyncActivity)
    }

    func testFailedAutomaticManualSyncPersistsOneRetryableFailureWithoutSpinning() async throws {
        let fixture = try persistedManualSync(status: .pending)
        let failure = NovelModelFailure(
            code: "state_sync_timeout",
            message: "状态同步请求超时，请稍后重试。",
            isRetryable: true
        )
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [.fail(failure)])]
        )

        harness.workspace.scheduleAutomaticStateSyncIfNeeded()

        let failurePersisted = await eventually {
            guard let loaded = try? await harness.repository.loadProject(id: harness.projectID),
                  let pending = loaded.document.pendingOperations.first(where: {
                      $0.id == fixture.pendingID
                  }) else {
                return false
            }
            return pending.status == .retryable &&
                pending.lastError == failure.message &&
                !harness.workspace.isPerforming
        }
        XCTAssertTrue(failurePersisted)

        try? await Task.sleep(for: .milliseconds(350))
        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 1)
        let persisted = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(persisted.branches[0].syncStatus, .needsSync)
        XCTAssertEqual(persisted.pendingOperations.first?.status, .retryable)
        XCTAssertEqual(persisted.pendingOperations.first?.lastError, failure.message)
        XCTAssertEqual(
            harness.workspace.projectSnapshot?.pendingOperations.first?.lastError,
            failure.message
        )
    }

    func testExactRunRetryDoesNotRetryAStillNewerTerminalBubble() async throws {
        let retryableFailure = NovelModelFailure(
            code: "first_failed",
            message: "第一次失败",
            isRetryable: true
        )
        let harness = try await makeHarness(scripts: [
            NovelModelScript(steps: [.fail(retryableFailure)]),
            NovelModelScript(steps: [.delta("第二次也中断"), .pause]),
            NovelModelScript(steps: [.delta("只重试第一条"), .complete]),
        ])
        harness.session.mode = .discussPlan
        let firstStarted = await harness.session.send(text: "第一条")
        XCTAssertTrue(firstStarted)
        let firstFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(firstFinished)
        let firstRunID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.first?.id)

        let secondStarted = await harness.session.send(text: "第二条")
        XCTAssertTrue(secondStarted)
        let sawSecondPartial = await eventually {
            harness.session.transientTail?.content == "第二次也中断"
        }
        XCTAssertTrue(sawSecondPartial)
        await harness.session.stop()
        let secondFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(secondFinished)
        let secondRunID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        let didRetryFirst = await harness.session.retryGeneration(runID: firstRunID)
        XCTAssertTrue(didRetryFirst)
        let retryFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(retryFinished)
        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.sessions[0].messages.last?.content, "只重试第一条")
        XCTAssertEqual(final.sessions[0].messages.last?.runID, final.activeRuns.last?.id)
        XCTAssertNotEqual(final.activeRuns.last?.id, secondRunID)
        let inputs = final.sessions[0].messages.filter { $0.role == .user }.map(\.content)
        XCTAssertEqual(inputs, ["第一条", "第二条", "第一条"])
    }

    func testProseRetryFailsClosedAfterBranchHeadMoves() async throws {
        let failure = NovelModelFailure(code: "retryable", message: "暂时失败", isRetryable: true)
        let harness = try await makeHarness(
            document: try NovelTestFixtures.documentWithForkableCheckpoint(),
            scripts: [
                NovelModelScript(steps: [.fail(failure)]),
                NovelModelScript(steps: [.delta("不应生成"), .complete]),
            ]
        )
        harness.session.mode = .writeProse
        let started = await harness.session.send(text: "续写")
        XCTAssertTrue(started)
        let finished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(finished)
        let runID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        await harness.workspace.undoBranchHead()
        XCTAssertFalse(harness.session.canRetryLastTerminal)
        let retried = await harness.session.retryGeneration(runID: runID)
        let bannerRetried = await harness.session.retryLastTerminal()
        let requests = await harness.adapter.requests
        XCTAssertFalse(retried)
        XCTAssertFalse(bannerRetried)
        XCTAssertEqual(requests.count, 1)
    }

    func testDiscussionRetryRemainsAllowedAfterBranchHeadMoves() async throws {
        let failure = NovelModelFailure(code: "retryable", message: "暂时失败", isRetryable: true)
        let harness = try await makeHarness(
            document: try NovelTestFixtures.documentWithForkableCheckpoint(),
            scripts: [
                NovelModelScript(steps: [.fail(failure)]),
                NovelModelScript(steps: [.delta("新 head 上的讨论"), .complete]),
            ]
        )
        harness.session.mode = .discussPlan
        let started = await harness.session.send(text: "讨论一下")
        XCTAssertTrue(started)
        let firstFinished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(firstFinished)
        let runID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        await harness.workspace.undoBranchHead()
        let retried = await harness.session.retryGeneration(runID: runID)
        XCTAssertTrue(retried)
        let retryFinished = await eventually {
            harness.session.durableMessages.last?.content == "新 head 上的讨论"
        }
        XCTAssertTrue(retryFinished)
        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 2)
    }

    func testPolishRetryFailsClosedAfterSourceChapterChanges() async throws {
        let fixture = try documentWithChapter()
        let failure = NovelModelFailure(code: "retryable", message: "暂时失败", isRetryable: true)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [.fail(failure)]),
                NovelModelScript(steps: [.delta("不应润色"), .complete]),
            ]
        )
        let started = await harness.session.startWholeChapterPolish(chapterID: fixture.chapterID)
        XCTAssertTrue(started)
        let finished = await eventually { !harness.session.isRunning }
        XCTAssertTrue(finished)
        let runID = try XCTUnwrap(harness.workspace.projectSnapshot?.activeRuns.last?.id)

        let saved = await harness.workspace.saveManualRewrite(
            chapterID: fixture.chapterID,
            title: "第一章",
            content: "剧情已被手动改写。"
        )
        XCTAssertTrue(saved)
        let retried = await harness.session.retryGeneration(runID: runID)
        let requests = await harness.adapter.requests
        XCTAssertFalse(retried)
        XCTAssertEqual(requests.count, 1)
    }

    func testWholeChapterPolishAdoptsCompatibleCandidate() async throws {
        let fixture = try documentWithChapter()
        let polished = "Mara crossed the quiet hall."
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [
                    .delta("\(polished)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
                    .complete,
                ]),
                NovelModelScript(steps: [.delta(compatibleDriftJSON), .complete]),
            ]
        )
        let baselineState = harness.workspace.branchSnapshot?.currentState

        let didStart = await harness.session.startWholeChapterPolish(chapterID: fixture.chapterID)
        XCTAssertTrue(didStart)
        let sawCandidate = await eventually { !harness.session.availablePolishCandidates.isEmpty }
        XCTAssertTrue(sawCandidate)
        let candidate = try XCTUnwrap(harness.session.availablePolishCandidates.first)
        await harness.session.adoptPolishCandidate(candidate.id)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.candidates.first { $0.id == candidate.id }?.status, .adopted)
        XCTAssertEqual(
            final.polishTransactions.first { $0.candidateID == candidate.id }?.status,
            .completed
        )
        XCTAssertEqual(final.chapterVersions.last?.kind, .polish)
        XCTAssertEqual(final.chapterVersions.last?.content, polished)
        XCTAssertEqual(final.chapterVersions.last?.sourceCandidateID, candidate.id)
        XCTAssertEqual(harness.workspace.branchSnapshot?.currentState, baselineState)
    }

    func testIncompatiblePolishCanConvertToManualRewriteAndNeedsSync() async throws {
        let fixture = try documentWithChapter()
        let rewritten = "Mara opened the gate and changed the plot."
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [
                NovelModelScript(steps: [
                    .delta("\(rewritten)\n\(NovelPromptCatalog.polishCompletionSentinel)"),
                    .complete,
                ]),
                NovelModelScript(steps: [.delta(incompatibleDriftJSON), .complete]),
            ],
            usesSnapshotGate: true
        )

        let didStart = await harness.session.startWholeChapterPolish(chapterID: fixture.chapterID)
        XCTAssertTrue(didStart)
        let sawCandidate = await eventually { !harness.session.availablePolishCandidates.isEmpty }
        XCTAssertTrue(sawCandidate)
        let candidate = try XCTUnwrap(harness.session.availablePolishCandidates.first)
        await harness.session.adoptPolishCandidate(candidate.id)
        let beforeConversion = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(
            beforeConversion.candidates.first { $0.id == candidate.id }?.status,
            .superseded
        )
        XCTAssertEqual(
            beforeConversion.polishTransactions.first { $0.candidateID == candidate.id }?.status,
            .incompatible
        )
        let manualVersionCount = beforeConversion.chapterVersions.filter { $0.kind == .manualEdit }.count
        await harness.snapshotGate?.failNextBranchSnapshots(2)
        let converted = await harness.session.convertPolishCandidateToManualRewrite(candidate.id)
        XCTAssertTrue(converted)

        let final = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(final.branches[0].syncStatus, .needsSync)
        XCTAssertEqual(final.chapterVersions.last?.kind, .manualEdit)
        XCTAssertEqual(final.chapterVersions.last?.content, rewritten)
        XCTAssertEqual(
            final.chapterVersions.filter { $0.kind == .manualEdit }.count,
            manualVersionCount + 1
        )
        XCTAssertNil(harness.workspace.errorMessage)
        XCTAssertNotNil(harness.workspace.reloadNoticeMessage)
        XCTAssertTrue(harness.workspace.requiresReload)
        await harness.workspace.retryCommittedMutationReload()
        XCTAssertFalse(harness.workspace.requiresReload)
        let afterReload = try await harness.repository.loadProject(id: harness.projectID).document
        XCTAssertEqual(
            afterReload.chapterVersions.filter { $0.kind == .manualEdit }.count,
            manualVersionCount + 1
        )
    }
}

private extension NovelSessionViewModelTests {
    struct NovelSessionLayoutHarness: View {
        let workspace: NovelCreationViewModel
        let session: NovelSessionViewModel
        let settings: IOSSharedSettingsStore

        @State private var inputText = ""
        @State private var injectionOverrides = NovelInjectionOverrides.none
        @State private var inputBudgetTokens = 16_000

        var body: some View {
            NovelSessionView(
                workspace: workspace,
                viewModel: session,
                sharedSettings: settings,
                inputText: $inputText,
                injectionOverrides: $injectionOverrides,
                inputBudgetTokens: $inputBudgetTokens,
                onOpenModel: {},
                onOpenCollection: { _ in },
                onOpenManualRewrite: { _ in },
                onFork: { _ in },
                onOpenSettingProposals: { _ in },
                onArchiveDiscussion: {}
            )
        }
    }

    struct Harness {
        let repository: any NovelProjectPersisting
        let adapter: ScriptedNovelModelAdapter
        let workspace: NovelCreationViewModel
        let session: NovelSessionViewModel
        let projectID: NovelProjectID
        let snapshotGate: NovelSessionSnapshotFailingCreation?
        let attachGate: NovelSessionAttachBlockingCreation?
    }

    func makeWindow(rootViewController: UIViewController) -> UIWindow {
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        } else {
            window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        }
        window.rootViewController = rootViewController
        window.makeKeyAndVisible()
        return window
    }

    func makeHarness(
        repository: (any NovelProjectPersisting)? = nil,
        document: NovelProjectDocumentV1? = nil,
        scripts: [NovelModelScript],
        resolutionFailure: NovelModelFailure? = nil,
        usesSnapshotGate: Bool = false,
        usesAttachGate: Bool = false,
        terminalQuietDelay: TimeInterval = 0
    ) async throws -> Harness {
        let document = try document ?? NovelTestFixtures.document()
        let repository = repository ?? InMemoryNovelProjectRepository()
        _ = try await repository.createProject(document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "session-provider",
                ownerProviderID: "session-owner",
                modelID: "session-model",
                wireModelID: "session-wire",
                displayName: "Session Model",
                contextWindowTokens: 128_000
            ),
            resolutionFailure: resolutionFailure,
            scripts: scripts
        )
        let baseCreation = DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter,
            now: { Date(timeIntervalSince1970: 1_700_800_000) }
        )
        let snapshotGate = usesSnapshotGate
            ? NovelSessionSnapshotFailingCreation(base: baseCreation)
            : nil
        let attachGate = usesAttachGate
            ? NovelSessionAttachBlockingCreation(base: baseCreation)
            : nil
        let creation: any NovelCreation
        if let snapshotGate {
            creation = snapshotGate
        } else if let attachGate {
            creation = attachGate
        } else {
            creation = baseCreation
        }
        let workspace = NovelCreationViewModel(creation: creation)
        await workspace.loadProjects(selecting: document.project.id)
        // 默认 0 静窗 = 完成即退役的快路径,保持既有用例的「完成即清空 tail」契约;
        // 验证延迟退役的新用例显式注入 >0 的静窗。
        let session = NovelSessionViewModel(workspace: workspace, terminalQuietDelay: terminalQuietDelay)
        await session.bindToCurrentSelection()
        return Harness(
            repository: repository,
            adapter: adapter,
            workspace: workspace,
            session: session,
            projectID: document.project.id,
            snapshotGate: snapshotGate,
            attachGate: attachGate
        )
    }

    func eventually(
        timeout: TimeInterval = 2,
        condition: @MainActor () async -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        return await condition()
    }

    func documentWithChapter() throws -> (
        document: NovelProjectDocumentV1,
        chapterID: NovelChapterID
    ) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        document.chapters.append(NovelChapterRecord(id: chapterID, createdAt: document.project.updatedAt))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "第一章",
            content: "Mara crossed the hall. The gate stayed closed.",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: document.project.updatedAt,
            operationID: document.appliedOperations[0].operationID
        ))
        let selection = NovelChapterSelection(chapterID: chapterID, versionID: versionID)
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == branch.headCheckpointID
        })
        let checkpoint = document.checkpoints[checkpointIndex]
        document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.parentCheckpointID,
            chapterSelections: [selection],
            stateSnapshotID: checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )
        document.branches[0].workingChapterSelections = [selection]
        try NovelDocumentValidator.validate(document)
        return (document, chapterID)
    }

    func persistedManualSync(
        status: NovelPendingOperationStatus
    ) throws -> (
        document: NovelProjectDocumentV1,
        pendingID: NovelPendingOperationID
    ) {
        let fixture = try documentWithChapter()
        let branch = fixture.document.branches[0]
        let edit = NovelSaveManualEditCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: fixture.document.project.revision,
                expectedConfigRevision: fixture.document.project.configRevision,
                expectedBranchHeadRevision: branch.headRevision
            ),
            projectID: fixture.document.project.id,
            branchID: branch.id,
            chapterID: fixture.chapterID,
            versionID: NovelChapterVersionID(),
            title: "第一章",
            content: "Mara opened the archive.",
            factCompatibilityID: UUID(),
            expectedWorkingRevision: branch.workingRevision
        )
        let edited = try NovelReducer.apply(.saveManualEdit(edit), to: fixture.document).document
        let editedBranch = edited.branches[0]
        let sync = NovelSyncManualEditsCommand(
            context: NovelMutationContext(
                operationID: NovelOperationID(),
                expectedProjectRevision: edited.project.revision,
                expectedConfigRevision: edited.project.configRevision,
                expectedBranchHeadRevision: editedBranch.headRevision
            ),
            projectID: edited.project.id,
            branchID: editedBranch.id,
            pendingID: NovelPendingOperationID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            expectedWorkingRevision: editedBranch.workingRevision
        )
        var prepared = try NovelFactTransactionReducer.prepareManualSync(
            sync,
            payloadSHA256: sync.canonicalPayloadSHA256(),
            in: edited
        ).document
        if status == .retryable {
            prepared = try NovelFactTransactionReducer.markRetryable(
                pendingID: sync.pendingID,
                message: "上一次状态同步超时",
                in: prepared
            )
        }
        return (prepared, sync.pendingID)
    }

    func assertPersistedManualSyncResumes(
        status: NovelPendingOperationStatus
    ) async throws {
        let fixture = try persistedManualSync(status: status)
        let harness = try await makeHarness(
            document: fixture.document,
            scripts: [NovelModelScript(steps: [
                .delta(validRebuildJSON),
                .complete,
            ])]
        )

        try? await Task.sleep(for: .milliseconds(100))
        let requestsBeforeAppearance = await harness.adapter.requests
        XCTAssertEqual(requestsBeforeAppearance.count, 0)

        harness.workspace.scheduleAutomaticStateSyncIfNeeded()

        let syncCompleted = await eventually {
            guard let loaded = try? await harness.repository.loadProject(id: harness.projectID) else {
                return false
            }
            return loaded.document.pendingOperations.isEmpty &&
                loaded.document.branches[0].syncStatus == .synchronized &&
                harness.workspace.projectSnapshot?.pendingOperations.isEmpty == true &&
                harness.workspace.branchSnapshot?.branch.syncStatus == .synchronized &&
                !harness.workspace.isPerforming
        }
        XCTAssertTrue(syncCompleted)
        let requests = await harness.adapter.requests
        XCTAssertEqual(requests.count, 1)
    }

    func documentWithMaterials() throws -> (
        document: NovelProjectDocumentV1,
        materialIDs: [NovelMaterialID]
    ) {
        var document = try NovelTestFixtures.document()
        let materialIDs = [NovelMaterialID(), NovelMaterialID()]
        for (index, materialID) in materialIDs.enumerated() {
            document = try NovelReducer.apply(
                NovelTestFixtures.materialAction(
                    document: document,
                    materialID: materialID,
                    revisionID: NovelMaterialRevisionID(),
                    title: "资料 \(index + 1)",
                    content: "设定内容 \(index + 1)"
                ),
                to: document
            ).document
        }
        return (document, materialIDs)
    }

    func quickStartDocument() throws -> NovelProjectDocumentV1 {
        try NovelReducer.createProject(NovelCreateProjectCommand(
            context: NovelTestFixtures.context(operationID: NovelOperationID()),
            projectID: NovelProjectID(),
            branchID: NovelBranchID(),
            sessionID: NovelSessionID(),
            initialStateSnapshotID: NovelStateSnapshotID(),
            initialCheckpointID: NovelCheckpointID(),
            name: "快速开始",
            branchName: "主线",
            creationMode: .quickStart,
            quickStartSeed: NovelQuickStartSeed(genre: "悬疑", coreIdea: "记忆可以作证")
        ), now: Date(timeIntervalSince1970: 1_700_000_000)).document
    }

    var quickStartSuggestionsJSON: String {
        """
        {
          "schemaVersion": 1,
          "overview": "一座会保存证词记忆的城市。",
          "world": {"title": "记忆城", "content": "记忆可以被封存并出庭作证。"},
          "characters": {"title": "人物", "content": "调查员林遥追查一段伪造记忆。"},
          "masterOutline": {"title": "总纲", "content": "林遥逐步发现城市证词系统被篡改。"},
          "writingRequirements": {"title": "写作要求", "content": "克制、悬疑，保持线索公平。"}
        }
        """
    }

    var validRebuildJSON: String {
        """
        {
          "schemaVersion": 1,
          "stateSummary": "Mara entered the archive.",
          "branchOutline": "Mara investigates the archive.",
          "events": [{
            "id": "archive-opened",
            "kind": "discovery",
            "summary": "Mara entered the archive.",
            "entityReferences": ["Mara"],
            "evidence": "Mara opened the archive."
          }],
          "characterStates": [],
          "relationships": [],
          "foreshadowing": [],
          "unresolvedEntityNames": ["Mara"],
          "settingProposals": []
        }
        """
    }

    var compatibleDriftJSON: String {
        """
        {"schemaVersion":1,"compatible":true,"differences":[]}
        """
    }

    var incompatibleDriftJSON: String {
        """
        {
          "schemaVersion": 1,
          "compatible": false,
          "differences": [{
            "id": "gate-opened",
            "category": "event",
            "summary": "The gate opened.",
            "sourceEvidence": "The gate stayed closed.",
            "candidateEvidence": "Mara opened the gate."
          }]
        }
        """
    }
}

private actor NovelSessionSnapshotFailingCreation: NovelCreation {
    private let base: any NovelCreation
    private var remainingSnapshotFailures = 0
    private var remainingBranchSnapshotFailures = 0
    private var remainingProjectSnapshotFailures = 0
    private var shouldBlockNextSnapshot = false
    private var blockedSnapshotContinuation: CheckedContinuation<Void, Never>?
    private var shouldBlockNextProjectSnapshot = false
    private var blockedProjectSnapshotContinuation: CheckedContinuation<Void, Never>?
    private var shouldBlockNextPerform = false
    private var blockedPerformContinuation: CheckedContinuation<Void, Never>?
    private var shouldBlockInterruptReturn = false
    private var blockedInterruptContinuation: CheckedContinuation<Void, Never>?

    init(base: any NovelCreation) {
        self.base = base
    }

    func failNextSnapshots(_ count: Int) {
        remainingSnapshotFailures = count
    }

    func failNextBranchSnapshots(_ count: Int) {
        remainingBranchSnapshotFailures = count
    }

    func failNextProjectSnapshots(_ count: Int) {
        remainingProjectSnapshotFailures = count
    }

    func blockNextSnapshot() {
        shouldBlockNextSnapshot = true
    }

    func snapshotIsBlocked() -> Bool {
        blockedSnapshotContinuation != nil
    }

    func resumeBlockedSnapshot() {
        let continuation = blockedSnapshotContinuation
        blockedSnapshotContinuation = nil
        continuation?.resume()
    }

    func blockNextProjectSnapshot() {
        shouldBlockNextProjectSnapshot = true
    }

    func projectSnapshotIsBlocked() -> Bool {
        blockedProjectSnapshotContinuation != nil
    }

    func resumeBlockedProjectSnapshot() {
        let continuation = blockedProjectSnapshotContinuation
        blockedProjectSnapshotContinuation = nil
        continuation?.resume()
    }

    func blockNextPerform() {
        shouldBlockNextPerform = true
    }

    func performIsBlocked() -> Bool {
        blockedPerformContinuation != nil
    }

    func resumeBlockedPerform() {
        let continuation = blockedPerformContinuation
        blockedPerformContinuation = nil
        continuation?.resume()
    }

    func blockInterruptReturn() {
        shouldBlockInterruptReturn = true
    }

    func interruptReturnIsBlocked() -> Bool {
        blockedInterruptContinuation != nil
    }

    func resumeBlockedInterruptReturn() {
        let continuation = blockedInterruptContinuation
        blockedInterruptContinuation = nil
        continuation?.resume()
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        if shouldBlockNextSnapshot {
            shouldBlockNextSnapshot = false
            await withCheckedContinuation { continuation in
                blockedSnapshotContinuation = continuation
            }
        }
        if case .project = scope, shouldBlockNextProjectSnapshot {
            shouldBlockNextProjectSnapshot = false
            await withCheckedContinuation { continuation in
                blockedProjectSnapshotContinuation = continuation
            }
        }
        if case .branch = scope, remainingBranchSnapshotFailures > 0 {
            remainingBranchSnapshotFailures -= 1
            throw NovelError.repositoryFailure("Injected branch snapshot refresh failure.")
        }
        if case .project = scope, remainingProjectSnapshotFailures > 0 {
            remainingProjectSnapshotFailures -= 1
            throw NovelError.repositoryFailure("Injected project snapshot refresh failure.")
        }
        if remainingSnapshotFailures > 0 {
            remainingSnapshotFailures -= 1
            throw NovelError.repositoryFailure("Injected snapshot refresh failure.")
        }
        return try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        if shouldBlockNextPerform {
            shouldBlockNextPerform = false
            await withCheckedContinuation { continuation in
                blockedPerformContinuation = continuation
            }
        }
        return try await base.perform(action)
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        try await base.start(request)
    }

    func interruptRun(_ command: NovelCancelRunCommand) async throws {
        try await base.interruptRun(command)
        if shouldBlockInterruptReturn {
            shouldBlockInterruptReturn = false
            await withCheckedContinuation { continuation in
                blockedInterruptContinuation = continuation
            }
        }
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        await base.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: runID
        )
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }
}

private actor NovelSessionAttachBlockingCreation: NovelCreation {
    private let base: any NovelCreation
    private var shouldBlockNextStart = false
    private var blockedStartContinuation: CheckedContinuation<Void, Never>?

    init(base: any NovelCreation) {
        self.base = base
    }

    func blockNextStart() {
        shouldBlockNextStart = true
    }

    func startIsBlocked() -> Bool {
        blockedStartContinuation != nil
    }

    func resumeBlockedStart() {
        let continuation = blockedStartContinuation
        blockedStartContinuation = nil
        continuation?.resume()
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        try await base.perform(action)
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        if shouldBlockNextStart {
            shouldBlockNextStart = false
            await withCheckedContinuation { continuation in
                blockedStartContinuation = continuation
            }
        }
        return try await base.start(request)
    }

    func interruptRun(_ command: NovelCancelRunCommand) async throws {
        try await base.interruptRun(command)
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        await base.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: runID
        )
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }
}

private actor NovelSessionFailingRepository: NovelProjectPersisting {
    private let base = InMemoryNovelProjectRepository()
    private var remainingCommitFailures = 0
    private var shouldBlockNextCommit = false
    private var blockedCommitContinuation: CheckedContinuation<Void, Never>?

    func failNextCommits(_ count: Int) {
        remainingCommitFailures = count
    }

    func blockNextCommit() {
        shouldBlockNextCommit = true
    }

    func commitIsBlocked() -> Bool {
        blockedCommitContinuation != nil
    }

    func resumeBlockedCommit() {
        let continuation = blockedCommitContinuation
        blockedCommitContinuation = nil
        continuation?.resume()
    }

    func listProjects() async throws -> [NovelProjectSummary] {
        try await base.listProjects()
    }

    func loadProject(id: NovelProjectID) async throws -> NovelLoadedProject {
        try await base.loadProject(id: id)
    }

    func createProject(_ document: NovelProjectDocumentV1) async throws -> NovelLoadedProject {
        try await base.createProject(document)
    }

    func commitProject(
        _ document: NovelProjectDocumentV1,
        expectedRevision: Int64,
        authorization: NovelRepositoryCommitAuthorization?
    ) async throws -> NovelLoadedProject {
        if shouldBlockNextCommit {
            shouldBlockNextCommit = false
            await withCheckedContinuation { continuation in
                blockedCommitContinuation = continuation
            }
        }
        if remainingCommitFailures > 0 {
            remainingCommitFailures -= 1
            throw NovelError.repositoryFailure("Injected session terminal failure.")
        }
        return try await base.commitProject(
            document,
            expectedRevision: expectedRevision,
            authorization: authorization
        )
    }

    func restorePreviousProject(
        id: NovelProjectID,
        expectedDocumentSHA256: String
    ) async throws -> NovelLoadedProject {
        try await base.restorePreviousProject(
            id: id,
            expectedDocumentSHA256: expectedDocumentSHA256
        )
    }

    func listRecoverySidecars() async throws -> [NovelRecoverySidecarV1] {
        try await base.listRecoverySidecars()
    }

    func writeRecoverySidecar(_ sidecar: NovelRecoverySidecarV1) async throws {
        try await base.writeRecoverySidecar(sidecar)
    }

    func removeRecoverySidecar(projectID: NovelProjectID, runID: NovelRunID) async throws {
        try await base.removeRecoverySidecar(projectID: projectID, runID: runID)
    }
}
