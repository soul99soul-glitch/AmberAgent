import Foundation

/// AmberAgent 内置技能出厂备份。
/// - 磁盘上的 `skills/<name>/` 可被用户或 agent 迭代修改。
/// - 代码内嵌文案是出厂硬备份；缺失时 seed，也可显式恢复。
/// - `requiredNames`：启动必装且默认启用，不可删除。
/// - `optionalSeedNames`：启动缺失时 seed，**不**默认启用，可删除。
enum IOSBuiltinSkills {
    static let requiredNames: [String] = ["skill-creator"]

    /// 可选出厂技能：装上但不自动启用，由用户在技能页打开。
    static let optionalSeedNames: [String] = ["visual-svg"]

    /// 历史上随包 seed、现已不再内置的技能目录名。启动时从本机移除并取消启用。
    static let deprecatedSeedNames: [String] = ["会议准备", "监控文档"]

    /// 有出厂备份、可「恢复出厂」的技能名（必需 + 可选）。
    static var factorySeedNames: [String] { requiredNames + optionalSeedNames }

    @discardableResult
    static func installIfMissing(
        into store: IOSSkillFileStore = IOSSkillFileStore(),
        enableWith settings: IOSSharedSettingsStore? = nil
    ) -> [String] {
        removeDeprecatedSeeds(from: store, settings: settings)

        var installed: [String] = []
        let existing = Set(store.listSkillDirNames())
        for name in requiredNames {
            if seedOrRefresh(name: name, existing: existing, into: store) {
                installed.append(name)
            }
            settings?.setSkillEnabled(name: name, enabled: true)
        }
        for name in optionalSeedNames {
            // 用户主动删除过的可选出厂技能不再冷启动回种。
            if isOptionalSeedRemoved(name, store: store) { continue }
            if seedOrRefresh(name: name, existing: existing, into: store) {
                installed.append(name)
            }
            // 可选 seed 不改启用状态：新装保持关闭，用户已启用则保留。
        }
        return installed
    }

    /// 用代码内嵌出厂备份覆盖本机技能（硬恢复）。
    static func restoreFactoryContent(
        name: String,
        into store: IOSSkillFileStore = IOSSkillFileStore()
    ) throws {
        let normalized = IOSSkillFileStore.normalizedSkillName(name)
        guard factorySeedNames.contains(normalized) else {
            throw IOSSkillFileStoreError.skillMissing(name)
        }
        guard let markdown = markdown(for: normalized) else {
            throw IOSSkillFileStoreError.skillMissing(normalized)
        }
        // 只覆盖 SKILL.md，保留用户已放进包内的 scripts/references/assets/mcp.json。
        let directory = try store.skillDirectoryURL(name: normalized)
        if FileManager.default.fileExists(atPath: directory.path) {
            try store.saveSkillMarkdown(
                dirName: normalized,
                expectedName: normalized,
                content: markdown
            )
        } else {
            _ = try store.saveSkillFiles(
                files: ["SKILL.md": markdown],
                allowBuiltinSkill: true
            )
        }
        clearOptionalSeedRemoved(normalized, store: store)
    }

    /// 用户删除可选出厂技能时记录，避免下次冷启动重新 seed。
    static func markOptionalSeedRemoved(_ name: String, store: IOSSkillFileStore) {
        let normalized = IOSSkillFileStore.normalizedSkillName(name)
        guard optionalSeedNames.contains(normalized) else { return }
        var removed = removedOptionalSeeds(in: store)
        guard removed.insert(normalized).inserted else { return }
        writeRemovedOptionalSeeds(removed, store: store)
    }

    /// 用户/agent 主动写回该可选技能时清除删除标记。
    static func clearOptionalSeedRemoved(_ name: String, store: IOSSkillFileStore) {
        let normalized = IOSSkillFileStore.normalizedSkillName(name)
        var removed = removedOptionalSeeds(in: store)
        guard removed.remove(normalized) != nil else { return }
        writeRemovedOptionalSeeds(removed, store: store)
    }

    static func isOptionalSeedRemoved(_ name: String, store: IOSSkillFileStore) -> Bool {
        removedOptionalSeeds(in: store)
            .contains(IOSSkillFileStore.normalizedSkillName(name))
    }

    static func markdown(for name: String) -> String? {
        contents[name]
    }

    /// 磁盘内容是否仍等于某个已知出厂快照（当前或历史）。
    static func isFactorySnapshot(_ content: String) -> Bool {
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        return factorySnapshots.contains { $0.trimmingCharacters(in: .whitespacesAndNewlines) == trimmed }
    }

