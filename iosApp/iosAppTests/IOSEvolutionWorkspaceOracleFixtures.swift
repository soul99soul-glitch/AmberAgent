import Foundation
@testable import iosApp

/// Slice A workspace 差分 oracle 的共享测试数据（不是断言）：note_writer
/// baseline/candidate 字节、冻结 scenario、三类 case 的 oracle 套件。
/// 供 IOSEvolutionWorkspaceOracleTests / IOSEvolutionWorkflowTests /
/// IOSEvolutionSuiteProviderTests 复用——同一失败类（binding/schema 错）
/// 在 evaluator、workflow、provider 三层各走一遍真实组装链。
enum IOSEvolutionWorkspaceOracleFixtures {

    /// note_writer：step1 把 ${input.text} 写到 inbox/note.txt（字面路径），
    /// step2 把内容复制到 out/copy.txt。baseline v1.0.0 的 copy content 绑到
    /// `${step.write.output.text}`——静态合法，但 write 的真实输出
    /// （{"ok","id","path","size_bytes"}）没有 "text" 字段 ⇒ 运行时
    /// argumentBinding 失败。这正是「schema/binding 错误」类真实失败。
    static func noteWriterRecipeData(
        version: String,
        copyContent: String?,
        copyContentLiteral: String? = nil,
        copyPath: String = "out/copy.txt"
    ) throws -> Data {
        let copyContentValue: Any = copyContentLiteral ?? copyContent ?? "${input.text}"
        let dict: [String: Any] = [
            "schema": "amber.recipe.v1",
            "name": "note_writer",
            "version": version,
            "description": "写笔记并复制一份到 out。",
            "inputs": ["text": "string"],
            "steps": [
                ["id": "write", "tool": "workspace_file_write",
                 "arguments": ["path": "inbox/note.txt", "content": "${input.text}"]],
                ["id": "copy", "tool": "workspace_file_write",
                 "arguments": ["path": copyPath, "content": copyContentValue]],
            ],
            "outputs": ["copied": "${step.copy.output.path}"],
        ]
        return try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    /// scripted 模型的修复候选响应：copy content 绑回 ${input.text} 的 v1.0.1。
    static func noteWriterCandidateJSON(name: String = "note_writer", version: String = "1.0.1") -> String {
        let dict: [String: Any] = [
            "schema": "amber.recipe.v1",
            "name": name,
            "version": version,
            "description": "写笔记并复制一份到 out。",
            "inputs": ["text": "string"],
            "steps": [
                ["id": "write", "tool": "workspace_file_write",
                 "arguments": ["path": "inbox/note.txt", "content": "${input.text}"]],
                ["id": "copy", "tool": "workspace_file_write",
                 "arguments": ["path": "out/copy.txt", "content": "${input.text}"]],
            ],
            "outputs": ["copied": "${step.copy.output.path}"],
        ]
        let data = try! JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
        return String(data: data, encoding: .utf8)!
    }

    /// 冻结的 baseline（note_writer@1.0.0 exact bytes）+ 从 baseline manifest
    /// 静态可派生的后置条件（copy 的 content 模板是 stepOutput 绑定，不可派生）。
    static func scenario() throws -> IOSEvaluationWorkspaceScenario {
        IOSEvaluationWorkspaceScenario(
            baseline: IOSEvaluationWorkspaceScenario.Baseline(
                artifactId: "recipe__note_writer",
                version: "1.0.0",
                canonicalJSON: try noteWriterRecipeData(
                    version: "1.0.0", copyContent: "${step.write.output.text}"
                ),
                failingStepIndex: 1
            ),
            postconditions: [
                .fileExists(pathTemplate: "inbox/note.txt"),
                .fileContentEquals(pathTemplate: "inbox/note.txt", contentTemplate: "${input.text}"),
                .fileExists(pathTemplate: "out/copy.txt"),
            ]
        )
    }

    /// 三类 case 全挂 workspace scenario 的 oracle 套件：replay（baseline 差分）
    /// + protected/sealed（同一份输入参数化后置条件）。
    static func suite(
        replayScenario: IOSEvaluationWorkspaceScenario? = nil
    ) throws -> IOSEvaluationSuite {
        let scenario = try replayScenario ?? scenario()
        let replay = IOSEvaluationCase(
            id: "case:replay-ws",
            kind: .failureReplay,
            recipeInputs: ["text": .string("复盘正文")],
            scriptedPrimitives: [:],
            assertions: IOSEvaluationAssertions(
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "write", tool: "workspace_file_write",
                        arguments: ["path": .string("inbox/note.txt"), "content": .string("复盘正文")]
                    ),
                    // 失败 step：候选可合法修改该调用的参数，只断言工具。
                    IOSEvaluationExpectedStepCall(stepId: "copy", tool: "workspace_file_write", arguments: nil),
                ]
            ),
            originalFailure: IOSEvaluationOriginalFailure(
                failedStepId: "copy", errorKind: .argumentBinding, completedStepIds: ["write"]
            ),
            workspaceScenario: scenario
        )
        let protected = IOSEvaluationCase(
            id: "case:protected-ws",
            kind: .protectedSuccess,
            recipeInputs: ["text": .string("受保护正文")],
            scriptedPrimitives: [:],
            assertions: IOSEvaluationAssertions(
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(
                        stepId: "write", tool: "workspace_file_write",
                        arguments: ["path": .string("inbox/note.txt"), "content": .string("受保护正文")]
                    ),
                    IOSEvaluationExpectedStepCall(
                        stepId: "copy", tool: "workspace_file_write",
                        arguments: ["path": .string("out/copy.txt"), "content": .string("受保护正文")]
                    ),
                ]
            ),
            originalFailure: nil,
            workspaceScenario: IOSEvaluationWorkspaceScenario(
                baseline: nil,
                postconditions: [
                    .fileContentEquals(pathTemplate: "inbox/note.txt", contentTemplate: "${input.text}"),
                    .fileContentEquals(pathTemplate: "out/copy.txt", contentTemplate: "${input.text}"),
                ]
            )
        )
        let sealed = IOSEvaluationCase(
            id: "case:sealed-ws",
            kind: .sealedHoldout,
            recipeInputs: ["text": .string("sealed-9f86d081884c7d65")],
            scriptedPrimitives: [:],
            assertions: IOSEvaluationAssertions(
                expectedStepCalls: [
                    IOSEvaluationExpectedStepCall(stepId: "write", tool: "workspace_file_write", arguments: nil),
                    IOSEvaluationExpectedStepCall(stepId: "copy", tool: "workspace_file_write", arguments: nil),
                ]
            ),
            originalFailure: nil,
            workspaceScenario: IOSEvaluationWorkspaceScenario(
                baseline: nil,
                postconditions: [
                    .fileContentEquals(pathTemplate: "inbox/note.txt", contentTemplate: "${input.text}"),
                    .fileContentEquals(pathTemplate: "out/copy.txt", contentTemplate: "${input.text}"),
                ]
            )
        )
        return IOSEvaluationSuite(
            suiteId: "suite-ws-oracle",
            failureReplayCases: [replay],
            protectedSuccessCases: [protected],
            sealedHoldoutCases: [sealed]
        )
    }
}
