# Android 会话列表左滑删除与限时撤销开发计划

## 目标与范围

为 SessionHome 会话列表增加参考 Telegram 交互形式的底部浮动撤销提示条：左滑完成后会话立即退出列表，5 秒内可撤销，超时才调用已有删除服务。不增加阻塞确认弹窗。不修改其他页面的删除语义，不跨产品仓库取代码。

## 当前接线

`HomeSessionRow.onDelete -> HomeConversationList -> SessionHomePage -> SessionHomeVM.deleteConversation -> ChatService.deleteConversation` 当前立即删除。列表使用 Paging，搜索使用会话摘要。需要同时处理普通列表、搜索、首页继续入口，不能仅增加提示文字。

## 交互约定

- 底部提示显示“已删除会话”、可见倒计时和“撤销”；多条时显示数量，文案使用 Android 字符串资源。
- 撤销窗口为 5 秒。连续删除合并到当前待删除批次，并从最后一次有效删除重新计时；撤销恢复整批。重复同一 ID 不重置期限或重复删除。
- 提示条位于系统导航栏之上，与新会话 FAB、键盘和双栏布局避让，遵循项目主题。恢复后的会话保持原有置顶和排序，滑动状态恢复，可再次删除。
- 旋转、重组、切换搜索不重置时限；页面仍在返回栈时 ViewModel 继续计时；ViewModel 销毁或进程死亡时未提交批次自动取消，数据库保留原始会话。该安全回退无需数据库迁移。
- 撤销只接受期限内且仍处于 pending 的批次。到期原子切换为 committing，关闭撤销入口，防止撤销/超时竞态。

## 实施步骤

1. 检查删除服务的停止任务、持久删除与清理语义，保留已有 domain owner；新增可定点测试的待删除状态管理器（或 ViewModel 内等价隔离逻辑），由 ViewModel scope 驱动，使用单调时间和唯一批次标识防止旧计时器提交新批次。
2. 状态至少涵盖 pending IDs、deadline、committing IDs、错误。pending 期间不调用破坏性服务；到期每个 ID 最多提交一次，逐项捕获错误并恢复失败项目、反馈错误。保留成功删除项目的隐藏状态直至数据源确认，避免刷新闪回。取消异常按协程语义处理。
3. 将隐藏 IDs 接入正常分页和搜索分页，正确处理空状态与首尾行；同步过滤指向待删除会话的继续入口，防止仍可从首页打开。验证 Paging 缓存/转换顺序与重新收集不会产生旧页面或重复消费。
4. 在 SessionHomePage 接入底部撤销条与状态；UI 只展示时限并发送撤销意图，不拥有提交计时器。采用项目已有样式和简洁进出场效果，保证可访问标签、触控尺寸与大字体布局。
5. 编写实际覆盖竞态与副作用的定点 JVM 测试：期限前撤销无删除、到期一次提交、连续删除批量撤销、重复 ID、旧计时器失效、边界撤销、失败恢复、owner 取消。补必要 Compose 测试验证底部撤销接线和恢复后再次滑动，复用现有 HomeSessionRowSwipeDeviceTest。

## 验证与交付

- 先运行相关 JVM 测试，再运行对应 debug APK 构建及必要 instrumentation 编译。
- 按仓库 AGENTS.md 要求检测已连接真机，安装 APK 并启动。设备可用时检查左滑、撤销、到期、多条、搜索、旋转与 FAB 避让；测试只使用明确标识的测试会话，不删除用户真实数据。
- 无真机、构建依赖或设备执行受限时记录准确原因和未完成验证，不宣称通过。
- 实施后更新本文件的结果、命令和限制；不提交、不推送、不创建 PR，除非用户另行要求。

## 执行分工

按用户本次指示，由当前主代理直接完成实施和验证，不启动子代理。实施位置为 `/Users/hugo/Desktop/git/AmberAgent`，不执行 commit、push 或 PR。


## 实施结果（2026-10-23）

