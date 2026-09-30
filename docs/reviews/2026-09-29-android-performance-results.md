# Android 性能优化验收记录

> 当前状态：已补齐本机可完成的实现、UI 长流程和本地后端流式验收。P3 真机、真实 provider 与长期运行仍未验证；F15 的 UI 缓存命中也未验证，不能宣布整体性能目标已完成。

日期：2026-09-29。代码由 Opus 5.5 high 调查与架构裁决、GPT-6 Sol xhigh 实现和独立审查，主 agent 集成验证。最终方案见 [计划](2026-09-29-android-performance-program.md)，数字见 [证据 JSON](2026-09-29-android-performance-evidence.json)。

## 交付范围

已落地：结束阶段等价写入去重、通知 PendingIntent 惰性创建、Sandbox 输入派生与解析复用、长会话一次准备、小说目录/账本/列表缓存、代码高亮结果缓存、表格提取复用、UUID 查找、首屏和历史分页后台 Markdown 准备、同键并发解析合并、可关闭的 DEBUG 分段日志。

未采用：增量 checkpoint、削减发送 FTS、按尾消息引用跳过子代理输出、议会持久化重写、整页架构重写、降低流式频率或视觉效果。选择不做的证据在计划中逐项说明。

## 固定负载 Android 结果

环境：Android 35 arm64 AVD，host GPU，Debug 构建，1080×2400，宿主 16GB。没有真实 provider。各探针单独启动，使用独立随机 ID 的本地内容，清理自己创建的数据。

| 指标 | 对照 | 修改后 | 解释 |
| --- | ---: | ---: | --- |
| 首开 80 节点 / 3104 虚拟项的主线程计划 | 784.44ms | 17.17ms | 五条 8–29k 长 Markdown 的解析移到 Default；不等同整页帧率 |
| UI 滚动加载历史后 120 节点 / 3144 项的主线程计划 | 258.58ms | 15.34ms | 只读预热后由原分页重新读取发布，原锁/anchor 不变 |
| 两个线程同时解析同一约 29k 文本 | 2 次解析，hit-after-parse | 1 次解析，hit-in-flight | 日志确认重叠，不只依赖结果引用相等 |
| 初次进入至历史可见 | 2225ms | 1947ms | 单次观察，不作为总体时延承诺 |
| 历史滚动触发至新页可见 | 732ms | 1315ms | 本次更慢；收益是把解析阻塞移出 Main，不宣称端到端加速 |

首次诊断曾测到 4206.79ms 的同类计划。后续同轮对照使用 784.44ms，不混用不同负载状态的数字放大收益。所有数字是单次固定夹具 Debug 模拟器记录，不是 p95、真机 FPS 或耗电结论。

在修改后日志中，五条长文本的 miss 均来自非主线程；计划开始时读取这些解析结果。`timelinePlan cacheHits/cacheMisses` 是虚拟项缓存统计，不是 Markdown 缓存统计，首次计划的虚拟项 miss 仍然正常存在。

仍观察到冷布局的 SLOW_MEASURE，包括一次滚动前后约 298ms 的布局测量。因此没有声称零慢帧，也没有用解析改善掩盖其它布局成本；这些进入真机 Profile 后续项。

## 正确性与构建

- 最终集中 JVM 回归 **142 tests / 0 failures / 0 errors / 0 skipped**。涉及生成 round、终态、队列、replay golden、DI、restore gate、时间线/anchor、Markdown、小说 runtime/VM/Store、议会 manager。没有重写 golden。
- 收尾补跑现成的 runtime/production canary、kernel/durable、dock、主题和议会仓库：初跑 66 项中 64 项通过。两项 durable guard 用例在完整 `44ff099` 快照上同样失败；当前设计允许新 callId 的 `file_read` 再次观察，而旧测试把它当成不可重复工具。仅将这两例的假工具改为非重复且无审批的 `guard_probe`，保留次数、跳过和 FAILED 断言；复跑该类 **8 项全部通过**。这两批有重叠，不相加成独立用例总数。`:feature:subagent:testDebugUnitTest` 为 NO-SOURCE，不能计作额外测试通过。
- Debug 主应用和 androidTest APK 构建通过。JDK 21、仓库规定的 daemon JDK、现有 SDK，离线、最多 3 workers。
- Android 真实 Service 探针通过：120 节点返回完整内容而普通 UI 保持 80 尾窗；窗口外无效工具清理后返回/session/库均 119；部分会话的真实 saveConversation 在超时约束内完成，仍保存 120 节点及原标题。
- Android 首开、并发解析和真实 UI 分页探针均通过。新探针默认跳过，仅在显式参数与模拟器守卫满足时执行。
- 日志开关验证：DEBUG tag 开启时记录 2 条 `conversationForGeneration.load`，关闭时记录 0 条；两个状态下功能探针均通过。其它真实 provider/Jev/MCP 网络阶段没有运行验收。
- 议会临时测量在未修改生产的情况下，通过 200 次/10ms 更新、50ms DAO 挂起的负载；流中完成 15 次写，最终内容正确。原“持续取消在途写入”的假设撤回，临时测试没有保留到仓内。

