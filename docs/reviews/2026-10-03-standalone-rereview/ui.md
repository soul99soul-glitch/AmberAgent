# 独立 UI rereview：两个 Harmony 应用

2026-10-03。只审查和记录，不修改生产代码，不执行 build/sign/install，不操作设备；本轮设备由 root 独占采集。读取了仓库 AGENTS.md 与 harmony-arkts skill。当前共享工作树存在大量已进行中的改动；结论绑定当前代码与下列具体设备帧，不把旧报告的通过结论当本轮事实。

## 当前结论

发现一个明确的局部触控问题：小说项目控制的“保存”完整控件原来完整控件只有约 28vp 宽，外围空白没有保存 handler。root 已局部加 minWidth44，源码与修后新包44×44vp完整热区、边缘点击回执均通过独立复核，R2-UI-01关闭。其余已查看的正常字号、竖屏、长名称、明暗主题截图未看到需要新增布局修复的错位、挤压或重叠。新的 Compose IME footer 帧补齐了上一轮证据缺口：键盘展开时模板与生成按钮都可滚动到完整可见。

这不是全应用、全字号的通过声明。小说项目控制偏好与项目设置润色偏好 footer 已取得本轮新 IME 帧并确认可达；快速项目种子／本轮重新生成 footer 尚未设备覆盖；busy 的阻止操作逻辑已读源码，尚未拿到 busy 期间的本轮 AX 与边缘点击证据。

## Finding R2-UI-01 — P2：项目控制保存横向热区过窄（已关闭）

触发：小说工作区 → 点击项目名称打开“项目控制” → 共创模式 → 向下滚至写作偏好下方的“保存”。

截图证据：旧 `../2026-10-03-standalone-completion/device/p7/novel-control-light-bottom.jpeg` 已实际打开查看，保存位于 sheet 右侧、可完整见到；未与其他动作重叠。AX 同名 JSON 的实际 `bounds` 与 `origBounds` 都为 `[1158,755][1257,909]`：完整 clickable Text 是 99×154px，在这组设备 3.5px/vp 下为约 28.3×44vp。不是拿文字 glyph 或 Scroll 裁切后的可视边界当热区。它的父 Row 不可点击；sheet 外层 Column 的点击是空 handler，不能使保存热区变宽。

审查基线代码证据：`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets:3021-3028`。Text 原来只有 `constraintSize({ minHeight: 44 })`，没有宽度约束或水平内边距，且保存 handler 只挂在这个 Text 上。

影响：这处横向热区比同 sheet 的“完成”44vp、冻结计划查看52vp、任务操作72vp都窄；靠近“保存”文字左侧的空白触摸不会保存，尤其键盘展开后需要准确命中。

最小修法：只给该 Text 增加 `minWidth: 44`，配合居中或水平 padding 保持右侧动作对齐。无需重构 sheet，也无需统一改所有默认40vp控件。本轮新 `device/novel-control-save-before.jpeg/json` 已实际查看：完整保存按钮 `bounds=origBounds=[1158,982][1257,1136]`，仍为99×154px，确认当前安装包复现。本轮 `device/novel-control-preferences-ime.jpeg/json` 同样为 `[1158,1526][1257,1680]`。root 随后已将3025行改为 `constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)`；已复读当前代码，满足最小修法，修后新包热区与边缘点击复核见下，已关闭。

## R2-UI-01 新包关闭证据

实际打开本轮 `device/novel-control-save-after.jpeg/json`：完整 clickable 保存 `bounds=origBounds=[1103,1444][1257,1598]`，154×154px即44×44vp；文字保持右侧对齐，与左侧回执区域、上下输入／操作间距正常，无重叠。新热区左边界从1158px扩到1103px。

root实际点击 `(1110,1520)`，该坐标在旧热区1158px左侧、在新热区内。本审查者实际打开 `device/novel-control-save-edge-result.jpeg` 并读JSON：出现“已保存到当前分支”，其 `bounds=origBounds=[63,1497][1103,1546]`，保存仍154px square，回执与动作不重叠。这个边缘点击结果验证扩大的空间可以执行保存，不只验证宽度声明。

