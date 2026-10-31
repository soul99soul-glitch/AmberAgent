# Android P4 决策：跨端任务、审批与数据保留（A4.1–A4.4）

> 2026-10-31，Android P4。范围依据 `amber-four-platform-phase-plan.md` §7 P4。
> 结论分三类：**本阶段已做并验证**、**本阶段明确不做（有决策理由）**、**待 macOS M4.1 后才能做**。

## A4.2 四栏看板：不做，保留任务监控列表

**决策：Android 不实现四栏看板（Draft / In Progress / Awaiting you / Done），保留现有 Mac Gateway 任务监控列表。**

理由来自代码，不是偏好：

1. **数据源不支持分栏。** `GET /v1/status`（`MacGatewayClient.status()`）返回 `MacGatewayStatus.Session`，字段只有
   `key / agent / subject / origin / host / state / waitReason / abnormal / monitored / updatedAt`。
   `state` 是 `running / waiting / stalled / completed / 其他` 的扁平枚举，`waitReason` 只有一个 `permission` 分支。
   四栏想要的 Draft、Awaiting-you、Done 边界里的「待我批准 / 待我输入 / 失败 / 需复核」在协议里不存在，
   Android 侧只能靠 `state` 猜。猜出来的列和真实 run 状态对不上，正是计划 M4.3 明令禁止的「看板不真实」。
2. **写入方向是单向的。** Android 只有 `setMonitored`、`registerPushToken`、`testPush`、`unpair` 四个写端点，
   没有任何「把任务移进某列」的能力。做四栏就等于做一个点不动的看板。
3. **替代方案已经存在且够用。** `SettingExperimentalMacGatewayPage` 已有任务监控列表：连接状态、健康度、
   逐任务 `state` 文案（含 `waiting` + `waitReason=permission`）、per-task 监控开关、Synara 深链。
   它回答的是手机端真正要回答的问题——「Mac 上有没有事等我」。

### 本阶段为此补的一处真实缺口

列表原先直接 `status.sessions.take(20)`，依赖网关返回顺序。10 秒轮询（`REFRESH_INTERVAL_MS`）下，
只要网关换序，行就在用户手指底下重排；更糟的是 `take(20)` 可能把正在等人工决定的任务挤出去。

- 新增 `MacGatewaySessionOrdering`（`app/src/main/java/app/amber/feature/macgateway/MacGatewayClient.kt`）：
  `waiting` → `running` → `stalled`/`abnormal` → 其他 → `completed`，同级按 `updatedAt` 倒序、再按 `key` 兜底。
- 页面改用 `MacGatewaySessionOrdering.visible(sessions, 20)`，先排序再截断，等待人工的任务不会被完成态挤掉。
- 这是展示层排序，不改协议、不改写方向，`monitored` 开关行为不变。
- 测试：`MacGatewayClientTest` 新增 4 项（等待优先、同时间戳稳定、截断不丢等待、异常态归位），该文件 7 项全绿。

## A4.3 数据保留 / 导出 / 备份：已完成并可测试

原始 transcript 不被压缩投影覆盖，这条原本没有测试兜底。新增
`app/src/test/java/app/amber/agent/data/context/ConversationDataRetentionTest.kt`（5 项，全绿），锁住：

| 断言 | 事实来源 |
| --- | --- |
| 压缩后消息条数、消息 id、消息文本三者都不变 | `ConversationContextEngine.compactInternal` 只调 `contextRepository.insertCompact` / `insertEvent`，不碰 message 表；`ConversationContextRepository` 唯一删除是 `invalidateCompacts` 删 compact 行 |
| 压缩后 `expand` 仍能取回原文并可标 stale | `ConversationContextRepository.expandResult` 按 `sourceMessageIds` 从当前 transcript 过滤 |
| 备份同时包含 transcript 与 compact | `SyncArchiveManager.SYNC_TABLES` 含 `conversationentity` / `message_node` / `conversation_compact` / `conversation_context_event`；`CONVERSATION_TABLES`（本地会话保留开关）同样含这三张 |
| 删除会话连带删除 compact 与 context event | `ConversationCompactEntity` / `ConversationContextEventEntity` 都声明 `ForeignKey(onDelete = CASCADE)` 指向 `conversationentity`；`ConversationRepository.deleteConversation` 只 `conversationDAO.delete`，靠 CASCADE 清理 |
| compact 保留 `source_message_ids`，原文永远可回溯 | `CompactSummaryPayloads` v2 payload + `ConversationCompact.sourceMessageIds` |

