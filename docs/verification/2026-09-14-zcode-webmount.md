# ZCode 网页代理工具

本次范围为 Android 仓库。ZCode 保持网页远程控制协议，Amber 使用 WebMount 的浏览器会话、快照引用、租约和工具回执访问页面；没有实现原生 A2A 协议，也不调用 ZCode 私有 RPC 或数据库。

## 使用路径

1. 在设置的 ZCode 页保存分享链接，开启「允许 Amber Agent 使用此连接」，并开启 WebMount 全局开关。
2. `wm_zcode_open` 不带参数时仅发现本地配置，返回不含凭据的 `connection_origin`；再次调用并把该 origin 放入 `url` 才打开浏览器，返回与 ZCode 页面共用的 `session_id`。后续专用工具也携带该 `url`，供现有域名策略核对。完整分享链接始终从本地配置提供。
3. `wm_zcode_read` 返回当前任务、紧凑 UI tree、最新渲染消息、输入路由及快照引用。任务选择、展开、滚动使用同一会话的 `wm_click` / `wm_scroll` 等工具；操作后重新读取快照。
4. 确认工作区、远端任务和具体提问后，调用 `wm_zcode_ask`，传入同次读取的 `remote_task_id`、`snapshot_id`、`composer_target.ref` 与 `send_target.ref`。此工具需要明确审批，只发送 `message`，不自动附带 Amber 历史。
5. 使用 `wm_zcode_read` 的消息游标读取新回复或同一条流式回复的更新。发送回执、空输入框或网页暂时安静都不证明远端任务完成。

页面可由用户接管；离开页面或交还控制后，agent 可继续同一浏览器。另一个 Amber 对话不能接管已经绑定的浏览器，需要用户关闭该 ZCode 浏览器并重新连接。浏览器被回收或页面加载失败时，可显式 `reopen=true` 从已保存链接恢复；旧操作不会自动重放。任务卡的人为重开也从匹配的 ZCode 配置取完整链接，不用脱敏 URL 重建凭据。

## 真实网页证据

2026-09-14，从用户连接的 Android 设备读取已保存的 `zcode.z.ai` 分享链接，并检查该设备现有 WebView 的页面 DOM 和截图。分享 token 未写入本报告或仓库。

| 页面区域 | 当前 ZCode v4 DOM 标记 | 用途 |
| --- | --- | --- |
| 工作区首页 | `task-item-*`、`data-state`、展开按钮 | 选择任务、识别当前项 |
| 当前任务 | `workspace-title`、`workspace-path`、`v4-session-pane-*` 的 `data-session-id` | 绑定远端任务，检测切换 |
| 对话区 | `v4-timeline`、`data-row-id`、行数和窗口行数 | 紧凑消息、增量读取、覆盖说明 |
| 运行状态 | `chat-loading`、`v4-stop`、`data-input-routing` | 区分工作中、排队和可立即发送 |
| 编辑器 | `v4-composer-input`、`data-lexical-editor` | 通过标准浏览器编辑事件输入 |
| 发送 | `v4-composer-send` | 当前公开静态资源确认的发送控件 |
| 输入区 | `v4-composer` | 限定输入、发送按钮和附加上下文的范围 |

实际运行中的任务使用 `enqueue` 输入路由，停止按钮占据发送位置。本次自动询问仅接受 `startNow`、空草稿、无附件或引用的 composer；不覆盖草稿、不静默排队、不点击停止。未知页面结构明确返回不支持，仍可用通用 WebMount 读取检查。

## 验证边界

- Android：工具、连接配置、域名权限、会话作用域、profile 与调度回执定点测试 58 项通过，包含 app 编译。最后新增 HUMAN 导航与重开恢复检查 3 项通过（另复跑工具 3 项通过），共 61 个不同测试通过。
- JavaScript：`scripts/webmount-bridge-regression.cjs`、`scripts/zcode-ui-tree-regression.cjs` 均通过。
- 真实浏览器：`scripts/zcode-lexical-browser-regression.cjs` 在 Chrome + Lexical 0.50.0 隔离页面通过输入、追加、替换、清空、单次发送及重复 ticket 拒绝。该页面仅向本地数组记录发送，不连接真实账号。
- 真实 Android 现有 WebView：读取真实任务、返回首页和恢复原任务的快照引用操作通过；测得首次紧凑读取约 9,800 字符 / 19 ms，相同预算的通用 `observe` 约 35,700 字符；优化后无变化的增量读取约 4,700 字符 / 7 ms。以上为该页面单次采样的 JS 处理时间与 JSON 字符数量级，不代表完整模型调用耗时或普遍性能保证。
- 真机输入检查曾复现 Lexical 重复插入及清空失效，已恢复空草稿。修复后在上述真实 Chrome 编辑器通过；此时手机断开连接，最终 Android 页面输入复验、安装新构建及真实 ZCode 提问/回答验收尚未执行。没有向用户的远端任务发送验证消息。
- 额外运行的既有 `PreferencesStoreTest.defaultAgentPromptUsesToolSearchForHiddenToolDiscovery` 失败：它要求的旧提示短语与当前 HEAD 的默认提示已经不匹配。本次保留该既有断言，不把它计入通过项；未新增提示文案字符串测试。

真实浏览器回归需要 Chrome，以及可通过 `NODE_PATH` 解析的 `playwright`、`esbuild`、`lexical`、`@lexical/rich-text`；本次使用 Lexical 0.50.0、esbuild 0.28.2。依赖安装在临时目录，不修改 Android 工程依赖。jsdom 回归使用 jsdom 30.0.1。

改动未提交。并发任务正在修改 WebMount 卡片、聊天页和浏览器页外观，本次只增加 ZCode 接线相关 hunk，未将其它 UI WIP 归入本功能结果。