同时实际查看 `device/novel-project-preference-unchanged.jpeg/json`，项目润色偏好只保留原 `P2 project polish preference`；`device/novel-control-bottom-reload.jpeg/json` 的分支写作偏好为空、后续计划仍为原两条Rhea/Rider文本；修后保存前后图同样为空偏好。早期IME布局验证用的R2草稿未被此次边缘保存带入。R2-UI-01关闭，未发现新的布局副作用。

## 新设备证据：Compose IME footer 可达

实际打开本轮 `device/compose-ime-footer.jpeg`；键盘仍在，三个模板和生成按钮完整展示，文章版式提示与下一行说明没有压到按钮内。键盘 top 为1757px。AX 的 Scroll `bounds=origBounds=[0,325][1320,1757]`；生成文章 clickable Text `bounds=origBounds=[70,1323][1250,1505]`，高182px即52vp，完整在IME上方。三个模板的 clickable 是整卡 Column，其 bounds=origBounds 分别为 `[70,374][1250,583]`、`[70,639][1250,848]`、`[70,904][1250,1113]`，不是只包文字。

当前代码 `DeepReadComposePage.ets:133-177` 是 Scroll+layoutWeight(1)，模板使用整卡 `onClick`，生成52vp。RESIZE 与 alive/token 回调保护在34-62、64-119。未新增滚动兜底的必要。

上一轮 `compose-keyboard-template.jpeg/json` 和 `compose-keyboard-generate.jpeg/json` 也实际查看：两帧停在相同滚动位置，生成按钮在可视区外；只有第二卡选中状态变化，因此文件名“generate”不能证明生成按钮在IME上方。这个证据缺口被本轮新 footer 帧关闭，不能据旧图报“生成按钮被遮挡”的产品缺陷。

## 新设备证据：小说项目控制 IME footer 可达

实际打开本轮 `device/novel-control-preferences-ime.jpeg`，写作偏好草稿为完整三行英文文本，往后计划／写作偏好分节和保存保持垂直间距。AX Scroll `bounds=origBounds=[63,509][1257,1708]`；偏好 `TextArea bounds=origBounds=[63,1225][1257,1505]`；保存 `bounds=origBounds=[1158,1526][1257,1680]`，高154px即44vp，IME top1757px。因此保存未被键盘遮住，不能把窄宽度 finding 扩展成footer不可达问题。截图中上一个计划输入在Scroll上边缘裁切是正常滚动结果。

本轮 `device/novel-setup-current.jpeg/json` 确认当前项目是空白项目，润色偏好与保存动作正常分区。另实际打开 `device/novel-project-polish-ime.jpeg` 与 `device/novel-project-polish-ime-real.jpeg`，后者输入了两行真实草稿文本，键盘保持；AX TextArea `bounds=origBounds=[70,823][1250,1215]`，保存 `bounds=origBounds=[0,1257][561,1425]`，高168px即48vp，完整在IME1757px之前，项目润色偏好footer可达通过。该组是空白项目，不冒充快速开始种子／重新生成路径设备证据。

## 已重新查看的代表状态

| 场景 | 实际图片和 AX | 判断及边界 |
|---|---|---|
| Compose 标题输入 | p7 `compose-44-keyboard-final`、`compose-long-title-keyboard` | 普通标题44vp、长标题横向输入可读；正文/链接左右 gutter 一致；输入内部滚动不等同整页遮挡 |
| 小说长项目名／分支名 | p7 `novel-light-long-header` | 标题与副标题截断，左右返回／刷新保留完整位置；没有把长名扩张压到圆形按钮 |
| 项目控制明色模型与计划 | p7 `novel-control-light`、`novel-control-light-bottom` | 模型行完整整行热区； unavailable MiniMax 为禁用行；普通 Scroll 边缘裁切可通过滚动解决；保存宽度单列为 finding |
| 小说设置暗色 | p7 `novel-settings-bottom` | 分节／说明／开关行对齐，正文可换行；本轮不把所有系统 Toggle 的图形尺寸机械升级为缺陷 |
| 编辑资料暗色+IME | p7 `material-alias-keyboard` | 内容在 Scroll 可视区底部裁切，固定取消／保存留在IME前；不以局部裁切推断不可达 |
| 正文编辑明色+IME | p7 `reader-normal-editor-keyboard-real` | 标题、正文、字数与取消／保存保持分区，footer未被IME遮住；长标题单行编辑横向滚动不误报截断 |
| 深读重新生成明色+长文章 | p7 `regenerate-longtitle-light` | 系统确认文案与双动作完整，无文章大字与dialog内容重叠 |
| 代笔联合审稿暗色 | p7 `ghostwrite-same-card-reviewing-fixed` | 阶段、冻结计划、候选与裁决在同一长卡内按层次排列；展开后下方内容交由宿主Scroll |
| 展开裁决后向下滚动 | p7 `verdict-44-expanded-bottom` | 图实际显示滚至模型／计划／偏好；不是文件名所暗示的“仍看到裁决底”；不能仅由该文件名证明特定正文尾部 |

