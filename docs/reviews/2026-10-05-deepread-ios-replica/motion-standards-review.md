# 动效与设置补修：Standards 独立复核

2026-10-05。只读 reviewer；本文由独立 Standards reviewer 维护。基线为 `/tmp/amber-deepread-motion-baseline-20261005` 的上一安装源快照，按文件使用 `/usr/bin/diff -u` 比较当前 `harmony/{entry,chat,deepread}/src/main`。不使用包含大量既有 WIP 的 HEAD 全量 diff 代表本轮；模块 branding/string 差异不算动效变更。未修改生产或测试、未构建、未操作设备。

## 最终源码复核

复核截止 **2026-10-05 17:25:26 CST**。各 worker 已完成生产源码定稿；下列结论覆盖设置草稿、来源、模板、阅读库、编辑室、夜间背景、reduce-motion 与 haptics，以及发现页散报/幸运横幅/骰子、TabBar、Symbol、主 agent 最后追加 Board arrival 初帧和设备反馈后的设置 session 修复。对固定基线的 28 个新增或修改 `.ets` 文件已逐项复核，无聊天或深读 domain 的本轮生产变更。按排序路径加 NUL 和文件原始字节累计 SHA-256 为 `1f1f8e18acc1943d0d07cee1b42ebcee5263914cbe1b14e299065d9130f15a58`。该摘要标识本报告的取样状态，不替代 HAP 验证；以后源码发生变化需增量重审。

**最终未发现仍未解决的确证正确性回归或硬规范违例。**

**后续设备证据发现的 P1，源码修复已窄增量复核：**主任务确证跨 Tab 替换页面会丢掉未保存设置草稿（搜索结果数 10→11，返回设置恢复 10；iOS 保留 11），见 `motion/device/11-settings-cross-tab-draft-result.json`。这属于此前仅验证子页返回与主题重挂时遗漏的生命周期场景。当前受 ability session 所有的内存草稿已修复，详见末节；reviewer 已确认代码与行为测试，不以源码复核代替最后设备回归。

初审未发现 P1 或硬规范违例。增量复核发现过一个 P2 宿主保存语义回归，**当前已修复并复核**：`DeepReadDiscoverySettingsPage.ets` 的宿主刷新间隔 callback 调用了新 `persistPreference`，入口最初未限定独立 DeepRead，使仍有保存按钮的宿主页面提前写 KV。当前入口行 104 在非 `deepread` 时只清理 notice 并返回，不写存储；定向行为 case `host discovery source edits retain the original explicit-save timing` 检查源开关及全部标量入口。独立 DeepRead 仍即时保存，宿主显式保存契约恢复。

其余确认如下：