删除语义现状：会话删除有 5 秒撤销窗口（`HomeDeleteUndo` / `HomeDeleteUndoBar`），撤销期内文件不清理
（`deferCleanup`），窗口结束后 `cleanupDeletedConversation` 才删附件与生成图。**没有**「删除后不可恢复」的文案，
这也是对的——撤销窗口内确实可恢复；窗口外由 CASCADE 与文件清理保证不再残留。

## 本阶段发现并修掉的一个真实入口缺陷

`Screen.SettingExperimentalMacGateway` 页面实现了、路由也注册了，但**全仓没有任何 `navigate(Screen.SettingExperimentalMacGateway)`**。
唯一的路径要经过 `Screen.SettingExperimental`（一个「实验特性」汇总页），而后者自己只被注册、从没被 navigate 过
（`SettingsPage` 的 ADVANCED FEATURES 分组直接连 WebMount / SubAgents / Model Council / MiniApps / Novel / Deep Read /
iCloud / Synara / ZCode，绕过了汇总页）。结果是 **Mac Gateway 功能在产品里完全不可达**：每个页面都能编译、能单独渲染，
所以没有任何单测发现它。

修法：
- `SettingPage` 的 ADVANCED FEATURES 分组在 ZCode 之后新增 Mac Gateway 行（`Lucide.Laptop`），直接连
  `Screen.SettingExperimentalMacGateway`。
- 新增 `setting_page_mac_gateway` / `setting_page_mac_gateway_desc` 两个字符串。
- 新增 `SettingEntryPointReachabilityTest`（3 项）：Mac Gateway 必须可从顶层设置页进入；四个实验子页都必须有调用点；
  设置行必须有独立标题串。该测试已实测能在修复前失败（不是事后凑的断言）。
- 模拟器实测：设置 → ADVANCED FEATURES → Mac Gateway → PAIR 页，浅色/深色截图各一张，布局无错位、边距一致。

## A4.1 Gateway 配对 / 任务监控 / FCM：代码在位，FCM 与真机未验证

已存在并读过的链路：
- 配对：`MacGatewayPairingPayload.parse`（QR/粘贴，校验 `v==1`、非空 addrs、端口、指纹、secret）→
  `MacGatewayClient.pair` → token 与连接信息存 app-private DataStore。
- 任务监控：`status()` 轮询 + `setMonitored()`（乐观更新，失败回滚并报错）。
- FCM：`MacGatewayMessagingService` 处理新 token 与前台消息；后台由系统按 `mac_gateway` 频道自展示。

**未验证（记 CONDITIONAL）**：真实 Mac Gateway 配对、真实 FCM 投递、锁屏推送点击。原因是没有可用的
macOS Gateway 实例与 FCM 凭据，且 P4 契约（M4.1）尚未在 macOS 侧冻结。

## A4.1 审批决策（普通风险手机批准 / 高风险手机只可拒）：本阶段不做

`MacGatewayClient` 的写端点只有 `monitor` / `push-token` / `test-push` / `unpair`。
**普通风险批准与高风险拒绝在 Android 侧没有任何端点可用**，协议由 macOS M4.1 冻结。
本阶段不臆造端点、不写假 UI。计划里这属于依赖 M4.1 的部分，按协调者指示留给下一个阶段。

## A4.4 C3/C5 合同 fixture：本阶段不做

同因：fixture 必须与 macOS 冻结后的协议同版。现在写只会写出一份自己编的合同。

## 待做清单（依赖 macOS M4.1）

1. `POST /v1/approvals/{runId}` 类端点到位后，接普通风险批准 + 高风险拒绝（高风险批准必须在 Mac 完成）。
2. 过期/旧 run 的批准不生效：需要 `runId` + `expiresAt` 字段，客户端做前置校验 + 服务端复核。
3. 审计记录含 run / tool / 决定：需要协议返回 `decisionId` 与 `decidedAt`。
4. C3（Gateway 协议）与 C5（看板状态模型）fixture：与 macOS / iOS / 鸿蒙同版后再落。
5. 四栏看板：**若** M4.1 之后协议提供了真实列状态，再重新评估；当前决策是不做（见 A4.2）。
6. FCM 真机验证：真实 Gateway + 真机 + 锁屏点击。

## 未做但已确认无需做

- **`ocr_text` 未脱敏**：P1 review 提出，实为误报。`ScreenPrimitiveTools.appendOcrTelemetry` 里
  `ocr_text` 与 `ocr_blocks[].text` 都走 `SensitiveScreenTextClassifier.redactIfSensitive`，
  `sensitive` 标记来自同一分类器，二者不矛盾。
- **回执 i18n**：按协调者指示留到 P5。
