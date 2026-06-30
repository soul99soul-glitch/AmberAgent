import XCTest
@testable import iosApp

// 生图重构后:独立的 IOSImageGenerationSettingsStore 已移除,apiKey/baseURL 由调用方
// (从「辅助任务 → 生图模型」所属 provider 解析后)直接传入 generate(...)。本测试对齐
// 当前 API:generate(request:apiKey:baseURL:transport:) 与 toolRequest(from:modelId:)。
@MainActor
final class IOSImageGenerationRepositoryTests: XCTestCase {
    private var generatedPaths: [String] = []

    override func tearDown() async throws {
        for path in generatedPaths {
            try? FileManager.default.removeItem(atPath: path)
        }
        generatedPaths = []
    }

    func testGenerateSavesBase64ImagesAndHistory() async throws {
        let history = isolatedHistory()
        let repository = IOSImageGenerationRepository(historyStore: history)
        let transport = MockImageTransport(responses: [
            .json(#"{"data":[{"b64_json":"aGVsbG8taW1hZ2U=","mime_type":"image/png"}]}"#)
        ])

        let record = try await repository.generate(
            request: IOSImageGenerationRequest(
                prompt: "A small amber lamp",
                model: "gpt-image-test",
                aspectRatio: .landscape,
                count: 2,
                style: "watercolor",
                source: "test"
            ),
            apiKey: "image-key",
            baseURL: "https://api.example.com/v1",
            transport: transport
        )
        generatedPaths = record.files.map(\.path)

        let request = try XCTUnwrap(transport.requests.first)
        let body = try jsonObject(try XCTUnwrap(request.httpBody))

        XCTAssertEqual(request.url?.absoluteString, "https://api.example.com/v1/images/generations")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer image-key")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/json")
        XCTAssertEqual(body["model"] as? String, "gpt-image-test")
        XCTAssertEqual(body["n"] as? Int, 2)
        XCTAssertEqual(body["size"] as? String, "1536x1024")
        XCTAssertTrue((body["prompt"] as? String)?.contains("Style: watercolor") == true)
        XCTAssertEqual(record.files.count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(record.files.first?.path)))
        XCTAssertEqual(history.records.first?.id, record.id)
    }

    func testMissingAPIKeyFailsBeforeNetwork() async {
        let repository = IOSImageGenerationRepository(historyStore: isolatedHistory())
        let transport = MockImageTransport(responses: [])

        do {
            _ = try await repository.generate(
                request: IOSImageGenerationRequest(
                    prompt: "A cat",
                    model: "gpt-image-test",
                    aspectRatio: .square,
                    count: 1,
                    style: "",
                    source: "test"
                ),
                apiKey: "",
                baseURL: "https://api.example.com/v1",
                transport: transport
            )
            XCTFail("Expected missing API key")
        } catch {
            XCTAssertEqual(error as? IOSImageGenerationError, .missingAPIKey)
            XCTAssertTrue(transport.requests.isEmpty)
        }
    }

    func testToolRequestParsesAspectCountAndStyle() throws {
        let request = try IOSImageGenerationRepository(historyStore: isolatedHistory()).toolRequest(
            from: #"{"prompt":"City at night","aspect_ratio":"9:16","count":12,"style":"cinematic"}"#,
            modelId: "gpt-image-test"
        )

        XCTAssertEqual(request.prompt, "City at night")
        XCTAssertEqual(request.model, "gpt-image-test")
        XCTAssertEqual(request.aspectRatio, .portrait)
        XCTAssertEqual(request.count, 4)
        XCTAssertEqual(request.style, "cinematic")
        XCTAssertEqual(request.source, "chat")
    }

    func testToolRequestParsesSourceImageURL() throws {
        let request = try IOSImageGenerationRepository(historyStore: isolatedHistory()).toolRequest(
            from: #"{"prompt":"Make it snowy","source_image_url":"data:image/png;base64,QUJD"}"#,
            modelId: "gpt-image-test"
        )

        XCTAssertEqual(request.prompt, "Make it snowy")
        XCTAssertEqual(request.sourceImageURL, "data:image/png;base64,QUJD")
    }

    func testCodexResponsesRequestBodyUsesCodexImageToolShape() throws {
        let body = IOSImageGenerationRepository.codexResponsesRequestBody(for: IOSImageGenerationRequest(
            prompt: "A cat",
            model: IOSCodexOAuthConstants.imageModelId,
            aspectRatio: .portrait,
            count: 4,
            style: "ink",
            source: "test"
        ))

        XCTAssertEqual(body["model"] as? String, "gpt-5.4")
        let instructions = try XCTUnwrap(body["instructions"] as? String)
        XCTAssertTrue(instructions.contains("image_generation"))
        XCTAssertTrue(instructions.contains("fan art"))
        XCTAssertEqual(body["store"] as? Bool, false)
        XCTAssertEqual(body["stream"] as? Bool, true)
        XCTAssertEqual(body["tool_choice"] as? String, "required")

        let tools = try XCTUnwrap(body["tools"] as? [[String: Any]])
        let tool = try XCTUnwrap(tools.first)
        XCTAssertEqual(tool["type"] as? String, "image_generation")
        XCTAssertEqual(tool["size"] as? String, "1024x1536")
        XCTAssertEqual(tool["quality"] as? String, "high")
        XCTAssertEqual(tool["moderation"] as? String, "low")
        XCTAssertNil(tool["model"])

        let input = try XCTUnwrap(body["input"] as? [[String: Any]])
        let firstInput = try XCTUnwrap(input.first)
        XCTAssertEqual(firstInput["role"] as? String, "user")
        let content = try XCTUnwrap(firstInput["content"] as? [[String: Any]])
        XCTAssertEqual(content.first?["type"] as? String, "input_text")
        XCTAssertEqual(content.first?["text"] as? String, "A cat\nStyle: ink")
    }

    func testCodexResponsesRequestBodyIncludesSourceImageForEdits() throws {
        let body = IOSImageGenerationRepository.codexResponsesRequestBody(for: IOSImageGenerationRequest(
            prompt: "Make the sky orange",
            model: IOSCodexOAuthConstants.imageModelId,
            aspectRatio: .square,
            count: 1,
            style: "",
            source: "test",
            sourceImageURL: "data:image/png;base64,QUJD"
        ))

        let input = try XCTUnwrap(body["input"] as? [[String: Any]])
        let firstInput = try XCTUnwrap(input.first)
        let content = try XCTUnwrap(firstInput["content"] as? [[String: Any]])

        XCTAssertEqual(content.count, 2)
        XCTAssertEqual(content[0]["type"] as? String, "input_text")
        XCTAssertEqual(content[0]["text"] as? String, "Make the sky orange")
        XCTAssertEqual(content[1]["type"] as? String, "input_image")
        XCTAssertEqual(content[1]["image_url"] as? String, "data:image/png;base64,QUJD")
    }

    func testCodexResponsesRequestBodyRewritesZeldaMarkersForOriginalImage() throws {
        let body = IOSImageGenerationRepository.codexResponsesRequestBody(for: IOSImageGenerationRequest(
            prompt: "A heroic young Hylian adventurer like Link from The Legend of Zelda in Breath of the Wild style",
            model: IOSCodexOAuthConstants.imageModelId,
            aspectRatio: .landscape,
            count: 1,
            style: "",
            source: "test"
        ))

        let input = try XCTUnwrap(body["input"] as? [[String: Any]])
        let firstInput = try XCTUnwrap(input.first)
        let content = try XCTUnwrap(firstInput["content"] as? [[String: Any]])
        let text = try XCTUnwrap(content.first?["text"] as? String)
        XCTAssertTrue(text.contains("original"))
        XCTAssertFalse(text.contains("Hylian"))
        XCTAssertFalse(text.contains("Link"))
        XCTAssertFalse(text.contains("The Legend of Zelda"))
        XCTAssertFalse(text.contains("Breath of the Wild"))
    }

    func testCodexImageExtractionParsesSSEOutputItemDone() {
        let sse = """
        event: response.output_item.done
        data: {"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig_1","status":"completed","result":"data:image/png;base64,QUJD"}}

        data: [DONE]

        """

        let extraction = IOSImageGenerationRepository.codexImageExtraction(from: Data(sse.utf8))

        XCTAssertEqual(extraction.images, ["data:image/png;base64,QUJD"])
        XCTAssertNil(extraction.failureReason)
    }

    func testCodexImageExtractionKeepsFailureReasonWhenNoImageResult() {
        let sse = """
        event: response.completed
        data: {"type":"response.completed","response":{"status":"failed","error":{"message":"Image generation refused by backend"}}}

        data: [DONE]

        """

        let extraction = IOSImageGenerationRepository.codexImageExtraction(from: Data(sse.utf8))

        XCTAssertTrue(extraction.images.isEmpty)
        XCTAssertEqual(extraction.failureReason, "Image generation refused by backend")
    }

    func testCodexImageResultResolvesStreamingOutputItemDone() async throws {
        let stream = AsyncThrowingStream<IOSImageGenerationSSEEvent, Error> { continuation in
            continuation.yield(IOSImageGenerationSSEEvent(
                event: "response.output_item.done",
                data: #"{"type":"response.output_item.done","item":{"type":"image_generation_call","id":"ig_1","status":"completed","result":"data:image/png;base64,QUJD"}}"#
            ))
            continuation.yield(IOSImageGenerationSSEEvent(event: nil, data: "[DONE]"))
            continuation.finish()
        }
        let repository = IOSImageGenerationRepository(historyStore: isolatedHistory())

        let image = try await repository.resolveCodexImageResult(fromEvents: stream)

        XCTAssertEqual(image, "data:image/png;base64,QUJD")
    }

    func testStreamEventsPreservesNonSSERawBody() async throws {
        let transport = MockImageTransport(responses: [
            .json(#"{"detail":"Not Found"}"#, status: 404)
        ])
        let request = URLRequest(url: URL(string: "https://example.com/responses")!)

        let (response, stream) = try await transport.streamEvents(request)
        var payloads: [String] = []
        for try await event in stream {
            payloads.append(event.data)
        }

        XCTAssertEqual(response.statusCode, 404)
        XCTAssertEqual(payloads, [#"{"detail":"Not Found"}"#])
    }

    func testCodexImageResultPollsInProgressResponse() async throws {
        let initial = """
        event: response.completed
        data: {"type":"response.completed","response":{"id":"resp_123","status":"in_progress"}}

        data: [DONE]

        """
        let transport = MockImageTransport(responses: [
            .json(#"{"id":"resp_123","status":"completed","output":[{"type":"image_generation_call","result":"data:image/png;base64,QUJD"}]}"#)
        ])
        let repository = IOSImageGenerationRepository(historyStore: isolatedHistory())

        let image = try await repository.resolveCodexImageResult(
            from: Data(initial.utf8),
            token: "codex-token",
            accountId: "account-1",
            transport: transport,
            maxPollAttempts: 1,
            pollDelayNanoseconds: 0
        )

        XCTAssertEqual(image, "data:image/png;base64,QUJD")
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(transport.requests.first?.url?.absoluteString, "https://chatgpt.com/backend-api/codex/responses/resp_123")
        XCTAssertEqual(transport.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer codex-token")
    }

    func testToolResultJSONIncludesChatImageURLs() throws {
        let record = IOSImageGenerationRecord(
            id: "record-1",
            prompt: "Prompt",
            model: "gpt-image-test",
            aspectRatio: .square,
            count: 1,
            style: "",
            source: "chat",
            files: [IOSGeneratedImageFile(id: "file-1", path: "/tmp/image.png", mimeType: "image/png")],
            createdAt: 1
        )

        let text = IOSImageGenerationRepository(historyStore: isolatedHistory()).toolResultJSON(record: record)
        let payload = try XCTUnwrap(jsonObject(text.data(using: .utf8)!))

        XCTAssertEqual(payload["status"] as? String, "ok")
        XCTAssertEqual(payload["source"] as? String, "generate_image")
        let files = try XCTUnwrap(payload["files"] as? [[String: Any]])
        let file = try XCTUnwrap(files.first)
        XCTAssertEqual(file["url"] as? String, "amber-image-generation://image-generation/image.png")
        XCTAssertEqual(file["mime_type"] as? String, "image/png")
    }

    private func isolatedHistory() -> IOSImageGenerationHistoryStore {
        let suiteName = "ImageHistory-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return IOSImageGenerationHistoryStore(userDefaults: defaults, key: "history")
    }

    private func jsonObject(_ data: Data) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

@MainActor
private final class MockImageTransport: IOSImageGenerationHTTPTransport {
    struct Response {
        let status: Int
        let headers: [String: String]
        let body: Data

        static func json(_ body: String, status: Int = 200) -> Response {
            Response(status: status, headers: ["Content-Type": "application/json"], body: Data(body.utf8))
        }
    }

    private var responses: [Response]
    private(set) var requests: [URLRequest] = []

    init(responses: [Response]) {
        self.responses = responses
    }

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        requests.append(request)
        let response = responses.isEmpty ? .json("{}") : responses.removeFirst()
        let http = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: response.status,
            httpVersion: "HTTP/1.1",
            headerFields: response.headers
        )!
        return (http, response.body)
    }
}
