import XCTest
@preconcurrency import Shared
@testable import iosApp

/// [Slice 4] Verifies the providers / ttsProviders / searchServices write-back
/// bridges. Mirrors the council-seat test discipline: each add must merge a
/// real entry into the snapshot AND survive a fresh store init (restart);
/// each delete must not resurrect after restart.
///
/// Acceptance covered:
///  - "新增模型→重启→还在" (addCustomModel)
///  - "删除 provider→重启→真没了" (removeCustomModel)
///  - TTS engine add/delete survives restart
///  - Search service add/delete survives restart
///
/// Uses isolated UserDefaults suites so tests don't touch the app's real
/// settings or bleed into each other.
@MainActor
final class IOSSharedSettingsStoreProvidersWriteBackTests: XCTestCase {

    // ---- Providers (custom model) ----

    func testAddCustomModelMergesProviderIntoSnapshot() {
        let store = makeIsolatedStore()
        let baseline = store.snapshot.providers.count

        store.addCustomModel(name: "测试模型", modelId: "test-model-1", providerName: "测试Provider")

        XCTAssertEqual(
            store.snapshot.providers.count,
            baseline + 1,
            "addCustomModel must add a real ProviderSetting to the snapshot"
        )
        let added = store.snapshot.providers.last
        XCTAssertEqual(added?.name, "测试Provider")
        // The model lives inside the provider's models list.
        let openAI = added as? ProviderSetting.OpenAI
        XCTAssertEqual(openAI?.models.count, 1)
        XCTAssertEqual(openAI?.models.first?.modelId, "test-model-1")
    }

    func testChatModelIdsExposeAddedCustomModelForSelection() {
        let store = makeIsolatedStore()

        store.addCustomModel(name: "可选模型", modelId: "selectable-chat-model", providerName: "可选Provider")

        XCTAssertTrue(
            store.chatModelIds.contains("selectable-chat-model"),
            "ModelDefaultsView must be able to offer user-added chat models for SettingsStore.modelId"
        )
    }

    /// Dual-source fix: resolveCurrentProviderSetting() must surface the provider
    /// configured via the formal Settings store (mirroring chat's resolution), so
    /// agent surfaces (SubAgent / Council / Board) no longer resolve via the
    /// legacy key-less ProviderRegistryStore and thus honor a provider the user
    /// configured in Settings.
    func testResolveCurrentProviderSettingReturnsConfiguredProvider() throws {
        let store = makeIsolatedStore()
        store.addCustomModel(name: "解析模型", modelId: "resolver-model", providerName: "ResolverProvider")
        let added = try XCTUnwrap(store.snapshot.providers.last as? ProviderSetting.OpenAI)
        let model = try XCTUnwrap(added.models.first)
        store.setCurrentChatModelId(model.id.description())

        let resolved = store.resolveCurrentProviderSetting()
        XCTAssertEqual(
            resolved?.name,
            "ResolverProvider",
            "resolver must return the provider configured in the shared settings store, not the legacy registry"
        )
        XCTAssertEqual(
            store.resolveCurrentModelId(),
            "resolver-model",
            "resolveCurrentModelId must return the selected model's wire id"
        )
    }

    func testAddedProviderSurvivesRestart() {
        let suiteName = "Slice4-Prov-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addCustomModel(name: "重启后应在", modelId: "persist-1", providerName: "持久Provider")
        let countBeforeRestart = store1.snapshot.providers.count

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertEqual(
            store2.snapshot.providers.count,
            countBeforeRestart,
            "Added provider must survive a fresh store init (app restart)"
        )
        XCTAssertTrue(
            store2.snapshot.providers.contains { $0.name == "持久Provider" },
            "The specific added provider must still be present after restart"
        )
    }

    func testRemoveCustomModelDoesNotResurrectAfterRestart() {
        let suiteName = "Slice4-ProvDel-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addCustomModel(name: "待删除模型", modelId: "del-1", providerName: "删除Provider")
        // The just-added provider is the last one in the mirror.
        let mirrorCount = store1.savedCustomModels.count
        XCTAssertGreaterThan(mirrorCount, 0)

        store1.removeCustomModel(at: mirrorCount - 1)
        XCTAssertFalse(
            store1.snapshot.providers.contains { $0.name == "删除Provider" },
            "removed provider must be gone from the snapshot immediately"
        )

        // Restart: must not come back.
        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(
            store2.snapshot.providers.contains { $0.name == "删除Provider" },
            "Deleted provider must not resurrect after restart"
        )
    }

