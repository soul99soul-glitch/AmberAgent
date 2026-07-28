# Claude Handoff — AmberAgent iOS Chat 性能战役(夜班交接)

Date: 2026-07-02 深夜
Repo: `/Users/mi/Downloads/AI/AmberAgent-iOS`
Branch: `feat/ios-provider-parity-claude`
HEAD: `32da3dbd6 feat(ios): scroll arbiter pure core (P3.1)`(已推送)

## Ground Rules(用户反复强调 + 本战役血泪立的规,全部必须遵守)

- 全程中文交流;先读真实代码/数据再判断,严禁臆想。
- 不要 `git reset`/`stash`/`checkout` 改变共享工作区状态(并行 agent 曾因 stash 险些互相覆盖)。
- 未经用户明确要求不 commit/push(本次 push 是用户明确授权的)。
- **分层解耦铁律**:流式性能/动画/手势按 L0-L5 分层(数据/投影/高度/滚动/渲染/手势),每层单一写者,改动前先回答"这动的是哪一层、跨层了吗"。
- **滚动/布局几何类改动,没有回放夹具离线复现,不许上真机;每批装机 ≤1 个几何变量**(违反过一次,真机剧烈闪烁+行重叠,全量回退)。
- **每个阶段收尾必须做多维独立 subagent review**(debug 缺陷 / 逻辑闭环 / 调用链路),reviewer 互不知晓对方结论,汇总裁决后才算关账。本流程已抓出 15+ 真实问题,不许省。
- vendored 库补丁"无效即撤",不留观赏性代码。
- 执行用 sonnet subagent(任务书写明禁转包、禁 stash),勘探用 haiku/Explore,主模型只做规划/review/裁决。
- 表格/排版类改动必须目检渲染 PNG(几何审计抓不到墨迹级问题)。

## 当前状态(全部已推送)

```
32da3dbd6 P3.1 滚动仲裁者纯核心(30 测试)
b89583acb 回归套件(溢出/digest/防重放/指纹)
cb16c1a03 P2.5 后台完成内容定向上屏
71cee1601 M0 流式录制钩子 + 回放夹具
02b744f10 chat 列表正确性/高度稳定/digest 契约(主修复批)
786c9c898 AmberMarkdownView 表格渲染(AmberTableLayout)
199f48722 vendor SwiftStreamingMarkdown + 段落复用溢出双补丁
```

已关账阶段:P1(夹具)、P2(digest 契约)、P2.5(后台上屏)、P4.1(表格)、P3.1(仲裁核心)。
测试资产:9 个套件 ~85 用例,全绿。关键套件:
- `ChatStreamReplayTests`:回放夹具,行重叠/offset 回跳/贴底硬断言(几何改动的守门员)
- `ChatMessageWidthOverflowTests`:真机病态"雷祖表格"数据,4 渲染路径裁剪感知审计
- `ChatScrollArbiterCoreTests`:仲裁者状态机 30 用例(virtual 链/单调钳制数值锁定)
- `ChatRowDigestTests`/`ChatRowContentHashCacheTests`/`ChatCollectionUpdateGateTests`

工作区未跟踪:4 个 handoff .md(含本文件)——按惯例不提交。

## 下一步:P3.2 滚动仲裁者接线(最大的活)

设计已定稿(详见记忆 ios-chat-p3-p5-design 或下述要点):
- 外壳 `ChatScrollArbiter` 持有 display link,把现有全部程序性滚动收编为意图提交:
  execute 的 `.initialAnchor/.resetForConversationSwitch`→anchorToBottom、
  `.followBottom`(delta)→followTail、其余 followBottom/按钮/键盘→snapToBottom、
  `scrollViewWillBeginDragging`→userTakeover、拖拽/减速结束→userReleased。
- `scrollToBottom`/`anchorToBottomConverged` 移入外壳成为 performSnap/performAnchor 的实现。
- `keepContentOffsetAtBottomOnBatchUpdates` 恒 true 不切换;following 每帧写 virtual(帧内最后写者胜)。

**两个强制契约(P3.1 复审裁决,不可省略)**:
1. performAnchor/performSnap 跨帧异步且 Core 无取消信号——外壳必须带 generation token,每步写入前核对 Core 状态仍是发起态,否则整体放弃;
2. `branchChanged` 现映射 `.none`,必须显式给仲裁者发 `.conversationReset`(截断/删除/切分支),否则单调钳制追逐幻影高度。

验收:ChatStreamReplayTests 全场景 + 新增仲裁者集成场景(流式中拖拽抢占/收尾 snap/进会话锚定)全绿,才允许装机;装一个 flag 可整体回退到旧路径,稳定一个周期后删旧路径。

## 之后:P5 消息拆块(设计定稿,见记忆)

