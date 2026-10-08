# Android 全面 Review 与分阶段精准修复（2026-10-02）

## 范围与基线

Android 独立构建根，main `6a66f7a3c91f9b4f8f0335c8b0f58fa88dca77d9`，包含开始时已有的 dirty/untracked WIP。原始快照：`/tmp/amber-android-review-20261002-baseline`。本次没有读取兄弟产品仓库，没有提交、推送或安装设备。

按小说存储/交互、备份与文件所有权、Jev/内核审批、通知任务、MiniApp/草稿/provider、终端/DeepRead/系统工具、设置与测试基线八条职责审查。候选须有实际调用路径和明确失败条件；并发问题使用受控交错；优先为实际失败增加回归，并区分源码确认与执行证据。每阶段交付后另行由未参与实现的subagent复核。

这是一轮尽可能广的源代码与JVM审查，不声称没有未知问题，也不把构建通过等同于真机、云端、OEM或视觉验收。

## 问题列表

以下48个生产问题组均已确认并修复（P1 21、P2 26、P3 1）；同一个问题的roundtrip/取消/输入边界补修并入该组，不重复计数。独立review发现的补丁遗漏也纳入对应回归。最终验证结果见后文。

| ID | 级别 | 真实触发与影响 | 最小修复 | 验证路径 |
|---|---|---|---|---|
| NS1 | P1 | 安装中途失败却已发布 manifest，下次永久跳过半安装项目 | 预验证并完成书籍/ledger/checkout 后发布 manifest；失败可重试 | migration/install故障注入 |
| NS2 | P2 | 项目 rename 推进全局 head，却使镜像分支 head 脱离 | 只同步原本镜像全局 head 的分支；独立父链保留 | rename/head ancestry |
| NS3 | P1 | 主分支 slug 重名后可能指向另一分支 | 统一分配 branch ID→slug 映射，manifest读取同一结果 | 重复slug导入 |
| NS4 | P1 | 点号开头标题生成隐藏路径，列表/交换中消失 | 可见名称fallback并限制隐藏写路径；安全隐藏查询仍返回空 | hidden title/路径边界 |
| NS5 | P1 | ZIP 对真实解压字节没有累计上限，可耗尽内存 | 流式累计100 MiB实际字节，越界拒绝 | 101 MiB实际ZIP |
| NS6 | P1 | 导入 NeedsSync 丢失剧情过期门禁；交换后状态还可反转 | 初始ledger记录stale分支，真实确认可清除；出口按分支ancestry投影 | needsSync/空章节/fresh与stale roundtrip |
| NS7 | P2 | 多行 quoted YAML 与值内分隔符不能无损读回 | 局部逻辑scalar与frontmatter边界解析；保留已有wire golden | 多行/字段替换/golden |
| NS8 | P1 | 无metadata的 thematic --- fence 被当frontmatter吞掉正文 | 无解析字段时保留整段原正文 | 真实Markdown opening丢失 |
| NS9 | P2 | 重复角色名或点号标题的卡片丢失.md扩展名，角色列表不可见 | 分配无扩展名basename后统一追加.md | 真实VM→kernel→文件/目录 |
| NS10 | P2 | 改frontmatter时trim正文，丢失代码缩进和尾随空白 | 只重写header，保留原正文suffix和leading prefix | CRLF/缩进/空白无损 |
| NR01 | P1 | 新文件同回合写两次，失败/取消回滚保留第一次草稿 | containsKey保存真正的null preimage | 实际runtime双写取消/错误 |
| NR02 | P2 | 交互讨论下一回合不带历史；带历史后旧回复可能被当新输出 | 传同分支有效用户/讨论历史；输出只读最后USER之后 | 真实讨论history/跨分支/旧回复 |
| NR03 | P2 | 排队的章节手动保存越过restore边界写入新恢复数据 | 沿用author edit的restore epoch与projection | 真实restore gate+重新加载 |
| NR04 | P2 | 会话持久化IO失败逃出viewModelScope | 局部接入已有UI错误路径，取消继续传播 | 实际collector故障注入 |
| SS01 | P1 | 完整备份仅有artifact行，恢复后缺正文 | 备份真实authority内容，恢复到应用自有持久目录并接入读取/更新/删除 | archive+repository真实文件集成 |
| SS02 | P1 | 独立preserve开关错误处理共享chat_images | 同保留/同替换/混合分别keep/replace/merge，本地碰撞优先 | 四种toggle组合 |
| SS03 | P2 | 设备密钥密文不可解后，后续DEVICE_BOUND导出永久失败 | 同步getOrCreate，当前不可读则替换；旧archive读取仍严格 | 密钥恢复/并发 |
| SS04 | P2 | 固定探测路径覆写并删除用户文件；清理异常还能吞掉取消 | 唯一探测文件不覆写，仅清理自身；保留主异常并附suppressed | MockEngine原文件/取消+cleanup失败 |
| SS05 | P2 | 宣称分块读取，却把整文件上限等同slice，普通300KB文件失败 | 整文件4MiB上限与返回slice预算分离 | 大UTF8分块边界 |
| SS06 | P2 | 早期解包/校验失败留下解密stage目录 | 既有finally覆盖全部stage生命周期 | 损坏payload/validation失败 |
| JEV01 | P1 | 网页快循环可点击支付/删除/提交等高风险目标 | 保真实候选类型与敏感label本地限制，并复核选中动作；普通导航button可用 | parser+actual runner正反控制 |
| JEV02 | P1 | ACTIVE运行期间改为SHADOW仍继续派发 | 每步/判题后/派发前检查当前配置 | 受控配置切换 |
| JEV03 | P2 | SHADOW/dry_run的done误报completed | 完成判定先保留测试态trace语义 | shadow/dryrun done |
| JEV04 | P1 | 省略输出提示同参重读，却被duplicate guard拦截 | 只有显式可重复观察工具允许projector给重读提示 | terminal不豁免/观察工具正控 |
| JEV05 | P1 | 设置释放原Pending跳过Jev自动批准复核 | 新调用与设置恢复共用局部gate；人工Approved保持一次批准契约 | 原Pending/低风险/人工批准 |
| JEV06 | P2 | 操作ok但页面未变不计no-progress，空转到大上限 | 按page_changed及snapshot变化记录无进展 | unchanged snapshot |
| JEV07 | P2 | cat/echo参数或引号中pytest文字误当实际检查 | 有限真实runner识别，仅在引号外分段并处理escape | actual unverifiedWrites+runner正控 |
| JEV08 | P1 | HTTP headers后取消不能cancel body；未知长度响应先读完再检查大小 | 同一continuation覆盖enqueue与body，先挂cancel，实际读取256KiB+1预算 | 有限阻塞body取消/实际字节上限 |
| RCI01 | P1 | 通知inline reply的immutable PendingIntent丢失RemoteInput | 只reply改mutable，批准token PendingIntent保留immutable | 真实通知结构 |
| RCI02 | P1 | 通知receiver等待整轮模型执行，broadcast可能超时且owner不清 | session持有LAZY Job，持久批准/audit完成后返回acceptance，生成继续受Stop控制 | 真实session持久化/长生成/Stop/新owner |
| RCI03 | P1 | 原始终端命令进入锁屏publicVersion/Xiaomi island | 使用单独脱敏公开标题 | private/public/island字段 |
| RCI04 | P2 | 延迟Council进度覆盖terminal任务状态 | task mutex内expectedStatus CAS，显式retry仍允许 | 控制延迟progress顺序 |
| MC01 | P2 | source checker在quoted/comment后多推进cursor，错过括号 | skipped token分支独占cursor推进 | 有效JS/CSS及坏括号 |
| MC02 | P1 | 旧编辑entity覆盖新metadata、复活删除app、盖掉新版HTML | Room事务重读并检查存在/version，只改source相关字段 | 真实Room rename/pin/delete/stale edit |
| MC03 | P2 | 附件take(2000)静默破坏签名URL/base64 | 保全字节或明确拒绝过大输入 | 完整URL/显式oversize |
| MC04 | P1 | Runner缓存创建时高风险批准开关，后续关闭仍auto-send | 实际factory的callback读取当前SettingsAggregator | 同gate true→false→true |
| MC05 | P2 | effectId去重check-insert并发创建重复artifact | singleton writer局部Mutex包lookup/create | 真实DAO受控并发 |
| MC06 | P1 | 消费草稿A时无条件clear，删除后来写入的B | DAO按conversation+准确draftId条件删除 | 实际挂起send/CAS |
| MC07 | P2 | Claude/Gemini把远程URL放base64/inlineData，图片请求无效 | Claude URL source；Gemini有限可取消下载；MiniApp图片沿既有guard快照；scheme一致 | 公共complete/stream/取消/大小/bridge公网私网redirect |
| MC08 | P2 | 返回已存在ChatVM不恢复MiniApp新草稿 | 小HostDraftComposer接实际visible/accepted-send，保护手输入和较新草稿 | 真实ChatInputState+Room/挂起load |
| T1 | P2 | 终端逐byte chunk解码破坏跨read中文/emoji | 同stream持续UTF8 Reader | 真实两类reader每read1byte |
| T2 | P2 | 模板重复替换把用户正文中的占位符再次展开 | 只对原template单次Regex替换 | 正文literal placeholders |
| T3 | P2 | force regenerate删掉pin/source，模型无效也删缓存 | 模型/TOOL验证后仅重置generation字段，阻断同标题旧缓存 | invalid model/实际准入force |
| T4 | P2 | DeepRead read-then-replace覆盖并发pin/unpin | Room事务SQL仅UPDATE生成字段，保最新pin/source | 真实Room受控写入顺序 |
| T5 | P2 | 同名/中文sanitize碰撞/重复分享覆写旧URI字节 | UUID子目录保存每次独立snapshot | 真实Workspace+FileProvider路径 |
| T6 | P2 | 循环事件DTEND为空时只改标题也被拒 | 仅时间编辑校验时间，缺DTEND的时间编辑明确拒绝 | recurring non-time/time controls |
| T7 | P3 | 定位同timestamp选择50m而不是5m精度 | 修正单个比较方向，保较新timestamp优先 | timestamp与accuracy tie |
| SET01 | P1 | 六独立流combine发布混合/旧设置，ACK后仍回退，误触Soul Stale | 同一durable snapshot在原Mutex内decode/publication；update/count/refs一致 | 确定性通知排序+Provider/Soul现有契约 |