## 审查发现与修复

| 发现 | 最终处理 |
| --- | --- |
| 省去结束第二次落盘会漏掉 restore writer 拒绝边界 | 无条件保留 writer 门控，只在其中省去重复 DB 动作 |
| resolver 在 Default 更新数据库后，session 替换在门控外 | 两者放在同一个既有门控内 |
| 代笔终态 job 可能配较早读取的 ledger，停止轮询后进度滞后 | 先选 job，再读取唯一 ledger |
| 等长、同时间戳原子换文件可能误命中 UI 缓存 | stamp 加入 fileKey/FileTime；属性不可用时直读 |
| 深链过早用空计划完成定位 | 等到 prepared 与入场均完成，effect 依赖覆盖这两项 |
| Service 分页 beforePublish + 新 writer gate 导致保存部分会话自死锁 | 整段方案撤回；只读预热不参与正式分页发布 |
| DEBUG 构建关闭 tag 时仍有新增日志计时 | 同一作用域的 DEBUG+tag 条件控制全部新增计时与输出 |

每项均经独立复核。没有为这些问题引入新的全局索引、锁、重入、恢复日志或重试框架。

## UI 长流程的 A/B 闭环

共保存 29 张真实模拟器 PNG，独立查看了聊天折叠/推理展开/上下文菜单、小说项目/工作区、议会、首页/显示设置等 8 张重点图；聊天折叠页与改前截图对照，未发现可归因于性能 diff 的明显错位、遮挡或间距回归。

此前三条流程均在中途停止；收尾阶段用相同 AVD、同一份修正后的 test APK，分别运行完整 WIP 基线 `44ff099` 与最终 APK：

| 流程 | 完整 WIP 基线 | 最终 APK |
| --- | --- | --- |
| ChatSoftTimelineSmokeTest | 通过 | 通过；跨会话、多任务、IME、完成、逐项 dismiss 与持久状态读回 |
| RedesignNavigationSmokeTest | 在缺失的 Extensions 入口失败 | 通过；原来的所有页面断言保留，包括快捷消息和收藏 |
| UiRefreshDeviceSmokeTest | 通过 | 通过；从 Appearance 切深浅色，核对持久偏好，再进入空聊天 |

聊天脚本改用当前 dock 展开状态语义、实际详情操作、1 秒推理显示及模型菜单内的上下文项。仅在这个手动 dismiss 场景中关闭任务自动隐藏，并恢复原设置；没有删除行为或持久化断言。主题脚本进入实际的 Appearance 页面，而不是在 Display 页面查找颜色选项。

次级页面停止点是一处既有产品缺陷：样式提交 `6aa0132e` 移除了 `SettingAgentExtensionsPage → Screen.Extensions`，而快捷消息管理与收藏列表仍只有这个入口。经 Opus 和 Sol 独立复核，精准恢复同一个既有列表项和 import，沿用当前样式、资源和路由。此项单列为 **L2 定位的既有 UI 缺陷修复**，不计作性能收益。

原始结果见 `closure-ui-results.json`；每条最终流程均保存完整日志与截图。任务夹具只更新 metadata，不代表真实并行 provider 任务；静态图也不证明帧率或高亮每一帧的效果。

最终三条流程共保存 38 张图。独立 reviewer 实际查看其中 12 张关键图，并对照 9 张对应基线图；所看页面没有本轮新增的错位、边距变化、遮挡或截断。没有把其余未逐张查看的截图算成视觉通过证据。

