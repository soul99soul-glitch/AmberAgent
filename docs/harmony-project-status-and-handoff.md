# AmberAgent 鸿蒙迁移 — 项目状态与进度文档

> 更新时间：2026-08-03
> 分支：`harmony-preview`
> 仓库 HEAD：`389ce6ea3`

---

## 1. 项目概述

AmberAgent 是一个 Android AI Agent 应用（Kotlin + Jetpack Compose），正在迁移到 HarmonyOS NEXT（ArkTS + ArkUI）。它不是简单的聊天壳，而是一个完整的 Agent 平台：

- 多 provider 对话（OpenAI / Google / Claude / DeepSeek 等 OpenAI 兼容）
- 流式 Chat UI + SSE streaming
- 工具调用循环（Agent 自主调用工具、审批、续跑）
- 助手配置（system prompt / 参数 / 正则 / 头像 / 技能 / MCP）
- 会话树（分支 / 编辑 / 重新生成 / fork）
- 消息 transformer 管线（模板 / think tag / regex / OCR / 文档 / 图片压缩）
- 子代理（SubAgent）和模型议会（Model Council）
- 搜索 SDK（17 个 provider）
- 记忆系统（召回 / 写入 / dream / 导入导出）
- DeepRead（深度阅读文章生成）
- 小说创作工作台
- 定时任务（Cron）

## 2. 技术栈

| 层 | Android | HarmonyOS |
|---|---|---|
| 语言 | Kotlin 2.3 | ArkTS |
| UI | Jetpack Compose | ArkUI V2 |
| 持久化 | Room + DataStore | relationalStore + Preferences |
| 网络 | OkHttp SSE | @kit.NetworkKit http |
| 模块化 | Gradle 多模块 | hvigor (entry HAP + chat HAR + deepread HAR) |
| 最低版本 | minSdk 26 | HarmonyOS 6.0.0 / API 20 |

## 3. 工程结构

```
harmony/
├── entry/              # HAP — UIAbility + 32 页面 + 51 组件 + platform_impl
├── chat/               # HAR(@amber/chat-domain) — 聊天域逻辑(1716 测试)
├── deepread/           # HAR(@amber/deepread-domain) — DeepRead/Council/Novel(610 测试)
├── hvigorw             # 构建工具
└── oh-package.json5    # 依赖管理

app/                    # Android 源码(只读基准,禁止修改)
ai/                     # Android AI SDK 抽象层(只读)
core/                   # Android 公共工具(只读)
```

## 4. 后端进度（domain logic）

### 4.1 代码量

| 模块 | 行数 | 文件数 | 测试数 |
|---|---|---|---|
| chat HAR | 40,443 | 162 | 1,716 |
| deepread HAR | 7,939 | 55 | 610 |
| entry platform_impl | 6,358 | 39 | — |
| **合计** | **~54,000** | **256** | **2,326** |

### 4.2 后端模块完成度

| 模块 | 状态 | 备注 |
|---|---|---|
| 核心模型(message/conversation/assistant/provider) | ✅ 全量 | |
| AI provider(OpenAI/Claude/Google 请求构建+SSE 解析) | ✅ 三家全量 | fixture 8/8 |
| 工具系统(registry/dispatcher/approval/policy/loop) | ✅ 全量 | |
| 内置工具(9 组) | ✅ | ask_user/conversation/introspection/local/memory/queue/session/time/deepread |
| 流式(accumulator/pending queue) | ✅ | |
| Transformer 管线(9 个 + OCR + 图片压缩 + 文档解析) | ✅ | |
| 搜索 SDK(26 文件 ~4500 行,17 provider) | ✅ 全量 | |
| 记忆系统(17 文件 ~3000 行) | ✅ | |
| SubAgent(9 文件) | ✅ | |
| MCP 协议(6 文件 ~2300 行) | ✅ | JSON-RPC 0.8.4 port |
| Council(6 文件) | ✅ | |
| DeepRead(agent 6 + research 9 文件) | ✅ | |
| Novel(7 文件) | ✅ | Harmony 独有,Android 无 |
| 持久化(RDB + KV + 序列化) | ✅ | 真实 adapter 存在 |
| Cron/scheduler | ✅ 域逻辑 | Worker 执行器 = P1(需 WorkScheduler) |
| OCR | ⚠️ | stage1 是 LLM-vision stub(设计如此) |
| streaming_display 纯逻辑 | ✅ | StreamingCharRevealClock + pacing math |
| reasoning_family 推断 | ✅ | 8 族 + providerRoutingKey + coerce |
| mention_roles 纯逻辑 | ✅ | buildMentionRoleItems + detectMentionContext |
| display_setting | ✅ | 32 字段 + load/save KV |

