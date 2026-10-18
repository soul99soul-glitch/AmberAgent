# Harmony 全链路审查与分阶段修复收尾

基线：`harmony-preview` / `e6d6c26b6bf151ee3c58cbaa0b4e2acf61deac55`。

结论：**26 项确认问题（5 项 P1、21 项 P2）均已完成修复和独立复核**。其中 22 项修复在本轮接手时已存在，本轮完成逐项复核、整合和交付检查；另补修二审发现的 R18、R19、R20、U05。此结论限于下述审查范围与实际验证，不代表仓库不再存在其他缺陷。

本轮接手时已有 [22 项问题报告](2026-10-18-comprehensive-review.md)，以及相应未提交修复和测试。原报告“修复尚未执行”的结论不能代表接手时的工作区。本轮保留这些修改，逐项沿当前生产路径核实，并由独立 subagent 检查修复与相邻边界。没有重置、清理或提交工作区。

范围为当前 Harmony 产品，覆盖会话与工具、Provider/上下文、媒体与存储、记忆与搜索、小说工作区和 Smart UI。Android/Web 全量实现及相邻独立 DeepRead 产品不在本轮范围。

## 问题清单与阶段

下列 22 项沿用原审查编号和严重度。具体原始复现、位置与判定见原报告；本轮以当前修复、生产调用链和回归为准。

| 阶段 | 编号 | 严重度 | 确认问题 | 修复落点 |
| --- | --- | --- | --- | --- |
| 1 数据与生命周期 | R01 | P1 | 中间 assistant 再生失败保存截断会话，丢失旧分支及后续节点 | 每次持久保存和 Service checkpoint 均投影回完整会话 |
| 1 | R02 | P1 | 失败视频下载关闭 fd 后仍有排队写入，可污染复用 fd 的文件 | 终止后停止排队写，等待活跃写完成再关闭 |
| 1 | R05 | P1 | 小说目录替换中断后项目失去列表和恢复入口 | 使用冷启动认识的 backup/staging 切换协议，保留恢复证据 |
| 1 | R06 | P2 | 历史回答工具审批后从会话末尾续跑 | 保留工具所属节点与活动前缀，审批、授权和写回使用一致边界 |
| 1 | R09 | P2 | HTTP 默认 5 MiB 限制阻断允许范围内的图片缓存 | 显式传入 25 MiB 图片接收上限 |
| 1 | R10 | P2 | 十分钟节流跳过容量收敛，下载可积累超额缓存 | 新文件发布后检查容量，低频过期扫描单独节流 |
| 1 | R16 | P2 | 从 head 恢复正文覆盖完好的用户 project.md | 恢复时保留现有项目文档，仅缺失时创建占位 |
| 1 | R17 | P2 | 前台打开项目抢占活跃后台润色，重复请求模型 | 前后台使用同一项目任务文件锁判断真实执行者 |
| 1 Standards | S01 | P2 | Blob 短写被发布为完整文件且永久命中 | 完成全部写入后才发布，失败不提交正式 blob |
| 2 协议与上下文 | R03 | P1 | JEV 请求/响应 codec 不符合供应商协议 | 使用各官方 criteria/noul/choice 方言及 Vercel v4 请求头 |
| 2 | R04 | P1 | OpenAI Chat、Responses、Google 丢弃工具图片 | 按各协议保留图片、调用身份和输出次序 |
| 2 | R07 | P2 | 图片预算晚于上下文裁剪，旧截图挤掉用户任务 | 先生成有预算的请求副本，再估算、编辑及压缩 |
| 2 | R08 | P2 | 原生 Context 显示把图片 token 再除以 4 | 在字符等价累加器中正确换算单位 |
| 3 记忆与搜索 | R12 | P2 | 独立 recency_days 和 quick 时间要求未发送 | 深度截断后在实际查询中保留有效时间约束 |
| 3 | R13 | P2 | 到期事实通过未过期主题进入 prompt | 召回时基于当前源事实有效性生成主题可用视图 |
| 3 | R14 | P2 | 删除最大记忆 ID 后复用，旧引用命中新记录 | 记录与 ID 高水位同文件原子持久化，所有新增路径在写队列分配 |
| 3 | R15 | P2 | 上文助手分支变化后仍提交旧上下文提炼 | 提交 guard 比较真实提炼输入 fingerprint |
| 4 产物与交互 | R11 | P2 | 图片索引与正文文本分组不同，漏收图片或误收代码示例 | 复用正文的连续文本合并语义 |
| 4 Smart UI Spec | U01 | P2 | 流式完成清零实际交互次序，显式继续漏选择 | 同定义流式完成保留交互身份 |
| 4 | U02 | P2 | action 模板展开后静默截断选择快照 | 按展开后完整草稿检查预算，超长明确拒绝 |
| 4 | U03 | P2 | 学习翻卡恢复时丢失已揭示答案状态 | 保留合法揭示态，与评分结果分别处理 |
| 4 | U04 | P2 | 重复选项 ID 导致错答案判对 | 在 study 协议校验拒绝同题重复 option ID |

