import Foundation
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let amberBackup = UTType(exportedAs: IOSSyncBackup.mimeType, conformingTo: .zip)
}

struct IOSSyncBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.amberBackup] }
    static var writableContentTypes: [UTType] { [.amberBackup] }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw IOSSyncBackupError.emptyPayload
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

struct SyncBackupAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String

    static func success(_ message: String) -> SyncBackupAlert {
        SyncBackupAlert(title: "同步与备份", message: message)
    }

    static func error(_ message: String) -> SyncBackupAlert {
        SyncBackupAlert(title: "同步与备份", message: message)
    }
}
