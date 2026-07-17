# Deep Read 鸿蒙 Entry — 编译校正指南

**当前状态：API 26 Command Line Tools 已能编译当前 Deep Read entry stub。推荐从仓库根目录运行 `node scripts/harmony-inventory/build-harmony-app.mjs`。**

本文档列出 entry 模块仍需功能接线的 TODO 标记点，按文件分组给出：当前代码的猜测用法 / 官方 API 参考 / 运行期风险 / 校正方向。当前工程已经能编出 unsigned `.hap/.app`；这些 TODO 不再是“能否编译”的 blocker，而是 P0/P1 真实能力验证前必须补的功能缺口。

> 参考文档(HarmonyOS API 26):
> - RCP: https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-rcp
> - relationalStore: https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-data-relationalstore
> - preferences: https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-data-preferences
> - notificationManager: https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-notificationManager

---

## 1. `platform_impl/RcpHttpClient.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L4 `createSession` 配置 | `rcp.createSession()` 无参 | `createSession` 签名要求 config 对象 | 按文档补 `RcpSessionConfig`(timeout / ca / proxy),或传 `{}` |
| L32 `s.fetch(url, params)` | `method as rcp.RequestMethod`、`headers: Record` | `RequestMethod` 枚举名 / `RequestParams` 字段名不符 | 以文档实际枚举与字段为准(可能是 `rcp.HttpMethod.GET` 或字符串字面量) |
| L47 `fetchStream` SSE | 抛 not implemented | — | API 24 下用 `session.fetch(url, { event:'dataStream', ... })` 注册 chunk 回调,接 `sse_assembler.ts` 的 `createSseStreamHandler` |
| L55 `uint8ToText` | 自写 UTF-8 解码 + `escape/unescape` | ArkTS 可能禁 `escape` | 换 `@ohos.util.TextDecoder('utf-8')`:`new util.TextDecoder().decode(new Uint8Array(buf))` |

## 2. `platform_impl/RdbRepository.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L24 ability context | `(globalThis as ...).__abilityContext` | ArkTS 严格模式禁 `globalThis` 动态访问 | 从 EntryAbility `onCreate` 把 `this.context` 写入 AppStorage,或通过 `getContext()` 全局 API;getRdbStore 第一参必须是 ability context |
| L48-54 建 schema | `executeSql(sql)` 多条 | 可能在事务外执行多条报错 | 用 `store.batch()` 或逐条 `executeSql`(当前已是逐条,通常 OK) |
| L64 upsert | 先 query 再 update/insert | `ValuesBucket` 类型 / `update` 第二参类型 | 官方 `ValuesBucket` = `Record<string, ValueType>`;或用 `insertWithConflictResolution(sql, bucket, ON_CONFLICT_REPLACE)` 一步 upsert |
| 全文 `RdbPredicates.getColumnIndex` | rs.getString(columnIndex) | 字段名 `output_json` 含下划线可能需引号 | 列名用反引号或确认 schema 列名一致 |

## 3. `platform_impl/PreferencesStorage.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L6 `getPreferences(context, name)` | 从 `globalThis.__abilityContext` 取 context | 同 RdbRepository 的 context 问题 | 同上:用 AppStorage 或 `getContext()` |
| `p.get(key, '')` 默认值类型 | 传字符串默认值 | `get` 泛型 / 默认值类型 | 文档:`get<T>(key, defValue: T)`,确保类型一致 |

## 4. `platform_impl/OpenAiCompatibleAiClient.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L63 `tools` 字段序列化 | 当前不发送 tools | 模型无法调工具 | 把 `exposedTools`(deepread 的 ToolDefinition)序列化成 OpenAI tools schema(`{type:'function', function:{name, description, parameters}}`)放入 body。deepread 的 ToolDefinition 有 name/description,parameters schema 可由各 writer tool 补 |
| L102 SSE 流式 | 非流式 callModelOnce | 无流式 UI(功能可用但无渐进显示) | stream=true 时改用 RCP dataStream:chunk → `sse_assembler.createSseStreamHandler` → `append_chunk.appendChunk` 合并 → 33ms throttle 的 `onMessagesUpdate`。纯逻辑全在 deepread |
| L114 retry 包装 | 无重试,429/5xx 直接抛 | 偶发失败无重试 | callModelOnce 外包一层:`classifyError` → `isRetryableCategory` → `decideRetry` → `delayForAttempt` 指数退避(纯逻辑全在 deepread/retry_classifier.ts) |