- 新增 `HomeDeleteUndo`，由 `SessionHomeVM.viewModelScope` 持有。使用 `SystemClock.elapsedRealtime()` 和递增批次标识维护 5 秒期限；有效新 ID 合并并重新计时，重复 ID 不延长窗口。到期同步转为 committing，再逐项调用原有 `ChatService.deleteConversation`，未改动其停止生成、tombstone、持久删除和清理流程。
- 撤销只接受期限内 pending 批次，恢复整批并递增各行恢复版本；旧计时器失效，提交中的批次无法撤销。新批次可以与已提交批次并存。没有 SavedState 或数据库 pending 写入；ViewModel 取消时 pending 自动放弃，已开始的 NonCancellable 项允许完成，后续未开始项不再调用。
- 删除报错后查询 `existsConversationById`：确认仍存在才恢复该行并反馈失败；确认已不存在时保留隐藏并反馈清理失败；查询也失败时保持隐藏，提示重启应用后核查。取消异常继续传播，不作普通删除失败处理。
- 新增 `observeVisibleHomeConversations`，顺序为原始 Paging `cachedIn` → 隐藏 ID 过滤 → 结果 `cachedIn`。普通分页与全量摘要搜索统一使用该链；继续候选同步过滤 Chat / ImageGeneration / CouncilRoom，点击时在数据库检查前后重新核对隐藏状态。列表打开也核对当前 owner 状态，阻止旧 UI 快照打开待删会话。
- `HomeSessionRow` 松手确认删除后只发送一次操作，不再等待动画，也不再从 `finally` 发出删除。恢复版本作为行组合 key 的一部分，覆盖快速撤销、未完成移除动画及恢复后再次左滑。
- 新增主题化底部浮动撤销条，包含数量、5 秒倒计时圆环和“撤销”，使用英语、简体与繁体资源。160ms 透明度进出场复用项目曲线，遵循系统动画时长；到期或撤销后退出动画期间按钮立即禁用。窄屏或大字体切换堆叠布局。底部容器合并导航栏与 IME insets，FAB 放在提示条上方，列表底部按容器实际测量高度留白；双栏提示条限制在首页面板内。

### 对计划的实现细化

为避免尚未消费的旧 Paging 页和 Continue 投影在删除完成后闪回，成功删除及无法核查的 ID 在当前 ViewModel 生命周期内保守保留隐藏标记，而非仅凭全量摘要缺少该 ID 就提前移除。标记不持久化，ViewModel 重建后以数据库为准。代价是标记内存随本次删除数量增长；如果其他入口在同一 ViewModel 存活期间以相同 ID 恢复会话，需重建首页 ViewModel 才会重新显示。待删除撤销和确认失败恢复不受此限制。

### 已执行验证

在 `/Users/hugo/Desktop/git/AmberAgent` 执行：

```sh
./gradlew :app:testFullDebugUnitTest --tests 'app.amber.feature.ui.pages.sessionhome.*' --console=plain
./gradlew :app:testFullDebugUnitTest --tests 'app.amber.feature.ui.pages.sessionhome.*' :app:assembleFullDebug :app:compileFullDebugAndroidTestKotlin --console=plain
git diff --check
adb devices -l
```

- 会话首页相关 JVM / Robolectric 测试 **40 项，0 失败、0 错误**。其中 `HomeDeleteUndoTest` 12 项覆盖期限前撤销、整批提交、有效删除续期、重复 ID、旧计时器、边界竞态、并发新批次、部分失败恢复、持久删除后的清理异常、存在性查询失败及 owner 取消。
- 真实 `Pager` / `PagingDataPresenter` 测试覆盖追加页、全部隐藏后的空列表、撤销保留原有顺序及置顶、重新收集、搜索模式切换及搜索撤销，没有重复消费 PageEvent 或额外重建同一 Pager。
- Compose / Robolectric 覆盖提示条按钮与倒计时、280dp / 200% 字体完整文案和可操作性，以及实际 `homeConversationItems` → 删除 owner → 撤销条 → 恢复后第二次左滑。首轮大字体检查发现文字自然宽度取整溢出，已改为占满可用文本宽度并通过复测。
- 复用并扩展 `HomeSessionRowSwipeDeviceTest`，新增生产分页列表的撤销后再次左滑用例；`:app:compileFullDebugAndroidTestKotlin` 编译通过。设备测试未执行。
- `:app:assembleFullDebug` 成功，包含 `:app:verifyFullDebugNativePageSize` 通过。APK applicationId 为 `app.amber.agent.graphite`，版本 `2.6.8`（396）。
- APK：`app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk`；另有 `app-full-universal-debug.apk`。
- 构建日志：`/tmp/session-undo-build.log`（最终重复确认日志 `/tmp/session-undo-final.log`）；测试 XML：`app/build/test-results/testFullDebugUnitTest/`。

### 真机验证与限制（首次实施记录，后续结果见下方）

两次 `adb devices -l` 均只输出 `List of devices attached`，没有任何设备。首次在沙箱中启动 adb 被系统拒绝，随后以获准的非沙箱命令启动服务并完成上述空设备列表检测。因此**未安装、未启动 APK，未执行真机 instrumentation，也未完成真机旋转、系统导航栏、键盘、双栏和连续删除交互检查**，不宣称这些验证通过。

所有自动化删除均为内存测试 fixture（含明确标识的 `Undo fixture only`），删除副作用由测试回调记录，没有连接用户数据库或删除真实会话。构建与测试存在项目已有 Compose 测试 API 弃用警告，不影响上述成功结果。未读取其他产品仓库，未 commit、push 或创建 PR。


## 真机补充验证（2026-10-23，用户接入设备后）

设备 `392288d7`（model `25128PNA1C`、device `nezha`）已连接。主 APK 安装成功，`am start -W` 返回 `Status: ok`，`RouteActivity` 在 `topResumedActivity`；设备测试结束后再次打开 debug 首页。原先“无设备、未安装、未启动”的限制已解除。

