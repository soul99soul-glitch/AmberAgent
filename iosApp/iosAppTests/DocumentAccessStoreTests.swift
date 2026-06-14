import XCTest
@testable import iosApp

@MainActor
final class DocumentAccessStoreTests: XCTestCase {
    func testExpiredGrantIsRejected() async throws {
        let store = DocumentAccessStore()
        let createdAt = Date(timeIntervalSince1970: 100)
        let grant = store.registerPickedFile(try makeTempFile(size: 16), now: createdAt)
        let executor = makeExecutor(documentStore: store)
        let request = IOSToolInvocationRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: request.toolName,
                operation: request.operation,
                scopeDigest: request.scopeDigest,
                payloadDigest: request.payloadDigest,
                isUserInitiated: request.isUserInitiated
            ),
            now: createdAt.addingTimeInterval(store.ttlSeconds + 1)
        )

        guard case .denied(let reason) = result else {
            return XCTFail("Expected denied, got \(result)")
        }
        XCTAssertTrue(reason.contains("expired"))
    }

    func testFilesLargerThanTwoMegabytesAreRejected() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeTempFile(size: Int(store.maxReadableBytes + 1)))
        let executor = makeExecutor(documentStore: store)
        let request = IOSLocalToolExecutionRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let result = await executor.execute(request)

        guard case .denied(let reason) = result else {
            return XCTFail("Expected denied, got \(result)")
        }
        XCTAssertTrue(reason.contains("larger than 2 MB"))
    }

    func testPreviewIsCappedAtSixtyFourKilobytes() async throws {
        let store = DocumentAccessStore()
        let grant = store.registerPickedFile(try makeTempFile(size: store.maxPreviewBytes + 4096))
        let executor = makeExecutor(documentStore: store)
        let request = IOSLocalToolExecutionRequest(
            toolName: grant.toolName,
            operation: grant.operation,
            scopeDigest: grant.scopeDigest,
            payloadDigest: grant.payloadDigest,
            isUserInitiated: true
        )

        let result = await executor.execute(request)

        guard case .selectedFilePreview(let readResult) = result else {
            return XCTFail("Expected success, got \(result)")
        }
        XCTAssertLessThanOrEqual(readResult.bytesRead, store.maxPreviewBytes)
        XCTAssertEqual(readResult.bytesRead, store.maxPreviewBytes)
    }

    func testFileSizeIsCheckedAgainAtReadTime() async throws {
        let store = DocumentAccessStore()
        let url = try makeTempFile(size: 16)
        let grant = store.registerPickedFile(url)
        let executor = makeExecutor(documentStore: store)
        try Data(repeating: 65, count: Int(store.maxReadableBytes + 1)).write(to: url)

        let result = await executor.execute(
            IOSLocalToolExecutionRequest(
                toolName: grant.toolName,
                operation: grant.operation,
                scopeDigest: grant.scopeDigest,
                payloadDigest: grant.payloadDigest,
                isUserInitiated: true
            )
        )

        guard case .failed(let message) = result else {
            return XCTFail("Expected failed, got \(result)")
        }
        XCTAssertTrue(message.contains("larger"))
    }

    private func makeExecutor(documentStore: DocumentAccessStore) -> IOSLocalToolExecutor {
        IOSLocalToolExecutor(
            permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
            documentStore: documentStore
        )
    }

    private func makeTempFile(size: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try Data(repeating: 65, count: size).write(to: url)
        return url
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
