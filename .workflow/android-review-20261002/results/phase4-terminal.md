# Phase 4 terminal / tools / DeepRead red-test preparation

只增加回归测试，尚未修改生产代码、尚未运行 Gradle。全部测试调用现有production API或通过反射调用现有private reader/cache函数，没有复制被测试算法。

- `app.amber.review.TerminalUtf8OutputTest`: real process/session readers，受控stream每次read最多1字节，断言中文/emoji/ASCII无损。
- `app.amber.feature.board.hotlist.DeepReadTemplateValidatorTest.customRendererTreatsPlaceholderSyntaxInArticleTextLiterally`: 标题和摘要中的字面占位符保留，template真正slot继续展开。
- `app.amber.review.DeepReadMetadataTest`: force在模型不可用时仍保留pin/source；真实Room配合production write gate，精确复现writer在等待写入期间用户pin/unpin先commit。
- `app.amber.review.ShareFileSnapshotTest`: 真实WorkspaceManager/Saf capped read，三个场景：不同目录同basename、中文名称sanitize碰撞、同path重复分享；旧snapshot路径及内容不可变。
- `feature/tools/access/.../CalendarAccessToolsTest.recurringEventWithoutDtendAllowsNonTimeUpdates`: 循环事件DTO DTEND=0，只改title/location应通过且不写时间字段。
- `feature/tools/access/.../LocationAccessToolsTest`: timestamp相等选择5m而不是50m，同时保留更新timestamp优先行为。

现有private终端类型及DeepRead manager依赖众多，测试通过一个局部ReflectionFixture隔离未触及的依赖；terminal只调用实际reader，manager只运行实际force->resolveModel失败路径；没有新增production测试接口。

建议root定点红测：app test --tests app.amber.review.* --tests app.amber.feature.board.hotlist.DeepReadTemplateValidatorTest；tools:access test --tests app.amber.feature.tools.CalendarAccessToolsTest --tests app.amber.feature.tools.LocationAccessToolsTest。若编译/fixture失败，先修测试直到暴露业务断言失败，再授权production修复。

Root验证更新：tools:access 11个tests，其中T6 recurring non-time、T7 accuracy tie两项按预期业务断言红；app编译发现getApplication错误泛型、provider mixed map类型推断，均已按root日志修正，仅测试fixture修正，production仍未修改。

## Production implementation

Root确认所有T1–T7现有API回归已actual red后授权修复。已实现：

- terminal两个stream各使用一个UTF_8 InputStreamReader和CharArray，跨read维护decoder状态。
- template仅在原template上进行一次Regex lambda替换；replacement字符串不再重扫。
- DeepRead DAO新增generation字段UPDATE，SQL不修改pinned、source_url使用COALESCE保留；事务包住UPDATE与缺行时INSERT，repository先serialization再在原restore gate内调用事务。
- force不再DELETE：进入createRunContext模型/TOOL能力校验后写入空content并保留metadata；direct空记录阻止title fallback拾回旧正文。invalid model不触碰原记录。新增真实manager admission通过但无sources的回归，确认不复用另topic同title缓存。
- 每次分享创建独立UUID子目录，保留安全文件名与既有FileProvider root。
- calendar非时间修改直接返回已构建字段；DTEND为空的recurring时间修改明确拒绝，防止插入非法DTEND。追加该边界回归。
- location等timestamp时只反向accuracy一次。

未运行Gradle；root统一串行验证。git diff --check定点通过。