- 全部新增状态使用 V1；没有 `export let` 新增全局状态。设置草稿上移至 Entry 页并用 `@Link` 传入，主题 ForEach 重挂载子组件时保留字体、搜索标量及模型草稿；服务目录刷新按稳定 ID 解析，保存合并最新持久配置而不覆盖服务凭据。
- 模板默认选择返回时重新读取，只在持久选择确有变化时替换父草稿；未保存字体/字号显式传至模板预览并阻止旧 KV 回读覆盖。模板行使用稳定 ID，选择弹跳持有 motion/lifecycle token，主题变化、后台、隐藏和销毁时归位。
- reduce-animation 高版本调用同时受 `sdkApiVersion >= 23` 与 `canIUse` 限制；观察者归 EntryAbility 所有，前台刷新、销毁移除自身回调，排队的销毁后回调不写 UI。API 12 路径保留正常动效，没有调用高版本入口。
- haptics 按产品、前后台与硬件能力门控；失败不阻断用户操作。阅读库运行圆点计时仅在前台/可见/运行态开启，并在失活、减少动效及销毁时释放。编辑室轮播与 pulse 有 active/background 生命周期控制；keyframe 回调校验 motion owner。
- 编辑室 reduce-motion 路径最终改为 4200 ms 的格言时钟，正常路径维持 300 ms 活字节拍；完整 1210 ms 光环使用独立有限 timeout，旧 owner 和 timer identity 隔离。完成章由真实持久完成 trigger 驱动，字幕/落章/退出分别清理，减少动效变化直接收敛且不回放旧触感。
- 主 agent 追加的 Paper 初帧延迟有 alive/timer 清理；StatusPanel 以可见/前台门控 top/opacity 过渡，进度 label 的 stable string key 只替换文字子树。Article 将实际 editorialActive 传入，未修改成功/失败/继续逻辑。
- 新模块按职责拆分：系统偏好观察、触觉、发现卡片动效和阅读库筛选各自独立；未引入 V2、通用动画引擎或无需求的接口层。
- prepare 脚本本轮只增加 DeepRead 的 ACCELEROMETER 权限；Novel 保持原权限，测试显式断言产品差异。图标字节复制由用户明确授权，旧图已单独备份，现有打包引用链不变。
- 发现页 sensor owner 留在主题重挂边界之外；订阅限当前可见、前台且无创建/操作 sheet，退出只取消自身 callback。散报/横幅/骰子的 timer 在隐藏、后台、取消、减弱动效和销毁时清理；有限骰子回弹有 expiry cycle 验证。骰子用已有基础 Shapes 表达，没有编造缺失的系统 symbol。横幅实测高度仅回流发现列表底部 padding，避免遮末端控件，未新增通用布局引擎。
- TabBar 预置 activeTab 让旧内容立即不可见，导航失败恢复原索引；成功后触感反馈，保留绑定 Router 的历史清理。指示器、卡片、刊头横线、首次印章和 notice 使用受生命周期保护的初帧延迟。该修正有源码证据；具体可见轨迹仍由设备连续帧确认。
- 最后增量的 Library 入场与空态反馈均有限、16 ms 初帧、motion ID 与可见性校验，离页/后台/减少动效后归位。Composer 成功反馈仅在已 upsert 的 draft 设置 pending ID 后触发，重试路由保持同一 ID，不按动画宣布生成完成。新 DiscoverySymbol 仅复用 badge bounce 和 empty wiggle 的两种实际用途，有限链条且清理 timer/cycle；标题通过已有 Motion 明确禁 scatter。减弱动效保留入场 opacity、移除几何位移，刊头 ink 140 ms 与几何 180 ms 分离，并保留浅色 MULTIPLY/深色 NONE。

## 判断建议与验收边界

暂无足以要求重构的 Fowler smell。既有大文件保留并以窄增量修复，不能把整个工作树的既有长度或测试删除归责本轮。`DeepReadHaptics` 的能力失败静默属于增强反馈的明确非阻断路径，不建议为它增加重试或新持久状态。

源码和生命周期正确不证明实际弹簧轨迹、首帧、帧率、触感或系统动画效果一致。主任务仍须完成 lint、构建/签名、最终包安装、主题截图和动态取样；本报告不把旧设备截图作为本轮动画验收。

强调色选择遵守现有 `themeEpoch` 整页子树重挂机制。它能保证新颜色重读并保留父页草稿，但 ring 的跨重挂连续 geometry 迁移不能凭源码认定已与 iOS 一致。系统 SF Symbols 的 breathe/replace、原生 zoom 与 Tab 收缩也不能以平台近似声明逐帧等价；这些属于 Spec / 设备验收边界，不是本轮要求引入新动画框架的依据。

独立 reviewer 读取了各 worker 的本地官方 API 证据：`geometryTransition` API 10，`springMotion` API 9，`onVisibleAreaChange` API 9，`keyframeAnimateTo` API 11，组件转场 API 10，MULTIPLY API 11，sensor API 9，以及 API 23 accessibility 的显式版本门控。此轮使用兼容形式，不使用更高版本的 frame-rate 选项。测试/lint 结果引用各实施报告的定向结果，reviewer 未独立重复执行测试，最终集成验证由主任务负责。

