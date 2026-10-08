# DeepRead / Research / Council 审查

审查基线：2026-10-02 当前工作区（任务入口 HEAD `5ea51f7a`，保留 WIP）。只读审查；没有修改生产源码。已读 `harmony-arkts` skill 与 `2026-09-30-harmony-deepread-product-completion.md`，以已有产品完成契约和活跃调用点裁定，未把明确排除项当作缺陷。

## 已确认问题

### DR-01 / P2：议会信号量排队席位读到同轮发言

- 位置：`harmony/deepread/src/main/ets/council/manager.ts:393`、`:422`、`:423`。
- 触发：DEBATE、至少 5 个席位共享模型（允许最多 8 席），第 2 轮及之后。前 4 席占有 semaphore；后面的席位等前面的席位完成，再基于已经变动的 `run.turns` 构建 prompt。
- 损害：前 4 席只看到前轮结果，排队席位额外看到先完成的**本轮**观点；末轮也有同样问题。结果受请求完成顺序影响，议会的并行公平对照和前轮摘要边界失效。
- 二次复核：实际调用 `ModelCouncilManager`，6 席同模型、3 轮，mock 每轮输出 `ROUND_<round>_SEAT_<id>`。第 6 席的第 2、3 轮 prompt 均含同轮 marker；运行仍 `completed`、18 turns。不是 by design：manager 顶部明确写“第 2..N 轮喂前轮摘要”。
- 最小修复：每轮启动时取前轮 turns 快照或在构建 prompt 时过滤 `turn.round < round`；不新增调度器。
- 失败测试：同模型 6 席延迟完成，断言第 2/末轮所有请求只含小于当前轮的 marker。

### DR-02 / P2：裁判空回答被记录为完成

- 位置：`harmony/deepread/src/main/ets/council/manager.ts:538`–`:541`；完成判定 `:326` 的 `synthOk = result.error.length === 0`。
- 触发：席位成功，但裁判 provider 正常 resolve `{text: '', warnings: []}`（包括空流/空响应），或空白回答。
- 损害：run 标为 `completed`，最终建议为空；UI 用户会看到已完成但没有裁决。席位路径已有 `content.trim()` 空回答检查，裁判遗漏同一规则。
- 二次复核：3 个席位回答 `valid seat opinion`，裁判返回空字符串，实测 `completed`、`finalRecommendation: ''`、`error: ''`。不是 by design：只有一个非结构化 JSON warning，不能表示真实生成失败。
- 最小修复：裁判空文本进入现有失败结果，保留成功席位，最终 `partial_failed`。
- 失败测试：空串/空白裁判返回；有正常裁判文本为正向回归。

### DR-03 / P1：既有私网 URL gate 漏掉等价地址

- 位置：`harmony/deepread/src/main/ets/research/url_filter.ts:50`–`:76`；消费者 `research/source_prefetcher.ts:148` 附近 `urlAllowedForBackgroundFetch(...)`。
- 触发：来源/seed URL 是 `http://localhost./`、`http://[0:0:0:0:0:0:0:1]/`，或 `http://[fe90::1]/`。这些均被返回 `false`（非私网），即使两项自动批准均 false，也会尝试后台抓取。
- 损害：`localhost.` 是合法绝对 DNS 名；展开 IPv6 loopback 是 `::1` 的等价形式；link-local 属 `fe80::/10`，不止 `fe80:`。既有保护明确拒绝 localhost、loopback、link-local，但这些合法拼写绕过保护。直接读取失败还可能把该 URL 交给 Jina Reader。
- 二次复核：domain 实际 probe 对 canonical localhost/::1 返回 true、上述三种 false；本 Mac 的 `dns.lookup('localhost.')` 返回 `::1` 和 `127.0.0.1`；标准 URL 将完整 IPv6 loopback 归一为 `[::1]`。不是要求全新 DNS/redirect 防御，属于既有地址分类自身不一致。
- 最小修复：规范化 hostname 末尾 root dot；正确解析 IPv6 等价 loopback/unspecified、link-local 前缀和 mapped v4。保留现有批准语义，不增加请求重试或 DNS 探测系统。
- 失败测试：上述 URL 被拒绝；公网 FQDN/public IPv6 仍允许；两项自动批准都打开时遵循既有私网放行规则。

### DR-04 / P2：视觉工具宣告参数和执行参数不一致