## 二次复核发现的相邻遗漏

| 编号 | 严重度 | 可复现触发与影响 | 修复方向 |
| --- | --- | --- | --- |
| R18 | P2 | 历史回答再生接收 STEER 后新建 assistant，再流出未执行工具并取消；仅取消原再生节点，新节点工具仍 auto、awaitingTools=true | 在活动前缀实际末节点执行取消收口，保留原后缀；普通失败保留可续跑语义 |
| R19 | P2 | 后台润色活跃时由另一 context 暂停并立即恢复；新 driver 因锁争用退出，旧 driver 结束后没有接替者 | 显式恢复/重试等待旧 driver 退出后接替；普通打开和后台调度保持非阻塞 |
| R20 | P2 | 后台 driver 首次异步 IO 尚未登记 owner 时系统 stop；stop 无效，随后仍请求模型 | 在首次 await 前登记本次调度所有权与停止意图，启动中停止安全 yield |
| U05 | P2 | 同一 assistant 的 text groups 被工具分隔后声明重复 block ID；正文和继续允许交互，产物索引却判歧义 | 消息级收集声明 ID，正文与 continuation 共用歧义判定，SVG 校验不提升已有错误 |

R18–R20 为独立 reviewer 用实际 Service/creation/repository 和可控端口执行得到；U05 另有 [原始协议要求](../plans/2026-10-10-harmony-intelligent-ui-and-visual-artifacts.md)，要求声明 ID 在整条 assistant 消息内唯一。

## 分阶段推进与独立审查

1. 数据完整性与运行生命周期：完成。已有故障路径复核后补 R18–R20；未参与实现的 reviewer 再查活动前缀、审批后 STEER/取消/排队 FOLLOWUP、跨 context 接替及系统停止。R20 首次补修遗漏 runner 内 claim/validation 窗口，最终将停止消费收口到 runner safe point 和真正模型启动前；六个异步窗口与启动前 microtask 均已复核通过。
2. Provider 与上下文：完成。官方 schema、raw wire serializer、首轮/续轮组合验证及独立 review 通过；实际请求每轮保留两张截图，持久会话仍保留全部原图。
3. 记忆与搜索：完成。持久 ID、读时过期、真实提炼来源和查询约束复核通过；实际 MemoryStore 混合并发新增验证了高水位。
4. 产物与 Smart UI：完成。已有五项修复复核后补 U05，独立二审覆盖跨类型重复/唯一声明、84 个 text part 切分位置及 SVG 校验；二审补齐可见错误提示，最终通过。
5. 集成验证：对本次改动完成一次有限可维护性检查；全量测试、改动生产文件分批 lint、Release/Debug SDK 构建及签名通过。模拟器记录见下。最终交付独立 review 也已通过，核对了全部问题编号、阶段闭环、66 个最终输入哈希、HAP、日志及截图证据。

独立 review 的原始记录位于 `.workflow/harmony-review-repair-20261018/results/`。`Standards` 与 `Smart UI Spec` 分轴记录，不以标准检查通过代替功能正确性。

## 排除项和能力边界

- 不将静态猜测计为缺陷：未接线旧 Dream、未证实的原生私网跳转、仅凭索引 key 推测的 UI 错配、Google fallback 总预算取舍、生产已覆盖的小说默认超时、未复现的负零输入。
- JEV 严格 decoder 不支持旧测试虚构的裸值/selected/value，不属于已登记供应商协议的兼容回归。
- assistant 图片或分支身份不同、但实际提炼文本完全等价时允许提交，符合当前文本提炼输入。
- 同一 NovelCreation 的 `launchPolishDriver.has(key)` 并非漏接替：实际 probe 确认 resume 先等待旧 driving promise，其 finally 会清理 Map。跨 context 的 R19 才是本次需要补修的可达问题。
- R14 提供新快照启用后的不复用保证。旧数组没有已删除 ID 的历史高水位，不能追溯还原。
- 搜索时间要求已送入实际 query；这不证明第三方搜索服务一定严格执行日期范围。
- 原报告登记的备份附件文件树缺口仍属于既有未实现范围，本轮未扩成备份格式或迁移功能。不能把当前备份称作可跨设备恢复全部文档附件。

