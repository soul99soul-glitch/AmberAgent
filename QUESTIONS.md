# AmberAgent iOS Autonomous Questions

## Q-1 · conversations · 会话数据接入时机

- **疑问**：`conversations` 首页的真实会话数据源/Repository 在当前 iOS 骨架里尚未接到此屏；原型基准要求先还原视觉和信息层级。
- **默认做法**：先使用 `index.html` 里的样例会话内容实现像素方向和导航骨架，不重写或猜测业务数据层；真实会话列表接入放到 Phase 3。

## Q-2 · 验证环境 · iOS simulator/runtime 缺失

- **疑问**：当前机器 `xcrun simctl list runtimes` / `devices` 为空，`xcodebuild -showdestinations` 只返回不可用目的地；`xcodebuild -showsdks` 能看到 iOS/iOS Simulator 26.5 SDK，但 scheme 仍提示无 destination，完整 build/simulator screenshot 无法完成。
- **默认做法**：不下载/安装 Xcode platform，不杀 Chrome，不阻塞；当前屏先用 `swift -frontend -parse`、scoped Swift checks 和原型 `renders/conversations.png` 进行静态/视觉方向验证，等本机 runtime/destination 可用后补完整构建与截图比对。

## Q-3 · search · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py search` 返回 `✗ renders/search.png`，脚本没有产出截图；原因需后续打开脚本或 Chrome 输出进一步排查。
- **默认做法**：继续按 `index.html` 中 `#search` section 的源码结构实现搜索覆盖层，先不阻塞；等渲染脚本可产出该屏后补视觉基准比对。

## Q-4 · permissions · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py permissions` 返回 `✗ renders/permissions.png`，脚本没有产出截图；当前没有进一步 Chrome 输出可定位原因。
- **默认做法**：继续按 `index.html` 中 `#permissions` section 的源码结构实现「权限与批准」入口屏，先用 Swift parse 与 diff check 验证；等渲染脚本可产出该屏后补视觉基准比对。

## Q-5 · capabilities · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py capabilities` 返回 `✗ renders/capabilities.png`，脚本没有产出截图；当前表现与 `search` / `permissions` 一致。
- **默认做法**：继续按 `index.html` 中 `#capabilities` section 与 CD-7 的最终能力模型实现能力门控页，保留现有 Swift 业务逻辑；等渲染脚本可产出该屏后补视觉基准比对。

## Q-6 · sandbox · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py sandbox` 返回 `✗ renders/sandbox.png`，脚本没有产出截图；当前表现与多个二级设置页一致。
- **默认做法**：继续按 `index.html` 中 `#sandbox` section 与 CD-8 的 Remote SSH exec runner 信息架构实现运行环境页，复用现有 `SettingsStore` / `IOSTerminalRuntime` / SSH Keychain 逻辑；等渲染脚本可产出该屏后补视觉基准比对。

## Q-7 · appearance · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py appearance` 返回 `✗ renders/appearance.png`，脚本没有产出截图；当前表现与多个二级设置页一致。
- **默认做法**：继续按 `index.html` 中 `#appearance` section 实现外观模式、强调色、背景色调选择 UI；暂时只持久化选择，不将其应用到全局主题，等 theme store 定义后再接入。

## Q-8 · displayFont · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py displayFont` 返回 `✗ renders/displayFont.png`，脚本没有产出截图；当前表现与 `appearance`、`sandbox` 等二级设置页一致。
- **默认做法**：继续按 `index.html` 中 `#displayFont` section 实现显示与字体设置 UI，先持久化本地偏好，不改全局聊天渲染与消息模型；等渲染脚本可产出该屏后补视觉基准比对。

## Q-9 · convStorage · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py convStorage` 返回 `✗ renders/convStorage.png`，脚本没有产出截图；当前表现与多个设置二级页一致。
- **默认做法**：继续按 `index.html` 中 `#convStorage` section 与 CD-14 实现对话存储 UI；清理缓存、清理旧对话、删除全部对话先保留确认入口，不接入真实删除，避免在未明确 iOS 存储服务前破坏数据。

## Q-10 · syncBackup · Google Drive 授权与原型基准图

- **疑问**：执行 `python3 render_screen.py syncBackup` 返回 `✗ renders/syncBackup.png`，脚本没有产出截图；真实 Google Drive 同步还需要 OAuth client、用户授权、云端快照服务和本地加密/恢复事务。
- **默认做法**：继续按 `index.html` 中 `#syncBackup` section 与 CD-12 实现同步与备份 UI；不请求账号授权、不发起网络请求、不读写备份文件，所有上传/下载/导入/导出/口令入口先弹出未接线提示。

## Q-11 · memory · 原型基准图与 iOS 记忆服务接线

- **疑问**：执行 `python3 render_screen.py memory` 返回 `✗ renders/memory.png`，脚本没有产出截图；iOS 端当前没有 Android `SettingAgentMemoryVM` / `MemoryRepository` 等价接线，也未实现 `agentsMd` / `memEdit` 子屏。
- **默认做法**：先按 `index.html` 中 `#memory` section 实现核心记忆总览、agents.md 预览、记忆配置开关与样例条目；开关用本地偏好持久化，新增/编辑/删除/agents.md 入口先提示未接线，子屏后续按一屏一提交继续做。

## Q-12 · agentsMd · 原型基准图与真实 prompt 注入

- **疑问**：执行 `python3 render_screen.py agentsMd` 返回 `✗ renders/agentsMd.png`，脚本没有产出截图；iOS 端尚未有 Android `buildAgentSoulPrompt()` / prompt config repository 的等价接线。
- **默认做法**：先按 `index.html` 中 `#agentsMd` section 实现 agents.md 编辑屏，并把内容保存为本地 draft；暂不注入真实 System Prompt，等 iOS prompt 配置服务明确后再接入。

## Q-13 · memEdit · 原型基准图与真实记忆写入

- **疑问**：执行 `python3 render_screen.py memEdit` 返回 `✗ renders/memEdit.png`，脚本没有产出截图；iOS 端尚未接入真实记忆库写入/更新/删除事务。
- **默认做法**：先按 `index.html` 中 `#memEdit` section 实现记忆编辑页，并从总览新增/条目点击进入；编辑内容、层级、置顶只维护本地页面状态，完成/删除不会写入或删除真实记忆。

## Q-14 · skills · 原型基准图与真实技能索引

- **疑问**：执行 `python3 render_screen.py skills` 返回 `✗ renders/skills.png`，脚本没有产出截图；iOS 端尚未接入真实 Skill 扫描、安装、详情和索引修复流程。
- **默认做法**：先按 `index.html` 中 `#skills` section 实现技能总览，只列工具技能/任务技能和 MCP 入口，不列 subagent；添加、详情、导入、全量规整与 MCP 服务器入口先提示未接线，不读写本地 Skill 文件。

## Q-15 · mcpServers · 原型基准图与真实 MCP 设置

- **疑问**：执行 `python3 render_screen.py mcpServers` 返回 `✗ renders/mcpServers.png`，脚本没有产出截图；iOS 端尚未接入真实 MCP 配置、连接状态、导入 JSON、手动添加、同步全部和删除流程。
- **默认做法**：先按 `index.html` 中 `#mcpServers` section 与 CD-9 实现技能页二级 MCP 服务器列表；三台服务器和状态为样例，本地开关只持久化 UI 状态，导入/添加不读取剪贴板、不连接外部服务器、不写设置。