## 本地后端上的真实流式生产链

`ChatStreamingPerfProbeTest` 只有一个默认跳过的模拟器场景。设置流确认当前模型指向本地 provider 后，从真实聊天输入框点击发送，经过 ChatVM、ChatService、OpenAI HTTP/SSE 和正式渲染路径。宿主仅监听 `127.0.0.1`，模拟器经 `10.0.2.2` 访问，不使用真实 provider 或账户。

固定回答为 **12,227 字符、40×6 表格、3 个代码块**。最终 Service 文本与 fixture 的 SHA-256 一致，真实 Repository 读回全文逐字相等，页面上的末尾标记通过可见性断言。原始协议流、测试、APK hash 和日志在 `closure-stream*` 工件中。

第一次功能运行通过，但三张完成态图片都停在尾段；后续子节点程序化滚动也未到达目标。只修正测试取景，改用真实触摸滚动，并精确匹配表头，避免子串同时匹配 JSON 内的 tableRows。诊断显示第 23 次小步手势才到代码，原先共用的 24 次上限不足；仅将固定场景上限调为 40，实际第 29 次到表头。旧图不作为代码和表格的视觉证据。

最终 `closure-stream-final.log` 场景完整通过（23.032s 测试时间）。独立 reviewer 查看最终 3 张图：Kotlin/JSON/Shell 代码着色、表头及可见行的列对齐与边距正常；右侧裁切符合横向视口的外观，但未运行横向手势，不声称全部 40×6 同时可见。现有右侧滚动浮层和底部建议胶囊会覆盖部分内容；其布局所有者 `ChatListNormalSection.kt` 相对 WIP 基线无差异，本轮没有改这项浮层设计，也不宣称全界面无遮挡。F15 缓存命中不由这些静态图证明。

第一轮流式日志记录：278 次 streamFlush，范围 0.02–1.68ms，中位数 0.04ms；1 条 resolver load 阶段记录，1 条强制 checkpoint 和 2 条周期 checkpoint 记录。99 次 Markdown miss 在后台；Main 有 76 字符/30.75ms 和 20 字符/0.90ms 两次冷观察，没有 ≥4,000 字符的 Main miss。仍见 DataTable 41.0ms、冷 ChatPage 162.3ms 测量。这些只是第一轮 Debug 模拟器样本；最终取景轮另观察到 DataTable 83.5ms、76 字符冷解析 16.76ms，显示单次测量的变化。没有足够迹线把冷耗时归因为 JIT，也不外推真实网络、帧率或并发负载。

**日志条数不等于 DAO 读写次数**。F1 写入去重的证据仍是实际分支与 writer gate 审查，加上全文落库正确性；F12 的长会话证据仍引用之前的 120 节点 Service 探针，本次 `nodes=1` 不证明长会话读库次数。长回复在流尾保护期内仍是同一个 lazy item，滚离屏幕不代表退出组合；**F15 的 UI 缓存命中和滚回无闪尚未验证**，保留源码/JVM 层结论。

## 仍未验证的环境与性能边界

| 未验证项 | 所需环境 | 已有入口或证据边界 |
| --- | --- | --- |
| 真机帧时间、jank 分位数、冷启动、热量与耗电 | 空闲物理 Android 设备、真实长会话、可比构建与负载 | baselineprofile、`scripts/amberagent_auto_jank.sh`；必须核对路径实际执行，不把 Debug AVD 当真机 |
| 真实 provider/Jev/MCP 首 token、网络与工具时延 | 对应设备、账户与可用 provider/服务 | AmberChatPerf 分段日志；本地 SSE 只验协议和正式调用链 |
| 多个真实并行任务、小说代笔、账户同步与长期后台 | 可持续运行的设备和真实任务/账户 | 现有 kernel/runtime 测试和静态 dock 不能证明 8 路真实任务性能 |
| Haze、主题纹理、Widget/WebView 的 GPU 与生命周期成本 | 物理设备上的 GPU/CPU profile | 本轮不减少视觉效果，也没有无数据重写架构 |
| F8 整页重组频率、F10 后台实际 SQL 成本 | composition/SQL 迹线及真实导航、checkpoint 负载 | F8 只记录既有 SLOW_MEASURE；不引入 tracing 框架。F10 默认分页不等同后台周期重查；保留非空搜索词时，完整摘要 Room Flow 有持续重查路径，未量化成本，不改生命周期 |