### 设备测试结果

**共 6 项 instrumentation 测试通过：**

- `HomeSessionRowSwipeDeviceTest`：`OK (5 tests)`，16.819 秒，覆盖 LTR / RTL 置顶与删除、手指未释放不触发操作、整批撤销恢复与生产分页行再次滑动。
- 新增 `HomeSessionUndoPersistenceDeviceTest`：`OK (1 test)`，20.664 秒。使用真实首页、导航 ViewModel、Room 和既有 `ChatService.deleteConversation` 链路；只插入随机 UUID、标题前缀 `UNDO DEVICE TEST ` 的两个空测试会话。
- 持久化断言确认：pending 期间数据库仍保留原始会话；第 2 条有效删除重启整批期限，超过第 1 条原始期限后两条仍存在；撤销恢复整批；恢复后再次左滑，搜索中不显示被隐藏行；真实横屏旋转后仍在原期限提交，数据库确认删除；未删除的另一个测试会话仍存在。
- UI 几何断言和截图确认：默认字体下撤销条位于系统手势栏上方，且没有覆盖“新对话”FAB。
- 关闭首页 Activity / 清理 Home ViewModel 后，前台改用无首页 ViewModel 的空测试宿主；等待超过 5 秒后，剩余 pending 测试会话仍存在，验证尚未提交的删除安全取消。
- 当前运行的两个测试会话和此前中断运行日志中明确记录的测试 UUID 均已核查清理；测试清理对 UUID 和 `UNDO DEVICE TEST ` 标题前缀双重限定。真实到期删除走生产 ViewModel / ChatService；测试收尾仅对从未生成内容的空 fixture 使用 repository 清理，没有批量枚举删除或操作用户会话。

### 本次新增的测试环境修正

- 默认 `AmberAgentAndroidTestRunner` 使用空 Application，在本机触发提醒广播时出现 `KoinApplication has not been started`，因此设备执行改用仓库已有的 `AmberAgentUiSmokeTestRunner`（`-PuiSmokeTest=true`），未修改生产代码来绕过问题。
- 设备系统拦截测试 Activity 的后台启动。对行组件测试新增外层 JUnit rule，在 Compose rule 之前通过 shell 显式前台启动空宿主。
- 中途尝试发生搜索“清除/取消”控件定位错误，以及首页退出后测试检查/清理超时；已修正测试操作、避免旋转后重复并发重建，并在清理 Home owner 后保持空宿主前台。最终上述两组测试均完整通过，未把中途失败计为通过。清理具有明确 UUID、标题校验和超时保护。

### 执行命令与证据

```sh
adb devices -l
adb -s 392288d7 install -r app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk
adb -s 392288d7 shell am start -W -n app.amber.agent.graphite/app.amber.agent.RouteActivity
./gradlew -PuiSmokeTest=true :app:assembleFullDebugAndroidTest --console=plain
adb -s 392288d7 install -r app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
adb -s 392288d7 shell am instrument -w -e timeout_msec 45000 -e cleanup_ids 9164ba9a-55ab-45ce-937d-1c787ecd47cd,4c4b4fbf-a30d-4986-afbd-c80a78d2a784 -e class app.amber.feature.ui.pages.sessionhome.HomeSessionUndoPersistenceDeviceTest app.amber.agent.graphite.test/app.amber.agent.AmberAgentUiSmokeTestRunner
adb -s 392288d7 shell am instrument -w -e timeout_msec 20000 -e class app.amber.feature.ui.pages.sessionhome.HomeSessionRowSwipeDeviceTest app.amber.agent.graphite.test/app.amber.agent.AmberAgentUiSmokeTestRunner
git diff --check
```

- 构建日志：`/tmp/session-undo-real-runner-build.log`。
- 最终测试结果：`/tmp/session-undo-persistence-device-verified.log`、`/tmp/session-undo-swipe-device-verified.log`。
- 检查点与 fixture 清理记录：`/tmp/session-undo-device-checkpoints.log`（包含真实链路最终成功运行）。
- 截图：`/tmp/session-delete-undo-device/pending-fab.png`、`/tmp/session-delete-undo-device/pending-search-ime.png`。已检查截图；后者虽为搜索状态，但未显示系统键盘，因此不将其当作键盘弹出避让通过的证据。
- 主 APK 仍为 `app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk`，本次仅增加/修正测试与验证记录，未修改先前已通过 40 项 JVM / Robolectric 验证的生产实现。

### 仍未完成的设备验证

本次未专门验证系统键盘真正弹出时的提示条避让、同时打开会话的实际双栏布局、真机 200% 字体以及进程被系统杀死的场景；大字体有前述 Robolectric 证据，ViewModel owner 取消已有真实设备证据。未宣称这些剩余设备场景通过。没有 commit、push 或创建 PR。
