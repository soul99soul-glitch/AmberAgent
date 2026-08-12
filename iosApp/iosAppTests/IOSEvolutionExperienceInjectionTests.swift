import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 3 Wave 2: Experience 检索注入 prompt + 建议审批状态机
/// （§11.3 / §15 Phase 3 / §18.3）。
///
/// 覆盖交付物 1/2/4：
/// 1. 注入在真实组装点（ChatRuntimeContextBuilder.injectingRuntimeContext）
///    生效：有经验时 prompt 含经验内容；无经验/检索失败（typed .failed）
///    时静默降级为不注入、不阻塞。
/// 2. 每轮刷新：第一轮后新增经验，第二轮组装必须包含（防已知陷阱——
///    条件注入缓存不得跨轮复用；同一 builder 实例连续组装两次验证）。
/// 3. 预算合并：技能目录与经验注入共享同一字节池——技能占满时经验被裁；
///    100 条经验时注入字节有界（topK + 预算双上限）。
/// 4. 冲突双规则不同时注入（被抑制一方以标记暴露）。
/// 5. UI 批准建议后状态机真实落库（approve → 降级 rejected / 删除 +
///    tombstone）；拒绝 → 建议消失、条目保持 active（dismissal 持久化）。
@MainActor
final class IOSEvolutionExperienceInjectionTests: XCTestCase {
    private var tempRoots: [URL] = []

    override func tearDown() {
        for root in tempRoots {
            try? FileManager.default.removeItem(at: root)
        }
        tempRoots.removeAll()
    }

    // MARK: - 交付物 1：真实组装点生效 / 静默降级

    func testExperienceIsInjectedAtTheRealAssemblyPoint() throws {
        let root = try makeTempRoot()
        let curator = IOSEvolutionExperienceCurator(store: IOSEvolutionExperienceStore(baseDirectory: root))
        guard case .added(let experience, _) = curator.add(
            applicability: "写文件决策",
            counterexamples: ["临时笔记不需要"],
            evidenceRefs: [],
            ruleText: "写文件前总是先搜索"
        ) else {
            return XCTFail("add must succeed")
        }

        let builder = makeBuilder(settings: isolatedSettings(), skillRoot: try makeTempRoot(), curator: curator)
        let prepared = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
            coalesceSystemMessages: true
        )
        let systemText = systemText(of: prepared)