F10 读码报告为 `closure-f10-paging.md`。`cachedIn(viewModelScope)` 首次订阅后可保留上游，但下一代分页事件流是懒启动，不能将“源失效”等同于每次 checkpoint 都重跑分页 SQL。非空搜索走完整摘要 Flow，需要单独测量。

源码审查还记录了两个不在本性能修改内解决的边界：resolve 在 hooks 建立前失败时 durable 审批状态的既有对账缺口；小说跨 step 非前缀 delta 的显示候选问题。没有把它们当成本次已修复的性能项。

## 可复核工件

原始日志、逐阶段 XML、PNG、A/B APK、模型审查报告及临时复现位于：

`/tmp/amber-android-perf-artifacts-20260929/`

关键文件：

- `final-jvm-results/summary.json`、`final-focused.log`
- `f4-before-first-perf.log`、`f4-after-first-perf.log`
- `f4-before-flight-perf.log`、`f4-after-flight-perf.log`
- `f4-older-baseline-perf.log`、`final-older-page-perf.log`
- `final-resolver-log-on-perf.log`、`final-resolver-log-off-perf.log`
- `p1-visual-review.md`、各 `p1-*-screens/`
- `final-app.apk`、`final-methods-test.apk`

首轮性能 APK SHA-256：`b49173eec262eb066702a854783a270a4c3c0fc1bd8256b18738836e812826bc`。补齐入口后的最终 `closure-app.apk`：`48134cba69e05a62b5f67751e04faed7cc0b6f1766be319e97dc0209ef02d595`。

## 本地提交与工作区保护

性能分支：`perf/android-performance-20260929`。`44ff099` 仅保存原有 WIP，不能当作性能改动提交。实现提交：

- `3e1992a` P0 重复工作收敛
- `4e14c8c` 一次生成准备
- `bcee010` 小说轮询
- `b1439d7` 高亮、表格与 UUID 查找
- `df9957c` 首屏/旧页后台 Markdown 准备与 opt-in 探针
- `db40a93` 可关闭的准备阶段日志

最终仅将相对 WIP 快照的性能差异应用回原 Android 工作区，原分支与原暂存区保持原状；没有推送远端。最终应用核对记录写在本报告末尾。

### 最终回写核对

- 已将 19 个性能相关文件的差异同步回原 Android 目录，逐文件与已验证 worktree 字节一致。
- 原 HEAD 仍为 `04d77a8f`、原分支与暂存区未变；未涉及的原 WIP 按 SHA-256 核对保持不变。
- 性能差异 `git diff --check 44ff099..HEAD` 通过。原工作区整体检查的 RouteActivity CRLF/尾空白记录与原 WIP 快照完全一致，未顺手规范化它。
- 独立模拟器已停止；没有操作或安装物理设备，没有推送远端。

### 收尾追加交付与核对

- 本轮追加同步 8 个文件：两个既有 UI smoke、durable 夹具、Extensions 入口、单个本地流式探针，以及计划/结果/证据 JSON。累计交付 24 个相关文件，逐文件与已验证 worktree 字节一致。
- 新增本地提交：`7411b76` 恢复入口与 UI 契约，`179256f` 修正 durable 假工具，`c62d2c0` 本地 SSE 正式链路探针。原仓仍是原 HEAD/分支/暂存区，没有推送。
- 本轮开始后原工作区的 README、`.github/workflows/android-native-build-check.yml` 及其它未涉及文件按 SHA-256 核对；仅复制交付清单，原 WIP 保留。
- 独立 AVD 和本地 SSE 服务已停止；未安装或操作物理设备。
- 收尾工件：`closure-ui-results.json`、`closure-durable-ab.json`、`closure-durable-corrected.xml`、`closure-stream-final.{json,log}`、`closure-stream-final-perf.log`、`closure-stream-final-screens/`、`closure-stream-analysis.json`（第一轮统计）、`closure-visual-review.md`、`closure-stream-visual-review.md`、`closure-opus-final-review.md`、`closure-integration.json`。
- 本机停止条件已满足；上面的真机/真实 provider/长期运行与 F15 UI 缓存路径仍明确未验证，整体目标没有标成全部完成。
