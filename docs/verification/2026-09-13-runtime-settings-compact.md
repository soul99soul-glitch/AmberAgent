# Agent 运行环境页整理

## 最终布局

- 主页面保留 Workspace、默认运行时和 SSH 档案列表。
- 工作区合并为一行；更换/清除入口保留，未设置时不展示无效的清除按钮。
- SSH 档案采用紧凑双行，长名称与地址省略显示，完整地址、验证状态、认证方式及编辑/删除/指纹探测在更多菜单中查看。
- SSH 命令、运行时维护和高级参数移入底部“更多设置”弹层，默认不占据主页面。命令和任务状态仍由外层持有，运行或操作中保持操作面板可达。
- 复用现有 Material 按钮与 Select 胶囊，不给本页增加独立的按钮配色/圆角。
- “添加配置”可见高度 32dp、图标 14dp、水平内边距 10dp；周围保留触控空间。其他低频操作仍使用标准按钮。
- Select 新增可选 labelMaxWidth，仅本页传入限制，其他调用保留原默认显示行为。
- 新增“更多设置”入口文案，使用 locale-tui 更新六种语言。

## 验证

- `:app:assembleGraphite` 与 `:app:assembleGraphiteAndroidTest` 通过；Graphite 测试使用既有 `/private/tmp/amber-capsule-graphite-test.init.gradle` 与 `-PuiSmokeTest=true`。
- `SandboxSettingsSmokeTest` 在 390dp / 字号 1.0 和 320dp / 字号 1.3 两种配置均通过。
- 验证默认低频设置不组合、更多设置弹层、高级参数展开、SSH 命令入口、档案更多菜单。
- 缩小后的添加按钮外侧触控区能打开新增档案弹窗，随后取消，未保存新档案。
- 截图使用临时示例 SSH 档案；测试在 finally 删除自己创建的档案并恢复默认档案，不读取原凭据，不执行 SSH、指纹探测、安装修复或真实删除操作。
- 模拟器已恢复 780×1688、density 320、font_scale 1.0。
- `git diff --check` 通过；首页文件 SHA-256 仍为 `5ba6a7b84a433b7c6f36b859a6c49005e3965b0259a8b9fc1b9ded7acb689dac`。
- 最终 APK SHA-256：`c5ed2d2135785ec8692b0ae4255928164b2148dd267e7742173835b7abdaa81a`。

截图：`/tmp/amber-sandbox-compact/final-390/sandbox-settings-smoke/` 与 `/tmp/amber-sandbox-compact/final-320-large/sandbox-settings-smoke/`，主页面为 `01-default.png`，更多设置为 `02-more-settings.png`。

仅完成模拟器视觉及交互验证。真机未连接，未覆盖安装；未提交或推送，既有 WIP 保留。