以上图片路径均在 `../2026-10-03-standalone-completion/device/p7/`；实际读取JSON的clickable、bounds、origBounds并结合当前代码，未沿用旧报告的结论作为证据。

## 当前动态卡和生命周期核对

`NovelGhostwriteCard.ets:21-27,44-49,117-150,193-215` 直接消费 `this.job`／`this.busy`／local expansion @State；冻结计划和候选 builder 没有将 job 副本存入不可观察局部变量。`NovelChapterVerdictCard.ets:21-24,54-104,128-154` 直接消费 this.review 与 this.expanded。`NovelPolishJobCard.ets:16-28,30-57,71-112,212-262` 同样直接读取 this.job，操作控件72×44vp，busy/retryReady具 enabled 判定。当前源码不支持重复报告上一轮的冻结 builder snapshot 缺陷；历史 reviewing→completed 帧只能算旧设备证据，本轮没有触发新的真实代笔或润色任务。

`NovelWorkspacePage.ets:352-434` 在show/hide保存恢复键盘模式，hide清理需要失效的编辑／审计回调，disappear清理轮询与订阅。`NovelProjectSetupPage.ets:29-49,98-148` 页面show启用RESIZE，主内容Scroll用layoutWeight(1)，种子长文本自然换行，方向／核心想法／偏好固定输入高但整体可滚。源码有正确结构，项目偏好已以本轮实际IME footer帧确认设备可达；快速种子与重新生成按钮仍待。

busy视觉和语义范围：Compose与项目设置使用enabled；GhostwriteCard primary/smallButton目前通过变色/opacity+handler guard阻止行为（240-254），未显式 enabled。只凭源码不足以把这项升级为本轮可见UI缺陷；需busy持续帧的AX、点击与业务状态确认。没有建议引入统一禁用抽象。

## 已交 root 的精准补证步骤

1. Compose输入标题、正文，保持IME，从Scroll边缘而非TextArea内上滑到底；截图与AX。已完成新footer采集，结论通过。
2. Novel共创项目控制，focus写作偏好保持IME，边缘上滑到保存／操作；截图AX，并在保存文字左侧空白轻触后确认是否出现保存回执。已采集新偏好IME帧，footer可达通过；窄热区已以新包44×44vp+旧边界外点击保存回执关闭。
3. 项目创建设置，focus项目润色偏好，保持IME滚到保存；有快速项目时同时查看原始种子与本轮方向／核心想法及重新生成按钮。项目润色偏好已完成新IME帧采集并通过；普通项目没有种子不算种子路径设备覆盖。
4. 若已有可持有busy的受控任务，记录按钮busy期间AX enabled和点击后状态；不为这项审查创建额外生产任务或假fixture。

尚未覆盖：API12零售机、横屏、系统大字号、所有导入文件名长度组合、所有主题×每个字段的组合、busy正在读文件/保存的全部瞬态、本轮动态任务阶段流转。这些是证据边界，不默认变成修复任务。

## INT-01 修复的独立复核（最终冻结后）

root要求本审查者独立检查integration作者修改的链；未修改其生产或测试文件。最终读取 `scheduler.ts`、`DeepReadGenerationActivity.ets`、`BackgroundGenerationKeepAlive.ets`、`EntryAbility.ets`：scheduler开始与finally结束活动都传入同一 `job.title`；bridge以topic+token持有独立 `{ tracker, title }`，progress与terminal读取同一title；KeepAlive topic Want携带完整title；Entry仅显示title走 `typeof string + trim` 而不受512限制，其他稳定ID仍沿原512验证；Article入口、render/export/runSection没有改动或标题截断。AppContainer仍直接接线 `onRunActivity: setDeepReadGenerationActivity`。

