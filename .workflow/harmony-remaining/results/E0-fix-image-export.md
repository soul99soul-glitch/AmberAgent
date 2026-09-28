# E0 · D7 图片导出与 Mermaid 尺寸修复

日期：2026-09-28。仅修改本任务所有权文件及窄领域分页 helper/test；保留主 agent 的导出入口、推理选项消费、Mermaid `onPageEnd` 改动。没有提交。

## 修复

- Chat 导出冻结最近 40 条消息、标题与推理选项；通过 `Scroll` 内内容 `onAreaChange` 取得实际高度，以物理像素决定最少页数并均分连续内容（商加余数，页高相差最多 1px）。每片 viewport 宽、高均不超过屏幕；普通短内容保持一张，长内容明确显示多张进度和保存说明。
- 每次页高更新、滚动后各等一帧，再使用 API 12 `SnapshotOptions { scale: 1, waitUntilRenderFinished: true }`。校验实际滚动偏移与目标偏移（只容许最终像素取整误差），避免 clamp 导致静默重复前页。尾片先缩短 viewport，再滚动至尾片起点，避免固定满屏 viewport 将尾片起点向前夹住。
- `ImageSaveSupport` 窄提取 PNG 打包、一次批量相册授权和临时文件清理。按分页顺序落缓存，逐片 `await PixelMap.release()`，仅保留路径；系统保存窗只调用一次。成功反馈实际保存张数，取消返回 false；失败或退出均进入临时文件清理。原单张保存和生成图保存复用同一相册链路。
- Mermaid 本地 host 在最新渲染完成后通过已有 `javaScriptProxy` 模式回报 SVG 的实际渲染高度与 source revision。普通图使用实际高度；大于屏幕的图使用可滚动安全视口，明确注明完整导出张数。导出入口移入 `MermaidBlock`，由其按高度逐页设置 Web 视口、滚动并截图，避免旧的固定 300vp 截底问题。
- source 变化立即清除 ready 和旧高度；过期 host 回报按 revision 丢弃。导出仅对当前 source 的成功渲染开放，捕获中 source 改变会停止并清理已经生成的临时页。生命周期只保留有限单次 frame/debounce timer，退出时清除并释放待等帧 Promise。
- 独立复审 F10 回喂：保留 host 测量的小数 CSSpx/vp 高度，仅在最终物理像素 helper 取整，避免先 ceil(vp) 在高密度屏幕放大尾片误差。2000.1vp×3 的尾片改为 289px，几何 clamp 仅 0.7px；新增多密度/分数高度测试。DOM 实际滚动取整和 Web vp 换算仍须设备验收。
- `MarkdownText` 的旧导出按钮和方法由 `fix_copy_mermaid_id` worker 移除；实例唯一图 ID 保留，不与该 worker 的所有权重叠。
- 设备视觉回喂 `/tmp/e0-mermaid.jpeg` 已查看：浅色主题的超屏图真实渲染且标为 4 张，但页脚继承外层 Markdown 代码块深色底、使用浅色主题 `INK3`，造成分页说明与导出按钮低对比。窄修为 Mermaid 自身整体 Column 使用 `SURFACE2` 背景和 10vp 圆角，让页脚回到 `SURFACE2/INK3` 设计配对；未修改外层代码块主题。修复后的亮暗色截图由主 agent 统一验证。
- 独立复审 F11 回喂：SDK `@ohos.file.photoAccessHelper.d.ts:3677-3688` 明确 `release(): Promise<void>`（since 10）应在不用 helper 后调用。现有 dialog→复制→成功反馈统一包进 try/finally，确认、取消和异常均 await release；释放失败沿现有资源清理惯例处理，不覆盖原保存错误。不新增抽象或重试。
- 真实设备后续阻断：已安装保留 host 浮点高度的构建，25 节点超屏图点击导出提示“图表滚动尚未就绪”，未到相册窗。按主 agent 指示临时记录 `[MermaidExport]` 页码、目标 nativepx/vp、实际 offset 原始返回与 Web 数字尺寸（scrollHeight/clientHeight/innerHeight/body/app 高度），不记录图表源码或消息文本；未放宽误差和新增重试，等待真实 hilog 回喂后精准修复并移除诊断。
- `/tmp/e0-mermaid-metrics.log` 实测定位：前 3 页 offset 精确为 0/816/1632vp；末页目标 2448vp，原始高度 2686.0224609375vp，834px 尾片为 238.285714vp，但 Web inner/clientHeight 量化成 239CSSpx，实际 offset 被夹到 2446.857177734375vp。不是任意时序失败。窄修：host 设置导出 offset 前，暂设 body `minHeight = top + window.innerHeight + 1` CSSpx，给末页 CSS/native 量化留足滚动范围；原始高度始终量 `#app+16`，分页不计入这段空白。结束、取消和失败 finally 调用 `__amberEndExport` 清除 minHeight 并滚回 0。没有放宽原 offset 校验、增加重试或额外分页；临时数字日志和 metrics 函数已经删除。
- 真实 Chat 相册回喂已查看 `/tmp/e0-chat-tail-photo.jpeg` 与 `/tmp/e0-chat-page9-photo.jpeg`：第 9 张包含 99/100/E0 IMAGE END，第 10 张只有气泡底圆角和 padding，没有正文。原因是固定满屏切片将略超整屏倍数的装饰尾部独立成图。最小修复仅调整共用 `imageExportPages`：先算 `ceil(totalPixels/screenPixels)`，再用整数商/余数均分每页，保持总张数、屏幕限制和完整像素覆盖，不用空白检测、裁文本或边界猜测。Mermaid 共用 helper 同样均分，host 临时 minHeight 与释放修复均保留。以上早期固定满页/短尾片数字仅是当时复现证据；当前设备 fixture 的 4 张 Mermaid 页高为 2351/2351/2350/2350px，仍覆盖 9402px。未操作或删除已存相册测试图。
- 中央实机均分 Mermaid 4 张已保存成功，独立 reviewer 确认节点 00–25 与连接完整；查看 `/tmp/e0-mermaid-first.jpeg` 发现右侧灰 Web 滚动条进入 PNG。仅 host 增加导出 class 的 `::-webkit-scrollbar` 隐藏样式；`__amberSetExportOffset` 开 class，`__amberEndExport` 移除。正常图内滚动保留，Native Web 参数和均分算法未改，也不调用 refresh。真实 host 执行测试增加 class 开始/结束生命周期断言，17/17 PASS；CSS 是否移除实际 Web 滚动条仍待主 agent 重建截图。
- CSS 后续真实证据：root 重新选择 13:04 新相册批次，`/tmp/e0-mermaid-css-new.jpeg` 仍有右侧黑色 Native 滚动条（已查看），CSS 无效得到确认。已改 Web 静态 `.verticalScrollBarAccess(false).horizontalScrollBarAccess(false)`；SDK `web.d.ts:10052–10104` 的接口 since 9 且只控制滚动条绘制，未用动态 @State/refresh。无效 CSS/class 开关及对应 class fixture/断言已移除，图内触摸滚动、minHeight 和分页保持。
- 暗色实际证据：`/tmp/e0-mermaid-footer-dark.jpeg`（已查看）页脚可读，但默认 Mermaid 的近黑连线落在深灰 Web 底，几乎不可见。仅 `MermaidBlock` 增加应用 `darkMode` StorageProp/Watch，主题变化沿既有 source watch 递增 revision、清 ready 并渲染；生产 host render 显式接收 dark/default，在实际 mermaid.render 前 initialize 对应主题，strict 安全级不变。应用手动暗色不依赖系统 prefers-color-scheme；没有全局主题改造。