        XCTAssertTrue(systemText.contains("<experiences>"))
        XCTAssertTrue(systemText.contains("写文件前总是先搜索"), "规则正文必须进入 prompt")
        XCTAssertTrue(systemText.contains("适用条件：写文件决策"))
        XCTAssertTrue(systemText.contains("反例：临时笔记不需要"))
        XCTAssertTrue(systemText.contains("帮助 0 / 有害 0"))
        XCTAssertFalse(systemText.contains(experience.id), "经验 id 是内部身份，不注入提示文本")
    }

    func testNoExperienceAndRetrievalFailureDegradeSilentlyWithoutBlocking() throws {
        // 空池：无经验 → 不注入，也不破坏其它注入。
        let emptyCurator = IOSEvolutionExperienceCurator(
            store: IOSEvolutionExperienceStore(baseDirectory: try makeTempRoot())
        )
        let emptyBuilder = makeBuilder(settings: isolatedSettings(), skillRoot: try makeTempRoot(), curator: emptyCurator)
        let emptyPrepared = emptyBuilder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
            coalesceSystemMessages: true
        )
        XCTAssertFalse(systemText(of: emptyPrepared).contains("<experiences>"))

        // 损坏文档 → retrieve 返回 typed .failed → 静默降级：不注入、不抛错，
        // 且技能目录等其它注入继续工作（不阻塞聊天）。
        let corruptRoot = try makeTempRoot()
        try FileManager.default.createDirectory(
            at: corruptRoot.appendingPathComponent("evolution", isDirectory: true),
            withIntermediateDirectories: true
        )
        try Data("not-a-document".utf8).write(
            to: corruptRoot.appendingPathComponent("evolution/experiences.json")
        )
        let failingCurator = IOSEvolutionExperienceCurator(
            store: IOSEvolutionExperienceStore(baseDirectory: corruptRoot)
        )
        let failingBuilder = makeBuilder(settings: isolatedSettings(), skillRoot: try makeTempRoot(), curator: failingCurator)
        let failingPrepared = failingBuilder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
            coalesceSystemMessages: true
        )
        let failingText = systemText(of: failingPrepared)
        XCTAssertFalse(failingText.contains("<experiences>"), "检索失败必须静默不注入")
        let allText = failingPrepared.map { $0.toText() }.joined(separator: "\n")
        XCTAssertTrue(allText.contains("写文件之前要不要先搜索一下"), "用户消息必须原样保留")

        // 技能目录在同一失败路径下仍注入（对照：不阻塞）。
        let skillRoot = try makeTempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: skillRoot)
        try skillStore.createSkill(name: "summarize", description: "Summarize any text concisely.", allowedTools: [])
        let skillSettings = isolatedSettings()
        skillSettings.setSkillEnabled(name: "summarize", enabled: true)
        let withSkill = makeBuilder(settings: skillSettings, skillRoot: skillRoot, curator: failingCurator)
            .injectingRuntimeContext(
                into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
                coalesceSystemMessages: true
            )
        let withSkillText = systemText(of: withSkill)
        XCTAssertTrue(withSkillText.contains("<available_skills>"))
        XCTAssertTrue(withSkillText.contains("<name>summarize</name>"))
        XCTAssertFalse(withSkillText.contains("<experiences>"))
    }

    // MARK: - 交付物 2：每轮刷新，检索结果不得跨轮缓存

    func testRetrievalRunsFreshPerRoundNoCrossRoundCache() throws {
        let root = try makeTempRoot()
        let store = IOSEvolutionExperienceStore(baseDirectory: root)
        let curator = IOSEvolutionExperienceCurator(store: store)
        let builder = makeBuilder(settings: isolatedSettings(), skillRoot: try makeTempRoot(), curator: curator)
        let userMessage = UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")

        // 第一轮：池为空 → 不注入。
        let roundOne = builder.injectingRuntimeContext(into: [userMessage], coalesceSystemMessages: true)
        XCTAssertFalse(systemText(of: roundOne).contains("<experiences>"))

        // 两轮之间新增经验（真实 store 落盘）。
        guard case .added = curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前总是先搜索"
        ) else {
            return XCTFail("add must succeed")
        }

        // 第二轮：同一 builder 实例、同一用户消息 → 必须包含新经验
        // （防回归：条件注入缓存若跨轮复用，这里会漏掉新增经验）。
        let roundTwo = builder.injectingRuntimeContext(into: [userMessage], coalesceSystemMessages: true)
        XCTAssertTrue(systemText(of: roundTwo).contains("<experiences>"))
        XCTAssertTrue(systemText(of: roundTwo).contains("写文件前总是先搜索"))
    }

    // MARK: - 交付物 3：与技能注入共享同一字节预算（合并核算）

    func testSkillFillingSharedBudgetCutsExperienceInjection() throws {
        // 一个巨型技能描述占满共享池 → 经验被裁；池足够大时两者共存。
        let skillRoot = try makeTempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: skillRoot)
        try skillStore.createSkill(
            name: "bulk",
            description: String(repeating: "A", count: 2_500),
            allowedTools: []
        )
        let settings = isolatedSettings()
        settings.setSkillEnabled(name: "bulk", enabled: true)

        let root = try makeTempRoot()
        let store = IOSEvolutionExperienceStore(baseDirectory: root)
        let curator = IOSEvolutionExperienceCurator(store: store)
        guard case .added(let experience, _) = curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前总是先搜索"
        ) else {
            return XCTFail("add must succeed")
        }

        // 先测出真实技能片段字节（无经验注入的同一 builder；技能片段是
        // 合并后的 system 文本尾部）。
        let measurementBuilder = makeBuilder(settings: settings, skillRoot: skillRoot, curator: nil)
        let measured = measurementBuilder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
            coalesceSystemMessages: true
        )
        let measuredText = systemText(of: measured)
        let skillFragmentStart = try XCTUnwrap(measuredText.range(of: "Enabled skills ("))
        let skillBytes = measuredText[skillFragmentStart.lowerBound...].utf8.count

        let experienceBytes = IOSExperienceByteAccounting.encodedByteCount(
            experience: experience,
            suppressedConflictingExperienceIds: []
        )
        XCTAssertGreaterThan(experienceBytes, 200, "fixture 经验编码必须显著大于零")

        let builder = makeBuilder(settings: settings, skillRoot: skillRoot, curator: curator)

        // 池 = 技能片段 + 经验编码 − 余量 → 技能占满，经验被裁。
        let tightBudget = skillBytes + experienceBytes - 100
        let tight = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
            coalesceSystemMessages: true,
            sharedSkillExperienceByteBudget: tightBudget
        )
        let tightText = systemText(of: tight)
        XCTAssertTrue(tightText.contains("<available_skills>"), "技能目录必须仍在预算内")
        XCTAssertFalse(tightText.contains("<experiences>"), "技能占满共享池时经验必须被裁")

        // 池足够大 → 技能与经验共存。
        let roomyBudget = skillBytes + experienceBytes + 5_000
        let roomy = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
            coalesceSystemMessages: true,
            sharedSkillExperienceByteBudget: roomyBudget
        )
        let roomyText = systemText(of: roomy)
        XCTAssertTrue(roomyText.contains("<available_skills>"))
        XCTAssertTrue(roomyText.contains("<experiences>"))
        XCTAssertTrue(roomyText.contains("写文件前总是先搜索"))
    }

    func testExperienceInjectionBytesStayBoundedWith100Entries() throws {
        let root = try makeTempRoot()
        let store = IOSEvolutionExperienceStore(baseDirectory: root)
        for index in 0..<100 {
            guard case .added = store.add(IOSEvolutionExperience(
                id: "exp-inject-\(index)",
                applicability: "RSS 简报整理任务",
                counterexamples: [],
                evidenceRefs: [],
                helpfulCount: 0,
                harmfulCount: 0,
                status: .active,
                supersededByExperienceId: nil,
                conflicts: [],
                ruleText: "总是先搜索再写文件，编号 \(index)",
                createdAtEpochMs: 1_000,
                updatedAtEpochMs: 1_000
            )) else {
                return XCTFail("bulk add \(index) failed")
            }
        }
        let curator = IOSEvolutionExperienceCurator(store: store)
        let builder = makeBuilder(settings: isolatedSettings(), skillRoot: try makeTempRoot(), curator: curator)

        let prepared = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "总是先搜索再写文件")],
            coalesceSystemMessages: true
        )
        let text = systemText(of: prepared)
        let start = try XCTUnwrap(text.range(of: "<experiences>")).lowerBound
        let injectedBlock = text[start...]
        let itemCount = injectedBlock.components(separatedBy: "- 适用条件：").count - 1

        XCTAssertGreaterThan(itemCount, 0)
        XCTAssertLessThanOrEqual(itemCount, ChatRuntimeContextBuilder.experienceInjectionTopK,
                                 "topK 必须封顶（100 条经验不得线性撑爆）")
        XCTAssertLessThanOrEqual(
            injectedBlock.utf8.count,
            ChatRuntimeContextBuilder.sharedSkillExperienceByteBudget,
            "注入片段（含脚手架）必须受共享字节池约束"
        )
    }

    // MARK: - 交付物 4：冲突双规则不同时注入

    func testConflictingExperiencesNeverBothInjectedAndCarryMarker() throws {
        let root = try makeTempRoot()
        let curator = IOSEvolutionExperienceCurator(store: IOSEvolutionExperienceStore(baseDirectory: root))
        guard case .added(let always, _) = curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前总是先搜索"
        ) else {
            return XCTFail("add must succeed")
        }
        guard case .added(let never, _) = curator.add(
            applicability: "写文件决策",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "写文件前从不搜索"
        ) else {
            return XCTFail("add must succeed")
        }

        let builder = makeBuilder(settings: isolatedSettings(), skillRoot: try makeTempRoot(), curator: curator)
        let prepared = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "写文件之前要不要先搜索一下")],
            coalesceSystemMessages: true
        )
        let text = systemText(of: prepared)

        let containsAlways = text.contains(always.ruleText)
        let containsNever = text.contains(never.ruleText)
        XCTAssertFalse(containsAlways && containsNever, "冲突双方不得同时无提示注入")

        if containsAlways {
            XCTAssertTrue(text.contains("已抑制冲突规则：\(never.id)"),
                          "胜者必须携带被抑制方的 id 标记")
        } else if containsNever {
            XCTAssertTrue(text.contains("已抑制冲突规则：\(always.id)"))
        } else {
            XCTFail("至少一方冲突经验必须被注入")
        }
    }

    // MARK: - 交付物 5：建议批准/拒绝状态机（真实落库）

    func testApproveSupersedeSuggestionRetiresEntryAndDeleteRemovesIt() throws {
        let root = try makeTempRoot()
        let curator = IOSEvolutionExperienceCurator(store: IOSEvolutionExperienceStore(baseDirectory: root))
        let model = IOSExperienceSettingsModel(
            curator: curator,
            dismissalStore: IOSExperienceSuggestionDismissalStore(baseDirectory: root)
        )

        guard case .added(let entry, _) = curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        ) else {
            return XCTFail("add must succeed")
        }
        for _ in 0..<3 {
            guard case .recorded = curator.recordHarmful(experienceId: entry.id) else {
                return XCTFail("recordHarmful must record")
            }
        }
        model.reload()
        let supersedeSuggestion = try XCTUnwrap(
            model.suggestions.first { $0.experienceId == entry.id && $0.kind == .supersede },
            "harmful==3 必须投影出 supersede 建议"
        )

        // 批准前：条目保持 active（验收 3：建议仍需批准才生效）。
        XCTAssertEqual(try curator.store.experience(id: entry.id)?.status, .active)

        model.approve(supersedeSuggestion)
        XCTAssertEqual(try curator.store.experience(id: entry.id)?.status, .rejected,
                       "批准 supersede 建议必须真实落库（降级为 rejected，永不被注入）")
        XCTAssertNil(model.lastError)
        XCTAssertFalse(model.suggestions.contains { $0.experienceId == entry.id })
        XCTAssertFalse(model.activeExperiences.contains { $0.id == entry.id })
        XCTAssertTrue(model.archivedExperiences.contains { $0.id == entry.id })

        // delete 建议：harmful==5 → 批准 → 物理移除 + tombstone。
        guard case .added(let victim, _) = curator.add(
            applicability: "翻译任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "先确认目标语言再翻译"
        ) else {
            return XCTFail("add must succeed")
        }
        for _ in 0..<5 {
            guard case .recorded = curator.recordHarmful(experienceId: victim.id) else {
                return XCTFail("recordHarmful must record")
            }
        }
        model.reload()
        let deleteSuggestion = try XCTUnwrap(
            model.suggestions.first { $0.experienceId == victim.id && $0.kind == .delete },
            "harmful==5 必须投影出 delete 建议"
        )
        model.approve(deleteSuggestion)
        XCTAssertNil(try curator.store.experience(id: victim.id), "批准 delete 建议必须物理移除")
        XCTAssertTrue(try curator.store.tombstones().contains { $0.id == victim.id }, "删除必须留 tombstone 防复读")
        XCTAssertFalse(model.activeExperiences.contains { $0.id == victim.id })
        XCTAssertNil(model.lastError)
    }

    func testRejectSuggestionDismissesItWhileEntryStaysActiveAndPersists() throws {
        let root = try makeTempRoot()
        let curator = IOSEvolutionExperienceCurator(store: IOSEvolutionExperienceStore(baseDirectory: root))
        let model = IOSExperienceSettingsModel(
            curator: curator,
            dismissalStore: IOSExperienceSuggestionDismissalStore(baseDirectory: root)
        )

        guard case .added(let entry, _) = curator.add(
            applicability: "RSS 简报整理任务",
            counterexamples: [],
            evidenceRefs: [],
            ruleText: "总是先搜索再写文件"
        ) else {
            return XCTFail("add must succeed")
        }
        for _ in 0..<3 {
            guard case .recorded = curator.recordHarmful(experienceId: entry.id) else {
                return XCTFail("recordHarmful must record")
            }
        }
        model.reload()
        let suggestion = try XCTUnwrap(
            model.suggestions.first { $0.experienceId == entry.id },
            "harmful==3 必须投影出建议"
        )

        model.reject(suggestion)
        XCTAssertFalse(model.suggestions.contains { $0.experienceId == entry.id }, "拒绝后建议消失")
        XCTAssertEqual(try curator.store.experience(id: entry.id)?.status, .active, "拒绝后条目保持 active")

        // dismissal 持久化：新模型实例（同一 base directory）重载后建议仍不出现。
        let freshModel = IOSExperienceSettingsModel(
            curator: curator,
            dismissalStore: IOSExperienceSuggestionDismissalStore(baseDirectory: root)
        )
        freshModel.reload()
        XCTAssertFalse(freshModel.suggestions.contains { $0.experienceId == entry.id },
                       "拒绝标记必须持久化，重进页面不得复现")
        XCTAssertTrue(freshModel.activeExperiences.contains { $0.id == entry.id })
    }

    func testCurrentSuggestionsProjectionFollowsThresholds() throws {
        let root = try makeTempRoot()
        let curator = IOSEvolutionExperienceCurator(
            store: IOSEvolutionExperienceStore(baseDirectory: root)
        )
        guard case .added(let low, _) = curator.add(
            applicability: "场景 A", counterexamples: [], evidenceRefs: [], ruleText: "规则 A 总是先搜索"
        ) else { return XCTFail("add must succeed") }
        guard case .added(let mid, _) = curator.add(
            applicability: "场景 B", counterexamples: [], evidenceRefs: [], ruleText: "规则 B 先确认语言"
        ) else { return XCTFail("add must succeed") }
        guard case .added(let high, _) = curator.add(
            applicability: "场景 C", counterexamples: [], evidenceRefs: [], ruleText: "规则 C 从不直接写文件"
        ) else { return XCTFail("add must succeed") }

        for _ in 0..<2 { _ = curator.recordHarmful(experienceId: low.id) }
        for _ in 0..<3 { _ = curator.recordHarmful(experienceId: mid.id) }
        for _ in 0..<5 { _ = curator.recordHarmful(experienceId: high.id) }

        let suggestions = curator.currentSuggestions(now: 1_234)
        XCTAssertFalse(suggestions.contains { $0.experienceId == low.id }, "harmful==2 无建议")
        XCTAssertTrue(suggestions.contains { $0.experienceId == mid.id && $0.kind == .supersede })
        XCTAssertTrue(suggestions.contains { $0.experienceId == high.id && $0.kind == .delete })
        XCTAssertFalse(suggestions.contains { $0.experienceId == high.id && $0.kind == .supersede },
                       "harmful==5 只投影 delete，不叠加 supersede")
    }

    // MARK: - Fixtures

    private func makeTempRoot() throws -> URL {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSEvolutionExperienceInjectionTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        tempRoots.append(root)
        return root
    }

    private func isolatedSettings() -> IOSSharedSettingsStore {
        let suite = "IOSEvolutionExperienceInjectionTests-\(UUID().uuidString)"
        return IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: suite)!)
    }

    private func makeBuilder(
        settings: IOSSharedSettingsStore,
        skillRoot: URL,
        curator: IOSEvolutionExperienceCurator?
    ) -> ChatRuntimeContextBuilder {
        var builder = ChatRuntimeContextBuilder(
            sharedSettings: settings,
            mcpTools: [],
            miniAppRepository: IOSMiniAppRepository(baseDirectory: try! makeTempRoot()),
            miniAppRuntimeEnabled: false
        )
        builder.skillFileStore = IOSSkillFileStore(baseDirectory: skillRoot)
        builder.experienceCurator = curator
        return builder
    }

    private func systemText(of messages: [UIMessage]) -> String {
        messages
            .filter { $0.role == MessageRole.system }
            .map { $0.toText() }
            .joined(separator: "\n")
    }
}