## 5. `platform_impl/SearchRegistry.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L24 jina reader | 返回空数组 | 无兜底搜索 | 接 `https://s.jina.ai/<query>` 返回的 markdown 正文,转 SearchHit[](免 key) |
| L32 enabled() 同步契约 | `enabled: () => cachedEnabled`(同步) | `SearchProviderRegistry.enabled` 契约是同步,但 apiKey 读取是 async | 两种方案:(a) 在 deepread 把契约改 `Promise`;(b) AppContainer 初始化时先 await apiKey 写入同步缓存。推荐 (b),改动小 |

## 6. `platform_impl/NotificationNotifier.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L6 publish/cancel 签名 | `notificationManager.publish(request)` | `NotificationRequest` 字段名不符 | 以文档为准:`contentType` / `normal` / `notificationSlotType` 的枚举值 |
| L36 Want 跳转 | 未实现 | 点通知不回 app | `NotificationRequest` 的 `wantAgent` 或 `actionButtons` 配置跳 `DeepReadArticlePage`,传 topicId |

## 7. `di/AppContainer.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L37 PLAYBOOK_MD 空串 | 空 playbook | 规则缺失(非阻塞) | 用 `resourceManager` 从 `rawfile/playbook.md` 读 |
| L47 model 硬编码 `'deepseek-chat'` | 写死模型 | 用户无法配模型 | 从 preferences 读 `board_model`(对照 Android `resolveModel`) |

## 8. `components/ArticleRenderer.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L6 markdown 渲染 | 纯 Text 直出 | 无 markdown 格式 | MVP:接 ArkUI `RichText` 组件;或自建轻量 parser。§7.3 已记风险 |
| L145 diagram 渲染 | 只列节点文本 | 无图形 | 用 `Canvas` 或 SVG 绘制节点+边(对照 `output.diagram`)。可后置 |

## 9. `pages/DeepReadArticlePage.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| L126 流式追加 | 显示"正在深度阅读…"+ LoadingProgress | 无流式内容 | 订阅 output 时,把 summary/timeline 边生成边追加(需要 §4 的 SSE 流式 onMessagesUpdate) |

## 10. `pages/HistoryPage.ets` & `pages/Index.ets`

| TODO | 当前猜测 | 预期错误 | 校正方向 |
|---|---|---|---|
| HistoryPage L18 / Index L29 recent 列表 | 返回空 | 无历史显示 | 在 deepread Repository 接口补 `listRecentDeepReads(sinceMs, limit): DeepReadOutput[]`(查 RDB `updated_at > sinceMs`),两处调用 |

## 11. `entryability/EntryAbility.ets`

| 潜在点 | 当前 | 预期 | 校正 |
|---|---|---|---|
| `want.parameters as Record<string, object>` | 类型断言 | ArkTS 严格模式对 `as` 有限制 | 用 `Want` 的类型化访问或 `interface` 声明参数结构 |
| `AppStorage.setOrCreate` | 泛型调用 | 通常 OK | — |
| `router` 未用 | 已 import 但 Index/ArticlePage 用 `router.pushUrl` | OK | — |

---

## 编译/验证顺序建议

1. **命令行构建** — 从仓库根目录运行 `node scripts/harmony-inventory/build-harmony-app.mjs`。当前已验证 `BUILD SUCCESSFUL`，产物 unsigned。
2. **连接设备/模拟器 + 配签名** — `hdc list targets` 不能再是 `[Empty]`，并配置 debug signing。没有这一步不能做 runtime spike。
3. **跑 entry runtime smoke** — 安装并打开当前 Deep Read stub，确认首页能启动、页面路由不崩。
4. **补 RCP SSE + retry** — `OpenAiCompatibleAiClient` 接 RCP dataStream → `sse_assembler` → `append_chunk`，这是 H0-01 的真实验证路径。
5. **补 RDB + Preferences 真实现** — 当前存在 memory fallback/TODO，不能算 H0-02/H0-03 通过。
6. **补 Markdown/streaming UI** — 当前 ArticleRenderer 仍是基础展示，不能算 H0-04 通过。
7. **Rust NAPI reader** — 可选性能升级，最后做。

## 逻辑层已验证的部分

deepread 450 tests pass,tsc clean:
- domain(models/enums/helpers/topic_id)
- platform 契约 + observable/sse_assembler/json_schema_validator
- agent(message/append_chunk/budget_prompt/retry_classifier/tool_execution/agent_loop/section_writer_tools/supervisor_loop/try_fallback/run_manager)
- research(url_filter/reader_extractor/image_scorer/search_provider/search_merger/source_prefetcher/evidence_pack/article_plan)
- 验收:`run() → isComplete()=true`(4 stages READY,phase COMPLETE)
