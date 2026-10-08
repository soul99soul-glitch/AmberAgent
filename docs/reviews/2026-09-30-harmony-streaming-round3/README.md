# 鸿蒙流式生成：真机帧率与节点稳定性验收

## 当前交付状态（2026-10-01）

**稳定120fps未达标。** 最终版本已经签名、覆盖安装并在真机启动；235项相关测试全部通过，改动ArkTS组件lint零缺陷。两次最终长文请求均完整显示STREAM-END。最后一次真机采样已结束，设备已释放。没有再启动模拟器，没有更改系统刷新率设置。

本流式任务上一轮已验证安装包为 `/tmp/amber-stream120-final.hap`，SHA256 `d0b262a6020916c19a1a6610f648beae3f8e198e48d0de951446c51f10e3fff5`。`evidence.json`记录构建、签名、安装、启动和源码哈希；每轮性能指标以对应分析JSON为准。下文阶段记录中的旧包指标不能当作当前包验收。

应用已经消除已证实的反复节点创建和冻结Markdown前缀更新，但仍有超过8.33ms的UI帧。系统在无触摸自动上移时还能独立限制60Hz；现有公开API的120Hz请求不能证明实际呈现120fps。尚不能承诺恒定120fps或与iOS同等流畅。

## 范围与方法

同一真机 6HK0226627000384（HOP-AL00 / API 26），同一 DeepSeek deepseek-flash 模型与3000字雨后城市散文请求。使用系统 SP_daemon 呈现帧率与 hitrace 分析，区分应用回调频率、UI执行耗时、实际呈现帧率；静止结束区间不计入生成帧率。请求内容和模型实际思考时长存在运行间差异，因此按trace中的组件出现时间划分阶段，不能按同一秒数硬比较。

## 证据确认的问题与修复

1. postFrameCallback 驱动的主列表跟随没有明确帧率请求，正文可继承系统30Hz动画投票。改用API11 DisplaySync，在运动期间请求设备已知支持的刷新率，停止、触摸、页面退出时释放。保留iOS临界阻尼弹簧与48ms内容发布节奏。
2. List目标额外加了属于外部输入区的导航inset，造成到边界提前钳位。目标改为原生实测内容底部。
3. 思考Markdown逐次重建，直接scrollTo无动画；后续单个长Text也会整段重排。改为原生Scroll平滑跟随与保留已完成文本行，仅更新当前增长行。思考按512字符目标聚合完整自然行，减少原生节点总数；思考到正文切换同时保留流式子树和6000字符显示范围，用户再次展开完成的思考区时恢复整块文本连续选择。
4. 原正文淡入每帧重设StyledString，会触发原生测量。改为DrawModifier仅绘制透明度遮罩，内容改变才提交文本。API14以下保持原生即时追加。
5. streamingTail和history是两个ForEach，完成时整条助手消息和所有Markdown块重建，List位置被重置。统一为同一个ForEach和稳定message.id；V1保留闭包通过当前状态解析最新消息、分支和操作元数据。

## 阶段记录

- stream3fullbefore：修改前完整40秒录制，生成中多秒29–30fps。
- stream3after：移除绘制帧文本重测、修复思考Scroll后，正文仍被系统明确调度30Hz。见stream3-after-first-analysis.json。
- stream3clock：DisplaySync后正文约57–64fps；长思考单Text测量p95约19.24ms，思考实际39–46fps。
- stream3final：稳定消息身份+分行思考后，思考稳定窗均值54.5fps，正文55.5fps；思考文本测量p95约1.90ms。助手与正文节点跨终态保持ID，完成窗口最大ScrollBy约30.39，旧版超过千单位位移消失。此版另发现思考转正文一次44.755ms长帧，随后增加保留思考子树修复。
- stream3delivery：仅保留思考子树未足够。更长思考时短行累积到153–172个Text，后段平均40.2fps；结束显示范围仍由6000扩为全文，153→217项造成44.695ms切换帧。正文57fps、终态identity稳定仍有效。见stream3-delivery-analysis.json。
- stream3blocks：改为按完整自然行聚合512字符小块，从原文0确定固定分组，再裁剪首块；切换时保留原6000字符窗口。200短行回归由193个原生Text降至20个以内，且逐字保留换行。新增用例先红后绿；225项相关测试通过，ArkTS lint无缺陷，构建成功，已覆盖安装真机并正常启动。用户明确允许继续真机后完成40秒复测：本次思考较短，ForEach最多4项，切换4→4；切换UI帧降到25.352ms。正文前段59.2fps、后段54fps；后段Markdown更新p9511.558ms，UI p9515.149ms，尚不符合120fps目标。长思考窗口仍需覆盖。见stream3-blocks-analysis.json。