    private static func seedOrRefresh(
        name: String,
        existing: Set<String>,
        into store: IOSSkillFileStore
    ) -> Bool {
        if !existing.contains(name), let markdown = markdown(for: name) {
            do {
                _ = try store.saveSkillFiles(
                    files: ["SKILL.md": markdown],
                    allowBuiltinSkill: true
                )
                return true
            } catch {
                return false
            }
        }
        if existing.contains(name) {
            refreshFactorySnapshotIfUnmodified(name: name, into: store)
        }
        return false
    }

    private static func removeDeprecatedSeeds(
        from store: IOSSkillFileStore,
        settings: IOSSharedSettingsStore?
    ) {
        let existing = Set(store.listSkillDirNames())
        for name in deprecatedSeedNames where existing.contains(name) {
            try? store.deleteSkill(dirName: name)
            settings?.setSkillEnabled(name: name, enabled: false)
            settings?.removeSkillFromAllAssistants(name: name)
        }
    }

    private static func refreshFactorySnapshotIfUnmodified(
        name: String,
        into store: IOSSkillFileStore
    ) {
        guard let factory = markdown(for: name),
              let onDisk = try? store.readSkillMarkdown(dirName: name),
              isFactorySnapshot(onDisk),
              onDisk.trimmingCharacters(in: .whitespacesAndNewlines)
                != factory.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return
        }
        // 只刷 SKILL.md；未改动的出厂快照刷新不得抹掉同级附属文件。
        try? store.saveSkillMarkdown(dirName: name, expectedName: name, content: factory)
    }

    private static let contents: [String: String] = [
        "skill-creator": skillCreatorMarkdown,
        "visual-svg": visualSvgMarkdown,
    ]

    private static var factorySnapshots: [String] {
        [
            skillCreatorMarkdown,
            legacyChineseSkillCreatorMarkdownV21,
            legacyEnglishSkillCreatorMarkdown,
            visualSvgMarkdown,
        ]
    }

    private static let removedOptionalSeedsFileName = ".removed-optional-seeds"

    private static func removedOptionalSeeds(in store: IOSSkillFileStore) -> Set<String> {
        let url = store.skillsDirectory.appendingPathComponent(removedOptionalSeedsFileName)
        guard let data = try? Data(contentsOf: url),
              let names = try? JSONDecoder().decode([String].self, from: data) else {
            return []
        }
        return Set(names.map(IOSSkillFileStore.normalizedSkillName))
    }

    private static func writeRemovedOptionalSeeds(_ names: Set<String>, store: IOSSkillFileStore) {
        let url = store.skillsDirectory.appendingPathComponent(removedOptionalSeedsFileName)
        if names.isEmpty {
            try? FileManager.default.removeItem(at: url)
            return
        }
        try? FileManager.default.createDirectory(
            at: store.skillsDirectory,
            withIntermediateDirectories: true
        )
        guard let data = try? JSONEncoder().encode(names.sorted()) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private static let skillCreatorMarkdown = #"""
---
name: skill-creator
version: 2.2.0
description: 当用户要创建、更新或迭代 AmberAgent 技能，编写可复用工作流或工具集成说明，改进现有技能（含 skill-creator 自身），或把本轮对话里的流程固化成技能时使用。用户提到「做成技能」「skill」「技能说明」「SKILL.md」、可复用流程或要改进触发不准时，也应使用本技能。
allowed-tools: workspace_file_write skill_import skill_validate skills_list use_skill mcp_import_from_skill mcp_test
---

# 技能创建器（Skill Creator）

用这个技能创建、更新和迭代 AmberAgent 本机技能。

## 技能是什么

技能是模块化、自包含的说明包，用来给 agent 补充领域知识、流程和工具用法。把它当成「某类任务的入职手册」：不必每次重新摸索。

### 技能能提供

1. 某个领域或任务的专用流程。
2. 文件格式、API、命令行或设备能力相关的工具指引。
3. 领域知识：schema、业务规则、项目约定、验收标准。
4. 可选附属资源：scripts、references、templates、assets。

### 技能与 MCP

技能和 MCP 分开：

- 技能说明「何时、如何」做专项工作。
- MCP 配置说明外部工具服务器在哪、用什么传输、工具是否需要审批。

若技能依赖外部 MCP，在 `SKILL.md` 旁放可选的 `mcp.json`，不要把服务器配置伪装成技能正文。

标准形状：

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

仅在 SSE 传输时使用 `type: "sse"`。除非用户明确提供，示例中不要写真实密钥。

## AmberAgent iOS 创建 / 更新流程

1. 起草 `SKILL.md`，frontmatter 至少包含 `name` 与 `description`。
2. 用 `workspace_file_write` 写到技能目录（例如 `/workspace/skills/my-skill/SKILL.md`，可选同级 `mcp.json`）。
3. 调用 `skill_import`，传入技能**目录**路径（例如 `/workspace/skills/my-skill`）；也可传单个 `SKILL.md` 路径，会顺带导入同级 `mcp.json`。
4. 若包内有 `mcp.json`，再调用 `mcp_import_from_skill`，然后 `mcp_test`。

更新已有技能（含本技能 `skill-creator`）时：同样走「写 workspace → `skill_import`」。导入会覆盖本机 `skills/<name>/`。不要改 frontmatter 的 `name`。

## 自迭代

- 本技能允许被 agent 改进：发现流程过时、缺步骤、中文/英文混杂或工具名变更时，应主动修订并 `skill_import`。
- 应用内嵌了出厂硬备份；用户可在技能详情「恢复出厂」还原，agent 不要伪造「已恢复」——只有用户或明确的恢复操作才会写回出厂文案。
- 迭代时保持简洁，并保留仍正确的创建/导入步骤。不要引入桌面专用评测流水线（子代理并行 baseline、Python eval-viewer、`.skill` 打包等）；Amber 移动端只做轻量试跑与口头验收。

## 创建过程

先判断用户卡在哪一步，再推进；用户只要「一起 vibe 一下」也可以跳过形式化步骤。

### 1. 先从对话抽意图

若用户说「把刚才流程做成技能」，优先从本轮对话提取：用过的工具、步骤顺序、用户纠正、输入/输出形态。缺口再问用户，确认后再写稿。

### 2. 写之前的小访谈

弄清后再动笔（可合并成少量问题）：

1. 技能要让 agent 做成什么？
2. 何时触发？用户会怎么说？
3. 期望输出格式是什么？
4. 边界与失败时怎么处理？
5. 是否需要 2～3 条真实口吻的试跑提示？（客观可验的流程建议要；文风/审美类可跳过）

### 3. 写 SKILL.md

- `name`：目录键，更新时不可改。
- `description`：主要触发信号——写清「做什么」和「何时用」；「何时用」只放 frontmatter，不要堆到正文。模型容易 undertrigger：描述要比「仅说明能力」更主动一点，补上近义说法与相关场景，但不要夸大到无关任务。
- 正文：短流程、短例子、必要约束。可选 `allowed-tools`：空表示未限制；若声明，只写 Amber 真实工具名。

### 4. 轻量验收

起草后给用户 2～3 条真实用户会说的试跑提示，征询是否合适；有机会则按技能说明试做一次，根据反馈再改。不要搭建完整评测 harness。

## 写作手法

- 对模型用祈使句（「先读 X，再调用 Y」），少用空泛说教。
- 讲清为什么重要，优于堆砌全大写 ALWAYS/NEVER；需要硬约束时说明后果。
- 写完后用旁观者视角收一遍：删掉不拉人的段落，避免只为那两三个例子过拟合。

## 渐进披露

1. **元数据**（name + description）：始终可见，宜短。
2. **SKILL.md 正文**：触发后加载；尽量精炼（理想远低于数百行）。
3. **附属资源**：仅在正文明确指引「何时再读」时才加 `references/`、`scripts/`、`assets/`。许多技能只有 `SKILL.md`——不要为缺失的附属路径反复重试。

大段参考拆到 `references/`，并在正文写清何时打开哪份文件。

## 核心原则

### 简洁优先

上下文窗口由系统提示、对话历史、其他技能、工具结果和用户请求共享。

默认假设：模型已经足够能干。只补充这个技能真正需要的上下文。逐段自问：这点 token 值得吗？

优先用短例子，少写长说明。

### 技能结构

```text
skill-name/
├── SKILL.md
├── scripts/      # 可选
├── references/   # 可选
└── assets/       # 可选
```

## 不要放什么

除非 agent 执行技能时真的需要，否则不要额外写 `README.md`、`INSTALLATION_GUIDE.md`、`CHANGELOG.md`。包里只留对完成任务有帮助的内容。
"""#

    /// 用于识别「仍是旧出厂中文 2.1、可自动刷新到 2.2」的历史快照；不作恢复目标。
    static let legacyChineseSkillCreatorMarkdownV21 = #"""
---
name: skill-creator
version: 2.1.0
description: 当用户要创建、更新或迭代 AmberAgent 技能，编写可复用工作流、工具集成说明，或改进现有技能（含 skill-creator 自身）时使用。
---

# 技能创建器（Skill Creator）

用这个技能创建、更新和迭代 AmberAgent 本机技能。

## 技能是什么

技能是模块化、自包含的说明包，用来给 agent 补充领域知识、流程和工具用法。把它当成「某类任务的入职手册」：不必每次重新摸索。

### 技能能提供

1. 某个领域或任务的专用流程。
2. 文件格式、API、命令行或设备能力相关的工具指引。
3. 领域知识：schema、业务规则、项目约定、验收标准。
4. 可选附属资源：scripts、references、templates、assets。

### 技能与 MCP

技能和 MCP 分开：

- 技能说明「何时、如何」做专项工作。
- MCP 配置说明外部工具服务器在哪、用什么传输、工具是否需要审批。

若技能依赖外部 MCP，在 `SKILL.md` 旁放可选的 `mcp.json`，不要把服务器配置伪装成技能正文。

标准形状：

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

仅在 SSE 传输时使用 `type: "sse"`。除非用户明确提供，示例中不要写真实密钥。

## AmberAgent iOS 创建 / 更新流程

1. 起草 `SKILL.md`，frontmatter 至少包含 `name` 与 `description`。
2. 用 `workspace_file_write` 写到技能目录（例如 `/workspace/skills/my-skill/SKILL.md`，可选同级 `mcp.json`）。
3. 调用 `skill_import`，传入技能**目录**路径（例如 `/workspace/skills/my-skill`）；也可传单个 `SKILL.md` 路径，会顺带导入同级 `mcp.json`。
4. 若包内有 `mcp.json`，再调用 `mcp_import_from_skill`，然后 `mcp_test`。

更新已有技能（含本技能 `skill-creator`）时：同样走「写 workspace → `skill_import`」。导入会覆盖本机 `skills/<name>/`。不要改 frontmatter 的 `name`。

## 自迭代

- 本技能允许被 agent 改进：发现流程过时、缺步骤、中文/英文混杂或工具名变更时，应主动修订并 `skill_import`。
- 应用内嵌了出厂硬备份；用户可在技能详情「恢复出厂」还原，agent 不要伪造「已恢复」——只有用户或明确的恢复操作才会写回出厂文案。
- 迭代时保持简洁，并保留仍正确的创建/导入步骤。

## 核心原则

### 简洁优先

上下文窗口由系统提示、对话历史、其他技能、工具结果和用户请求共享。

默认假设：模型已经足够能干。只补充这个技能真正需要的上下文。逐段自问：这点 token 值得吗？

优先用短例子，少写长说明。

### 技能结构

每个技能必须有 `SKILL.md`，可选资源：

```text
skill-name/
├── SKILL.md
├── scripts/
├── references/
└── assets/
```

`SKILL.md` frontmatter 必须包含：

- `name`：技能名（目录键，更新时不可改）。
- `description`：做什么、何时触发。这是主要触发信号，把重要「何时使用」都写进这里。

## 创建过程

1. 先弄清用户目标与具体例子。
2. 规划可复用说明、脚本、参考资料和资源。
3. 写好带 frontmatter 的简洁 `SKILL.md`。
4. 用真实任务试一次。
5. 根据实际使用再迭代。

## 不要放什么

除非 agent 执行技能时真的需要，否则不要额外写 `README.md`、`INSTALLATION_GUIDE.md`、`CHANGELOG.md`。包里只留对完成任务有帮助的内容。
"""#

    /// 用于识别「仍是旧出厂英文、可自动刷新到中文」的历史快照；不作恢复目标。
    static let legacyEnglishSkillCreatorMarkdown = #"""
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

    /// 融合 visualise（对话内联图解）与 upbrew svg-creator（插画技法），统一 Amber `show-widget` 出口。
    /// 可选 seed：安装但不默认启用。
    private static let visualSvgMarkdown = #"""
---
name: visual-svg
version: 1.0.1
description: 当用户要画 SVG、矢量图、流程图、架构图、示意图、信息图、图标、Logo 线稿、插画风角色/场景矢量图，或说「画一个」「画张图」「可视化」「diagram」「illustrate」且适合用矢量表达时使用。启用后可 use_skill 加载画法；已加载或目录已提示时，按 diagram/illustration 直接输出 Amber show-widget。不要用本技能替代真实照片级生图（generate_image）。
---

# Visual SVG（图解 + 插画）

一个技能、两条画法、统一 Amber 出口。技法提炼自开源 visualise（内联图解纪律）与 svg-creator（插画光影），已改成 Amber 时间线可渲染的 `show-widget`。

## 何时调用

用户要「看得见」的矢量结果时使用本技能画法。选模式，再画，再交付。不必为「只是画图」另开工具链；需要事实时先取事实，再画最终图。

| 用户意图 | 模式 |
|---------|------|
| 流程、架构、时序、对比、结构拆解、示意图、信息图 | **diagram** |
| 角色、吉祥物、场景、装饰插画、有光影的矢量画 | **illustration** |
| 小图标、线稿 Logo、徽章 | **diagram**（极简网格；不要插画滤镜） |
| 写实照片、厚涂、材质丰富海报 | **不要用本技能** → `generate_image` |

不确定时：关系/步骤清楚 → diagram；要「好看/氛围/角色」→ illustration。

## 统一出口（强制）

1. 可见回复先一句短说明（可省略标题复述）。
2. 立刻输出**一个**完整 fenced 块：

````
```show-widget
{"title":"简短标题","widget_code":"<svg width=\"100%\" viewBox=\"0 0 680 H\" xmlns=\"http://www.w3.org/2000/svg\">...</svg>"}
```
````

3. 规则：
- `widget_code` 必须是**完整**单根 `<svg>`，JSON 内转义引号；尽量单行 JSON。
- `width="100%"` + `viewBox="0 0 680 H"`；内容留 ≥24px 边距，勿画出 viewBox。
- `title` 是原生卡片标题；**不要**在 SVG 里再画一遍相同大标题。
- `widget_code` 默认上限约 12000 字符；超限先减细节，不要拆成多个 widget。
- 不要用 \`\`\`visualizer、\`\`\`svg、单独 HTML 页、MiniApp、或本机 Python/Cairo 渲染环。
- 不要为「只是画图」去调 browser / eval_javascript / 终端；需要事实时先工具，再画最终图。
- 勿在 reasoning/thinking 里藏 show-widget JSON。

## 模式 A — diagram（清晰优先）

目标：手机上一眼可读。

- 扁平色块 + 细描边；**少用**渐变、阴影、模糊、噪点（避免糊成一团）。
- 节点圆角 `rx="8"`～`12`；连线 stroke `#94a3b8`，宽 2；箭头用 `marker` 或明确三角。
- 标签 12–16px；长文手动换行；单图颜色 ≤ 3 组语义色（如蓝=输入、绿=处理、橙=结果）。
- 分层：`#background` → `#nodes` → `#labels` → `#connections`（连线最后画，避免被挡住）。
- 先心算布局：清单元素 → 网格占位 → 按文字估宽 → 再写 SVG。
- 交付前自检：无重叠框、无出界文字、箭头不穿字、viewBox 高度贴合内容。

## 模式 B — illustration（观感优先）

目标：矢量也有体积与光感（仍须是合法、安全的静态 SVG）。

- 结构：`<defs>`（渐变/滤镜）→ `#background` → `#midground` → `#foreground` → `#effects`。
- 重要色面用 **4+ 色标** 渐变；球体用径向渐变并偏移高光（如 `fx="0.3" fy="0.3"`）。
- 五区光（非平面色）：高光（偏暖）→ 亮部 → 固有色 → 形影（偏冷蓝紫，忌纯黑）→ 反射光（阴影边缘低透明暖色）。
- 阴影用深蓝/紫/青（如 `#1a1a4e`），不要 `#000` 死黑。
- 角色：躯干 → 腿 → 臂 → 头 → 细节；关节用圆帽描边或圆点衔接，避免悬浮断肢。
- 可少量 `feGaussianBlur` 投影；控制滤镜数量，优先观感与体量。
- 仍遵守 viewBox/边距/字号与 12000 字符上限。

## 安全与禁忌

- 不要：`<script>`、外部 URL、`foreignObject` 套复杂 HTML、iframe、表单、事件处理器。
- 装饰性可用 `aria-hidden="true"`；表意图加 `<title>`。
- 不要把多页 PPT 画进一张 SVG 网格；幻灯片走 full_html 演示路径（若用户要 PPT）。
- 不要输出占位模板图；每个 widget 必须对应当前用户请求。

## 最短自检

- [ ] 模式选对（diagram / illustration）
- [ ] 仅一个完整 `show-widget`，SVG 在 `widget_code` 内
- [ ] viewBox 680 宽 + 边距；标题不重复
- [ ] diagram 清晰可读 / illustration 有光影体积
- [ ] 无脚本、无外链、体积可控
"""#
}