## 分阶段计划与独立复审

| Phase | 目标与条目 | 独立复审记录 | 状态 |
|---|---|---|---|
| 1 | 小说durability/identity/interactive：NS1–10、NR01–04 | `.workflow/android-review-20261002/results/phase1-review.md` | 源码与独立复审闭合，全量通过 |
| 2 | 备份与文件所有权：SS01–06 | `results/phase2-review.md` | 源码与独立复审闭合，全量通过 |
| 3 | Jev执行、审批、HTTP与通知任务：JEV01–08、RCI01–04 | `results/phase3-review.md` | 源码与独立复审闭合，全量通过 |
| 4 | MiniApp/provider/终端/DeepRead/系统工具：MC01–08、T1–7 | `results/phase4-miniapp-review.md`、`results/phase4-terminal-review.md` | 源码与独立复审闭合，全量通过 |
| 5 | SET01、真实测试基线与全部模块回归、普通配置APK构建 | `results/settings-review.md`及最终验收记录 | 源码与独立复审闭合，全量与普通APK构建通过 |

SET01 是多个基线偶发失败的共同生产根因，因此提前到基础修复，避免靠重复测试掩盖。全部Gradle由主agent串行执行；worker只负责各自文件。每个phase详录与初始候选证据均在 `.workflow/android-review-20261002/results/`。

