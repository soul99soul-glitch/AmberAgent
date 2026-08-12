import Foundation

/// 后台任务持久文件统一使用的 requestId 净化规则。
enum IOSChatBackgroundJobFileNaming {
    static func sanitized(_ requestId: String) -> String {
        String(requestId.map { character -> Character in
            character.isLetter || character.isNumber || character == "." || character == "-"
                ? character
                : "-"
        })
    }
}
