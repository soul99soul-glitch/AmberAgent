import Foundation
import ActivityKit

struct AgentActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var presentation: AgentActivityPresentation
    }

    let runId: String
    let startedAt: Date
}

struct AgentActivityPresentation: Codable, Hashable {
    var statusText: String
    var toolTitle: String
    var phase: AgentActivityPhase
    var steps: [AgentActivityStep]

    init(
        statusText: String,
        toolTitle: String,
        phase: AgentActivityPhase,
        steps: [AgentActivityStep]
    ) {
        self.statusText = Self.sanitizedStatus(statusText)
        self.toolTitle = Self.sanitizedToolTitle(toolTitle)
        self.phase = phase
        self.steps = Self.normalizedSteps(steps)
    }
}

enum AgentActivityPhase: String, Codable, Hashable {
    case running
    case waitingForUser
    case completed
    case failed
    case cancelled
}

struct AgentActivityStep: Codable, Hashable, Identifiable {
    var id: String
    var title: String
    var state: AgentActivityStepState

    init(id: String = UUID().uuidString, title: String, state: AgentActivityStepState) {
        self.id = id
        self.title = AgentActivityPresentation.sanitizedStepTitle(title)
        self.state = state
    }
}

enum AgentActivityStepState: String, Codable, Hashable {
    case done
    case current
    case pending
    case failed

    var marker: String {
        switch self {
        case .done:
            "✓"
        case .current:
            "●"
        case .pending:
            "○"
        case .failed:
            "!"
        }
    }
}

extension AgentActivityPresentation {
    static let defaultRunning = AgentActivityPresentation(
        statusText: "Amber 正在阅读 Apple 文档",
        toolTitle: "网页搜索",
        phase: .running,
        steps: [
            AgentActivityStep(id: "search", title: "搜索 ActivityKit", state: .done),
            AgentActivityStep(id: "read", title: "阅读 Apple 文档", state: .current),
            AgentActivityStep(id: "draft", title: "生成适配方案", state: .pending)
        ]
    )