## 已验证的交互

流式中手动上滑后，直到生成完成，旧消息的STREAM-END坐标均为[55,870][1209,931]，未被拉回底部。对应stream3pause.json与stream3pausedend.json。4000字补充请求本次只返回思考内容，不能作为正文完整性样本；3000字主性能样本最终正文与STREAM-END可见。stream3fade9.jpeg捕获正文新增文字淡入且旧字清晰，stream3fade12.jpeg/23.jpeg显示完整FADE-END与加粗/斜体；这组画面来自最后思考分块改动前的包，正文渲染代码相同。

## 限制

实际呈现仍有偶发33–50ms间隔，不能称为恒定满60fps或已等同iOS。系统此次将显示屏调度在60Hz；trace中可见UI_ANIMATION_ENERGY_ASSURANCE的60Hz策略，不能推测温度或电量原因。用户要求不再开启模拟器后，已关闭本任务开启的 Amber Motion QA 实例。后续安装、流式、性能和暂停验证只使用真机；保留的模拟器截图来自较早候选版，不能作为当前交付版证据。

原始trace保留在/tmp，较小的CSV、分析JSON、测试日志与截图位于本目录。trace-manifest.json记录原始文件位置、大小和SHA256。未提交或推送仓库；工作区包含其他会话并发修改。

## 新增目标：稳定120fps

用户在本轮追加稳定120fps目标。以实际屏幕呈现间隔和8.33ms UI帧预算验收，不能将120Hz回调或帧率请求当作达标。当前DisplaySync已申请高刷新率；官方API说明请求受系统仲裁。OpenHarmony6.0固定版本源码中，UI idle能耗配置会同时钳制min/max/preferred，和本机ENERGY_ASSURANCE[60-60] trace一致；零售固件细节仍以实际trace为准。已将Markdown各块隔离成V1观察对象和独立组件，仅发布变化的尾块，避免冻结段落重复更新。

系统刷新率已在真机只读核对，原先就选中“高，最高120Hz”；未改任何刷新率设置。截图stream120rate.jpeg和布局stream120rate.json为证。物理模式支持120，但不会覆盖无触摸能耗策略。参数与系统XML读取被系统拒绝，未尝试绕过权限。

Markdown块隔离版：231项相关回归通过，两个新拆分ArkTS组件lint零缺陷，独立机械对照审查未发现渲染行为退化。AGC整包构建成功19.164秒；/tmp/amber-stream120-slots.hap（SHA256 83d44e2d73f0437257f9232d26e8ba6859f4bf202624b0dc2d9d3c6e133d7324）已覆盖安装真机并启动PID48374。stream120slots40秒采样已完成，最终正文STREAM-END可见，冻结块实际更新范围与帧预算见下文实测。

### Markdown块隔离实测

实际trace已验证冻结块不再随每个增量刷新：127次正文发布时父Markdown仅19次更新，各块仅在活动尾窗口更新，终态统一更新一次。正文主窗UI p959.807ms，322帧中35帧超过8.33ms、无帧超过16.67ms。思考前/后段p9513.373/13.718ms，思考->正文首建28.377ms、终态偶发17.643ms。因此稳定120fps仍未达到。

20.9775–21.3790秒有连续22个运动UI帧全低于8.33ms，p955.884ms、最大7.958ms，21次ScrollBy；DisplaySync请求[60,120,120]，DrawFPS/VSyncRate仍60，周期16.676ms。配合同轮ENERGY_ASSURANCE[60-60]，可确认此低负载窗口的60Hz不能仅归因于主线程忙。系统“高”模式原本已选中，未改设置。

