import XCTest
@testable import iosApp

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
        let settingsStore = makeSettingsStore(apiKey: "image-key", baseUrl: "https://api.example.com/v1")
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
            settingsStore: settingsStore,
            transport: transport
        )
        generatedPaths = record.files.map(\.path)

        let request = try XCTUnwrap(transport.requests.first)
        let body = try jsonObject(try XCTUnwrap(request.httpBody))

        XCTAssertEqual(request.url?.absoluteString, "https://api.example.com/v1/images/generations")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer image-key")
        XCTAssertEqual(body["model"] as? String, "gpt-image-test")
        XCTAssertEqual(body["n"] as? Int, 2)
        XCTAssertEqual(body["size"] as? String, "1536x1024")
        XCTAssertTrue((body["prompt"] as? String)?.contains("Style: watercolor") == true)
        XCTAssertEqual(record.files.count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(record.files.first?.path)))
        XCTAssertEqual(history.records.first?.id, record.id)
    }

    func testMissingAPIKeyFailsBeforeNetwork() async {
        let settingsStore = makeSettingsStore(apiKey: "", baseUrl: "https://api.example.com/v1")
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
                settingsStore: settingsStore,
                transport: transport
            )
            XCTFail("Expected missing API key")
        } catch {
            XCTAssertEqual(error as? IOSImageGenerationError, .missingAPIKey)
            XCTAssertTrue(transport.requests.isEmpty)
        }
    }

    func testToolRequestParsesAspectCountAndStyle() throws {
        let suiteName = "ImageSettings-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        let settings = IOSImageGenerationSettingsStore(userDefaults: defaults, key: "settings")
        settings.model = "gpt-image-test"
        let request = try IOSImageGenerationRepository(historyStore: isolatedHistory()).toolRequest(
            from: #"{"prompt":"City at night","aspect_ratio":"9:16","count":12,"style":"cinematic"}"#,
            settings: settings
        )

        XCTAssertEqual(request.prompt, "City at night")
        XCTAssertEqual(request.aspectRatio, .portrait)
        XCTAssertEqual(request.count, 4)
        XCTAssertEqual(request.style, "cinematic")
        XCTAssertEqual(request.source, "chat")
    }

    func testToolResultJSONIncludesFileURLs() throws {
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
        XCTAssertTrue(text.contains("file:///tmp/image.png"))
    }

    private func makeSettingsStore(apiKey: String, baseUrl: String) -> SettingsStore {
        let suiteName = "ImageSettingsStore-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        let store = SettingsStore(
            userDefaults: defaults,
            storageKey: "settings",
            apiKeyStore: FakeSettingsAPIKeyStore(key: apiKey)
        )
        store.baseUrl = baseUrl
        return store
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

private final class FakeSettingsAPIKeyStore: SettingsAPIKeyStore {
    private var key: String

    init(key: String) {
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