## 官方依据与 API 边界

- 已加载 `.agents/skills/harmony-arkts/SKILL.md`。
- 已读主 agent 提供的 `/tmp/e0-snapshot-doc.md`、`/tmp/e0-snapshot-options.md`：组件截图取最近一帧；API 12 支持 scale/waitUntilRenderFinished；截图不建议超过屏幕尺寸。未使用 API 15 region 或 API 26 getSizeLimitation。
- 新使用的 Scroller `scrollTo`/`currentOffset` 与 `enableScrollInteraction` 已查本地官方 `API参考/ArkUI_方舟UI框架/ArkTS组件/滚动与滑动/Scroll/ts-container-scroll`，存于 `/tmp/e0-scroll-doc.md`；相关字段为 API 10，偏移单位 vp。
- 相册批量方法沿用现有 API，已核 SDK `@ohos.file.photoAccessHelper.d.ts` 的 `showAssetsCreationDialog` 为 API 12，入参与返回均为数组。

## 验证

- `harmony/chat`：`tsx --test src/test/image_export_pages.test.ts src/test/conversation_export.test.ts`，12/12 PASS。
- F10 补测：同一命令 13/13 PASS；其中新增测试实际执行分页 helper，检验尾片 viewport 缩短后的真实几何最大偏移与不足一个物理像素的 clamp。
- CSS 量化补测：加入 `src/test/mermaid_export_host.test.ts` 后同组 15/15 PASS、Chat tsc PASS。测试实际执行保存文件中的 host JavaScript，以已测量 DOM 几何作窄 fixture，先复现 2446.857177734375vp 夹取，再验证修复后各页实际偏移连续、末页覆盖全部原始物理像素，及结束时恢复文档。该测试不渲染图像，不代替原生 Web/PNG 验收；设备重建后仍须核对全部 25 节点和分片接续。
- 均分补测：同组 17/17 PASS、Chat tsc 与 diff 检查 PASS。新增刚过整屏倍数（9 屏加 92px）的 10 张均分案例和跨多个尺寸/屏幕上限的约束案例；既有逐像素覆盖测试保留，真实 host 执行测试改用当前均分页，继续验证实际偏移连续和完整内容覆盖。均分后的相册图片由主 agent 再验。
- 主题/静态滚动条补测：同组 18/18 PASS、Chat tsc 与 diff 检查 PASS。真实 host render 函数以库/DOM stub 核对 dark→default 参数、strict 与 readiness revision；不模拟实际图像或声明设备已验。首次 Mermaid 单文件 lint 在工具日志目录因 sandbox EPERM，原命令授权重试成功；加入最终静态滚动条后再跑同一单文件 lint，同为 0 error/2 性能 warn（导出循环中的既有状态更新），退出 0。实际暗色连接对比与 Native bar 隐藏仍由中央新版实拍验证。
- 新 helper 的 5 个测试实际执行算法，覆盖：短内容单张、92783 物理像素逐行恰好一次且无缝接续、短尾片、整数倍无空页、浮点测量最后像素和非法尺寸。没有以提取源码断言代替算法测试。
- `harmony/chat`：`tsc --noEmit` PASS。
- Mermaid host 的真实内联 JavaScript 通过 `node --check` 语法检查；这不是浏览器或设备运行时验收。
- `git diff --check` PASS。
- 按主 agent 统一安排，本 worker 未执行 codelinter、构建、签名、安装、截图或相册实存验收。

## 设备验收所需案例

1. 普通聊天导出仍生成一张完整图；推理开关对应正文存在/不存在。
2. 单条超长正文及多条长正文：多张图片的分页交界文字连续、尾片完整、输出次序正确、系统保存窗只出现一次；取消后不显示成功且无缓存页残留。
3. 高度超过 300vp 的纵向 Mermaid：首次打开历史消息可见渲染，普通图完整显示与保存。
4. 超过一屏的 Mermaid：图内可滚至尾部，文案注明分为多张；保存后所有节点和首尾均存在。捕获时改 source 或离开页面不会保存过期图或遗留临时页。

历史记忆仅用于确认 API 12/V1 和设备证据不能由测试替代的既有约定；全部修改与当前验证以本仓库和当前官方文档为准。