append-only 块切分、blockID=messageId#ordinal、完成后不回并、open 块吸收 markdown 歧义、块间距用 per-item interItemSpacing(8/14)。前置:P3.2 完成。每 delta O(1) 是"越长越卡"的根治。

## 挂起的真机事项(用户在场时)

1. 装最新版(HEAD 构建)验证:新 session 长内容不闪烁不重叠、用户气泡完整多行、表格成型、生成结束思考胶囊即消失、进长 session 精准贴底、上滑不被拽回。
2. 录一段真实流式喂夹具:DEBUG 包 + 启动参数/UserDefaults 打开 `chat.stream.recording.enabled`,生成后从 `Library/Caches/stream-recordings/*.jsonl` 用 devicectl 拉出,放进 ChatStreamReplayTests 识别的目录(见该文件顶部常量,或改常量指向新路径)。
3. 已知未修:淡入观感依赖库内 diff 假设(fade 只对纯尾部追加生效)、AmberMarkdown 表格淡入无(静态渲染器)、"逐行跳"待 P3.2 的 followTail 解决。

## 构建/测试命令

```bash
# 工程生成(新增 Swift 文件后必须;pbxproj 被 gitignore)
cd iosApp && /opt/homebrew/bin/xcodegen generate

# 全量 chat 测试
cd /Users/mi/Downloads/AI/AmberAgent-iOS
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild test -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:iosAppTests/ChatStreamReplayTests \
  -only-testing:iosAppTests/ChatMessageWidthOverflowTests \
  -only-testing:iosAppTests/ChatScrollArbiterCoreTests \
  -only-testing:iosAppTests/ChatRowDigestTests \
  -only-testing:iosAppTests/ChatRowContentHashCacheTests \
  -only-testing:iosAppTests/ChatCollectionUpdateGateTests \
  -only-testing:iosAppTests/ChatViewportPolicyTests \
  -only-testing:iosAppTests/ChatMessageProjectionTests \
  -only-testing:iosAppTests/IOSConversationStoreTests \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentChatTestsDerivedData

# 真机构建+安装(手机必须亮屏解锁;Xcode 账号会话过期会报 No Accounts,需 GUI 重登)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=00008150-000A594E0AF8401C' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation build
xcrun devicectl device install app --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /Users/mi/Library/Developer/Xcode/DerivedData/AmberAgent-gfeldsdglrkbjmebgswcebkbpeiy/Build/Products/Debug-iphoneos/iosApp.app
```

## 下一个 AI 的执行 Prompt

```text
你接手 AmberAgent iOS/KMP 项目(/Users/mi/Downloads/AI/AmberAgent-iOS,分支
feat/ios-provider-parity-claude,HEAD 32da3dbd6,已推送)。全程中文。

先完整阅读仓库根目录 CLAUDE_HANDOFF_CHAT_2026-07-02-NIGHT.md(本文件,含全部纪律、
状态、命令),以及 iosApp/iosApp/ChatScrollArbiterCore.swift 和
iosApp/iosApp/ChatCollectionMessageList.swift 的真实代码。

铁律(违反过、真机炸过,不是虚文):
- 滚动/布局几何改动必须先在 ChatStreamReplayTests 夹具上全绿才许真机;每批 ≤1 个几何变量。
- 执行用 sonnet subagent(任务书写明禁转包、禁 git stash/reset);每阶段收尾跑
  debug/逻辑闭环/调用链路三个独立 subagent review,汇总裁决后才关账。
- 排版类改动必须目检测试输出的渲染 PNG。
- 不 commit/push,除非用户明确要求。

你的任务:P3.2 —— 把 ChatScrollArbiterCore 接线为唯一程序性滚动写者。
- 新建外壳 ChatScrollArbiter(display link + 几何采样 + 意图翻译),收编
  ChatCollectionMessageList 里的 scrollToBottom / anchorToBottomConverged /
  execute 的 followBottom / 键盘跟随 / scrollViewWillBeginDragging。
- 两个强制契约不可省略:① performAnchor/performSnap 用 generation token 防
  跨帧写入越权;② branchChanged/截断/删除必须向仲裁者发 conversationReset
  (现有 viewport 契约里它是 .none,不会自动到达)。
- keepContentOffsetAtBottomOnBatchUpdates 恒 true;following 每帧写 virtual。
- 带一个 flag 整体切换新旧路径。
- 验收:全部 9 个测试套件 + 你为仲裁者新增的回放集成场景(流式中拖拽抢占、
  收尾 snap、进会话锚定)全绿;真机验证等用户在场,不要自行安装。

完成 P3.2 后如用户继续,进 P5 消息拆块(设计在 handoff 与记忆里)。
```
