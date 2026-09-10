import Foundation
import Shared

let arguments = CommandLine.arguments
let encoded = arguments.dropFirst().first ?? ""
guard let payload = Data(base64Encoded: encoded),
      let document = String(data: payload, encoding: .utf8),
      !document.isEmpty else {
    fputs("missing base64 JSON payload\n", stderr)
    exit(2)
}

let rootPath = "/private/tmp/amber-ios-production-conversation-import-from-android"
let fileManager = FileManager.default
try? fileManager.removeItem(atPath: rootPath)
let storage = JsonConversationStorage(baseDir: ConversationFile(path: rootPath))
let semaphore = DispatchSemaphore(value: 0)
var importError: Error?
storage.importConversations(serializedConversations: [document]) { error in
    importError = error
    semaphore.signal()
}
semaphore.wait()
if let importError {
    fputs("import failed: \(importError)\n", stderr)
    exit(1)
}
let imported = ConversationFile(path: rootPath)
    .child(name: "11111111-1111-1111-1111-111111111111.json")
    .readText() ?? ""
print("android-export-imported-by-ios-production-codec")
print(imported)
