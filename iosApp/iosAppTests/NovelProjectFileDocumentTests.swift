import Foundation
import SwiftUI
import UniformTypeIdentifiers
import XCTest
@testable import iosApp

final class NovelProjectFileDocumentTests: XCTestCase {
    private let identifier = "app.amber.ios.novel-project"
    private let filenameExtension = "ambernovel"
    private let mimeType = "application/vnd.amberagent.novel+json"

    func testFileDocumentRoundTripPreservesPackageBytes() throws {
        let data = Data("{\"package\":\"exact bytes\"}".utf8)
        let document = NovelProjectFileDocument(data: data)

        let wrapper = try document.fileWrapper(configuration: writeConfiguration())
        let restored = try NovelProjectFileDocument(
            configuration: readConfiguration(file: wrapper)
        )

        XCTAssertEqual(wrapper.regularFileContents, data)
        XCTAssertEqual(restored.data, data)
        XCTAssertEqual(NovelProjectFileDocument.readableContentTypes, [.amberNovelProject])
        XCTAssertEqual(NovelProjectFileDocument.writableContentTypes, [.amberNovelProject])
    }

    func testFileDocumentRejectsDirectoryAndOversizedReadAndWrite() throws {
        let directory = FileWrapper(directoryWithFileWrappers: [:])
        XCTAssertThrowsError(try NovelProjectFileDocument(
            configuration: readConfiguration(file: directory)
        )) { error in
            guard case .invalidPackage = error as? NovelError else {
                return XCTFail("Expected invalidPackage, got \(error)")
            }
        }

        let maximum = NovelProjectPackageLimits.standard.maximumEnvelopeBytes
        let oversized = Data(count: maximum + 1)
        let oversizedDocument = NovelProjectFileDocument(data: oversized)
        XCTAssertThrowsError(try oversizedDocument.fileWrapper(
            configuration: writeConfiguration()
        )) { error in
            XCTAssertEqual(
                error as? NovelError,
                .packageTooLarge(maximumBytes: maximum)
            )
        }

        let oversizedWrapper = FileWrapper(regularFileWithContents: oversized)
        XCTAssertThrowsError(try NovelProjectFileDocument(
            configuration: readConfiguration(file: oversizedWrapper)
        )) { error in
            XCTAssertEqual(
                error as? NovelError,
                .packageTooLarge(maximumBytes: maximum)
            )
        }
    }

    func testFileReaderReadsCompleteRegularFileAtLimit() throws {
        let directory = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("project.ambernovel")
        let data = Data("12345678".utf8)
        try data.write(to: url, options: .atomic)
        let limits = NovelProjectPackageLimits(
            maximumProjectBytes: 4,
            maximumEnvelopeBytes: data.count
        )

        let read = try NovelProjectFileReader.readPackage(from: url, limits: limits)

        XCTAssertEqual(read, data)
    }

    func testFileReaderRejectsMissingAndNonRegularFiles() throws {
        let directory = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let missing = directory.appendingPathComponent("missing.ambernovel")

        XCTAssertThrowsError(try NovelProjectFileReader.readPackage(from: missing)) { error in
            guard case .invalidPackage = error as? NovelError else {
                return XCTFail("Expected invalidPackage, got \(error)")
            }
        }
        XCTAssertThrowsError(try NovelProjectFileReader.readPackage(from: directory)) { error in
            guard case .invalidPackage = error as? NovelError else {
                return XCTFail("Expected invalidPackage, got \(error)")
            }
        }
    }

    func testFileReaderEnforcesEnvelopeLimitAndRejectsInvalidLimits() throws {
        let directory = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("large.ambernovel")
        try Data("123456789".utf8).write(to: url, options: .atomic)
        let limits = NovelProjectPackageLimits(
            maximumProjectBytes: 4,
            maximumEnvelopeBytes: 8
        )

        XCTAssertThrowsError(try NovelProjectFileReader.readPackage(
            from: url,
            limits: limits
        )) { error in
            XCTAssertEqual(error as? NovelError, .packageTooLarge(maximumBytes: 8))
        }

        let invalidLimits = NovelProjectPackageLimits(
            maximumProjectBytes: 9,
            maximumEnvelopeBytes: 8
        )
        XCTAssertThrowsError(try NovelProjectFileReader.readPackage(
            from: url,
            limits: invalidLimits
        )) { error in
            guard case .invalidInput = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
        }
    }

