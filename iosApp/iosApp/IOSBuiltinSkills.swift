import Foundation

/// Android `assets/builtin-skills` parity for the required AmberAgent skills.
/// Content is embedded so seed works without Bundle folder-resource wiring.
enum IOSBuiltinSkills {
    static let requiredNames: [String] = ["skill-creator", "会议准备", "监控文档"]

    @discardableResult
    static func installIfMissing(
        into store: IOSSkillFileStore = IOSSkillFileStore(),
        enableWith settings: IOSSharedSettingsStore? = nil
    ) -> [String] {
        var installed: [String] = []
        let existing = Set(store.listSkillDirNames())
        for name in requiredNames {
            if !existing.contains(name), let markdown = markdown(for: name) {
                do {
                    _ = try store.saveSkillFiles(
                        files: ["SKILL.md": markdown],
                        allowBuiltinSkill: true
                    )
                    installed.append(name)
                } catch {
                    continue
                }
            }
            // Mirror Android AMBER_AGENT_REQUIRED_SKILLS: keep required skills enabled.
            settings?.setSkillEnabled(name: name, enabled: true)
        }
        return installed
    }

    static func markdown(for name: String) -> String? {
        contents[name]
    }

    private static let contents: [String: String] = [
        "skill-creator": skillCreatorMarkdown,
        "会议准备": meetingPrepMarkdown,
        "监控文档": documentMonitorMarkdown,
    ]

    private static let skillCreatorMarkdown = #"""
---
name: skill-creator
version: 2.0.0
description: Use when the user wants to create a new AmberAgent skill, update an existing skill, package reusable instructions, or add specialized workflows, file handling, or tool-integration guidance.
---

# Skill Creator

Use this skill to create effective AmberAgent skills.

## About Skills

Skills are modular, self-contained packages that extend AmberAgent with specialized knowledge, workflows, and tool guidance. Treat them as onboarding guides for a domain or task: they give the agent procedural knowledge that should not have to be rediscovered every time.

### What Skills Provide

1. Specialized workflows for a domain or task.
2. Tool guidance for file formats, APIs, command-line tools, or mobile capabilities.
3. Domain knowledge such as schemas, business rules, project conventions, or acceptance checks.
4. Bundled resources such as scripts, references, templates, and assets.

### Skills And MCP

Keep Skills and MCP separate:

- Skills explain when and how the agent should do specialized work.
- MCP config explains where an external tool server lives, what transport it uses, and whether tools require approval.

If a skill depends on an external MCP server, include an optional `mcp.json` next to `SKILL.md` instead of pretending the MCP server is part of the skill body.

Use the standard shape:

```json
{
  "mcpServers": {
    "server-name": {
      "type": "streamable_http",
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ..."
      }
    }
  }
}
```

Use `type: "sse"` only for SSE transports. Do not put secrets in examples unless the user explicitly provides them.

## AmberAgent iOS Creation Flow

1. Draft `SKILL.md` with frontmatter `name` + `description`.
2. Write it with `workspace_file_write` under a skill directory (e.g. `/workspace/skills/my-skill/SKILL.md`, optional sibling `mcp.json`).
3. Call `skill_import` with the skill **directory** path (e.g. `/workspace/skills/my-skill`); a single `SKILL.md` path also works and will pick up sibling `mcp.json` when present.
4. If the package includes `mcp.json`, call `mcp_import_from_skill` then `mcp_test`.

## Core Principles

### Concise Is Key

The context window is shared by the system prompt, conversation history, other skills, tool results, and the user's request.

Default assumption: the model is already capable. Add only context the model needs for this skill. Challenge each paragraph: does it justify its token cost?

Prefer concise examples over long explanations.

### Anatomy Of A Skill

Every skill has a required `SKILL.md` file and optional resources:

```text
skill-name/
├── SKILL.md
├── scripts/
├── references/
└── assets/
```

`SKILL.md` frontmatter must include:

- `name`: skill name.
- `description`: what the skill does and when to trigger it. This is the primary trigger signal, so include all important "when to use" cases here.

## Skill Creation Process

1. Understand the skill by asking for or extracting concrete examples.
2. Plan the reusable instructions, scripts, references, and assets.
3. Create `SKILL.md` with proper frontmatter and concise instructions.
4. Test the skill on a real task.
5. Iterate based on actual usage.

## What Not To Include

Do not create extra files such as `README.md`, `INSTALLATION_GUIDE.md`, or `CHANGELOG.md` unless the agent truly needs them to perform the skill. The package should contain only what helps the agent do the job.
"""#

    private static let meetingPrepMarkdown = #"""
---
name: 会议准备
version: 1.0.0
description: 扫描未来会议并生成可派发的会议准备机会；默认只读，不写回飞书。
---

# 会议准备

当用户输入 `/会议准备`、要求扫描未来会议、或希望提前准备会议材料时使用。

## 默认行为

- 默认扫描未来 7 天；用户写明 `3天`、`7天`、`14天` 时按用户范围。
- 先读取系统日历；只把带有飞书/Lark 文档链接的会议当成高置信机会。
- 不直接创建任务；输出 Opportunity 列表，让用户选择是否派发。
- 不自动写回飞书、不发消息、不修改正式文档。

## 输出格式

对每个机会输出：

1. 会议标题和时间
2. 关联文档链接
3. 可能需要准备的内容
4. 建议动作
5. 风险和需要用户确认的点

涉及写回飞书、发消息、修改正式文档、ADB/Accessibility 操作时，必须停在确认前。
"""#

    private static let documentMonitorMarkdown = #"""
---
name: 监控文档
version: 1.0.0
description: 建立我的文档与上游飞书文档之间的依赖监控，识别数据或强陈述是否过期。
---

# 监控文档

当用户输入 `/监控文档`，或给出“我的文档 + 上游文档链接”并要求长期监控时使用。

## 默认行为

- 让用户明确哪篇是“我的文档”，哪些是“上游文档”。
- v1 只处理飞书/Lark 文档。
- 优先识别数字、指标、带实体的强陈述。
- 高置信数字锚点可以自动确认；强陈述、低置信、缺引用的锚点必须等待用户确认。
- 监控结果应生成 Opportunity，不直接创建 BoardTask。

## 输出格式

1. 监控关系：我的文档 <- 上游文档
2. 已识别的候选锚点
3. 自动确认的锚点
4. 需要用户确认的锚点
5. 后续扫描频率和提醒方式

任何写回飞书、发消息、修改正式文档的动作，都必须等用户二次确认。
"""#
}
