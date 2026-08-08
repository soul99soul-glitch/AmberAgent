import XCTest
import Shared
@testable import iosApp

final class IOSGenerativeWidgetParserTests: XCTestCase {
    func testWidgetPayloadPreflightSkipsPlainMarkdown() {
        XCTAssertFalse(IOSGenerativeWidgetParser.mayContainWidgetPayload(
            """
            ## 标题
            - 第一条
            - 第二条

            普通 Markdown 正文不应该进入生成式 UI 完整解析路径。
            """
        ))
    }

    func testWidgetPayloadPreflightRecognizesWidgetAndHtmlPayloads() {
        XCTAssertTrue(IOSGenerativeWidgetParser.mayContainWidgetPayload(
            """
            Intro
            ```show-widget
            {"title":"Flow","widget_code":"<svg></svg>"}
            ```
            """
        ))
        XCTAssertTrue(IOSGenerativeWidgetParser.mayContainWidgetPayload(
            #"<!DOCTYPE html><html><body><div id="deck">A</div></body></html>"#
        ))
    }

    func testParsesWidgetBetweenMarkdownText() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            Before
            ```show-widget
            {"title":"Flow","widget_code":"<svg viewBox=\\"0 0 680 240\\"><text x=\\"24\\" y=\\"48\\">流程：需求收集、方案设计、开发、测试、上线发布与复盘</text></svg>"}
            ```
            After
            """,
            streaming: false
        )

        XCTAssertEqual(segments.count, 3)
        if case .text(_, let before) = segments[0] {
            XCTAssertEqual(before, "Before")
        } else {
            XCTFail("Expected leading text")
        }
        guard case .widget(let widget) = segments[1] else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.title, "Flow")
        XCTAssertEqual(widget.widgetCode, #"<svg viewBox="0 0 680 240"><text x="24" y="48">流程：需求收集、方案设计、开发、测试、上线发布与复盘</text></svg>"#)
        XCTAssertTrue(widget.complete)
        if case .text(_, let after) = segments[2] {
            XCTAssertEqual(after, "After")
        } else {
            XCTFail("Expected trailing text")
        }
    }

    func testExtractsPartialWidgetCodeWhileStreaming() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect
            """,
            streaming: true
        )

        XCTAssertEqual(segments.count, 2)
        guard case .widget(let widget) = segments[1] else {
            return XCTFail("Expected partial widget")
        }
        XCTAssertEqual(widget.title, "Draft")
        XCTAssertEqual(widget.widgetCode, "<svg><rect")
        XCTAssertFalse(widget.complete)
    }

    func testPartialWidgetIDStaysStableWhileStreamingCodeGrows() {
        let first = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect
            """,
            streaming: true
        )
        let second = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect width=\\"20\\"
            """,
            streaming: true
        )

        guard case .widget(let firstWidget) = first.last,
              case .widget(let secondWidget) = second.last else {
            return XCTFail("Expected streaming widgets")
        }
        XCTAssertEqual(firstWidget.id, secondWidget.id)
        XCTAssertFalse(firstWidget.complete)
        XCTAssertFalse(secondWidget.complete)
    }

    func testWidgetIDStaysStableFromPartialToComplete() {
        let partial = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg><rect
            """,
            streaming: true
        )
        let complete = IOSGenerativeWidgetParser.parse(
            """
            Intro
            ```show-widget
            {"title":"Draft","widget_code":"<svg viewBox=\\"0 0 680 240\\"><text x=\\"24\\" y=\\"48\\">Flow Chart Overview with detailed steps and outcomes</text><rect width=\\"20\\"/></svg>"}
            ```
            """,
            streaming: false
        )

        guard case .widget(let partialWidget) = partial.last,
              case .widget(let completeWidget) = complete.dropFirst().first else {
            return XCTFail("Expected partial and complete widgets")
        }
        XCTAssertEqual(partialWidget.id, completeWidget.id)
        XCTAssertFalse(partialWidget.complete)
        XCTAssertTrue(completeWidget.complete)
    }

    func testRendersStructuredChartSpec() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Trend","renderer":"chart","spec":{"type":"line","x":["Mon","Tue"],"series":[{"name":"Count","data":[1,3]}]}}
            ```
            """,
            streaming: false
        )

        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.renderer, "chart")
        XCTAssertTrue(widget.widgetCode.contains("<svg"))
        XCTAssertTrue(widget.widgetCode.contains("Count"))
    }

    func testChartDoesNotRenderPointsPastItsLabels() {
        let svg = IOSGenerativeWidgetRenderer().render(
            renderer: "chart",
            specJson: #"{"type":"line","x":["Only"],"series":[{"name":"Count","data":[1,2,3]}]}"#
        )

        XCTAssertNotNil(svg)
        XCTAssertFalse(svg?.contains("1198.0") ?? true)
    }

    func testSingleFlowNodeIsHorizontallyCentered() {
        let svg = IOSGenerativeWidgetRenderer().render(
            renderer: "diagram",
            specJson: #"{"type":"flow","nodes":[{"label":"Only"}]}"#
        )

        XCTAssertTrue(svg?.contains(#"x="254.0""#) ?? false)
    }

    func testNormalizesWrappedSlidesSpec() {
        let segments = IOSGenerativeWidgetParser.parse(
            """
            ```show-widget
            {"title":"Deck","renderer":"slides","spec":{"schemaVersion":2,"style":"magazine","accent":"#123456","fontPack":"source-han-serif-sc-regular","slides":[{"layout":"cover","title":"A","content":["B"]}]}}
            ```
            """,
            streaming: false
        )

        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.renderer, "slides")
        XCTAssertTrue(widget.specJson?.contains(#""fontPack":"source-han-serif-sc-regular""#) ?? false)
        XCTAssertTrue(widget.widgetCode.contains("A"))
    }

    func testParsesFullHtmlRendererAndLegacyAlias() {
        let html = #"<!DOCTYPE html><html><body><div id="deck"><section class="slide">A</section></div></body></html>"#
        let content = """
        ```show-widget
        {"title":"Live Deck","renderer":"guizang_html","widget_code":"<svg viewBox=\\"0 0 20 20\\"><text>G</text></svg>","spec":{"html":\(jsonStringLiteralForTest(html))}}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)
        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.renderer, "full_html")
        XCTAssertTrue(widget.specJson?.contains(#"<div id=\"deck\">"#) ?? false)
    }

    func testRejectsFragmentFullHtmlInsteadOfAcceptingPreviewCover() {
        let content = """
        ```show-widget
        {"title":"Broken","renderer":"full_html","spec":{"html":"<div>no deck structure</div>"}}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)

        XCTAssertFalse(segments.contains { segment in
            if case .widget = segment { return true }
            return false
        })
    }

    /// G6: 单页海报（完整 HTML、无 slide 结构）在非 SLIDES 路由下不再被误杀；
    /// 但 SLIDES 路由的 deck 校验不变——validateHtml(expectSlides: true) 仍要求 slide。
    func testNonSlidesFullHtmlAcceptsCompleteDocumentWithoutSlideSections() {
        let posterHtml = #"<!DOCTYPE html><html><body><div class="poster"><h1>单页活动海报</h1><p>时间、地点、内容摘要</p></div></body></html>"#

        XCTAssertTrue(IOSGuizangHtmlDeckValidator.validateHtml(posterHtml).valid)
        XCTAssertFalse(IOSGuizangHtmlDeckValidator.validateHtml(posterHtml, expectSlides: true).valid)

        let content = """
        ```show-widget
        {"title":"Poster","renderer":"full_html","spec":{"html":\(jsonStringLiteralForTest(posterHtml))}}
        ```
        """
        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)
        XCTAssertTrue(segments.contains { segment in
            if case .widget = segment { return true }
            return false
        })
    }

    /// G6: SLIDES 路由下，解析器已放行的无 slide full_html（海报）必须被终态
    /// 校验拦下；含 slide 结构的完整 deck 正常通过。
    func testGenerativeUiTerminalPolicyRequiresSlideStructureForSlidesRoute() {
        let baseline = [message(role: .user, text: "做一份 PPT")]
        let requirement = IOSGenerativeUiRequirement(
            required: true,
            expectSlides: true,
            expectFullHtmlDeck: true
        )
        let posterHtml = #"<!DOCTYPE html><html><body><div class="poster"><h1>海报</h1><p>正文内容</p></div></body></html>"#
        let posterMessages = baseline + [message(role: .assistant, text: """
        ```show-widget
        {"title":"Poster","renderer":"full_html","spec":{"html":\(jsonStringLiteralForTest(posterHtml))}}
        ```
        """)]
        XCTAssertEqual(
            IOSGenerativeUiRequestPolicy.widgetIssue(
                in: posterMessages,
                afterDisplayMessageCount: baseline.count,
                requirement: requirement
            ),
            "expected slide sections in full_html deck"
        )

        let deckHtml = #"<!DOCTYPE html><html><body><div id="deck"><section class="slide"><h1>第一页</h1></section><section class="slide"><h1>第二页</h1></section></div></body></html>"#
        let deckMessages = baseline + [message(role: .assistant, text: """
        ```show-widget
        {"title":"Deck","renderer":"full_html","spec":{"html":\(jsonStringLiteralForTest(deckHtml))}}
        ```
        """)]
        XCTAssertNil(IOSGenerativeUiRequestPolicy.widgetIssue(
            in: deckMessages,
            afterDisplayMessageCount: baseline.count,
            requirement: requirement
        ))
    }

    /// G6: 「placeholder 真标题含 TODO 不拒」——真实标题里的 "TODO" 是内容不是占位。
    func testWidgetTitleContainingTodoIsNotRejectedAsPlaceholder() {
        let content = """
        ```show-widget
        {"title":"TODO 清单：今天要做的事","widget_code":"<svg viewBox=\\"0 0 680 240\\"><text x=\\"24\\" y=\\"48\\">TODO 清单：需求评审、方案设计、编码实现、单元测试、发布上线</text></svg>"}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)
        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.title, "TODO 清单：今天要做的事")
    }

    /// G6: 空骨架 widget_code（去标签后无可见文本）按结构化判定视为占位并拒绝。
    func testSkeletonWidgetCodeIsRejectedAsPlaceholder() {
        let content = """
        ```show-widget
        {"title":"Skeleton","widget_code":"<svg viewBox=\\"0 0 20 20\\"></svg>"}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)
        XCTAssertFalse(segments.contains { segment in
            if case .widget = segment { return true }
            return false
        })
    }

    /// G6: 含真实图形元素但无文本的极简图（两节点流程图等）不判占位。
    func testGraphicElementWithoutTextIsNotRejectedAsPlaceholder() {
        let content = """
        ```show-widget
        {"title":"Flow","widget_code":"<svg viewBox=\\"0 0 680 240\\"><rect x=\\"24\\" y=\\"24\\" width=\\"100\\" height=\\"40\\"/><rect x=\\"400\\" y=\\"24\\" width=\\"100\\" height=\\"40\\"/><line x1=\\"124\\" y1=\\"44\\" x2=\\"400\\" y2=\\"44\\"/></svg>"}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)
        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertTrue(widget.complete)
        XCTAssertEqual(
            widget.widgetCode,
            #"<svg viewBox="0 0 680 240"><rect x="24" y="24" width="100" height="40"/><rect x="400" y="24" width="100" height="40"/><line x1="124" y1="44" x2="400" y2="44"/></svg>"#
        )
    }

    /// G6: `<script>`/`<style>` 块内文不计入可见文本，不能靠脚本内文绕过
    /// 占位阈值。
    func testScriptAndStyleContentDoesNotCountTowardVisibleText() {
        let content = """
        ```show-widget
        {"title":"Skeleton","widget_code":"<svg viewBox=\\"0 0 20 20\\"><script>var payload = 'long script text that should not count as visible';</script><style>.label{color:red}</style></svg>"}
        ```
        """

        let segments = IOSGenerativeWidgetParser.parse(content, streaming: false)
        XCTAssertFalse(segments.contains { segment in
            if case .widget = segment { return true }
            return false
        })
    }

    func testActionsMatchAndroidLimitsAndSafetyRules() {
        let widgetJson = jsonString([
            "title": "Actions",
            "widget_code": #"<svg viewBox="0 0 20 20"><text>Action Menu: open, save, and summarize the key points</text></svg>"#,
            "actions": [
                ["id": "do one!", "label": " 继续   优化 ", "instruction": " 生成   三条 摘要 "],
                ["id": "two", "label": "对比", "instruction": "对比两个版本"],
                ["id": "blocked", "label": "链接", "instruction": "打开链接 https://example.com"],
                ["id": "long-label", "label": String(repeating: "长", count: 21), "instruction": "忽略"],
                ["id": "long-instruction", "label": "过长", "instruction": String(repeating: "x", count: 241)],
                ["id": "three", "label": "总结", "instruction": "总结关键点"],
                ["id": "four", "label": "第四", "instruction": "这条会被上限截断"],
            ],
        ])!
        let segments = IOSGenerativeWidgetParser.parse(
            """
            ```show-widget
            \(widgetJson)
            ```
            """,
            streaming: false
        )

        guard case .widget(let widget) = segments.single else {
            return XCTFail("Expected widget")
        }
        XCTAssertEqual(widget.actions.map(\.label), ["继续 优化", "对比", "总结"])
        XCTAssertEqual(widget.actions.map(\.instruction), ["生成 三条 摘要", "对比两个版本", "总结关键点"])
        XCTAssertEqual(widget.actions.first?.id, "do-one")
    }

    func testFullHtmlRuntimeRewritesAllowedRemoteResourcesAndBlocksRawHttps() {
        let html = """
        <!DOCTYPE html>
        <html>
        <head>
          <link rel="stylesheet" href="https://cdn.example.com/deck.css">
          <style>
            .hero { background-image: url(https://cdn.example.com/hero.png); }
            .tracker { background-image: url(https://tracker.example.com/pixel); }
          </style>
        </head>
        <body>
          <div id="deck">
            <section class="slide">
              <img src="https://cdn.example.com/photo.png">
              <img src="https://tracker.example.com/pixel">
            </section>
          </div>
        </body>
        </html>
        """
        let prepared = IOSGuizangHtmlDeckValidator.prepareRuntimeHtml(
            IOSGuizangHtmlDeckValidator.DeckSpec(
                html: html,
                allowRemoteImages: true,
                allowRemoteFonts: true
            )
        )

        XCTAssertTrue(prepared.contains("amber-full-html-resource://image/"))
        XCTAssertTrue(prepared.contains("amber-full-html-resource://stylesheet/"))
        XCTAssertFalse(prepared.contains("https://cdn.example.com/photo.png"))
        XCTAssertFalse(prepared.contains("https://cdn.example.com/deck.css"))
        XCTAssertFalse(prepared.contains("https://tracker.example.com/pixel"))
        XCTAssertFalse(prepared.contains("img-src https:"))

        let imageBlocked = IOSGuizangHtmlDeckValidator.prepareRuntimeHtml(
            IOSGuizangHtmlDeckValidator.DeckSpec(
                html: html,
                allowRemoteImages: false,
                allowRemoteFonts: true
            )
        )
        XCTAssertFalse(imageBlocked.contains("amber-full-html-resource://image/"))
    }

    func testFullHtmlRuntimeAcceptsSharedProtocolAssetURLs() {
        let html = """
        <!DOCTYPE html><html><body>
        <div id="deck"><section class="slide">A</section></div>
        <script src="https://amberagent.local/full-html/lucide.min.js"></script>
        <script type="module">await import('https://amberagent.local/full-html/motion.min.js')</script>
        </body></html>
        """

        let prepared = IOSGuizangHtmlDeckValidator.prepareRuntimeHtml(html)

        XCTAssertTrue(IOSGuizangHtmlDeckValidator.validateHtml(html).valid)
        XCTAssertTrue(prepared.contains(IOSGuizangHtmlDeckValidator.localLucideURL))
        XCTAssertTrue(prepared.contains(IOSGuizangHtmlDeckValidator.localMotionURL))
        XCTAssertFalse(prepared.contains("https://amberagent.local/full-html/"))
    }

    func testSanitizerRemovesDangerousContent() {
        let result = IOSGenerativeWidgetSanitizer.sanitize(
            """
            <div onclick="alert(1)" style="position:fixed;background:url(https://example.com/bg.png)">
              <script>alert(1)</script>
              <iframe src="https://example.com"></iframe>
              <img src="https://example.com/chart.png">
              <a href="javascript:alert(1)">open</a>
              <svg onload="alert(1)"></svg>
            </div>
            """
        )

        XCTAssertEqual(result.status, .ready)
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("script"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("onclick"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("onload"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("iframe"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("javascript:"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("https://example.com/chart.png"))
        XCTAssertFalse(result.html.localizedCaseInsensitiveContains("position:fixed"))
    }

    func testGenerativeUiTerminalPolicyRejectsMissingRequiredWidget() {
        let baseline = [message(role: .user, text: "画一个流程图")]
        let messages = baseline + [message(role: .assistant, text: "这里是文字版流程。")]

        let issue = IOSGenerativeUiRequestPolicy.widgetIssue(
            in: messages,
            afterDisplayMessageCount: baseline.count,
            requirement: IOSGenerativeUiRequirement(
                required: true,
                expectSlides: false,
                expectFullHtmlDeck: false
            )
        )

        XCTAssertEqual(issue, "missing required complete show-widget")
    }

    func testGenerativeUiTerminalPolicyAcceptsCompleteStreamedSvgWidget() {
        let baseline = [message(role: .user, text: "画一个流程图")]
        let messages = baseline + [message(
            role: .assistant,
            text: """
            ```show-widget
            {"title":"Flow","widget_code":"<svg viewBox=\\"0 0 680 180\\"><rect x=\\"24\\" y=\\"24\\" width=\\"632\\" height=\\"132\\"/><text x=\\"48\\" y=\\"60\\">Flow Chart: Collect requirements, design, develop, test, release</text></svg>"}
            ```
            """
        )]

        XCTAssertNil(IOSGenerativeUiRequestPolicy.widgetIssue(
            in: messages,
            afterDisplayMessageCount: baseline.count,
            requirement: IOSGenerativeUiRequirement(
                required: true,
                expectSlides: false,
                expectFullHtmlDeck: false
            )
        ))
    }

    func testGenerativeUiTerminalPolicyDoesNotAcceptSvgForDeckRequirement() {
        let baseline = [message(role: .user, text: "做一份 PPT")]
        let messages = baseline + [message(
            role: .assistant,
            text: """
            ```show-widget
            {"title":"Deck","widget_code":"<svg viewBox=\\"0 0 680 180\\"><text x=\\"24\\" y=\\"48\\">Quarterly deck with roadmap, milestones, metrics, risks, and next steps</text></svg>"}
            ```
            """
        )]

        let issue = IOSGenerativeUiRequestPolicy.widgetIssue(
            in: messages,
            afterDisplayMessageCount: baseline.count,
            requirement: IOSGenerativeUiRequirement(
                required: true,
                expectSlides: true,
                expectFullHtmlDeck: true
            )
        )

        XCTAssertEqual(issue, "expected renderer \"full_html\"")
    }

    /// G6: 重试改「追加修复」——保留原草稿（含已完成的工具轮），只追加一条
    /// 可见的「正在补绘」通知，第二轮输出作为新 assistant 消息接续。
    func testGenerativeUiRetryKeepsDraftAndAppendsRepairNotice() {
        let tool = UIMessagePart.Tool(
            toolCallId: "search-1",
            toolName: "search_web",
            input: #"{"query":"Amber"}"#,
            output: [UIMessagePart.Text(text: "SEARCH_RESULT", metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let toolMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [tool],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let failedWidgetResponse = message(role: .assistant, text: "这里是文字版流程。")
        let original = [
            message(role: .user, text: "先搜索再画图"),
            toolMessage,
            failedWidgetResponse,
        ]

        let retryBase = IOSGenerativeUiRequestPolicy.retryBaseMessages(original)

        // 草稿原样保留，只追加通知
        XCTAssertEqual(retryBase.count, original.count + 1)
        XCTAssertEqual(retryBase.map(\.role), [MessageRole.user, .assistant, .assistant, .assistant])
        XCTAssertEqual(retryBase[retryBase.count - 2].toText(), "这里是文字版流程。")
        XCTAssertTrue(retryBase.last?.toText().contains("补绘") ?? false)
        XCTAssertTrue(retryBase[1].parts.contains { part in
            guard let tool = part as? UIMessagePart.Tool else { return false }
            return tool.output.contains { ($0 as? UIMessagePart.Text)?.text == "SEARCH_RESULT" }
        })
    }

    /// G6: 重试不再剥工具、不强制 reasoning off——修复轮是普通续跑。
    func testGenerativeUiRetryParamsKeepToolsAndReasoning() {
        let model = Model(
            modelId: "test-model",
            displayName: "Test Model",
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
            tools: [ToolKt.createSearchWebToolDeclaration()],
            reasoningLevel: ReasoningLevel.auto_,
            customHeaders: [],
            customBody: []
        )

        let retried = IOSGenerativeUiRequestPolicy.retryParams(params)

        XCTAssertEqual(retried.tools.map(\.name), ["search_web"])
        XCTAssertEqual(retried.reasoningLevel, ReasoningLevel.auto_)
    }

    /// G6: 补绘轮二次失败收口——notice 被替换为中性失败说明，不残留「请稍候」，
    /// 且不新增消息（最多一次重试，不再发起第三轮）。
    func testGenerativeUiRetryTerminalFailureClosesRepairNotice() throws {
        let baseline = [message(role: .user, text: "画一个流程图")]
        let retryBase = IOSGenerativeUiRequestPolicy.retryBaseMessages(baseline)
        let notice = try XCTUnwrap(retryBase.last)
        XCTAssertTrue(notice.toText().contains("请稍候"))

        // 第二轮仍只产出文字版回答（没有完整 widget）
        let failedSecondRound = retryBase + [message(role: .assistant, text: "第二轮仍然只有文字。")]
        let closed = IOSGenerativeUiRequestPolicy.terminalRepairFailureMessages(
            failedSecondRound,
            afterDisplayMessageCount: retryBase.count
        )
        XCTAssertEqual(closed.count, failedSecondRound.count)
        let closedNotice = closed[retryBase.count - 1].toText()
        XCTAssertFalse(closedNotice.contains("请稍候"))
        XCTAssertTrue(closedNotice.contains("可视化未能生成"))
        XCTAssertEqual(closed[0].toText(), "画一个流程图")

        // baseline 边界不符（指向的不是 notice）时不误伤
        let unchanged = IOSGenerativeUiRequestPolicy.terminalRepairFailureMessages(
            failedSecondRound,
            afterDisplayMessageCount: failedSecondRound.count
        )
        XCTAssertEqual(unchanged.last?.toText(), "第二轮仍然只有文字。")
    }

    @MainActor
    func testBackgroundRetryDisplayKeepsDraftAndNoticeAcrossColdCheckpoint() throws {
        let runtimeOnly = message(role: .system, text: "runtime-only context")
        let user = message(role: .user, text: "画一个流程图")
        let draft = message(role: .assistant, text: "第一轮文字草稿")
        let uploadPrefix = [runtimeOnly, user]
        let retryUploadBase = IOSGenerativeUiRequestPolicy.retryBaseMessages(
            uploadPrefix + [draft]
        )

        let checkpointDisplay = IOSChatBackgroundGenerationCoordinator.reconciledMessagesForTesting(
            resultMessages: retryUploadBase,
            uploadMessageCount: uploadPrefix.count,
            displayMessages: [user]
        )

        XCTAssertEqual(checkpointDisplay.map { $0.toText() }, [
            "画一个流程图",
            "第一轮文字草稿",
            IOSGenerativeUiRequestPolicy.generativeUiRepairNoticeText,
        ])
        XCTAssertFalse(checkpointDisplay.contains { $0.toText().contains("runtime-only") })

        let secondFailure = checkpointDisplay + [message(role: .assistant, text: "第二轮仍无 widget")]
        let closed = IOSGenerativeUiRequestPolicy.terminalRepairFailureMessages(secondFailure)
        XCTAssertEqual(closed[1].toText(), "第一轮文字草稿")
        XCTAssertEqual(closed[2].toText(), IOSGenerativeUiRequestPolicy.generativeUiRepairFailedText)
        XCTAssertEqual(closed.last?.toText(), "第二轮仍无 widget")
    }

    func testTerminalRepairDoesNotReplaceModelReplyThatQuotesNoticeText() {
        let notice = IOSGenerativeUiRequestPolicy.generativeUiRepairNotice()
        let quotedReply = message(
            role: .assistant,
            text: "模型复述：\(IOSGenerativeUiRequestPolicy.generativeUiRepairNoticeText)，随后继续给出真实回答。"
        )

        let closed = IOSGenerativeUiRequestPolicy.terminalRepairFailureMessages([
            message(role: .user, text: "画一个流程图"),
            notice,
            quotedReply,
        ])

        XCTAssertEqual(closed[1].toText(), IOSGenerativeUiRequestPolicy.generativeUiRepairFailedText)
        XCTAssertEqual(closed[2].toText(), quotedReply.toText())
    }

    func testBaselineTerminalRepairDoesNotReplaceQuotedNoticeReply() {
        let quotedReply = message(
            role: .assistant,
            text: "状态说明：\(IOSGenerativeUiRequestPolicy.generativeUiRepairNoticeText)，但这是模型正文。"
        )
        let messages = [
            message(role: .user, text: "画一个流程图"),
            quotedReply,
            message(role: .assistant, text: "第二轮继续回答"),
        ]

        let unchanged = IOSGenerativeUiRequestPolicy.terminalRepairFailureMessages(
            messages,
            afterDisplayMessageCount: 2
        )

        XCTAssertEqual(unchanged[1].toText(), quotedReply.toText())
    }

    func testSVGExportUsesSanitizedSVGFragmentAndSafeFilename() {
        let widget = IOSGenerativeWidget(
            id: "flow",
            title: "流程图 / v1",
            widgetCode: "<svg><text>raw</text></svg>",
            complete: true
        )
        let sanitized = IOSSanitizedGenerativeWidget(
            status: .ready,
            html: "<div class=\"widget\"><svg viewBox=\"0 0 10 10\"><rect/></svg></div>"
        )

        let artifact = IOSGenerativeWidgetSVGExport.artifact(widget: widget, sanitized: sanitized)

        XCTAssertEqual(artifact?.svg, "<svg viewBox=\"0 0 10 10\"><rect/></svg>")
        XCTAssertEqual(artifact?.filename, "流程图 - v1.svg")
    }

    func testSVGExportKeepsNestedSVGInsideOuterDocument() {
        let source = #"<div><svg viewBox="0 0 10 10"><g><svg><rect/></svg></g><circle/></svg></div>"#

        XCTAssertEqual(
            IOSGenerativeWidgetSVGExport.extractSVG(from: source),
            #"<svg viewBox="0 0 10 10"><g><svg><rect/></svg></g><circle/></svg>"#
        )
    }

    func testSVGExportOnlyAllowsCompleteReadySVGWidgets() {
        let svg = "<svg><rect/></svg>"
        let completeWidget = IOSGenerativeWidget(id: "complete", title: nil, widgetCode: svg, complete: true)
        let partialWidget = IOSGenerativeWidget(id: "partial", title: nil, widgetCode: svg, complete: false)

        XCTAssertNotNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: completeWidget,
            sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: svg)
        ))
        XCTAssertNil(
            IOSGenerativeWidgetSVGExport.artifact(
                widget: completeWidget,
                sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: "<div>wrapped</div>")
            ),
            "净化内容未保留 SVG 时不应回退原始 SVG"
        )
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: partialWidget,
            sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: svg)
        ))
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: completeWidget,
            sanitized: IOSSanitizedGenerativeWidget(status: .unsafe)
        ))
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: IOSGenerativeWidget(id: "html", title: nil, widgetCode: "<div>not svg</div>", complete: true),
            sanitized: IOSSanitizedGenerativeWidget(status: .ready, html: "<div>not svg</div>")
        ))
    }

    func testSVGExportExcludesFullHtmlAndSlidesRenderers() {
        let svg = "<svg><rect/></svg>"
        let sanitized = IOSSanitizedGenerativeWidget(status: .ready, html: svg)

        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: IOSGenerativeWidget(
                id: "full-html",
                title: "Deck cover",
                widgetCode: svg,
                complete: true,
                renderer: IOSGuizangHtmlDeckValidator.renderer
            ),
            sanitized: sanitized
        ))
        XCTAssertNil(IOSGenerativeWidgetSVGExport.artifact(
            widget: IOSGenerativeWidget(
                id: "slides",
                title: "Deck preview",
                widgetCode: svg,
                complete: true,
                renderer: "slides"
            ),
            sanitized: sanitized
        ))
    }

    func testSVGExportFilenamePreservesCaseInsensitiveExtensionAndCleansControlCharacters() {
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "Title.SVG"), "Title.SVG")
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "设计.SVG"), "设计.SVG")
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "flow\nchart\u{0007}"), "flowchart.svg")
        XCTAssertEqual(IOSGenerativeWidgetSVGExport.filename(for: "\n\u{0000}\t"), "visualization.svg")
    }

    private func message(role: MessageRole, text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func jsonStringLiteralForTest(_ value: String) -> String {
        let data = try! JSONSerialization.data(withJSONObject: [value])
        let array = String(data: data, encoding: .utf8)!
        return String(array.dropFirst().dropLast())
    }
}

private extension Array {
    var single: Element? {
        count == 1 ? self[0] : nil
    }
}
