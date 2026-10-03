# P5 小说可达 UI 实施与验证

## 范围与入口

- 项目控制新增「润色与结果」，打开 `pages/NovelPolishPage`，仅携带 `projectId`。查看已有任务不受普通页面的写入门禁阻挡；页内启动和操作由实际领域门禁检查。
- 普通生成恢复使用独立 `NovelRunRecoveryCard`，Workspace 仅读取实际 `readOrdinaryRun` 投影、绑定回调和既有 `attachNovelRun`。有真实响应游标才提供「继续已保存的响应」，「重试原请求」始终为明确作者动作，不自动降级成重新发送。
- 已收录回复显示「再次收录」，作者明确确认以当前书稿重新使用原文本后，冻结 CAS 调用 `cloneCollectedMessage`，刷新实际新候选后打开原收录表单。原收录标签和记录保持，克隆候选展示来源标记。
- 讨论归档使用独立 `NovelDiscussionArchiveCard` 与两个纯呈现 helper。默认折叠，当前页可展开；只按持久归档的 `sourceMessageIds` 折叠讨论原文。正文、部分正文、归档范围外的回复及待采用设定卡保持可达。切换分支或重新进入页时按默认折叠，无新持久偏好字段。
- 收录反馈区分正文及确定性剧情同步点已保存，与后台资料建议分析的数量或警告。只在当前项目、分支和本次收录 token 一致时更新反馈。新开整章后才提供讨论归档邀请，追加和替换不邀请。
- 实际消费者核查确认旧全局润色文本只在 Settings 读写，任务只冻结项目 polishPreference；root 明确批准移除该无效编辑控件及该页调用。设置页保留四个有效上下文默认开关，标为「润色上下文默认项／保存润色上下文」，并说明润色要求在项目的「项目创建设置」编辑。原全局 getter／setter 模块与透传代码保留，无新未来项目默认行为。

## 源码边界

- Workspace 只追加薄入口、恢复投影和回调，未另建存储或任务状态机。
- `NovelPolishPage` 独立承接任意选章、启动前项目偏好与上下文快照、逐章结果、选定失败或未处理章节重试。
- `NovelRunRecoveryCard` 承接原请求、保存部分内容、完整复制、明确重试与继续确认；确认重新核对记录和组件生命周期。
- `NovelArchivePresentation` 和 `NovelDiscussionArchiveCard` 承接归档范围呈现与展开交互。

## 已验证与独立检查