### 4.3 后端缺失（平台专属,鸿蒙不可行或不适用）

| 项 | 原因 |
|---|---|
| Accessibility 服务 | 平台专属,鸿蒙无等价 |
| 通知监听(NotificationListener) | 平台专属 |
| Cron Worker 后台执行器 | 需 WorkScheduler(P1) |

## 5. 前端进度（UI）

### 5.1 代码量

| 维度 | 数量 | 行数 |
|---|---|---|
| 页面 | 32 | 13,376 |
| 组件(components/) | 51 | 3,718 |
| design 原语 | 3 文件 | 534 |

### 5.2 设备验证状态

**Gate 0 已解锁。** HarmonyOS 模拟器(phone, HarmonyOS 6.1.1)已启动运行,`hdc list targets` 返回 `127.0.0.1:5557`。

HAP 已安装并成功运行。已验证：

| 功能 | 设备验证 |
|---|---|
| 应用启动 | ✅ 正常 |
| 页面导航(Index→ChatList→ChatPage→SettingPage→Provider) | ✅ 全链路通畅 |
| SSE 流式回复(DeepSeek API) | ✅ **完整链路通**(输入→SSE→API→chunk 累积→UI) |
| Markdown RichText 渲染 | ✅ 标题/列表/段落渲染正常 |
| 代码块语法高亮 | ✅ 颜色着色生效(span style) |
| 模型标签显示 | ✅ "DeepSeek / deepseek-v4-flash" |
| ReasoningPicker 段集 | ✅ DeepSeek 3 档(off/high/max)显示正常 |
| FlatTextField mono 模式 | ✅ API key 等宽字体 |
| SearchField 搜索框 | ✅ 渲染正确 |
| SettingPage 索引 | ✅ 4 分组完整 |

### 5.3 设备验证发现的 BUG

| # | 严重度 | 描述 | 根因推测 |
|---|---|---|---|
| 1 | **高** | 消息操作按钮(复制/重新生成/分叉/编辑)不可见 | RichText 不参与 ArkUI 布局测量 → ActionFooter 被挤出可视区或位置错误 |
| 2 | 中 | `uitest dumpLayout` 看不到 RichText 内部内容 | RichText 用独立渲染引擎,不暴露 accessibility 树(ArkUI 已知行为,非 bug) |

### 5.4 前端模块覆盖率（功能矩阵视角）

| Android 模块 | Android 行数 | Harmony 状态 | 备注 |
|---|---|---|---|
| message/(24 文件) | 10,020 | ~14 组件 MVP 级 | ChatMessage* 族基本移植,但渲染质量依赖 RichText |
| richtext/(23 文件) | 9,411 | markdown_html+code_highlight+RichText | HTML 路径;AST renderer/Mermaid/LaTeX 未移植(需 WebView) |
| ai/(15 文件) | 7,518 | ChatInput+TopModelMenu+ReasoningPicker+MentionPanel+3 Picker | ~40% 覆盖 |
| ui/(48 文件) | 7,814 | 49 原语(design/components+ui/) | ~60% 覆盖(G1 新增 26 组件) |
| workspace/ | 976 | 0% | 需文件 sheet UI |
| webview/ | 389 | 0% | 需 ArkUI Web 组件 |
| table/ | 289 | 0% | DataTable |

### 5.5 source-only 前端已穷尽的项

以下项继续堆 source-only 代码无意义,需要设备迭代：

