import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSThemePackToolTests: XCTestCase {
    private var runtime: AmberThemeRuntime { .shared }
    private var savedPaper: AmberThemeRuntime.Paper!
    private var savedAccent: UInt32 = 0
    private var savedInk: UInt32 = 0
    private var savedCanvas: AmberCanvasStyle = .flat
    private var savedBrand: AmberBrandMarkStyle = .systemWordmark
    private var savedShortcut: AmberShortcutIconStyle = .phosphorFill
    private var savedChrome: AmberChromeTypeface = .system
    private var savedCanvasScope: AmberCanvasScope = .homeOnly
    private var savedBubble: AmberBubbleChrome = .standard
    private var savedGlass: AmberGlassChrome = .standard
    private var savedEmpty: AmberEmptyArtStyle = .none
    private var savedSettingsChrome = false
    private var savedLaunch: AmberLaunchBrandStyle = .none
    private var savedAsset: AmberThemeAssetMode = .builtinOnly
    private var savedImmersive: AmberImmersivePolicy = .hidden
    private var libraryRoot: URL!

    override func setUp() {
        super.setUp()
        savedPaper = runtime.paper
        savedAccent = runtime.accentHex
        savedInk = runtime.accentInkHex
        savedCanvas = runtime.canvasStyle
        savedBrand = runtime.brandMarkStyle
        savedShortcut = runtime.shortcutIconStyle
        savedChrome = runtime.chromeTypeface
        savedCanvasScope = runtime.canvasScope
        savedBubble = runtime.bubbleChrome
        savedGlass = runtime.glassChrome
        savedEmpty = runtime.emptyArt
        savedSettingsChrome = runtime.settingsChrome
        savedLaunch = runtime.launchBrand
        savedAsset = runtime.assetMode
        savedImmersive = runtime.immersivePolicy
        libraryRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("theme-pack-tool-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        runtime.discardTryOn()
        runtime.paper = savedPaper
        runtime.accentHex = savedAccent
        runtime.accentInkHex = savedInk
        runtime.canvasStyle = savedCanvas
        runtime.brandMarkStyle = savedBrand
        runtime.shortcutIconStyle = savedShortcut
        runtime.chromeTypeface = savedChrome
        runtime.canvasScope = savedCanvasScope
        runtime.bubbleChrome = savedBubble
        runtime.glassChrome = savedGlass
        runtime.emptyArt = savedEmpty
        runtime.settingsChrome = savedSettingsChrome
        runtime.launchBrand = savedLaunch
        runtime.assetMode = savedAsset
        runtime.immersivePolicy = savedImmersive
        try? FileManager.default.removeItem(at: libraryRoot)
        super.tearDown()
    }

    private func makeLibrary() -> AmberThemePackLibrary {
        AmberThemePackLibrary(
            fileURL: libraryRoot.appendingPathComponent("library.json")
        )
    }

    private func makeService() -> IOSThemePackToolService {
        IOSThemePackToolService(runtime: runtime, library: makeLibrary())
    }

    private func makeRuntime() -> ChatToolRuntime {
        ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(
                userDefaults: UserDefaults(suiteName: "IOSThemePackToolTests-\(UUID().uuidString)")!
            ),
            localToolExecutor: nil,
            searchTransport: ThemePackCountingSearchTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
    }

    private func makeParams(toolNames: [String]) -> TextGenerationParams {
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
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
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: ToolKt.iosToolDeclarations(names: toolNames),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "theme-pack-test",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func importJSON(
        id: String = "rain-bookstore",
        displayName: String = "雨天书店",
        paper: String = "paper",
        accent: AmberAccentOption = .terracotta,
        canvasStyle: String = "dotGrid",
        extra: [String: Any] = [:]
    ) -> String {
        var body: [String: Any] = [
            "id": id,
            "display_name": displayName,
            "paper": paper,
            "accent_hex": AmberThemePackTransfer.hexString(accent.accentHex),
            "ink_hex": AmberThemePackTransfer.hexString(accent.inkHex),
            "canvas_style": canvasStyle,
            "brand_mark": "serifWordmark",
            "shortcut_icon_style": "phosphorFill",
            "chrome_typeface": "serif",
        ]
        extra.forEach { body[$0.key] = $0.value }
        return IOSWorkspaceStore.json(body)
    }

    private func parseJSON(_ text: String) -> [String: Any] {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("输出必须是可解析 JSON，实际: \(text.prefix(200))")
            return [:]
        }
        return object
    }

    func testStatusReportsCurrentRecipeAndAllowedSlots() {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        let payload = parseJSON(makeService().status())
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual((payload["contrast_min"] as? NSNumber)?.doubleValue, 3.0)
        let current = payload["current"] as? [String: Any]
        XCTAssertEqual(current?["paper"] as? String, "notion")
        XCTAssertTrue(payload["try_on"] is NSNull)
        let allowed = payload["allowed"] as? [String: Any]
        let papers = allowed?["paper"] as? [String]
        XCTAssertEqual(papers, ["paper", "neutral", "white", "pi", "notion"])
        XCTAssertFalse(papers?.contains("garnet") == true)
        let builtins = payload["builtin_ids"] as? [String]
        XCTAssertEqual(builtins, ["sit-terracotta", "pi-steel", "notion-blue"])
        let rules = payload["rules"] as? [String] ?? []
        XCTAssertTrue(rules.contains { $0.contains("套用") }, "rules: \(rules)")
    }

    func testPrepareTryOnDoesNotPersistUntilCommit() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        let paperKey = "app.amber.ios.theme.paper"
        let accentKey = "app.amber.ios.theme.accentHex"
        let persistedPaper = UserDefaults.standard.string(forKey: paperKey)
        let persistedAccent = UserDefaults.standard.object(forKey: accentKey) as? Int
        let library = makeLibrary()
        let service = IOSThemePackToolService(runtime: runtime, library: library)

        let document = try service.prepareImport(argumentsJSON: importJSON())
        XCTAssertEqual(document.id, "rain-bookstore")
        XCTAssertTrue(runtime.isTryOnActive)
        XCTAssertEqual(runtime.paper, .paper)
        XCTAssertEqual(UserDefaults.standard.string(forKey: paperKey), persistedPaper)
        XCTAssertEqual(UserDefaults.standard.object(forKey: accentKey) as? Int, persistedAccent)

        let status = parseJSON(service.status())
        let tryOn = status["try_on"] as? [String: Any]
        XCTAssertEqual(tryOn?["id"] as? String, "rain-bookstore")
        let current = status["current"] as? [String: Any]
        XCTAssertEqual(current?["paper"] as? String, "notion")

        let committed = parseJSON(try service.commitPreparedImport())
        XCTAssertEqual(committed["ok"] as? Bool, true)
        XCTAssertEqual(committed["installed"] as? Bool, true)
        XCTAssertFalse(runtime.isTryOnActive)
        XCTAssertEqual(UserDefaults.standard.string(forKey: paperKey), "paper")
        XCTAssertTrue(library.contains(id: "rain-bookstore"))
        XCTAssertTrue(parseJSON(service.status())["try_on"] is NSNull)
    }

    func testDiscardRestoresBaselineWithoutLibraryWrite() throws {
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        let library = makeLibrary()
        let service = IOSThemePackToolService(runtime: runtime, library: library)
        _ = try service.prepareImport(argumentsJSON: importJSON())
        service.discardPreparedImport()
        XCTAssertFalse(runtime.isTryOnActive)
        XCTAssertEqual(runtime.paper, .notion)
        XCTAssertTrue(library.installed.isEmpty)
    }

    func testImportRejectsBuiltinIdWithoutTryOn() {
        let service = makeService()
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)
        let output = service.execute(
            toolName: "theme_pack_import",
            argumentsJSON: importJSON(id: "sit-terracotta", displayName: "点阵")
        )
        let payload = parseJSON(output)
        XCTAssertEqual(payload["ok"] as? Bool, false)
        XCTAssertTrue((payload["reason"] as? String ?? "").contains("内置"))
        XCTAssertFalse(runtime.isTryOnActive)
        XCTAssertEqual(runtime.paper, .notion)
    }

    func testImportRejectsImmersivePaperAndLowContrast() {
        let service = makeService()
        runtime.apply(AmberThemePack.builtins.first { $0.id == "notion-blue" }!)

        let immersive = parseJSON(service.execute(
            toolName: "theme_pack_import",
            argumentsJSON: importJSON(paper: "garnet")
        ))
        XCTAssertEqual(immersive["ok"] as? Bool, false)
        XCTAssertFalse(runtime.isTryOnActive)

        let contrast = parseJSON(service.execute(
            toolName: "theme_pack_import",
            argumentsJSON: importJSON(extra: [
                "accent_hex": "0x808080",
                "ink_hex": "0x909090",
            ])
        ))
        XCTAssertEqual(contrast["ok"] as? Bool, false)
        XCTAssertTrue((contrast["reason"] as? String ?? "").contains("对比度"))
        XCTAssertFalse(runtime.isTryOnActive)
    }

    func testDefaultCanvasScopeIsShell() throws {
        let service = makeService()
        let document = try service.prepareImport(argumentsJSON: importJSON())
        XCTAssertEqual(document.canvasScope, AmberCanvasScope.shell.rawValue)
        service.discardPreparedImport()
    }

    func testToolsDeferredAndChineseSearchHit() throws {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(
                userDefaults: UserDefaults(suiteName: "IOSThemePackToolSearch-\(UUID().uuidString)")!
            ),
            localToolExecutor: IOSLocalToolExecutor(
                permissionStore: IOSPermissionStore(
                    userDefaults: UserDefaults(suiteName: "IOSThemePackToolPerm-\(UUID().uuidString)")!
                ),
                documentStore: DocumentAccessStore(),
                workspaceStore: IOSWorkspaceStore(
                    baseDirectory: FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString)
                )
            ),
            autoGenerateResponses: false
        )
        _ = viewModel.currentToolDeclarationNames()
        let bridge = try XCTUnwrap(viewModel.toolExposureBridgeForTesting())
        let full = Set(bridge.fullToolDeclarations().map(\.name))
        for name in IOSThemePackToolCatalog.toolNames {
            XCTAssertTrue(full.contains(name), "\(name) must be in bridge catalog")
            XCTAssertFalse(Set(bridge.visibleTools().map(\.name)).contains(name), "\(name) deferred")
        }
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"主题","limit":12}"#)
        XCTAssertTrue(payload.contains("theme_pack_status"), payload)
        XCTAssertTrue(payload.contains("theme_pack_import"), payload)
    }

    func testBackgroundRegistersStatusOnlyDeniesImport() async {
        let chatRuntime = makeRuntime()
        let params = makeParams(toolNames: Array(IOSThemePackToolCatalog.toolNames).sorted())
        let executors = chatRuntime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: params,
            runId: "bg-theme-pack",
            conversationId: nil
        )
        XCTAssertNotNil(executors["theme_pack_status"])
        XCTAssertNotNil(executors["theme_pack_import"])

        let statusBox = ThemePackUncheckedToolExecutorBox(try! XCTUnwrap(executors["theme_pack_status"]))
        let statusOutcome = await statusBox.execute(
            name: "theme_pack_status",
            arguments: "{}",
            isUserInitiated: false
        )
        guard case .filled(let statusText) = statusOutcome else {
            return XCTFail("status should fill: \(statusOutcome)")
        }
        XCTAssertTrue(parseJSON(statusText)["ok"] as? Bool == true)

        let importBox = ThemePackUncheckedToolExecutorBox(try! XCTUnwrap(executors["theme_pack_import"]))
        let importOutcome = await importBox.execute(
            name: "theme_pack_import",
            arguments: importJSON(),
            isUserInitiated: false
        )
        guard case .denied(let reason) = importOutcome else {
            return XCTFail("background import must deny: \(importOutcome)")
        }
        XCTAssertTrue(reason.contains("前台"), reason)
        XCTAssertFalse(runtime.isTryOnActive)
    }

    func testApprovalCardAndShellUseApplyRevertCopy() throws {
        let card = try source("iosApp/MemoryToolApprovalCard.swift")
        XCTAssertTrue(card.contains("套用"))
        XCTAssertTrue(card.contains("还原"))
        XCTAssertTrue(card.contains("struct AmberThemeTryOnBar"))
        XCTAssertTrue(card.contains("swatchpalette"))
        XCTAssertTrue(card.contains("arrow.uturn.backward"))
        XCTAssertTrue(card.contains(".chatApprovalHitTarget()"))
        XCTAssertTrue(card.contains(".frame(width: 180, height: 120)"))
        let support = try source("iosApp/ChatToolSupport.swift")
        XCTAssertTrue(support.contains("试穿主题"))
        let shell = try source("iosApp/AppShell.swift")
        XCTAssertTrue(shell.contains("AmberThemeTryOnBar"))
        XCTAssertTrue(shell.contains("amberThemeTryOnTakenOver"))
        XCTAssertTrue(shell.contains("isResolvingThemeTryOn"))
        let upsertIndex = shell.range(of: "AmberThemePackLibrary.shared.upsert")?.lowerBound
        let commitIndex = shell.range(of: "AmberThemeRuntime.shared.commitTryOn")?.lowerBound
        XCTAssertNotNil(upsertIndex)
        XCTAssertNotNil(commitIndex)
        if let upsertIndex, let commitIndex {
            XCTAssertTrue(upsertIndex < commitIndex, "orphan 套用 must upsert before commitTryOn")
        }
        let appearance = try source("iosApp/AppearanceSettingsView.swift")
        XCTAssertTrue(appearance.contains("applyTakingOverTryOn { runtime.paper = paper }"))
        XCTAssertTrue(appearance.contains("applyTakingOverTryOn { runtime.apply(option) }"))
        XCTAssertTrue(appearance.contains("AmberThemePackTransfer.document(from: runtime)"))
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        XCTAssertTrue(coordinator.contains("discardPreparedThemeImport()"))
        XCTAssertTrue(coordinator.contains("clearPendingMcpApproval(discardThemeTryOn: false)"))
    }

    func testTimelineTitles() {
        XCTAssertEqual(ChatToolStepModel(tool: makeTool("theme_pack_status")).title, "查看主题")
        XCTAssertEqual(ChatToolStepModel(tool: makeTool("theme_pack_import")).title, "试穿主题")
    }

    private func makeTool(_ name: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "theme-\(name)",
            toolName: name,
            input: "{}",
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func source(_ relativePath: String) throws -> String {
        let testsDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        return try String(
            contentsOf: testsDirectory.deletingLastPathComponent().appendingPathComponent(relativePath),
            encoding: .utf8
        )
    }
}

private final class ThemePackCountingSearchTransport: IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let http = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/html; charset=utf-8"]
        )!
        return (http, Data())
    }
}

private final class ThemePackUncheckedToolExecutorBox: @unchecked Sendable {
    private let base: any IOSToolExecutor
    init(_ base: any IOSToolExecutor) { self.base = base }
    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        await base.execute(name: name, arguments: arguments, isUserInitiated: isUserInitiated)
    }
}