    static func generatingResponse(modelName: String) -> AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 正在生成回复",
            toolTitle: "生成回复",
            phase: .running,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备上下文", state: .done),
                AgentActivityStep(id: "generate", title: "生成回复", state: .current),
                AgentActivityStep(id: "finish", title: "整理结果", state: .pending)
            ]
        )
    }

    static func waitingForUser(toolTitle: String = "终端命令") -> AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 需要你确认",
            toolTitle: toolTitle,
            phase: .waitingForUser,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备命令", state: .done),
                AgentActivityStep(id: "confirm", title: "等待确认", state: .current),
                AgentActivityStep(id: "execute", title: "执行任务", state: .pending)
            ]
        )
    }

    static var readingSelectedFile: AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 正在读取文件",
            toolTitle: "文档读取",
            phase: .running,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备文件", state: .done),
                AgentActivityStep(id: "read", title: "读取文件", state: .current),
                AgentActivityStep(id: "preview", title: "整理预览", state: .pending)
            ]
        )
    }

    static var selectedFileReadCompleted: AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 已完成处理",
            toolTitle: "文档读取",
            phase: .completed,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备文件", state: .done),
                AgentActivityStep(id: "read", title: "读取文件", state: .done),
                AgentActivityStep(id: "preview", title: "整理预览", state: .done)
            ]
        )
    }

    static var selectedFileReadFailed: AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 遇到问题",
            toolTitle: "文档读取",
            phase: .failed,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备文件", state: .done),
                AgentActivityStep(id: "read", title: "读取失败", state: .failed),
                AgentActivityStep(id: "return", title: "返回应用查看", state: .pending)
            ]
        )
    }

    static var selectedFileReadWaitingForUser: AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 需要你确认",
            toolTitle: "文档读取",
            phase: .waitingForUser,
            steps: [
                AgentActivityStep(id: "choose", title: "选择文件", state: .current),
                AgentActivityStep(id: "read", title: "读取文件", state: .pending),
                AgentActivityStep(id: "preview", title: "整理预览", state: .pending)
            ]
        )
    }

    static func completed(toolTitle: String = "生成回复") -> AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 已完成处理",
            toolTitle: toolTitle,
            phase: .completed,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备上下文", state: .done),
                AgentActivityStep(id: "generate", title: "生成回复", state: .done),
                AgentActivityStep(id: "finish", title: "整理结果", state: .done)
            ]
        )
    }

    static func failed(toolTitle: String = "生成回复") -> AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 遇到问题",
            toolTitle: toolTitle,
            phase: .failed,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备上下文", state: .done),
                AgentActivityStep(id: "fail", title: "生成失败", state: .failed),
                AgentActivityStep(id: "return", title: "返回应用查看", state: .pending)
            ]
        )
    }

    static func cancelled(toolTitle: String = "生成回复") -> AgentActivityPresentation {
        AgentActivityPresentation(
            statusText: "Amber 已停止处理",
            toolTitle: toolTitle,
            phase: .cancelled,
            steps: [
                AgentActivityStep(id: "prepare", title: "准备上下文", state: .done),
                AgentActivityStep(id: "stop", title: "停止生成", state: .failed),
                AgentActivityStep(id: "return", title: "返回应用查看", state: .pending)
            ]
        )
    }

    static func sanitizedStatus(_ raw: String) -> String {
        sanitize(raw, fallback: "Amber 正在处理", maxLength: 18)
    }

    static func sanitizedToolTitle(_ raw: String) -> String {
        sanitize(raw, fallback: "工具执行", maxLength: 12)
    }

    static func sanitizedStepTitle(_ raw: String) -> String {
        sanitize(raw, fallback: "处理任务", maxLength: 16)
    }

    private static func normalizedSteps(_ rawSteps: [AgentActivityStep]) -> [AgentActivityStep] {
        let firstThree = Array(rawSteps.prefix(3))
        guard !firstThree.isEmpty else {
            return [
                AgentActivityStep(id: "prepare", title: "准备任务", state: .done),
                AgentActivityStep(id: "current", title: "处理任务", state: .current),
                AgentActivityStep(id: "finish", title: "整理结果", state: .pending)
            ]
        }
        return firstThree.map {
            AgentActivityStep(id: $0.id, title: $0.title, state: $0.state)
        }
    }

    private static func sanitize(_ raw: String, fallback: String, maxLength _: Int) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return fallback }

        let generic = genericPrivateSummary(for: trimmed)
        let candidate = generic ?? trimmed
        let collapsed = candidate
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")

        guard isAllowedPublicSummary(collapsed) else { return fallback }
        return collapsed
    }

    private static func isAllowedPublicSummary(_ text: String) -> Bool {
        allowedPublicSummaries.contains(text)
    }

    private static let allowedPublicSummaries: Set<String> = [
        "Amber 正在处理",
        "Amber 正在阅读 Apple 文档",
        "Amber 正在生成回复",
        "Amber 需要你确认",
        "Amber 已完成处理",
        "Amber 遇到问题",
        "Amber 已停止处理",
        "Amber 正在读取文件",
        "网页搜索",
        "工具执行",
        "生成回复",
        "终端命令",
        "文档读取",
        "搜索 ActivityKit",
        "阅读 Apple 文档",
        "生成适配方案",
        "准备上下文",
        "整理结果",
        "准备命令",
        "等待确认",
        "执行任务",
        "生成失败",
        "停止生成",
        "返回应用查看",
        "准备任务",
        "处理任务",
        "准备文件",
        "读取文件",
        "整理预览",
        "读取失败",
        "选择文件",
        "搜索资料",
        "阅读文档",
        "生成方案",
        "阅读失败",
        "处理敏感配置",
        "阅读网页",
        "处理私密信息",
        "执行终端命令"
    ]

    private static func genericPrivateSummary(for text: String) -> String? {
        let lowercased = text.lowercased()
        if lowercased.contains("/users/") ||
            lowercased.contains("/var/") ||
            lowercased.contains("/private/") ||
            lowercased.contains("~/") ||
            lowercased.contains("file://") ||
            lowercased.contains("\\") {
            return "读取文件"
        }
        if lowercased.contains("api_key") ||
            lowercased.contains("apikey") ||
            lowercased.contains("bearer ") ||
            lowercased.contains("password") ||
            lowercased.contains("secret") ||
            lowercased.contains("sk-") ||
            lowercased.contains("token=") ||
            lowercased.contains("authorization") ||
            lowercased.contains(".env") ||
            lowercased.contains(".pem") ||
            lowercased.contains(".key") {
            return "处理敏感配置"
        }
        if lowercased.contains("http://") || lowercased.contains("https://") {
            return "阅读网页"
        }
        if looksLikeEmail(lowercased) || looksLikeIPAddress(lowercased) {
            return "处理私密信息"
        }
        if lowercased.contains("rm ") ||
            lowercased.contains("sudo ") ||
            lowercased.contains("chmod ") ||
            lowercased.contains("curl ") ||
            lowercased.contains("git ") {
            return "执行终端命令"
        }
        return nil
    }

    private static func looksLikeEmail(_ text: String) -> Bool {
        text.contains("@") && text.contains(".")
    }

    private static func looksLikeIPAddress(_ text: String) -> Bool {
        let separators = CharacterSet.whitespacesAndNewlines.union(.punctuationCharacters.subtracting(CharacterSet(charactersIn: ".")))
        return text
            .components(separatedBy: separators)
            .contains { token in
                let components = token.split(separator: ".")
                guard components.count == 4 else { return false }
                return components.allSatisfy { component in
                    guard let value = Int(component) else { return false }
                    return (0...255).contains(value)
                }
            }
    }
}