    func testRemoveProviderDeletesCredentialAndRetargetsCurrentChatModel() throws {
        let suiteName = "ProviderDelete-\(UUID().uuidString)"
        let store = makeIsolatedStore(suiteName: suiteName)
        let provider = IosSettingsMutations.shared.buildOpenAIProvider(
            name: "待删除服务商",
            apiKey: "sk-delete-me",
            baseUrl: "https://example.com/v1",
            modelName: "待删除模型",
            modelId: "delete-model"
        )
        let added = store.addProvider(provider)
        let providerId = added.id.description()
        let model = try XCTUnwrap(added.models.first)
        store.setCurrentChatModelId(model.id.description())
        store.setCurrentAssistantChatModelId(model.id.description())
        XCTAssertEqual(store.snapshot.getCurrentChatModel()?.id, model.id)

        XCTAssertTrue(store.removeProvider(providerId: providerId))
        XCTAssertFalse(store.snapshot.providers.contains { $0.id.description() == providerId })
        XCTAssertNotEqual(store.snapshot.getCurrentChatModel()?.id, model.id)
        XCTAssertNil(
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.providerApiKey(providerId: providerId))
        )

        let restarted = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(restarted.snapshot.providers.contains { $0.id.description() == providerId })
    }

    func testRemoveProviderRefusesBuiltInProvider() throws {
        let store = makeIsolatedStore()
        let builtIn = try XCTUnwrap(store.snapshot.providers.first)
        let providerId = builtIn.id.description()

        XCTAssertFalse(store.canRemoveProvider(providerId: providerId))
        XCTAssertFalse(store.removeProvider(providerId: providerId))
        XCTAssertTrue(store.snapshot.providers.contains { $0.id.description() == providerId })
    }

    // ---- TTS engines ----

    func testAddOpenAITtsEngineMergesIntoSnapshot() {
        let store = makeIsolatedStore()
        let baseline = store.snapshot.ttsProviders.count

        store.addTtsEngine(name: "测试TTS", engineType: "openai", apiKey: "sk-test", model: "gpt-4o-mini-tts")

        XCTAssertEqual(
            store.snapshot.ttsProviders.count,
            baseline + 1,
            "addTtsEngine(openai) must add a real TTSProviderSetting to the snapshot"
        )
        let added = store.snapshot.ttsProviders.last as? TTSProviderSetting.OpenAI
        XCTAssertEqual(added?.name, "测试TTS")
        XCTAssertEqual(added?.apiKey, "sk-test")
    }

    func testAddedTtsEngineSurvivesRestart() {
        let suiteName = "Slice4-TTS-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addTtsEngine(name: "重启TTS", engineType: "openai", apiKey: "sk-x", model: "gpt-4o-mini-tts")
        let countBeforeRestart = store1.snapshot.ttsProviders.count

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertEqual(
            store2.snapshot.ttsProviders.count,
            countBeforeRestart,
            "Added TTS provider must survive restart"
        )
    }

    func testRemoveTtsEngineDoesNotResurrectAfterRestart() {
        let suiteName = "Slice4-TTSDel-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addTtsEngine(name: "待删TTS", engineType: "openai", apiKey: "sk-x", model: "gpt-4o-mini-tts")
        let mirrorCount = store1.savedTtsEngines.count
        XCTAssertGreaterThan(mirrorCount, 0)

        store1.removeTtsEngine(at: mirrorCount - 1)
        XCTAssertFalse(
            store1.snapshot.ttsProviders.contains { $0.name == "待删TTS" },
            "removed TTS provider must be gone from the snapshot immediately"
        )

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(
            store2.snapshot.ttsProviders.contains { $0.name == "待删TTS" },
            "Deleted TTS provider must not resurrect after restart"
        )
    }

    // ---- Search services ----

    func testAddSearchProviderMergesIntoSnapshot() {
        let store = makeIsolatedStore()
        let baseline = store.snapshot.searchServices.count

        store.addSearchProvider(name: "测试搜索", apiKey: "tvly-test", serviceType: "tavily")

        XCTAssertEqual(
            store.snapshot.searchServices.count,
            baseline + 1,
            "addSearchProvider must add a real SearchServiceOptions to the snapshot"
        )
        let added = store.snapshot.searchServices.last as? SearchServiceOptions.TavilyOptions
        XCTAssertNotNil(added, "serviceType tavily must build a TavilyOptions")
        XCTAssertEqual(added?.apiKey, "tvly-test")
        let addedId = added?.id.description()
        XCTAssertNotNil(addedId)
        XCTAssertEqual(
            Int(store.snapshot.searchServiceSelected),
            store.snapshot.searchServices.count - 1,
            "newly added search provider should become the selected default provider"
        )
        XCTAssertTrue(
            addedId.map { id in store.snapshot.searchEnabledServiceIds.contains { $0.description() == id } } ?? false,
            "newly added search provider should be enabled for execution"
        )
    }

    func testEnableWebSearchToggleSurvivesRestart() {
        let suiteName = "Slice4-SrchGate-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)

        store1.setEnableWebSearch(false)
        XCTAssertFalse(store1.snapshot.enableWebSearch)

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(store2.snapshot.enableWebSearch, "web search gate must persist after restart")

        store2.setEnableWebSearch(true)
        XCTAssertTrue(store2.snapshot.enableWebSearch)
    }

    func testSearchProviderEnabledToggleSurvivesRestart() {
        let suiteName = "Slice4-SrchEnabled-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addSearchProvider(name: "Bing", serviceType: "bing_local")
        let serviceId = store1.snapshot.searchServices.last!.id.description()

        store1.setSearchProviderEnabled(serviceId: serviceId, enabled: false)
        XCTAssertFalse(
            store1.snapshot.searchEnabledServiceIds.contains { $0.description() == serviceId },
            "search provider enabled toggle must update the live snapshot immediately"
        )

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(
            store2.snapshot.searchEnabledServiceIds.contains { $0.description() == serviceId },
            "disabled search provider must stay disabled after restart"
        )

        store2.setSearchProviderEnabled(serviceId: serviceId, enabled: true)
        XCTAssertTrue(
            store2.snapshot.searchEnabledServiceIds.contains { $0.description() == serviceId },
            "provider can be re-enabled through the same settings bridge"
        )
    }

    func testAddedSearchProviderSurvivesRestart() {
        let suiteName = "Slice4-Srch-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addSearchProvider(name: "重启搜索", apiKey: "tvly-y", serviceType: "exa")
        let countBeforeRestart = store1.snapshot.searchServices.count

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertEqual(
            store2.snapshot.searchServices.count,
            countBeforeRestart,
            "Added search service must survive restart"
        )
    }

    func testAddAmberAgentSearchProviderBuildsMatchingSubtype() {
        let store = makeIsolatedStore()

        store.addSearchProvider(name: "AmberAgent", apiKey: "amber-search", serviceType: "amber_agent")

        let added = store.snapshot.searchServices.last as? SearchServiceOptions.AmberAgentSearchOptions
        XCTAssertNotNil(added, "serviceType amber_agent must build AmberAgentSearchOptions, not fallback to Tavily")
        XCTAssertEqual(added?.apiKey, "amber-search")
    }

    func testSearchProviderBuilderCoversMenuOnlyTypes() {
        let store = makeIsolatedStore()

        store.addSearchProvider(name: "SearXNG", serviceType: "searxng")
        XCTAssertTrue(
            store.snapshot.searchServices.last is SearchServiceOptions.SearXNGOptions,
            "serviceType searxng must build SearXNGOptions, not fallback to Tavily"
        )

        store.addSearchProvider(name: "Ollama", apiKey: "ollama-key", serviceType: "ollama")
        let ollama = store.snapshot.searchServices.last as? SearchServiceOptions.OllamaOptions
        XCTAssertNotNil(ollama, "serviceType ollama must build OllamaOptions, not fallback to Tavily")
        XCTAssertEqual(ollama?.apiKey, "ollama-key")
    }

    func testRemoveSearchProviderDoesNotResurrectAfterRestart() {
        let suiteName = "Slice4-SrchDel-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addSearchProvider(name: "待删搜索", apiKey: "tvly-z", serviceType: "tavily")
        let mirrorCount = store1.savedSearchProviders.count
        XCTAssertGreaterThan(mirrorCount, 0)

        store1.removeSearchProvider(at: mirrorCount - 1)
        let beforeRestartCount = store1.snapshot.searchServices.count

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertEqual(
            store2.snapshot.searchServices.count,
            beforeRestartCount,
            "Deleted search service must not resurrect after restart"
        )
    }

    func testTodayBoardOptionsPersistThroughKMPMutationAndRestart() {
        let suiteName = "Slice4-TodayBoard-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)

        store1.updateTodayBoard { _ in
            TodayBoardSettingPatch(
                boardModelId: "board-model-uuid",
                hotListRefreshIntervalMinutes: 120,
                hotListWifiOnly: true,
                hotListEnabledSources: ["github_trending_ai", "hacker_news"],
                hotListFocusKeywords: ["OpenAI", "Agent", "OpenAI"],
                hotListFilterModeWireName: "focus_only",
                boardReadingFontModeWireName: "system",
                clearBoardReadingFontPackId: true,
                deepReadFontScale: 1.2,
                deepReadTemplateId: "editorial_slant"
            )
        }

        let board1 = store1.todayBoard
        XCTAssertEqual(board1.boardModelId, "board-model-uuid")
        XCTAssertEqual(Int(board1.hotListRefreshIntervalMinutes), 120)
        XCTAssertTrue(board1.hotListWifiOnly)
        XCTAssertEqual(Set(board1.hotListEnabledSources.map { String(describing: $0) }), ["github_trending_ai", "hacker_news"])
        XCTAssertEqual(board1.hotListFocusKeywords, ["OpenAI", "Agent"])
        XCTAssertEqual(board1.hotListFilterMode.wireName, "focus_only")
        XCTAssertEqual(board1.boardReadingFontMode.wireName, "system")
        XCTAssertEqual(board1.deepReadFontScale, 1.2, accuracy: 0.001)
        XCTAssertEqual(board1.deepReadTemplateId, "editorial_slant")

        let store2 = makeIsolatedStore(suiteName: suiteName)
        let board2 = store2.todayBoard
        XCTAssertEqual(board2.boardModelId, "board-model-uuid")
        XCTAssertEqual(Set(board2.hotListEnabledSources.map { String(describing: $0) }), ["github_trending_ai", "hacker_news"])
        XCTAssertEqual(board2.hotListFilterMode.wireName, "focus_only")
        XCTAssertEqual(board2.deepReadTemplateId, "editorial_slant")

        store2.updateTodayBoard { _ in
            TodayBoardSettingPatch(clearBoardModelId: true, boardReadingFontModeWireName: "serif", deepReadFontScale: 3.0)
        }
        XCTAssertNil(store2.todayBoard.boardModelId)
        XCTAssertEqual(store2.todayBoard.boardReadingFontMode.wireName, "serif")
        XCTAssertEqual(store2.todayBoard.deepReadFontScale, 1.25, accuracy: 0.001)
    }

    // ---- hot-list title translation ----

    func testHotListTranslateToChineseFlagPersistsThroughKMPMutationAndRestart() {
        let suiteName = "Slice5-Translate-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertFalse(store1.todayBoard.hotListTranslateToChinese, "default should be off (opt-in)")

        store1.updateTodayBoard { _ in TodayBoardSettingPatch(hotListTranslateToChinese: true) }
        XCTAssertTrue(store1.todayBoard.hotListTranslateToChinese)
        // Editing an unrelated field must not reset the flag.
        store1.updateTodayBoard { _ in TodayBoardSettingPatch(hotListWifiOnly: true) }
        XCTAssertTrue(store1.todayBoard.hotListTranslateToChinese, "unrelated patch must preserve the flag")

        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertTrue(store2.todayBoard.hotListTranslateToChinese, "flag must survive restart")
    }

    func testNeedsTranslationSkipsTitlesWithCJK() {
        XCTAssertTrue(IOSHotListTitleTranslator.needsTranslation("Apple unveils new chip"))
        XCTAssertFalse(IOSHotListTitleTranslator.needsTranslation("苹果发布新芯片"))
        // Mixed title already reads in Chinese → leave as-is.
        XCTAssertFalse(IOSHotListTitleTranslator.needsTranslation("OpenAI 发布 GPT-5"))
        // Too short / empty → nothing to translate.
        XCTAssertFalse(IOSHotListTitleTranslator.needsTranslation("A"))
        XCTAssertFalse(IOSHotListTitleTranslator.needsTranslation("   "))
    }

    func testTranslatorParseMapsIndicesBackToTitles() {
        let pending = ["Apple unveils new chip", "Rust 2.0 released", "Kubernetes turns ten"]
        let json = """
        Here you go:
        ```json
        {"items":[{"i":1,"zh":"苹果发布新芯片"},{"i":3,"zh":"Kubernetes 迎来十周年"}]}
        ```
        """
        let result = IOSHotListTitleTranslator.parse(json, pending: pending)
        XCTAssertEqual(result["Apple unveils new chip"], "苹果发布新芯片")
        XCTAssertEqual(result["Kubernetes turns ten"], "Kubernetes 迎来十周年")
        XCTAssertNil(result["Rust 2.0 released"], "model omitted index 2 → caller keeps original")
    }

    func testTranslatorParseRejectsOutOfRangeAndEmpty() {
        let pending = ["Only one"]
        let json = #"{"items":[{"i":5,"zh":"越界"},{"i":1,"zh":"  "},{"i":1,"zh":"唯一"}]}"#
        let result = IOSHotListTitleTranslator.parse(json, pending: pending)
        XCTAssertEqual(result, ["Only one": "唯一"])
    }

    // ---- helpers ----

    private func makeIsolatedStore(suiteName: String = "Slice4-Providers-\(UUID().uuidString)") -> IOSSharedSettingsStore {
        IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: suiteName)!)
    }
}