独立复审具体抓到并补修：导入分支状态出口投影、角色卡扩展名、清理异常覆盖取消、普通input按钮误拦、引号内检查命令误判，以及Gemini body取消/MiniApp网络边界/scheme一致性。测试夹具自身的死锁、冷启动未ready、既有8字符fallback期待错误没有混入生产问题计数。

## 假阳性、by design 与测试基线

- RCI05：Koin graph测试遗漏实际启动的jevModule；反射又把factory直接构造或constructor默认参数当DI依赖。仅增加真实启动模块与逐definition的精确injectedParameters，不新增生产binding，也不全局豁免File/transport。
- Provider测试seed只等待ID，没有等待完整canonical provider与全部model slot；仅修fixture等待条件。产品共享发布race由SET01修复。
- WebMount折叠标题实际为status/title/url组合文字，最小高度44dp；旧exact title/36dp期待过时。修测试，无产品布局改动。
- Antigravity client ID/secret默认空，refresh不会到mock网络latch，这是明确配置门禁。测试用合成非secret Gradle参数；最终APK构建必须用普通配置，不放合成值。
- iCloud overwrite先trash旧文件，是现有明确工具契约和批准行为；不把它擅改成新的回滚系统。
- workspace镜像不传播删除、保留额外文件是既有同步策略。
- CONFIG_ONLY只迁移对应配置；workspace exchange不迁移私有runtime/job/session/checkpoint。NeedsSync是章节/剧情领域状态，已单独核实后才接受NS6。
- Jev fail-open审批配置、shadow路由等待、recipe内部步骤例外、pinned retention前缀、人工Approved不重审均属已有明确策略。
- fork清除私有未解决/新鲜度状态有现有resolution与测试依据，不误改。
- 未接线的conversation auto-approve legacy helper没有当前可达UI触发证据，仅标记候选；没有顺手删除死代码。
- Settings旧scope cancellation→fatal限制是继承行为，不误称本次新回归，也不新增shutdown兜底。
- CI/action版本、SDK远端可用性及未固定esbuild等没有实际失败证据，不猜测为bug。
- 未进行无关大文件机械拆分、死代码删除、通用重试/全局网络guard/新权限框架。