最后继续保留思考块的Text身份：用V1观察槽更新text，针对旧实现246次删1建1。235项相关回归通过、ArkTS lint零缺陷；整包构建成功23.919秒，AGC签名HAP已覆盖安装并启动PID56835。当前包 `/tmp/amber-stream120-final.hap`，SHA256 `d0b262a6020916c19a1a6610f648beae3f8e198e48d0de951446c51f10e3fff5`。第一轮stream120final完整40秒trace与结束画面已采集，STREAM-END可见；其SP命令误传`-N40`未启动，不能提供该轮呈现FPS数据。已使用官方帮助确认的`-N 40`完成stream120final2补采，CSV有40个样本；静止后的0fps不纳入生成评价。两轮结束画面均显示STREAM-END，最终第二轮统计见下文。

系统机制参考官方OpenHarmony6.0固定commit（与零售固件行为相符，不等同于固件源码）：[无触摸能耗上限与min/max/preferred钳制](https://github.com/openharmony/graphic_graphic_2d/blob/0fa4396a14a9717472a45ca45f184338760f720a/rosen/modules/hyper_graphic_manager/core/frame_rate_manager/hgm_energy_consumption_policy.cpp#L211)、[刷新率模式变更重载配置](https://github.com/openharmony/graphic_graphic_2d/blob/0fa4396a14a9717472a45ca45f184338760f720a/rosen/modules/hyper_graphic_manager/core/frame_rate_manager/hgm_frame_rate_manager.cpp#L964)。

### 最终思考节点复用：第一轮

stream120final（最终安装包PID56835）269次思考Text测量只涉及11个原生ID（10个正文块和1个计时标题），同一活动块多次复用；只创建10个ReasoningLineText，思考ForEach仅10次结构更新。旧版几乎每delta删1建1的现象消失，冻结Markdown块隔离仍成立。思考后段UI p9512.264ms，342帧104帧超过8.33ms；正文p9510.598ms，233帧45帧超过8.33ms、1帧超过16.67ms。思考转正文首建仍有30.327ms帧，完成仍有21.071ms帧。SP无效使本轮实际呈现FPS未知；不以回调率替代。请求思考长度不同，不把轮间p95小幅变化当作确定的性能提升或回归。

### 最终补采：实际呈现帧率

stream120final2沿用同一最终包与PID56835，正确SP命令`-N 40`取得40个样本。通过trace实时时钟标记对齐CSV，排除第13–40个静止0fps样本。

| 阶段 | 实测窗口 | 实际呈现 | UI p95 | 超8.33ms |
| --- | --- | --- | --- | --- |
| 短思考 | 1.60–2.28秒 | 没有完整稳定SP窗 | 5.406ms | 0/81 |
| 正文前段 | 2.65–3.68秒 | 1个完整样本120fps /120Hz | 7.437ms | 3/123 |
| 正文后段 | 5.65–9.50秒 | 4个完整样本60fps /60Hz | 11.147ms | 43/226 |

3.802770秒trace第189878行出现`UI_ANIMATION_ENERGY_ASSURANCE [60–60]`。本轮短暂120fps不能代表全程稳定120；应用超预算和系统限帧同时存在。本轮思考转正文最大11.074ms，完成最大18.951ms，但同包第一轮长思考分别仍30.327/21.071ms，不能用短思考结果宣称长帧已消除。

两轮终态消息行与Markdown ID分别保持1120/1208和3231/3287，末段最大单次ScrollBy为24.504160/23.777399，List坐标连续，没有千单位终态跳跃复发。两轮最终STREAM-END与加粗/斜体画面已目测。6000字符裁剪边界的真实设备覆盖仍无法从trace字符数确认；专门回归已验证裁剪文本和边缘身份。手动上滑暂停已在前一版真机验证，最终包没有重复该交互采样。

真实完成条件仍欠缺：长思考、正文和状态切换都需要满足8.33ms预算，实际自动上移还需系统持续允许120Hz；当前公开帧率请求并不能强制覆盖观察到的系统策略。最终包保留已经实测有效的局部优化，没有增加伪触摸、空动画或系统设置改动来制造120Hz。

## 2026-10-01 稳定性跟进（设备对比待进行）

用户反馈大幅改善但大部分60fps、少量120fps。重新按Touch事件对齐最终两轮trace：松手到TouchIdle分别3.000986秒和3.001182秒，随后出现60Hz能耗投票。第二轮限帧发生在DisplaySync停止之前，高刷请求当时仍有效；延长空转时钟不能解释这次退档。精确行号见refresh-transition-followup.json。

本地新增一个局部改动：AgentMessageRow每个message快照缓存groupMessageParts分组，6个实际render读取从分组6次降为1次，仍读取message/parts保留V1依赖；文字、finishedAt、工具状态变化会使缓存失效。新增4项加既有分组共23项通过，流式相关239项通过，lint零缺陷；尚未以真机trace量化节省的UI耗时。

为核对官方文档所述固定范围的优先请求，已构建两个AGC签名候选：A保持[60,120,120]，B仅改为[120,120,120]（均只在真实自动滚动期间使用，到位/触摸/退出即释放）。B的10项运动回归和lint通过。它没有绕过系统限制的保证，不能把构建成功当作实际120。两个HAP、SHA、验证边界见stability-followup-state.json。当前工作源码已恢复原范围，固定范围只保存在候选包/快照。

已向用户询问约两分钟的真机独占使用窗口，尚待回复；本次没有安装或操作设备。后续先采A再采B，各40秒，按TouchIdle后实际呈现帧率和8.33ms预算判断；B无效则恢复A，不以参数设置宣称达标。

## 2026-10-01 改为90fps目标

用户明确提出90fps滚动，120 A/B计划已被替代。NativeTimelineFollow现在只在真实自动跟随运动期间请求固定90，停止/触摸/退出即释放。API20+从已支持刷新率中选不超过90的最高值；没有90模式的设备不会推测其支持90。API12保持保守已知档位。48ms内容发布节奏、淡入和弹簧曲线不变。

23项聚焦回归全部通过，覆盖60/90/120Hz弹簧收敛、90请求及释放、缺少90模式、API12、消息分组缓存和终态身份；原实现对应新目标先失败，修改后通过。官方ArkTS lint零缺陷，AGC整包构建成功25.634秒，候选为`/tmp/amber-stream90.hap`。SHA和源文件哈希见stream90-state.json。

本轮尚未覆盖安装，也未测实际90fps：深读会话已进入真机现场验收，之前设备使用时间问题也尚待用户回复，避免互相操作。物理屏支持90已由此前系统dump确认，但系统仍可能在TouchIdle把呈现限制到60；不能把代码设置90当作持续90实测结论。

### 90fps版本真机安装完成

用户明确要求安装后，已通知并行深读会话短暂协调。确认物理目标6HK0226627000384在线，HAP SHA256仍为491f3deb812201a91a79c1edd3b645a61941fed07630609cd11c260f8fef4236。`hdc install -r`返回install bundle successfully，保留现有数据；重启EntryAbility成功，PID16217。stream90installed.jpeg/JSON已确认正常ChatListPage和既有会话可见。本次仅安装与启动确认，没有运行生成帧率采样，因此持续实际90fps仍未验证。


## 生成中偶发跳变：局部修复（2026-10-01）

用户明确时机为“生成过程中偶尔跳一下”。本轮保持当前支持90Hz的运动请求、48ms快照发布与原弹簧曲线，未启动模拟器。

已复现并修复的三处问题：

1. 建议区域有独立52vp尾部留白。正文增长将留白挤出视口时，`measuredBottomTarget()`原来返回null，`NativeTimelineFollow`便无动画`scrollEdge`。现通过仍可见的正文项矩形加实际52vp留白测量目标，继续弹簧跟随；留白本身可见时不重复加高，其他未测结构语义保留。
2. `NativeMarkdownText`原来在`onAppear`才绑定文字，旧真机trace记录到新Text第一帧0×0，下一帧113字/305px。API15起官方文档支持在`aboutToAppear`预先设置属性字符串并在绑定Text时自动应用；本轮利用该能力准备首次布局，API12–14保留原附着后绑定路径。通过`canIUse`与API版本双重判断，附着时不重复提交相同内容。
3. iOS `ParagraphView.makeUIView`对新块入场固定淡入500ms，只有既有块追加按新增字数缩短。鸿蒙此前初次120字块约50ms即全亮，现新块固定500ms，后续追加仍走原规则，完成状态不截断既有淡入。

证据边界：`/tmp/stream120final2.trace`的229059/229601行及`/tmp/stream120final.trace`的740217/740904行来自旧120版本，支持空高度后整段填入的机制分析，不能当作当前90版本复现。旧trace生成中未见ScrollToEdge；因此不能断言用户这次跳动必然来自建议留白。当前iOS主聊天同样48ms全量发布，12–36字呈现预算只被小说/Council使用，本轮未引入额外文字队列。

验证：建议留白测试先红5/6、修复后6/6；Markdown预绑定与首次淡入新增两例均先红；相关真实方法/渲染器/滚动/稳定身份测试47/47通过。两份Markdown文件lint零缺陷，ChatPage lint零error/3条已有warning。首次构建遇到同工作区并行构建，完成后再单独构建以确认交付产物。安装与当前真机跳变/FPS状态见`streamjump-state.json`，未测项目不作通过声明。


后续用户反馈“视觉上又跳了两次”时，只读核对bm updateTime为2026-10-01 00:35:08.906 +08:00，早于修复HAP时间00:48:17.804，故这次报告仍发生在未包含本轮修复的安装包上。零售真机不允许读取应用HAP哈希，未绕过权限。另一个调试会话刚执行返回/点击后读到“正在回复…”的主聊天正文，确认设备操作重叠，已通知暂停；缺少两次跳动的同步记录，不能直接归因于该干扰。随后40秒纯只读trace/SP采样仅覆盖静止状态（无ScrollBy/ScrollToEdge/Markdown更新），未取得有效复现，不视为修复验证。安装与主动采样仍待独占真机窗口确认。


## 真机安装与再次定位（2026-10-01，当前结论）

用户批准占用后，首次候选HAP（03bbb44e…）在API26真机出现历史用户/助手正文空白，`aboutToAppear`预绑定未按文档预期生效。立即撤回该候选的预绑定逻辑，所有设备恢复Text的`onAppear`绑定；**此前“消除空高度首帧”方案未通过真机，不属于已交付修复**。保留建议留白几何修正和新块500ms淡入。修正包8d7d1430…已覆盖安装成功、启动PID41130，历史正文恢复；图`streamjumpfinish.jpeg`确认新生成终态STREAM-END、粗体/斜体均正常显示。

本轮40秒生成录制使用已有建议栏的“雨后城市”测试会话。生成期间没有ScrollToEdge（唯一一次在提交阶段0.7398s）；但4.43345s仍出现native trace offset100.536736的大步长。前一帧新建的三个Text从空内容填为34字/122px、8字标题/69px、87字/244px，弹簧在正常约16.6ms间隔内快速追赶，已能在当前90Hz请求版本重现。普通采样段实际约53–57fps，初期曾到120；固定90Hz请求并未得到稳定90fps，系统仲裁边界仍存在，不能宣称达成90fps。

针对已录到的大块布局追赶，新增有界速度：沿用原临界阻尼计算，仅将位移与保留速度限制为720vp/s（60Hz常规帧最多12vp，90Hz8vp）。典型单行增长仍由原弹簧决定；160vp批量增长的回归测试先红（首帧17.289vp），修复后连同相关用例48/48通过，并在60/90/120Hz均于一秒内追平。新包真机验证状态以`streamjump-state.json`为准，不能用本地测试替代体验结论。


## 120fps优先与LTPO适配：已安装及用户触发实测（2026-10-01）

用户重新要求以120fps为目标，掉帧时适配LTPO。当前运动期间请求范围改为[60,120,120]，从设备已知支持档位选择上限；静止、触摸和退出仍释放请求。以DisplaySync的targetTimestamp−timestamp推进运动，真实降刷新率时使用新的帧间隔，单次漏帧则不一次补走积压时间。此前720vp/s布局追赶上限一并交付。48ms发布及文字淡入保持不变。官方LTPO文档建议开放范围由系统仲裁，不能将expected120当成实际120。

50项相关测试通过，新增120→90→60Hz切换与50ms漏帧用例先红后绿；ArkTS lint零缺陷，AGC构建22s217ms。120LTPO包SHA256为95ba2f890209114f05aad24fe04c45ae3e7a57e7c0d0ef08d6dd0089ebd3e14b，已覆盖安装真机并启动PID52886。安装后曾遇前台系统设置，主动UI操作停止；本次性能测量由用户手动发起生成，全程只读采样，未启动模拟器或修改刷新率设置。

01:32一轮SP60秒和hitrace40秒以trace_event_clock_sync对齐：自动List移动为01:32:30.037–37.471，466次ScrollBy，没有ScrollToEdge。初始应用整秒样本106/115fps，120→60过渡秒92fps；持续60Hz自动上移的六个完整秒为59、57、56、55、53、54fps，均值55.67fps。末尾43/32/24fps发生在已测List运动结束之后，不能计入自动上移。样本结束截图已回ChatListPage，不是最终正文完整性证据。

本轮仍出现TouchUp→TouchIdle和系统UI_ANIMATION_ENERGY_ASSURANCE[60–60]，当时应用的[60,120,120]请求存在。实际主要为60Hz，未达到持续120fps。稳态328个呈现间隔中25个超过25ms（主要33.3ms），仍存在偶发漏帧。List单步trace单位最大32.994，对比前次100.537的大步追赶已受上限约束；内容不同，不能据此宣称所有跳变消失。用户本轮反馈“感觉不错”。

完整当前状态见streamltpo-state.json，具体FPS、帧间隔和时间对齐见streamltpolive-analysis.json。此节替代前文固定90目标及bounded-follow尚未安装的旧状态。


## 官方原生滚动动画候选（2026-10-01，等待真机）

用户授权继续以120fps为目标尝试官方系统动画。NativeTimelineFollow已改为Scroller.scrollTo的原生线性动画，只有目标几何变化时才更新动画，DisplaySync只请求60–120Hz且expected120并观察布局，不再逐帧计算scrollBy位移。最短160ms、最大720vp/s；真实设备漏帧时的原生时间推进仍需采样，不能把主机测试视为消除跳变的证明。

ChatPage以driver活动状态覆盖原250ms程序滚动归因窗口，真实DRAG/FLING先取消原生动画；触摸、离页保留原取消入口。独立只读审查发现回顶部与原生动画抢占，已修复为先暂停跟随再停止动画；历史定位和键盘直接位移也先取消旧动画。未改公共Timeline壳。

56项相关测试通过；原生动画调度新用例及3个归因用例、回顶部用例均有先红后绿记录。ArkTS lint为0error/3条原有ChatPage警告。最终AGC构建28 s 378 ms成功，HAP为/tmp/amber-stream120-native-animation.hap，SHA256为e719b2fef270a6c6b31ae455f41d30e16f2fdea6f89550582ce02ffe7212f517。当前源码仍是候选实现，尚未安装或实测FPS。

真机只读布局确认停在锁屏，已询问用户解锁并提供约2分钟采样窗口，尚待回复。没有启动模拟器或修改设备设置。streamnative-state.json记录当前候选边界；streamnative-candidate.patch与/tmp/streamnative-baseline保留本轮局部改动与回退依据。此前已验证LTPO包/tmp/amber-stream120-ltpo.hap保留。


## 官方原生动画已安装并实测（2026-10-01，替代上节待安装状态）

用户解锁并授权继续后，e719b2fe…原生动画候选已覆盖安装到6HK0226627000384，hdc返回install bundle successfully，启动PID25171；现有会话保留。安装后ChatListPage正常，在“雨后城市”会话发起一次真实DeepSeek长文本生成，并同时采30秒hitrace/SP。未启动模拟器，未改系统刷新率设置。采样结束已通知深读会话释放真机窗口。

通过trace实时时钟对齐，原生List动画调度从02:26:34.493开始，最终OnScrollStop在02:26:42.590。共92次原生动画目标提交；唯一ScrollToEdge在首次动画之前的发送阶段。原生动画没有逐帧ScrollBy记录，因此不沿用旧脚本的ScrollBy步长作为本轮运动证据，也不据此宣称所有跳动已消失。

前段两个完整120Hz采样秒呈现104、120fps。TouchUp到TouchIdle为3.000538秒，02:26:37.479仍出现UI_ANIMATION_ENERGY_ASSURANCE[60–60]，短暂回120后再次降60。三个完整60Hz自动上移样本为60、60、59fps，均值59.67；176个呈现间隔中1个超过25ms（33.337ms），中位16.671ms、p9516.688ms。本次60Hz窗口比前轮59/57/56/55/53/54更均匀，但仅一次短采样、文本不同，不能当作严格A/B量化提升。50fps尾窗跨越运动结束，32/20fps发生在结束之后，静止0fps也均未计入稳定滚动统计。

终态streamnativefinish.jpeg已目测确认STREAM-END、小标题、加粗、斜体和操作栏正常。用户反馈“整体还不错，远远没有稳定维持在120fps，但看着还算流畅”。保留本版；持续120fps目标未达成，系统能耗仲裁仍存在。最终包手动拖动/回顶部的真机重测未包含在本次窗口内，当前该部分证据为56项主机回归中的覆盖。完整数据见streamnative-analysis.json和streamnative-state.json；原始trace保留/tmp/streamnative.trace并记录SHA。


## Fixed 120Hz trial prepared (2026-10-01; waiting for unlock)

User requested a fixed120Hz trial. Only DisplaySync min changes from min(60,rate) to rate. Native animation,48ms content cadence and lifecycle release stay unchanged. Three assertions failed before the change;17 focused tests passed after it. ArkTS lint found no defects.

Control build15.476s: /tmp/amber-stream120-native-control.hap, SHA256 a0ef199c2252fdc87e06e925030a7e7ff2d24e67137f783dd0ce6d1e7b46b858. Fixed build15.472s: /tmp/amber-stream120-native-fixed.hap, SHA256 726cfe65999a40a072a9712f9b6d6e40f217145fba0bb65b859104ee8fa6de0d. Both use existing AGC signing.

Two read-only device checks showed lockscreen. User unlock requested; candidate not installed or measured. Source and tests restored to original60-120 range while waiting to avoid accidental propagation into concurrent builds. Fixed source retained at /tmp/streamfixed120-native-candidate and streamfixed120-candidate.patch. Next: after unlock, install fixed candidate, measure real presentation after TouchIdle; restore control package if ineffective. No simulator or system setting changes.


## Fixed120 physical result: ineffective; control restored (2026-10-01)

After user unlock, fixed candidate726cfe65 installed successfully and launched PID59941. Real streaming test used existing conversation and DeepSeek model. Trace contains668 DisplaySync events with FrameRateRange[120,120,120], proving request reached runtime. Native List animation spans11:06:28.816-38.403. TouchUp11:06:28.756 becomesTouchIdle11:06:31.757; systemUI_ANIMATION_ENERGY_ASSURANCE[60-60] appears11:06:31.963 while List still animates. Fixed120 does not prevent cap.

An additional touchDown11:06:37.141/touchUp37.165 precedes recovery to120Hz. The capture script injected only initial send, so this later touch was not part of automated sampling. The120fps sample after11:06:39 occurs after List animation ended and is not counted as120fps scrolling. Content differs from earlier tests; do not claim quantified regression from44/47/50fps samples. Final screenshot confirmsSTREAM-END and bold/italic rendering.

Control packagea0ef199c successfully reinstalled and launched PID62667, ChatList verified. Source remains original60-120 native animation. Device window released and authorized deepread thread notified. State, trace hash, CSV, screenshot and aligned events recorded in streamfixed120-state.json and streamfixed120-analysis.json.