## Standards

独立标准审查未发现仍需修复的 documented hard violation 或有明确维护影响的 judgement call。新增 ID 高水位、文件锁和 owner stop 信号均对应已复现故障，保留在现有 owner；没有新增通用重试、兜底框架或权限。S01 的短写行为问题已修复。ArkTS API 基线仍为 12，`File.tryLock` 经本机官方 SDK 核为 API 9。

## Spec

以仓库 Smart UI 两份设计为依据，U01–U05 均已复核闭环；重复 ID 的实际可见诊断也已检查。其余运行/存储问题按真实生产调用、持久化约定及供应商正式协议判断，没有把风格偏好或旧范围外能力当成缺陷。

## 集成结果

| 验证层 | 实际结果 | 边界 |
| --- | --- | --- |
| 全量回归 | `npm run test:harmony`：4808/4808 通过，167 suites，fail/cancelled/skipped 均为 0 | Node、域逻辑和转译平台端口证据 |
| ArkTS lint | 全部 34 个改动生产文件已由首批及后续受影响文件批次覆盖，均 0 error | 首批 29 文件 6827 warn；U05 五文件批次 194 warn；最后 creation/runner 两文件批次 2117 warn，重叠文件不能相加。未留修改前同批 warning 基线，未宣称告警清零或全为旧告警 |
| Release 构建/签名 | `BUILD SUCCESSFUL in 1 min 44 s 421 ms`，SignHap/SignApp 完成 | 符号包仍有 EEXIST/ENOENT 警告，未把 app-symbol.zip 宣称为成功产物 |
| Debug 构建/签名 | `BUILD SUCCESSFUL in 17 s 5 ms`，SignHap/SignApp 完成 | 供本地无 Provider 的可视回放 |
| 工作区检查 | `git diff --check` 通过；最终验证输入已固定 SHA-256 | build-profile 与两个 pelican 文件保持接手时字节不变；未提交或推送 |

Release HAP SHA-256：`0f8c1671ad72c83b346009d22ca72774989c26ec7a86f731b0daf44a0f43e387`。Debug HAP SHA-256：`c623a8a5c30513135a8bdd1eb824893c6bcdea7f1b11afa399718acc16c0422a`。已分别保留在 `.workflow/harmony-review-repair-20261018/artifacts/`。

模拟器：`127.0.0.1:5555`，API 24，1320×2856，包 `app.amber.deepread`。采用 `install -r`，没有卸载或清库；未对全部历史用户数据做逐项完整性验收。可视回放不请求模型、不写默认会话数据库。

- 任务卡：空必填时按钮禁用；实际输入 `offline` 后按钮启用；点击准备草稿后，界面实际草稿含当前方案、50 分钟、`offline` 及完整 `【选择结束】`。截图与 AX 文本在 `.workflow/harmony-review-repair-20261018/screenshots/task-ui*`；实际草稿文本为 `task-ui-draft.txt`。该场景在同一最终 UI 源码的前一 Debug 构建中执行。
- 最终 Debug 包已覆盖安装，并 force-stop 后成功启动。学习卡实际作答正确项后得分为 `1 / 3`；到第四张无选项翻卡点击“显示答案”，实际显示 `1368 年`，得分仍为 `1 / 3`。截图为 `.workflow/harmony-review-repair-20261018/screenshots/study-ui-revealed.jpeg`，相应 AX 文本记录为同名 JSON。未用这一步手工交互替代 U03 的序列化/恢复回归，也未手工验收主题重挂载。
- 最终进程 PID 3129 的 `Chat.VisualFixture` 日志确认 study 回放 started→finished；同 PID 的 AppKit ERROR/FATAL 查询为空，交互后进程仍存活。只据此报告本次运行观察，不推断所有运行路径均无崩溃。结束回放后再次 force-stop 并普通启动，正常首页已截图为 `home-after.jpeg`。

未取得的验收：真实供应商/计费请求、物理设备、API 12 设备、真实系统 WorkScheduler 回调或进程断电。没有把 controlled ports、模拟器或构建成功写成这些验收。