独立执行 navigation/activity/Want-route/KeepAlive四组测试28/28，另scheduler_start_failure+progress两组10/10，合计38/38通过，覆盖普通与600字符标题经过实际Activity→Want→Entry→Article guard后调用loadArticle，非字符串title拒绝、超长稳定ID拒绝、冷/热路由、旧tracker清理及原UIAbility lease owner保护。这是实际模块的VM与平台stub验证，不是设备通知点击验收。

额外独立生命周期探针先复现一次新引入的完整标题去重边界：真实bridge旧topic:1开始（80字符同前缀+旧后缀）→真实KeepAlive UIAbility切后台并发布→新topic:2开始（同前缀+新后缀）→旧token清理→flush。活动集始终非空，没有stop，中间两活动状态被发布合并；修前signature只计前60字符显示title，publishes仍1且Want残留旧后缀。对应scheduler已有5秒abort等待截止及late owner finally场景，不是同run修改冻结title。

root授权integration将完整 `activity.title`加入signature后，本审查者用相同真实bridge+KeepAlive+lease探针重新验证：`publishes=2`、显示title保持相同、Want完整title为新后缀、旧token迟到正文snapshot不产生新publish、新token terminal只stop1次、最终 `generationKeepAliveHeld=false`。probe没有新增生产入口；只将私有flush在VM中导出供确定性检查。title修复未改变tracker测量或owner清理语义。INT-01及这个修复过程中暴露的同前缀替换边界均已通过独立源码/集成验证；新包普通标题DATA_TRANSFER点击与终态移除已随后设备补证并独立核对，见下；600字符标题与同前缀替换仍是源码/函数链验证，不冒充设备复现。


## INT-01 本轮新包设备验收补证（独立核图与AX）

实际打开四张新图并读取同名AX，核对 `device-validation.md` 的卡片身份与结果：

- `device/deepread-live-notification.jpeg/json` 的顶部独立卡为 `Local Reader Source Verification · 深读中` 与“模型输出”，完整card `bounds=origBounds=[56,635][1264,866]`。AX clickable ListItem／Column的系统ID明确含 `app.amber.deepread.reader_bgmode_20020075`，子节点是 `LiveCardSystemView`。下方“更多通知”后的普通卡 `bounds=[56,1062][1264,1384]` 显示“正在深度阅读”及其他旧完成通知，确实是另一张卡。截图标题肉眼省略，AX保留完整标题；不把可视省略当Want标题被截断。
- root实际点的是顶部bgmode卡。随后 `device/deepread-live-notification-opened.jpeg/json` 的root明确为 `bundleName=app.amber.deepread.reader`、`pagePath=pages/DeepReadArticlePage`；AX heading与图片都是 `Local Reader Source Verification`，本篇版式“杂志 · 立场”、原正文仍显示；状态条“重新生成中，原文章已保留”，取消生成完整热区 `[1025,416][1278,570]`。没有“文章参数缺失”。这组能与顶部独立bgmode卡身份对应，不混用下方普通通知作为INT-01点击证据。
- `device/deepread-notification-final-result.jpeg/json` 同bundle、同Article页、同标题／版式／正文，状态变“完成”，导出 `[816,143][970,297]`、保存到工作区 `[702,416][1025,570]`、重新生成 `[1025,416][1278,570]`恢复显示且完整。root的本地规划+四段5次HTTP请求完成记录写于device-validation；本审查者此次独立验证的是截图与AX中的完成结果，没有冒称再次执行provider请求。
- `device/deepread-notification-terminal-cleanup.jpeg/json` 顶部独立bgmode／LiveCardSystemView已没有，普通卡现在位于 `[56,747][1264,1069]`，本篇第一条为“深度阅读完成 / Local Reader Source Verification”。未见本篇“正在深度阅读”或“模型输出”保活卡残留；其他旧DeepRead/Novel完成通知继续存在，不将它们误判为未清理的本轮活动。

INT-01已补齐新包模拟器上的真实系统DATA_TRANSFER卡点击→正确文章原稿+运行态→终态完成+保活卡移除证据；独立UI核对通过。该结论仍不扩张为API12零售机、600字符title设备回放、云provider、多活动聚合或无限后台运行的验收。

fixture/18771 reverse已由root在验收后停止/移除，原18765保留，见device-validation；本审查者只核读报告与设备文件，没有操作设备、网络转发或生产代码。
