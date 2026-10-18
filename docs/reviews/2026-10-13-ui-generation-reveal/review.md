# 鸿蒙生成结果揭示：实现与审查记录

日期：2026-10-13

验收范围：网页生成卡片、静态可视化预览、聊天内嵌和独立 MiniApp、原生智能 UI 的一次性揭示；独立 subagent 审查调用链和 UI 细节。用户明确本轮不启动鸿蒙模拟器，因此原生屏幕视觉验收保留。

## 已实现

- 内置 `amber-ui-design` Skill，按鸿蒙现有协议生成静态卡片、原生智能 UI 和显式 MiniApp。`full_html` 保持现有静态预览，不声称具备 iOS 的交互网页运行容器。
- `WidgetMessageContent` 使用实际消息流式状态保留本次生成资格，向网页/原生卡片传递稳定来源 key；历史初次挂载不获得资格。
- `UIGenerationMotion` 统一 Web 播放所有权。MiniApp 两个运行入口及无运行权限时的静态预览共用 `miniapp:id:version`，普通产物使用 `widget:` 来源 key。准备阶段不消费，运行时确认开始或主动跳过才消费；状态在进程内去重，独立 MiniApp 还利用已有 runCount 区分首次打开。
- 本地 JS/CSS 运行代码由可信宿主注入。静态 iframe 仍只有 `allow-same-origin`，没有 `allow-scripts`；父窗口运行时钟，目标文档负责 DOM 测量和绘制。
- 图片预取刷新、字体/图片就绪、渲染确认与尺寸稳定后再播放。触摸、滚动、尺寸变化、后台和错误路径取消并恢复成品；减少动态效果直接显示内容。
- 原生智能 UI 使用实际测量的非容器节点框绘制 Canvas 覆盖层，约 680ms。覆盖层绝对定位、不占布局、不接收触摸；继续使用现有原生控件和状态逻辑。

## Finding ledger

| 状态 | 问题 | 修复及证据 |
| --- | --- | --- |
| fixed | Web 工厂/CSS 的测试装配与生产注入位置不一致，实际 skipped | 适配器读取生产词法变量；浏览器测试直接从 `UIGenerationMotion.ets` 提取注入模板，真实 `svg-host.html` 播放成功 |
| fixed | iframe 自己的计时器被禁脚本策略阻止，opacity 已变但没有线框 | DOM 使用目标 realm，计时器/帧调度留在可信父窗口；沙箱浏览器测试中覆盖层存在，结束后内容恢复 |
| fixed | 显式目标被外层容器抢走 | 分别查找 `data-amber-reveal`、main、app、root；浏览器用超高外层和有标记内层复现并验证 |
| fixed | 准备阶段过早消费、旧页面回调影响新页面、双入口重播 | 共享宿主所有权和 epoch；真实平台类测试覆盖 pending 取消、迟到回调、重复入口和有限轮询 |
| fixed | MiniApp 后台跳过预取后无法恢复，刷新前错误播放旧文档 | 前台恢复继续预取，刷新前清 readiness 并取消动效，新 onPageEnd 后再播放；真实方法测试通过 |
| fixed | 原生 animateTo 闭包同步结束，普通 State 不能驱动 Canvas 每帧 | 改为可清理的有界帧时钟，按实际时间重绘；时钟与状态测试通过 |
| fixed | 原生 root 框提前擦除整卡、root-only 产生空遮罩 | 仅非 column 框用于擦除；过滤后无框直接消费并显示成品；行为测试通过 |
| fixed | Canvas 使用不明确的八位色顺序 | 使用 rgba；测试确认 `#B8623A` 的通道为 184、98、58 |
| fixed | 原生 ready/streaming/eligible 失效后旧遮罩仍活动 | 状态 watcher 和文档变化取消动效；真实方法测试通过 |
| fixed | Runner 平台初始化在离页后回写 | alive + revision 守卫；离页后 resolve 测试通过 |
| fixed | Web 错误分支仍保留可播放状态 | 三个 Web 入口均取消动效并清除 readiness；独立 reviewer 逐项复核 |
| fixed | 禁用运行时 MiniApp 静态预览漏传资格 | 与运行入口共用 MiniApp key 和资格；调用链及测试复核 |

审查由独立 `review_chains`、`review_ui` subagent 完成，实施者未作为唯一审查人。复核只针对确认问题和修复证据，没有扩大为全仓审计。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| 11 个改动 ArkTS 文件 lint | 0 error，1 个既有组件性能建议 warning |
| 新动效及相关入口 Node 测试 | 26/26 通过 |
| 真实 Chromium/生产注入/真实沙箱宿主 | 8 个场景通过 |
| chat 域完整回归 | 2825/2826 通过；1 项因缺少既有 `harmony/native/python/build/host/amber_python_host.node` 环境产物失败 |
| Skill quick_validate | 通过 |
| assembleApp、HAP/APP 签名 | 通过，使用 DevEco 自带 JDK，未更改系统 Java 配置 |
| 已签名 HAP 资源核验 | JS、CSS、适配器、许可证和 Skill 与当前源文件逐字节一致 |
| git diff --check | 通过 |
| 鸿蒙模拟器/真机 | 用户明确本轮不启动，未安装、未截图 |

可复现命令：

```sh
NODE_PATH="$PWD/harmony/chat/node_modules" node --test \
  harmony/entry/src/test/ui_generation_motion.test.cjs \
  harmony/entry/src/test/miniapp_reveal_entry.test.cjs \
  harmony/entry/src/test/inline_miniapp_runtime.test.cjs \
  harmony/entry/src/test/native_smart_ui_card.test.cjs
python3 harmony/entry/src/test/ui_generation_browser.test.py
JAVA_HOME=/Applications/DevEco-Studio.app/Contents/jbr/Contents/Home \
PATH="/Applications/DevEco-Studio.app/Contents/jbr/Contents/Home/bin:$PATH" \
node scripts/harmony-inventory/build-harmony-app.mjs
```

## Web 视觉证据

这里是生产 Web 宿主的测试页，不是鸿蒙整屏截图。iframe 下方白色区域属于测试页背景。

- [320 浅色线框](browser/320-light-wireframe.png) / [完成](browser/320-light-complete.png)
- [320 深色线框](browser/320-dark-wireframe.png) / [完成](browser/320-dark-complete.png)
- [390 浅色线框](browser/390-light-wireframe.png) / [完成](browser/390-light-complete.png)
- [390 深色线框](browser/390-dark-wireframe.png) / [完成](browser/390-dark-complete.png)

独立截图复核：线框与成品边界一致，320 宽时 x=16..303，390 宽时 x=16..373；y=16..207。文字与按钮左内距约 16–17px，无新增横向溢出或偏移。浏览器断言覆盖层/目标坐标差不超过 1px，播放前后报告高度均为 208；应用深色设置在系统浅色下正确生效。

## Closure

Verdict: PARTIAL（实现和源码/浏览器审查完成；设备视觉验收待后续）

可关闭：已确认的调用链问题、一次性状态问题、Web 沙箱运行与截图问题、原生时钟/根容器/颜色问题。

仍未验证：ArkWeb 在目标鸿蒙设备上的帧率与兼容性，原生 Stack padding 与 Canvas 实际坐标、字体缩放和真实手势表现，以及真实模型生成质量。原生动效为节点框轮廓揭示，没有实现逐字行/图标路径重建。长 Web 文档及超过紧凑预览高度的结果保持直接显示，未强制缩小内容以播放动画。

未改动预先存在的 build-profile 签名配置及其他用户文件；未提交或推送。