## 验证证据

中间执行日志保存在 `/tmp/amber-review-*.log`；定点及最终统计保存在workflow results JSON。最终完整回归与普通APK构建均通过，完整最终日志另存为workflow `results/final-tests.log` 与 `results/final-assemble-debug.log`。Java21用于Gradle；真实ZIP超限回归的测试heap为1536MiB，未改产品堆配置。

最终全仓 `testDebugUnitTest` **BUILD SUCCESSFUL**（45秒）。18个有测试结果的模块累计 **3,390 cases / 0 failures / 0 errors / 24 skipped**，即 **3,366 passed**。其中app **2,696 cases / 0 failures / 14 skipped**，core/settings95全部通过。其余模块与suite详情见 `.workflow/android-review-20261002/results/final-verification.json`。

完整命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew --offline --continue -I /tmp/amber-review-test-heap.gradle -PantigravityClientId=review-fixture-client -PantigravityClientSecret=review-fixture-secret testDebugUnitTest
```

Antigravity参数为mock-only合成配置，并未写入项目文件。另行执行不带这些参数的普通 `:app:assembleDebug`，**BUILD SUCCESSFUL（21秒）**；重新生成的ai BuildConfig确认不含合成测试值。全部skip保留：app14、terminal8、两小说模块各1，不当成通过。

`git diff --check`通过。原始WIP快照中2,216文件保持原字节，72个既有文件精准修改，0个原文件缺失；新增23个source/test文件（4个小生产模块，其余回归/fixture）。详情见 `results/change-manifest.json`。

未证明的外部验收：真实Jev服务判题、真实provider图片响应、iCloud账户/Android Keystore签名环境、SAF授权设备、OEM RemoteInput/锁屏交互、屏幕自动化与UI视觉效果。JVM fake/seam验证不会冒充这些结论。


## 构建产物与完成状态

普通配置生成 `app/build/outputs/apk/debug/app-universal-debug.apk` 和 `app-arm64-v8a-debug.apk`，各114,234,923 bytes，SHA-256均为 `1fa289a599c265248bc97487a099a2659b4bebbfb475aede642faab854bf855a`。使用SDK apksigner验证，v2签名通过。这是Debug构建与签名验证，未声称Release发布或设备安装/启动。

本轮48个接受问题全部完成修复、回归及独立源码复审，没有剩余接受问题等待实现。所有skip、配置门禁、by design与外部验收边界保持显式记录。未提交、未推送、未创建PR、未安装设备。

详细证据索引：

- 原始候选：`results/novel-storage.md`、`novel-runtime.md`、`sync-security.md`、`jev-context.md`、`runtime-ci.md`、`miniapp-chat.md`、`phase4-terminal.md`、`settings-baseline.md`。
- 实现记录：`phase1-storage.md`、`phase1-runtime.md`、`phase2.md`、`phase3-jev.md`、`phase3-runtime.md`、`phase4-miniapp.md`、`phase4-terminal.md`。
- 独立复审：`phase1-review.md`、`phase2-review.md`、`phase3-review.md`、`phase4-miniapp-review.md`、`phase4-terminal-review.md`、`settings-review.md`、`phase5-review.md`。
- 最终统计/构建/签名：`final-verification.json`；变更相对原WIP：`change-manifest.json`。

以上results均位于仓库 `.workflow/android-review-20261002/results/`；旧报告的中间“pending/等待root”记录保留执行历史，完成状态以本报告及最终verification为准。