| 项 | 需要什么 |
|---|---|
| LazyForEach 虚拟化 | IDataSource 重构 + 设备性能验证 |
| HighlightCodeBlock 交互壳(复制/折叠/行号) | RichText 不支持嵌入交互组件 → 需原生渲染管线替代 |
| Mermaid / LaTeX | 需 Web 组件 + JS bundle |
| GenerativeWidgetCard 真实渲染 | 需 WebView + GenerativeWidgetParser |
| TTSController | 需 TTS 引擎绑定 |
| camera/file picker | 需平台 picker API |
| 原语动画(LiveDot/BlinkingCursor/DotLoading) | 需设备验证动画效果 |
| 流式 Markdown 增量性能 | 需设备验证(RichText 每 chunk 重解析是否卡顿) |

## 6. 环境信息

### 模拟器
- 设备: HarmonyOS phone emulator
- 镜像: HarmonyOS 6.1.1(24) / API 12
- hdc target: `127.0.0.1:5557`
- 启动: `/Users/mi/Library/Huawei/commandline/commandline-tools/emulator/Emulator -start MyPhone`
- hdc: `/Users/mi/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony/toolchains/hdc`

### 构建命令
```bash
cd harmony && hvigorw assembleHar --no-daemon    # 构建 HAR
cd harmony && hvigorw assembleHap --no-daemon    # 构建 HAP
cd harmony/chat && npm test && npx tsc --noEmit  # chat 测试+类型
cd harmony/deepread && npm test && npx tsc --noEmit  # deepread 测试+类型
```

### 安装到模拟器
```bash
HDC=.../hdc
$HDC file send entry-default-signed.hap /data/local/tmp/entry.hap
$HDC shell "bm install -g -p /data/local/tmp/entry.hap"
$HDC shell "aa start -a EntryAbility -b app.amber.deepread"
```

### 当前 debug 配置
- `module.json5` 中注释掉了 READ_CONTACTS/WRITE_CONTACTS/READ_CALENDAR 等权限(debug profile ACL 不含)
- `AppContainer.ets` 的 `getChatKvStore` 注入了 DeepSeek debug Provider(seedDebugProvider,上线前删除)
- DeepSeek 配置: baseUrl=`https://api.deepseek.com/v1`, model=`deepseek-v4-flash`, apiKey=`sk-bd402b35619b42ec85233a467274d7b2`

## 7. 治理体系

| 文档 | 用途 |
|---|---|
| `MIGRATION_STATE.yaml` | 迁移状态快照(需更新:Gate 0 已解锁) |
| `DECISION_LOG.md` | 决策记录 D-001..D-133+ |
| `PARITY_DEBT.md` | 等价偏差(10 条 open) |
| `docs/harmony-frontend-progress.md` | 前端进度 |
| `docs/harmony-frontend-gap-audit.md` | 前端缺口 audit |
| `docs/goal-ui-primitives-phase1.md` | G1 goal(ui 原语库) |
| `docs/goal-richtext-ai-phase2.md` | G2 goal(渲染逻辑+输入增强) |
| `docs/goal-settings-phase3.md` | G3 goal(设置页生态) |

## 8. 下一步优先级

### P0:修复 RichText 布局 bug
消息操作按钮不可见是当前最严重的 UX bug。根因是 ArkUI RichText 不参与宿主布局测量。可能的修复方向：
1. 给 RichText 包裹固定高度容器(需测量内容高度)
2. 放弃 RichText,改用 ArkUI 原生 Text 组件树渲染 Markdown(工作量大但布局可控)
3. 用 Web 组件替代 RichText(功能最全但内存开销)

### P1:用户视角全功能走查 + 迭代修复
拿着模拟器当真实用户用,发现什么修什么：
- 操作按钮(复制/重新生成/编辑/分叉/删除)交互验证
- TopModelMenu 下拉交互
- 消息编辑/重新生成/分支切换
- 工具调用审批 UI
- 助手配置编辑
- 记忆/搜索/子代理设置页交互
- 会话列表(搜索/置顶/删除/撤销)

### P2:前端覆盖继续推进
按用户可见度优先级,带设备验证迭代推进剩余前端缺口。