    func testSwiftUTTypeUsesStableIdentifierAndDataConformance() {
        XCTAssertEqual(UTType.amberNovelProject.identifier, identifier)
        XCTAssertTrue(UTType.amberNovelProject.conforms(to: .data))
        XCTAssertFalse(UTType.amberNovelProject.isDynamic)
    }

    func testSourceInfoPlistRegistersExportedAndEditableNovelDocumentType() throws {
        let plistURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("iosApp/Info.plist")
        let plist = try plistDictionary(at: plistURL)

        try assertNovelDocumentRegistration(in: plist)
    }

    func testHostedStableAppProcessedInfoPlistKeepsNovelDocumentRegistration() throws {
        let plist = try XCTUnwrap(
            Bundle.main.infoDictionary,
            "The hosted iosApp test must expose its processed Info.plist."
        )

        try assertNovelDocumentRegistration(in: plist)
    }
}

private extension NovelProjectFileDocumentTests {
    // SwiftUI exposes these configuration values but not public initializers.
    // Matching their two-field ABI lets the test exercise the real FileDocument entry points.
    func readConfiguration(file: FileWrapper) -> FileDocumentReadConfiguration {
        precondition(
            MemoryLayout<FileDocumentReadConfiguration>.size ==
                MemoryLayout<(UTType, FileWrapper)>.size
        )
        return unsafeBitCast(
            (UTType.amberNovelProject, file),
            to: FileDocumentReadConfiguration.self
        )
    }

    func writeConfiguration() -> FileDocumentWriteConfiguration {
        precondition(
            MemoryLayout<FileDocumentWriteConfiguration>.size ==
                MemoryLayout<(UTType, FileWrapper?)>.size
        )
        return unsafeBitCast(
            (UTType.amberNovelProject, Optional<FileWrapper>.none),
            to: FileDocumentWriteConfiguration.self
        )
    }

    func plistDictionary(at url: URL) throws -> [String: Any] {
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(
            PropertyListSerialization.propertyList(
                from: data,
                options: [],
                format: nil
            ) as? [String: Any]
        )
    }

    func assertNovelDocumentRegistration(
        in plist: [String: Any],
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let exportedTypes = try XCTUnwrap(
            plist["UTExportedTypeDeclarations"] as? [[String: Any]],
            file: file,
            line: line
        )
        let declaration = try XCTUnwrap(
            exportedTypes.first { $0["UTTypeIdentifier"] as? String == identifier },
            file: file,
            line: line
        )
        XCTAssertEqual(
            declaration["UTTypeConformsTo"] as? [String],
            [UTType.data.identifier],
            file: file,
            line: line
        )
        let tags = try XCTUnwrap(
            declaration["UTTypeTagSpecification"] as? [String: Any],
            file: file,
            line: line
        )
        XCTAssertEqual(
            tags["public.filename-extension"] as? [String],
            [filenameExtension],
            file: file,
            line: line
        )
        XCTAssertEqual(
            tags["public.mime-type"] as? String,
            mimeType,
            file: file,
            line: line
        )

        let documentTypes = try XCTUnwrap(
            plist["CFBundleDocumentTypes"] as? [[String: Any]],
            file: file,
            line: line
        )
        let documentType = try XCTUnwrap(
            documentTypes.first {
                ($0["LSItemContentTypes"] as? [String])?.contains(identifier) == true
            },
            file: file,
            line: line
        )
        XCTAssertEqual(
            documentType["CFBundleTypeRole"] as? String,
            "Editor",
            file: file,
            line: line
        )
        XCTAssertEqual(
            plist["LSSupportsOpeningDocumentsInPlace"] as? Bool,
            false,
            file: file,
            line: line
        )
    }
}