三个 legacy fixture 的最后适配补齐新 EntryAbility MotionPreferences 的 constructor 依赖、宿主 getProductKind 与 persistPreference 方法依赖，现有行为断言保留；没有把宿主测试转换为独立 DeepRead 的即时保存语义。主任务报告集成 4854/4854、184 suites 通过及 DeepRead 构建签名成功；这些属于主任务执行证据，并非 reviewer 重跑。

17:06 最后两文件增量：Board `arrive` 持有 16 ms timer，active tab／背景／减少动效变化及 onPageHide／aboutToDisappear 清理，迟到 callback 校验 timer、可见性与前台条件；不改真实生成入口。Board fixture 补齐 `settleArrival` 方法依赖，并把过宽的整文件“没有 setTimeout”检查限于 `randomItems/randomRead/openArticle/openRun` 这四个生成入口，继续禁止 scheduler 直接 run，同时保留 active run、来源 metadata、随机选择和导航行为断言。这是排除合法装饰动画的测试范围修正，不是放宽生成所有权约束。此两文件复核没有新增问题；最终重跑结果由主任务收集。

Novel 本轮最终交付按原 HAP 仅替换两处图标 PNG，再由官方工具签名；主任务的 payload 比较限定 109 项、ABC/native 字节不变及 4 KB 代码对齐偏移。该证据只支持图标包的受控替换，不能把 Novel repack 称为新增功能源码构建或本轮全部共享源码交付。

## 17:25 设置 session 窄增量

只追加复核 `SettingDeepReadPage.ets`、EntryAbility 的 DeepRead onCreate 清理，以及新的 session 测试和相关 fixture；未重做其他 26 个生产文件。

- `deepreadSettingsSessionDraft` 为显式 typed AppStorage snapshot，未注册 PersistentStorage；只在独立 root 的已编辑或 dirty model 情况缓存。搜索只留本页拥有的标量与 preferred service ID，不保留服务数组、API key 或 provider credentials；reading 留字体/字号及模板持久 baseline，model 留 dirty pair 和显示信息。缓存深拷贝，不共享旧页面可变引用。
- root 初次 appear 恢复内存草稿，随后重读最新 KV；只覆盖本页拥有的搜索标量，并按 service ID 合并新目录，最新 key、enabled IDs、其他 search option 仍来自实际 KV。删除的 preferred ID 保留可见错误，禁止悄悄选别的服务。模型目录同样重读，已删除模型保留不可用草稿及错误，保存不写模型配置。
- restored reading 的晚到 KV 不覆盖字体草稿；模板仍比较原持久 baseline，若 child/外部持久默认确实改变就采用新值，而其他未保存字段继续保留。没有通过切 Tab、预览或恢复 draft 提前写 KV；正文读持久设置，不消费此 session 的字体。
- Save 成功通过原有存储链提交后清 dirty 状态并删除 session；后续 hide/disappear 不重建已提交草稿。新的 DeepRead EntryAbility.onCreate 在确认 product identity 后删除 key，确保同进程新 ability 也不恢复上一 session 未提交内容；Novel/宿主路径不清此 root draft。原 Want 路由与 MotionPreferences start 保持。
- 五个实际 Page 重建测试覆盖跨 root、目录重排及 key 更新、已提交清理、冷 session/宿主/嵌套隔离、child 模板 baseline、删除服务与删除模型；新 onCreate 测试执行真实方法，检查 DeepRead 清理、Novel 不清理且路由/观察启动不变。fixture 仅补新的 restore/saveSession 依赖，原断言未降级。worker 报告定向 36/36、页面 lint 0 error / 7 warn，主任务的 API 生命周期定向测试亦通过；reviewer 未重复执行。

没有发现新的 correctness 或硬规范问题。Settings 文件现在 1431 行，仍在仓库 1500 行常规上限内；本次保留 UI/设置所有权的局部实现，不建议为一个真实 session 场景另建通用缓存或事务框架。设备回归、最终集成重跑和安装由主任务收集。
