import Foundation
import Shared

let rootPath = "/private/tmp/amber-ios-production-conversation-fixture"
let fileManager = FileManager.default
try? fileManager.removeItem(atPath: rootPath)

let root = ConversationFile(path: rootPath)
let storage = JsonConversationStorage(baseDir: root)
let conversationId = KotlinUuid.companion.parse(uuidString: "11111111-1111-1111-1111-111111111111")
let assistantId = KotlinUuid.companion.parse(uuidString: "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
let message = UIMessage.companion.user(prompt: "production codec fixture")
let node = MessageNode.companion.of(message: message)
let conversation = Conversation.companion.ofId(
    id: conversationId,
    assistantId: assistantId,
    messages: [node],
    newConversation: false
)

let semaphore = DispatchSemaphore(value: 0)
var saveError: Error?
storage.saveConversation(conversation: conversation) { _, error in
    saveError = error
    semaphore.signal()
}
semaphore.wait()
if let saveError {
    fputs("save failed: \(saveError)\n", stderr)
    exit(1)
}

let document = ConversationFile(path: rootPath).child(name: "11111111-1111-1111-1111-111111111111.json").readText() ?? ""
print("saved-by-ios-production-codec")
print(document)

let importedRootPath = "/private/tmp/amber-ios-production-conversation-import"
try? fileManager.removeItem(atPath: importedRootPath)
let importedStorage = JsonConversationStorage(baseDir: ConversationFile(path: importedRootPath))
let importSemaphore = DispatchSemaphore(value: 0)
var importError: Error?
importedStorage.importConversations(serializedConversations: [document]) { error in
    importError = error
    importSemaphore.signal()
}
importSemaphore.wait()
if let importError {
    fputs("import failed: \(importError)\n", stderr)
    exit(1)
}
let importedDocument = ConversationFile(path: importedRootPath).child(name: "11111111-1111-1111-1111-111111111111.json").readText() ?? ""
print("reread-by-ios-production-codec")
print(importedDocument)