- 新归档／克隆／收录反馈 actual-method 测试 6 项通过，覆盖原记录保留、冻结 CAS、新候选 ID、另一分支拒绝、失败可见、旧反馈 owner/token 拒绝和纯归档投影；真实 reload 读取失败后不从旧快照打开空收录表单。
- 普通恢复 Workspace actual-method 测试 7 项通过，覆盖 cold running、活动／已完成／等待交互排除、原历史正文不冒充新部分内容、完整复制、精确 retry/resume ID 与无静默降级；真实 recover/attach 结合领域 ordinaryRunMessages 投影验证两种恢复流均显示新正文，并覆盖 AskUser 同 ID 助手消息续接与部分内容复制。
- 独立恢复组件 actual-method 测试 5 项通过，覆盖作者确认、记录与生命周期失效及续接资格。
- 润色页 actual-method／实际 creation→repository 测试 17 项通过，包含任意选章、项目偏好快照、启动 CAS 原子校验、输入失效、返回生命周期、完成后重读最新 CAS、定向重试后真实逐章结果进度统计，以及同实例同任务 ID 的连续内容更新。
- P4 已有 57 项与本轮 38 项（含 Settings 回归）组合 95/95 通过（`/tmp/amber-p5-ui-focused.log`）。
- Workspace 窄 lint 无缺陷；归档新增两文件 lint 0 error、1 条 custom-component 性能建议，无新增 API。
- 独立源码 review 发现普通恢复 raw provider snapshot 与页面 historyCount 不一致可能截掉新正文，已由领域统一投影为 ordinaryRunMessages，UI 明确 retry 以当前历史长度、resume 以原记录 transcriptPrefix 长度接入；cold active reattach 使用服务的实际 historyCount。
- 独立源码 review 发现 clone 已成功但 reload 捕获读取失败后仍 resolve，原逻辑会打开空收录表单；已精准要求实际当前 project 含新 candidate 后才打开，保留真实读取错误。
- 独立源码 review 发现 failed 任务卡「重试」未随空选择禁用；已新增可选 retryReady 并传入页内选中资格，确定性 jobKey 包含选择集合。真实 actionRow 回归覆盖清空禁用、重新选中启用和 busy 禁用。
- 独立 reviewer 最后执行实际 core collectWithEvents outward → 实际 Workspace recoverOrdinaryRun／attachNovelRun → 真实 ordinary helper 联合复验，retry 前缀 5／resume 前缀 3 均显示完整尾文（`/tmp/amber-p5-workspace-reviewed-outbound.cjs`）。WUI01、WUI02、WUI03 均关闭，详见 `p5-workspace-ui-independent-review.md`。本 ownership 源码已冻结。
- Settings P1 actual-method 冷读取／返回模型刷新／保存回归先复现不应存在的全局文本依赖与写入，再修改真实页方法，3/3 通过（`/tmp/amber-p5-settings-actual-tests.log`）。
- Settings 单页 lint 无缺陷（`/tmp/amber-p5-settings-lint.log`）；独立 reviewer 核实移除范围、上下文真实消费者和项目偏好入口后，独跑同一 actual-method 测试 3/3 通过，无新源码 blocker（`/tmp/amber-p5-settings-independent-tests.log`）。
- root 确认选定重试后 cursor 归零但既有逐章结果保留，卡片「已处理」不可用 cursor 计数。已精准改为真实 projectPolishOutcomes 非 unprocessed 数量，进度条与剩余统计使用同一投影；cursor 保留当前章节定位。实际 progressSection 回归覆盖 cursor 0／既有成功与失败结果／一章未处理，显示 2/3、66%、剩余 1；单 Card lint 无缺陷，不改领域模型或任务流程。
- 独立 ordinary review 确认 AskUser 续接会更新原同 ID 助手消息；首次 continueTool 精确定位实际工具消息位置，cold 工具续接 retry／resume 消费 ordinaryRunHistoryCount，normal turn 切片规则保持。工具续接 partial 使用真实 ordinaryRunMessages 投影，与原 request.history 中同 ID 不可变旧正文比较（领域仅合并工具审批与结果，正文及思考保持冻结），仅剥离未变化的旧文本前缀；新 ID 回复保留全文，普通历史过滤保持。按钮显示「重试工具续接」，说明沿用已保存回答和工具结果，以及重试会替换本次工具续接部分内容、可先复制保留。实际回归覆盖第二次失败的相同 partial，不因 presentationprefix 已含旧 partial 而显示为空。单 Workspace lint 无缺陷（`/tmp/amber-p5-workspace-tool-continuation-lint.log`）。
- 独立 reviewer 最新真实 Creation／Adapter／AskUserDispatcher／FileRepository → 实际 Workspace continue／recover／attach／partial／copy 联合 4/4 通过，包含旧讨论前缀、真实首次失败和第二次重试失败的新部分内容完整复制、随后成功的新正文末尾、回答只执行一次、原 user 只保留一条及 failedpartial 不进入 retry 请求；Responses 真实 HTTP GET 恢复也显示完整末尾。实际工具 UI／RecoveryCard 12/12 独立通过，WUI05 关闭（`/tmp/amber-p5-actual-adapter-workspace-review.test.mts`、`/tmp/amber-p5-askuser-ui-final-independent-tests.log`）。这些 local fixture 验证不等同于设备或真实云 provider 验收。
- root 设备观察到 Workspace 润色卡片停在旧的 0/2／章 1，但独立润色页同任务已为 2/2 混合结果。实际 schedulePolishPoll／pollPolish 方法复验确认 State 已依次取得 cursor 0→1→completed 2，原 jobId 与 token 续排正常（`/tmp/amber-p5-workspace-polish-poll-review.cjs`）。离线官方 Builder 文档确认默认按值参数不会随状态变化刷新；卡片的多层任务 Builder 正好传递此类参数。
- WUI06 精准修复仅限 `NovelPolishJobCard`：任务相关 Builder 与 progressBar 无参直接读取 this.job；详情和按钮仅传静态类别，动态展开、busy、retryReady 直接读取 State／Prop。不改轮询，不添加任务 ForEach 重挂，保留同实例展开状态。stage／warning Column 左对齐，根 Scroll TopStart。独立 actual Builder 整链回归 17/17、单文件 lint 无缺陷，报告见 `p5-workspace-ui-independent-review.md`，日志 `/tmp/amber-p5-polish-builder-independent-tests.log`。Node 方法重调用验证读取合同，实际自动重绘另由设备证据核验。
- root 新签名安装包已完成 WUI06 实机 gate：从热通知进入同一 Workspace 后，卡片连续显示 0/1 等待→已暂停→继续运行→已完成 1/1，并显示第 4 章成功结果；全过程无页面导航或重挂。截图与 AX 对为 `device/p5/card-final-hot-workspace-writing`、`card-final-paused`、`card-final-resumed`、`card-final-automatic-completed`；各执行阶段标题 AX 左边界均为 108 px，与正文左边界一致。源码冻结保持，本轮仅更新验收报告，不提前开展后续 phase。

构建、签名、安装及设备截图由 root 统一执行。本报告不把源码与 stub 方法验证当作设备验收。
