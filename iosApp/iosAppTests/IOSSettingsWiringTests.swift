import XCTest
@testable import iosApp

final class IOSSettingsWiringTests: XCTestCase {
    private func source(_ relativePath: String) throws -> String {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosAppRoot = testsDir.deletingLastPathComponent()
        let fileURL = iosAppRoot.appendingPathComponent(relativePath)
        return try String(contentsOf: fileURL, encoding: .utf8)
    }

    func testNativeTimelineScrollAndChatRendererTogglesAreIndependent() throws {
        let settings = try source("iosApp/DisplayFontSettingsView.swift")

        XCTAssertTrue(settings.contains("@AppStorage(NativeChatTimelineStaticRenderFeatureFlags.key)"))
        XCTAssertTrue(settings.contains("@AppStorage(NativeChatTimelineStreamingTailFeatureFlags.key)"))
        XCTAssertTrue(settings.contains("@AppStorage(NativeTimelineScrollFeatureFlags.key)"))
        XCTAssertTrue(settings.contains("isOn: nativeTimelineScrollDriver"))
        XCTAssertTrue(settings.contains("nativeTimelineScrollDriver.toggle()"))
        XCTAssertTrue(settings.contains("isOn: nativeChatTimelineEnabled"))
        XCTAssertTrue(settings.contains("nativeTimelineStaticRender && nativeTimelineStreamingTail"))
        XCTAssertTrue(settings.contains("nativeTimelineStaticRender = enabled"))
        XCTAssertTrue(settings.contains("nativeTimelineStreamingTail = enabled"))
        XCTAssertFalse(settings.contains("nativeTimelineStaticRender && nativeTimelineStreamingTail && nativeTimelineScrollDriver"))
        XCTAssertFalse(settings.contains("nativeTimelineScrollDriver = enabled"))
    }

    func testNovelCreationAdvancedEntryOpensFeatureAndSharedSettingsPersistRoleDefaults() throws {
        let home = try source("iosApp/PlaceholderViews.swift")
        let shell = try source("iosApp/AppShell.swift")
        let settings = try source("iosApp/NovelCreation/NovelCreationSettingsView.swift")

        XCTAssertTrue(home.contains("title: \"小说创作\""))
        XCTAssertTrue(home.contains("color: AmberTheme.accentIndigo, route: .novelCreation)"))
        XCTAssertFalse(home.contains("route: .novelCreationSettings"))
        XCTAssertTrue(shell.contains("case novelCreationSettings"))
        XCTAssertFalse(shell.contains("NovelSettingsTransitionSource"))
        XCTAssertTrue(shell.contains("NovelCreationSettingsView("))
        XCTAssertTrue(settings.contains("创作模型"))
        XCTAssertTrue(settings.contains("剧情同步模型"))
        XCTAssertTrue(settings.contains("preferences.set(policy, for: purpose)"))
        XCTAssertTrue(settings.contains("NovelProjectManagementView("))
        XCTAssertFalse(settings.contains("projectID:"))
    }

    func testChatSendDismissesTheComposerKeyboardBeforeStartingGeneration() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let start = try XCTUnwrap(chatView.range(of: "private func sendComposerMessage()"))
        let end = try XCTUnwrap(
            chatView.range(of: "private func openComposerModelSheet()", range: start.upperBound..<chatView.endIndex)
        )
        let sendBody = chatView[start.lowerBound..<end.lowerBound]