- 位置：`harmony/deepread/src/main/ets/agent/section_writer_tools.ts:472` schema vs `:477`、`:479` 的读取；生产映射 `harmony/entry/src/main/ets/di/AppContainer.ets:231` 原样透传 schema。
- 触发：模型按提供的 tool schema 调用 `deep_read_write_visuals`，传 `hero: {url, caption}` 和 `inline: [{url}]`。
- 损害：执行器只读取 `hero_image_url`、`hero_caption`、`image_assets`，将模型依据契约选的有效图片全部丢弃，却返回 `status: ok`。真实候选存在时也会发生，不是空候选/质量过滤的设计。
- 二次复核：注入一个合格 hero 候选，使用 schema 宣告字段执行；返回 ok，`heroImageUrl === null`、`imageAssets.length === 0`。schema 里没有任何执行器实际读取的字段。
- 最小修复：使工具 schema 与已有执行字段相同（含候选 URL/caption 及必要 array item 字段），保持候选池质量 gate；不要再增加兼容解析层。
- 失败测试：依据模型可见 schema 构造的 hero/inline 请求实际写入文章；不在候选池的 URL 仍拒绝。

### DR-05 / P2：图解工具不向模型声明必需 type

- 位置：`harmony/deepread/src/main/ets/agent/section_writer_tools.ts:521` schema；`:770` 的 `parseDiagram` 必需类型校验。
- 触发：模型按声明的 title/nodes/edges/caption 提交图解；schema 没有 `type`，description/prompt 也没列出允许类型名。
- 损害：有效 title、两个合法 nodes、合法 edge 的请求被报 `invalid type`，图解无法写入。模型只能凭未声明的执行细节猜参数；实际失败不是按设计的 optional 隐藏。
- 二次复核：依据当前 schema 执行节点 a/b 和 a→b，实测 `missing_required_content / invalid type`。
- 最小修复：补充既有类型 enum/required 字段及 nodes/edges items 参数，不改变图解域模型和尺寸限制。
- 失败测试：当前 schema 能表达一个可被解析接受的图解；未知 type 仍被拒绝。

## 排除的假阳性 / 设计边界

- **固定 article WantAgent requestCode=991 导致多话题通知串页：排除。** 初看像 Android PendingIntent identity 问题，二次查 OpenHarmony API12 基线源码后发现 `Want::IsEquals` 明确比较 `parameters_ == want.GetParams()`，`PendingWantManager::CheckPendingWantRecordByKey` 使用它。不同 topic parameters 不是相同实例，不能据 Android 经验修改代码。证据：[Want::IsEquals (v5.0.0)](https://github.com/openharmony/ability_ability_base/blob/OpenHarmony-v5.0.0-Release/interfaces/kits/native/want/src/want.cpp#L1259)、[PendingWantManager key 比较](https://github.com/openharmony/ability_ability_runtime/blob/OpenHarmony-v5.0.0-Release/services/abilitymgr/src/pending_want_manager.cpp#L159)。
- **没有跨进程 durable 自动续写：by design。** 已有完成契约明确不纳入全局 durable 队列/无损跨进程续写；当前中断缓存显式继续不是 bug。
- **force 重生成期间旧稿继续显示 COMPLETE：by design。** 旧稿成功替换前保留；scheduler 真实活动作业优先于 phase 消费，不应以此新建第二套持久状态。
- **LLM 非结构化输出降为 BASIC section：by design。** 现有 supervisor/writeFallbackSection 明确有基础稿降级，不能仅因 BASIC 质量而要求移除所有降级；本轮没有将其算作新问题。
- **DeepReadRunConfig 旧配置函数、conceptual fallback provider 返回空：暂不算问题。** 活跃生产 DI 已用聊天 Provider runtime 和 SearchRegistry snapshot；不能只根据遗留文件字符串认定主功能缺失。
- **API12/13 不支持 PDF：by design。** 已有 API14 能力门和准确提示，并非“需要补一个 PDF 引擎”。

## 证据与验证

- 临时确定性 probe：`/tmp/amber-deepread-review-probes.mts`，直接导入实际 domain 实现，覆盖 DR-01/02/03/04/05。原样输出确认上述反例；未调用真实付费 Provider/私网 HTTP。
- 既有聚焦测试：`council_manager`、`scheduler_signal`、`product_p1`、`url_filter`、`section_writer_tools` 共 **110/110 通过**，exit 0（7.29 秒）。这说明现有回归未覆盖上述反例，不代表这些问题不存在。
- 源码覆盖：run manager、scheduler、source prefetcher、evidence/article plan、writer/supervisor/agent loop、Council manager/validator/prompts、Entry DeepRead page/history、HotList provider/state/aggregator/Board、run repository/artifact/export、generation tracker/activity/background keep-alive/notifier。
- 本审查没有对 UI 几何、真实 Provider、后台长时系统通知或真机视觉作已通过声明。
