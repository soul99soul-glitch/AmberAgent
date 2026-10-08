# Phase 4 T1–T7 独立二次复核

范围：只读取 Android 仓库当前生产代码、对应回归测试和 `/tmp/amber-android-review-20261002-baseline` 原始副本；未改生产代码，未运行 Gradle，未读取其他产品。报告写入为本 reviewer 唯一修改。

结论：七项原问题均是真问题；当前修复与触发条件匹配，未发现阻塞合入的新缺陷。没有以猜测的资源泄漏、未知厂商行为或新增通用策略要求扩展修复。测试是否 green 由 root 串行执行确认，本报告只给源码与夹具复核结论。

| ID | 二次判定 / 基线触发 | 当前修复核对 | 结论 |
| --- | --- | --- | --- |
| T1 | 真问题。两个 reader 每个 `InputStream.read` 的字节块直接构造 String；UTF-8 多字节落在块边界就会被替换字符破坏。不是日志的设计性截断。 | `TerminalRuntime.kt:449,721` 各建立一个 InputStreamReader，整个读取生命周期复用其 decoder。CharArray 大小大于一个 surrogate pair，不存在单字符 read 的 pair 拆分接口问题。后续 append/output/log 仍调用原路径。`stopSession:415` 与 `terminateProcess:953` 的实际进程停止、等待及 lease 归还路径保持原实现；这次不引入新的取消作业或独立 reader 所有者。 | 通过。生产私有 reader 的单字节受控 stream 回归确实复现原 bug；不是复制算法的测试。不能据此声称设备进程取消已做实测。 |
| T2 | 真问题。占位符顺序 fold 把用户 title/summary 中形似后续 placeholder 的字面文本再次替换，正文语义改变。 | `DeepReadTemplateRenderer.kt:208` Regex 只遍历原 templateHtml；lambda 返回字符串不二次扫描，也不把 `$` 或反斜杠当 replacement 指令。所有现有 map key 均符合 `[a-z_]+`。未知 placeholder 原样保留，与基线一致。HTML 转义、安全图片/链接及 CSS 处理保留。 | 通过。新测试同时断言 title/summary 字面 token 保留和真正 analysis slot 展开。 |
| T3 | 真问题。force 先 DELETE，模型不可用也丢失 pin/source；DELETE 后 title fallback 可复用同标题旧正文。 | `DeepReadAgentRunManager.kt:376–417` 在模型解析及 TOOL 能力准入成功后才 reset output。reset 经 repository/DAO 保留当前 pin；无新 source 的写入保留已有 source。空 direct row 是可解析且 fresh 的 DeepReadOutput，`fresh` 先读取 direct，阻断另 topic 同标题 fallback。随后 collecting/error stage 不重新拾旧 content。 | 通过。invalid-model 测试实际调用 manager；admitted-force/no-source 测试实际 prefetch 空源、检查本 topic 空正文且另一 topic 正文未改。preview 的 clear 是其临时 preview namespace 的清理合约，by design，不扩展为删除所有缓存的 bug。 |
| T4 | 真问题。generation 在 restore gate 前读取 pin/source，等待期间用户 pin/unpin 后 REPLACE 把旧值写回。 | `HotListRepository.kt:187–208` 不再提前捕获 metadata；`HotListDAO.kt:75–99` 在 Room transaction 内 UPDATE 生成字段，SQL不修改 pinned；source_url=COALESCE 显式新值, 当前值。不存在 row 时才 INSERT，插入与检查同事务串行。仍位于原 owner/restore gate 内，stale owner 的 epoch 语义保持。 | 通过。真实 Room + 真 write gate 的 pin/unpin 两个方向回归，等待期间真实用户 UPDATE，能识别旧 REPLACE 行为。显式新非空 seed URL 替换来源与既有合约一致，并非必须永远保留原来源。 |
| T5 | 真问题。固定 sanitized basename 文件路径使不同目录、不同中文名称或重复分享覆盖已授权 URI 的字节。 | `ShareAccessTools.kt:78–88` 先通过既有 capped workspace reader 获得完整快照，每次写 UUID 子目录，原 safe basename/MIME 与分享审批保留。现有 `file_paths.xml` cache root 覆盖 UUID 后代路径，因此无需扩大 FileProvider 权限。 | 通过。三项真实 SAF/WorkspaceManager 回归检查旧 snapshot 路径和字节不变。没有添加与实际冲突无关的缓存管理系统。 |
| T6 | 真问题。Events 中 recurring row 用 DURATION，query.getLong(DTEND) 得 0；标题/位置更新却验证无关 end > start，拒绝有效非时间更新。 | `CalendarAccessTools.kt:241–261` 仅实际 time 参数触发时间校验；非时间 ContentValues 从已构建字段返回，不添加 DTSTART/DTEND。缺 DTEND 时拒绝时间修改是局部且明确的能力边界：原 start-only 修改本就失败，而提供 end 会写入不适用于该 recurring row 的 DTEND。此次没有声称支持 occurrence/series recurrence 编辑，避免为此实现额外时间模型。普通非循环单侧时间变更仍用未变侧校验。 | 通过。非时间 recurring 与拒绝非法 recurring DTEND 两项测试覆盖边界；修改仍经过原显式 approval。 |
| T7 | 真问题。max + thenByDescending(-accuracy) 两次反向，时间相等时选 50m 而非 5m。 | `LocationAccessTools.kt:71–74` 仅去掉 accuracy 的负号，保持 max 时间主序，同时间选更小 accuracy。较新时间仍优先，未引入年龄/精度过滤策略。 | 通过。equalTimestamp 和 newerTimestamp 两项调用真实 latestLocation，分别保护 tie 修复及原主排序。 |

## 复核过但不作为新问题的点

- reader 未新增 `use` 并不意味着本次修复引入流泄漏：原 InputStream/Process 所有权和停止流程没有改变，没有可复现失败时不改外围生命周期。
- force 在准入后若预取无源，留下空 content 是显式重建尝试的状态；pin/source 保留。准入失败前丢用户记录才是原 bug，已经修复。
- DeepRead save 提供非空 sourceUrl 时替换来源是原合约；只有不带 seed 的后续生成写入保留当前来源。
- Calendar recurring 时间编辑拒绝属于明确能力范围，非时间修改可继续；不用假 DTEND 或 guessed duration 兜底。
- 分享文件系统缓存自然可能保留多个快照，但删除旧快照会破坏已有 grant；无用户要求和具体容量故障不扩展出保留策略。

## root 交付门

需要 root 定点及全量 green：`app.amber.review.TerminalUtf8OutputTest`、`DeepReadMetadataTest`、`ShareFileSnapshotTest`、`DeepReadTemplateValidatorTest`；tools:access Calendar/Location tests，并结合模块全测/最终 app build。JVM与源码证据不替代设备原生终端、系统 Calendar provider 或第三方接收 app 的验收。