        XCTAssertTrue(sendBody.contains("dismissKeyboard()"))
        XCTAssertLessThan(
            try XCTUnwrap(sendBody.range(of: "dismissKeyboard()")?.lowerBound),
            try XCTUnwrap(sendBody.range(of: "viewModel.sendMessage()")?.lowerBound)
        )
    }

    func testNativeTimelineRouteReadsSettingsToggleFlags() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let list = try source("iosApp/ChatCollectionMessageList.swift")

        XCTAssertTrue(chatView.contains("@AppStorage(NativeChatTimelineStaticRenderFeatureFlags.key)"))
        XCTAssertTrue(chatView.contains("@AppStorage(NativeChatTimelineStreamingTailFeatureFlags.key)"))
        XCTAssertTrue(chatView.contains("nativeTimelineStaticRenderEnabled: nativeTimelineStaticRenderEnabled"))
        XCTAssertTrue(chatView.contains("nativeTimelineStreamingTailEnabled: nativeTimelineStreamingTailEnabled"))
        XCTAssertTrue(chatView.contains("NativeChatTimelineView("))
        XCTAssertTrue(list.contains("NativeChatTimelineRoutePolicy.shouldUseNativeTimeline"))
        XCTAssertTrue(list.contains("staticRenderEnabled: nativeTimelineStaticRenderEnabled"))
        XCTAssertTrue(list.contains("streamingTailEnabled: nativeTimelineStreamingTailEnabled"))
    }

    func testNativeTimelineScrollDriverFlagIsConsumedByNativeTimelineView() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let list = try source("iosApp/ChatCollectionMessageList.swift")

        XCTAssertTrue(chatView.contains("@AppStorage(NativeTimelineScrollFeatureFlags.key)"))
        XCTAssertTrue(chatView.contains("nativeScrollDriverEnabled: nativeTimelineScrollDriverEnabled"))
        XCTAssertTrue(list.contains("@State private var scrollDriver = NativeTimelineScrollDriver()"))
        XCTAssertTrue(list.contains("var nativeScrollDriverEnabled: Bool"))
        XCTAssertTrue(list.contains("scrollDriver.attach(scrollView)"))
        XCTAssertTrue(list.contains("viewportState.followPaused || !viewportState.isAtBottom"))
        XCTAssertTrue(list.contains("scrollDriver.submit(.streamContentGrew)"))
    }

    func testNativeTimelineScrollDriverIsSharedByEveryStreamingConversationSurface() throws {
        let driver = try source("iosApp/NativeTimelineScrollDriver.swift")
        let chat = try source("iosApp/ChatCollectionMessageList.swift")
        let council = try source("iosApp/CouncilChatRuntimeView.swift")
        let novel = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(driver.contains("struct NativeTimelineScrollViewResolver"))
        for surface in [chat, council, novel] {
            XCTAssertTrue(surface.contains("@State private var scrollDriver = NativeTimelineScrollDriver()"))
            XCTAssertTrue(surface.contains("NativeTimelineScrollViewResolver("))
            XCTAssertTrue(surface.contains("scrollDriver.submit(.streamContentGrew)"))
            XCTAssertTrue(surface.contains("scrollDriver.submit(.userDragBegan)"))
            XCTAssertTrue(surface.contains("scrollDriver.submit(.userDragEnded(isAtBottom:"))
            XCTAssertTrue(surface.contains("scrollDriver.setAutomaticFollowEnabled("))
            XCTAssertTrue(surface.contains("NativeTimelineScrollReturnPolicy.returnedToBottom("))
            XCTAssertTrue(surface.contains("isNativeScrollSurfaceVisible"))
        }
    }

    func testChatTopBarSoftEdgeEndsAtBottomAlignedControls() {
        XCTAssertEqual(ChatTopBarLayout.controlsHeight, 54)
        XCTAssertEqual(ChatTopBarLayout.toolbarButtonDiameter, 38)
    }

    func testCustomTopBarsUseNativeSoftEdgesAndLiquidGlassControls() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let activityIsland = try source("iosApp/ChatActivityIslandView.swift")
        let composer = try source("iosApp/ChatComposerViews.swift")
        let chat = try source("iosApp/ChatCollectionMessageList.swift")
        let appearance = try source("iosApp/AppearanceSettingsView.swift")
        let swiftUISoftEdgeCount = chat.components(
            separatedBy: ".scrollEdgeEffectStyle(.soft, for: .top)"
        ).count - 1

        XCTAssertGreaterThanOrEqual(swiftUISoftEdgeCount, 2)
        XCTAssertTrue(chat.contains("collectionView.topEdgeEffect.style = .soft"))
        XCTAssertTrue(appearance.contains(".scrollEdgeEffectStyle(.soft, for: .top)"))
        XCTAssertFalse(chatView.contains("ChatTopEdgeFadeMaterial"))
        XCTAssertFalse(chatView.contains("bottomExtension"))
        XCTAssertTrue(
            chatView.contains(".frame(height: ChatTopBarLayout.controlsHeight, alignment: .bottom)")
        )
        XCTAssertTrue(chatView.contains("GlassEffectContainer(spacing: 12)"))
        XCTAssertTrue(composer.contains(".foregroundStyle(Color(uiColor: .label))"))
        XCTAssertTrue(composer.contains(".symbolRenderingMode(.monochrome)"))
        XCTAssertTrue(chatView.contains("topBarGlyphOverlay"))
        XCTAssertTrue(chatView.contains("showsGlyph: false"))
        XCTAssertTrue(activityIsland.contains(".glassEffect(.regular, in: Capsule())"))
        XCTAssertTrue(activityIsland.contains("Capsule().fill(.ultraThinMaterial)"))
    }

    func testStreamingMarkdownRendererTogglesAreWiredAndMutuallyExclusive() throws {
        let settings = try source("iosApp/DisplayFontSettingsView.swift")
        let bubble = try source("iosApp/MessageBubbleView.swift")

        XCTAssertTrue(settings.contains("@AppStorage(IOSDisplayPreferenceKeys.microsoftStreamingMarkdown)"))
        XCTAssertTrue(settings.contains("@AppStorage(IOSDisplayPreferenceKeys.liyananStreamingMarkdown)"))
        XCTAssertTrue(settings.contains("microsoftStreamingMarkdown.toggle()"))
        XCTAssertTrue(settings.contains("liyananStreamingMarkdown = false"))
        XCTAssertTrue(settings.contains("liyananStreamingMarkdown.toggle()"))
        XCTAssertTrue(settings.contains("microsoftStreamingMarkdown = false"))

        XCTAssertTrue(bubble.contains("@AppStorage(IOSDisplayPreferenceKeys.microsoftStreamingMarkdown)"))
        XCTAssertTrue(bubble.contains("@AppStorage(IOSDisplayPreferenceKeys.liyananStreamingMarkdown)"))
        XCTAssertTrue(bubble.contains("LiyananStreamingMarkdownContentView(content: content)"))
        XCTAssertTrue(bubble.contains("ChatStableStreamingMarkdownView("))
        XCTAssertTrue(bubble.contains("microsoftStreamingMarkdown && shouldUseExperimentalMarkdownRenderer"))
        XCTAssertTrue(bubble.contains("liyananStreamingMarkdown && shouldUseExperimentalMarkdownRenderer"))
    }

    func testStreamingBlockMarkdownToggleIsConsumedByTableBlockRenderer() throws {
        let settings = try source("iosApp/DisplayFontSettingsView.swift")
        let bubble = try source("iosApp/MessageBubbleView.swift")

        XCTAssertTrue(settings.contains("@AppStorage(IOSDisplayPreferenceKeys.streamingBlockMarkdown)"))
        XCTAssertTrue(settings.contains("streamingBlockMarkdown.toggle()"))
        XCTAssertTrue(settings.contains("表格流式块渲染"))

        XCTAssertTrue(bubble.contains("@AppStorage(IOSDisplayPreferenceKeys.streamingBlockMarkdown)"))
        XCTAssertTrue(bubble.contains("guard streamingBlockMarkdown else { return false }"))
        XCTAssertTrue(bubble.contains("ChatStreamingMarkdownBlockParser.containsTable"))
        XCTAssertTrue(bubble.contains("text: table.markdown"))
        XCTAssertTrue(bubble.contains("cacheIdentity: renderCacheNamespace.map"))
    }

    func testGrokWebLoginIsWiredToProviderSettingsAndChatRuntime() throws {
        let detail = try source("iosApp/ProviderDetailView.swift")
        let configuration = try source("iosApp/ChatProviderConfiguration.swift")
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        let grokProvider = try source("iosApp/IOSGrokWebProvider.swift")

        XCTAssertTrue(detail.contains("GrokWebLoginView("))
        XCTAssertTrue(detail.contains("IOSGrokWebConstants.webBaseUrl"))
        XCTAssertTrue(detail.contains("updateProviderChatModels"))
        XCTAssertTrue(detail.contains("grokSection"))

        XCTAssertTrue(configuration.contains("IOSGrokWebProviderResolver.isGrokWebProvider(provider)"))
        XCTAssertTrue(configuration.contains(".grokNotSignedIn"))

        XCTAssertTrue(coordinator.contains("IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI)"))
        XCTAssertTrue(coordinator.contains("IOSGrokWebClient(providerId: providerId).streamText"))
        XCTAssertTrue(coordinator.contains("grokWebStreamTask?.cancel()"))
        XCTAssertTrue(coordinator.contains("!IOSGrokWebProviderResolver.isGrokWebProvider(handoff.providerSetting)"))
        XCTAssertTrue(detail.contains("providerBackup: grokProviderBackup"))
        XCTAssertTrue(detail.contains("baseUrl: backup.baseUrl"))
        XCTAssertTrue(detail.contains("if shouldSeedModels"))
        XCTAssertTrue(grokProvider.contains("IOSGrokWebBrowserTransport"))
        XCTAssertTrue(grokProvider.contains(#"credentials: "include""#))
        XCTAssertFalse(grokProvider.contains("session.bytes(for:"))
    }

    func testGrokWebLoginRequiresNonEmptySSOCookie() {
        let analytics = makeCookie(name: "analytics", value: "present")
        let sso = makeCookie(name: "sso", value: "session-token")

        XCTAssertNil(IOSGrokWebCookieValidator.ssoCookieHeader(from: [analytics]))
        XCTAssertEqual(
            IOSGrokWebCookieValidator.ssoCookieHeader(from: [analytics, sso]),
            "sso=session-token"
        )
        XCTAssertFalse(IOSGrokWebCookieValidator.hasSSOCookie(in: "analytics=present"))
        XCTAssertTrue(IOSGrokWebCookieValidator.hasSSOCookie(in: "analytics=present; sso=session-token"))
    }

    func testGrokWebRuntimeRestoresReadAndWriteCookiesFromSavedSSO() throws {
        let cookies = IOSGrokWebCookieValidator.authenticationCookies(from: "sso=session-token")

        XCTAssertEqual(Set(cookies.map(\.name)), Set(["sso", "sso-rw"]))
        XCTAssertTrue(cookies.allSatisfy { $0.value == "session-token" })
        XCTAssertTrue(cookies.allSatisfy { $0.domain == ".grok.com" })
        XCTAssertTrue(cookies.allSatisfy(\.isSecure))
    }

    func testGrokWebModelResolverMapsSeededModelAndRejectsAPIOnlyModel() throws {
        XCTAssertEqual(
            try IOSGrokWebModelResolver.resolve("grok-4.20-fast"),
            IOSGrokWebWireModel(modelName: "grok-420", modelMode: "MODEL_MODE_FAST")
        )

        XCTAssertThrowsError(try IOSGrokWebModelResolver.resolve("grok-4.5")) { error in
            XCTAssertTrue(error.localizedDescription.contains("xAI API"))
            XCTAssertTrue(error.localizedDescription.contains("sub2api"))
        }
    }

    func testGrokWebStreamParserKeepsTokenFromTerminalFrame() {
        let line = #"{"result":{"response":{"token":"final","isThinking":false,"finalMetadata":{"done":true}}}}"#

        XCTAssertEqual(
            IOSGrokWebStreamParser.parse(line),
            IOSGrokWebStreamFrame(token: "final", isFinished: true, errorMessage: nil)
        )
    }

    func testGrokWebClientClosesTransportWhenTerminalFrameArrives() throws {
        let provider = try source("iosApp/IOSGrokWebProvider.swift")

        XCTAssertTrue(provider.contains("return frame.isFinished"))
    }

    func testProviderConnectionTestWaitsForTheModelRequestResult() throws {
        let detail = try source("iosApp/ProviderDetailView.swift")

        XCTAssertFalse(
            detail.contains("连接测试已发起；模型获取结果会显示在模型页。"),
            "Starting an async request is not a successful connection result."
        )
    }

    func testApprovedCouncilRunIsOwnedByTheCoordinatorCancellationTask() throws {
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        let start = try XCTUnwrap(coordinator.range(of: "private func finishPendingCouncilToolApproval"))
        let end = try XCTUnwrap(
            coordinator.range(of: "private func resumeAfterApproval", range: start.upperBound..<coordinator.endIndex)
        )
        let approvalPath = coordinator[start.lowerBound..<end.lowerBound]

        XCTAssertTrue(approvalPath.contains("foregroundToolExecutionTask = executionTask"))
        XCTAssertTrue(approvalPath.contains("clearForegroundToolExecution(matching: executionToken)"))
    }

    func testCouncilSettingsExposeAnExplicitCurrentModelConnectivityProbe() throws {
        let settings = try source("iosApp/CouncilSettingsView.swift")

        XCTAssertTrue(settings.contains("测试当前议会模型"))
        XCTAssertTrue(settings.contains("IOSCouncilModelConnectivityTester"))
        XCTAssertTrue(settings.contains("实际回退"))
    }

    func testGrokWebStreamParserSurfacesProviderError() {
        let line = #"data: {"error":{"message":"session expired"}}"#

        XCTAssertEqual(
            IOSGrokWebStreamParser.parse(line),
            IOSGrokWebStreamFrame(token: nil, isFinished: true, errorMessage: "session expired")
        )
    }

    func testGrokWebPayloadDefaultsToChatAndSupportsIsolatedNovelRequests() {
        let wireModel = IOSGrokWebWireModel(
            modelName: "grok-420",
            modelMode: "MODEL_MODE_FAST"
        )

        let chatPayload = IOSGrokWebPayloadBuilder.makePayload(
            prompt: "chat prompt",
            wireModel: wireModel
        )
        XCTAssertEqual(chatPayload["disableSearch"] as? Bool, false)
        XCTAssertEqual(chatPayload["disableMemory"] as? Bool, false)

        let novelPayload = IOSGrokWebPayloadBuilder.makePayload(
            prompt: "novel prompt",
            wireModel: wireModel,
            options: .novel
        )
        XCTAssertEqual(novelPayload["disableSearch"] as? Bool, true)
        XCTAssertEqual(novelPayload["disableMemory"] as? Bool, true)
        XCTAssertEqual(novelPayload["message"] as? String, "novel prompt")
        XCTAssertEqual(novelPayload["modelName"] as? String, "grok-420")
    }

    func testProviderEndpointPolicyAllowsHTTPOnlyForIPAddressHosts() {
        XCTAssertTrue(IOSProviderEndpointPolicy.isValidBaseURL("https://sub2api.example/v1"))
        XCTAssertTrue(IOSProviderEndpointPolicy.isValidBaseURL("http://203.0.113.10:8080/v1"))
        XCTAssertTrue(IOSProviderEndpointPolicy.isValidBaseURL("http://[2001:db8::10]:8080/v1"))
        XCTAssertFalse(IOSProviderEndpointPolicy.isValidBaseURL("http://sub2api.example/v1"))
        XCTAssertFalse(IOSProviderEndpointPolicy.isValidBaseURL("203.0.113.10:8080/v1"))
    }

    func testInfoPlistAllowsInsecureHTTPOnlyForIPAddressRanges() throws {
        let info = try source("iosApp/Info.plist")

        XCTAssertTrue(info.contains("NSAppTransportSecurity"))
        XCTAssertTrue(info.contains("NSExceptionAllowsInsecureHTTPLoads"))
        XCTAssertTrue(info.contains("0.0.0.0/0"))
        XCTAssertTrue(info.contains("::/0"))
        XCTAssertFalse(info.contains("NSAllowsArbitraryLoads"))
    }

    func testInfoPlistOptsIntoProMotionFrameRates() throws {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let plistURL = testsDir.deletingLastPathComponent().appendingPathComponent("iosApp/Info.plist")
        let data = try Data(contentsOf: plistURL)
        let plist = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any]
        )

        XCTAssertEqual(plist["CADisableMinimumFrameDurationOnPhone"] as? Bool, true)
    }

    private func makeCookie(name: String, value: String) -> HTTPCookie {
        HTTPCookie(properties: [
            .domain: ".grok.com",
            .path: "/",
            .name: name,
            .value: value,
            .secure: "TRUE",
        ])!
    }
}
