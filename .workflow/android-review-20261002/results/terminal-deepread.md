# Terminal / Tools / DeepRead supplemental review

只读检查当前工作树；未改生产代码、未运行 Gradle。范围：feature/terminal、feature/tools/{impl,access}、feature/board/impl/hotlist/deepread、app HotList/DeepRead repository/manager及其直接调用路径。下面前三条按主 agent 指示进入下一阶段；另四条也有具体代码证据，交主 agent 二次判定/排序。没有把设备、Android provider 或 JVM 测试当成已跑过。

## T1 — P2 — 本地终端输出在 UTF-8 跨 read 边界时乱码（confirmed）

位置：`feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt:448-455`、`:719-725`。

`readSessionOutput` 和 `readProcessOutput` 都把每个原始字节块单独 `String(buffer, 0, count)`。管道 read 没有字符边界保证，所以中文/emoji 在两个 read 中间被拆开时，各块分别产生 replacement characters，后续字节再到达也无法恢复。实际路径分别是 persistent Alpine session、builtin_alpine/android_shell job（`runProcessJob`启动reader）；SSH已经使用连续的`InputStreamReader(...UTF_8)`，因此不是产品有意采用单块解码。

复核：执行了 Python 对同样的逐块 UTF-8 解码语义的确定性演示，`中文🙂`在byte 1切分得到`���文🙂`，byte 7切分得到`中文����`。这是算法演示，不是production JVM/device测试。

最小修复：两个reader都使用一个覆盖完整stream生命周期的`InputStreamReader(..., UTF_8)`和CharArray，已有SSH reader可以作为本仓参考。不要加入探测编码/重试/多级fallback。

回归：受控InputStream每次返回1字节，完整读取包含中文及emoji的字符串，断言输出精确相等；保留ASCII、EOF检查。

## T2 — P2 — Custom template把正文里的字面占位符递归替换为HTML（confirmed）

位置：`feature/board/impl/src/main/kotlin/app/amber/feature/board/hotlist/deepread/template/DeepReadTemplateRenderer.kt:193-210`。

顺序fold先插入escaped title/summary，然后执行analysis_html等replace；escapeHtml不处理花括号。因此新闻标题`Literal {{analysis_html}}`会变成`<h1>Literal <section>Analysis</section></h1>`，而不是保留字面标题。summary、hero caption及之前生成的HTML文本也受同类影响。文章production路径是`DeepReadScreen.kt:651`调用renderCustom；不是只有模板预览才发生。

复核：按相同map顺序运行确定性替换演示，标题被插入section且分析重复。模板validator明确约束block placeholder放置位置，但本问题发生在验证后的数据插入，validator不能防止。普通正文出现模板语法就触发，无需非法template。这里主张内容/版式损坏，不把它夸大为任意JS执行。

最小修复：只在原template字符串上做一次Regex.replace(match -> lookup)，replacement内部不再扫描。使用lambda避免把数据里的$或反斜杠当替换指令。

回归：合法最小template，title含`{{analysis_html}}`、summary含`{{extended_reading_html}}`，断言字面文本保留、真实slot仍展开一次。

## T3 — P2 — Force regenerate删除收藏和来源元数据（confirmed）

位置：`app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadAgentRunManager.kt:128`；`HotListRepository.kt:195-214`、`:219-224`。

用户历史页收藏通过`setDeepReadPinned`持久化；DeepReadScreen的重试/重新生成会调用`runAll(force=true)`，scheduler->worker->manager.run进入第128行，先DELETE整行。后续saveDeepRead试图从existing保留pinned/sourceUrl，但行已经不存在，所以新行pinned=false；未重新传seedUrl时来源也丢失。既有saveDeepRead注释明确要求regeneration保留收藏，因而不是设计上的取消收藏。模型配置无效等createRunContext早返回时甚至原收藏文章已删除，却没有新行。

最小修复：force重置生成内容，不删除用户元数据。让repository提供窄的reset/restart写入操作，保留现有pinned/sourceUrl；确保force生成不借title fallback重新拾取被清空的旧内容。预览runPreview的临时记录清理和历史页显式删除仍独立保留。

回归：已有pinned=true/sourceUrl的complete文章；force启动后及完成后这些字段保持；resolveModel失败时不丢收藏。需要manager production路径测试，不只再次测试saveDeepRead的既有保留逻辑。

## T4 — P2 — SaveDeepRead读写间隙覆盖用户刚设置的收藏（code-confirmed interleaving）

位置：`HotListRepository.kt:197-216`，DAO的upsertDeepRead使用REPLACE，setDeepReadPinned使用独立UPDATE。

发生次序：writer读取existing.pinned=false -> 用户setDeepReadPinned(true)完成 -> writer把预先读取的false整行REPLACE，收藏被撤销。反向也会恢复刚取消的收藏。读取existing和REPLACE未在同一Room事务中；restore gate只保证restore epoch，不是这两次database操作的事务（读发生在gate之前）。阶段writer每次更新都会save，所以此窗口并非一次性的冷启动极端场景。

最小修复：在DAO事务中读取元数据+保存，或单条SQL更新生成字段保留pinned，并在缺行时插入；避免引入新层/全局mutex。serialization可在事务前完成，缩小锁持有时间。与T3可同阶段处理，但T3是不并发也发生的不同触发。

回归：fake DAO设置暂停点在读取后，插入用户pin/unpin更新，然后继续save；断言最后仍是用户最新选择。真正事务行为可用Room integration测试验证。

## T5 — P2 — 同名及中文文件分享共用cache URI，被后续分享覆盖（code-confirmed）

位置：`feature/tools/impl/src/main/kotlin/app/amber/feature/tools/ShareAccessTools.kt:59-64`、`:77-85`。

缓存路径只取basename并把非ASCII替换为下划线，固定写进`cacheDir/agent-share/<safeName>`。`left/report.pdf`与`right/report.pdf`确定性同路径；`中文.pdf`与`报道.pdf`均为`__.pdf`。用户先分享A、再分享B时，先前被授权的Content URI仍指向同一路径，此后目标应用读取A得到B。并发调用也能让本次chooser对应的字节在返回前已被覆盖。这是错误文件内容/外发数据风险，不是合理的“最新同名缓存”语义。

复核：执行basename+sanitize算法演示，两组碰撞均确认；没有实际启动系统share sheet。

最小修复：每次分享建立唯一子目录（UUID）并保留友好的basename，确保已发出的URI对应不可变快照。不要求加入广泛缓存生命周期机制。

回归：连续缓存两个不同目录的同名文件以及两个同长度中文名称，断言路径不同、前一次内容保持；FileProvider可用existing cache root。

## T6 — P2 — 日历循环事件仅改标题也被错误时间校验拒绝（API + code-confirmed）

位置：`feature/tools/access/src/main/kotlin/app/amber/feature/tools/CalendarAccessTools.kt:241-251`、`:276-291`。

queryCalendarEvent只读Events.DTSTART/DTEND，将null DTEND读成0。Android provider的循环事件使用DURATION并要求DTEND为空；calendar_list通过Instances能列到这些合法事件，而calendar_update即使只改title/description/location也无条件require(end > start)，因此抛错，合法循环事件无法更新非时间字段。

官方primary API证据：[CalendarContract.Events](https://developer.android.com/reference/kotlin/android/provider/CalendarContract.Events)说明循环事件使用duration，非循环事件使用dtend；[calendar provider overview](https://developer.android.com/identity/providers/calendar-provider)说明Instances是各次occurrence的开始结束时间。

最小修复：非时间字段更新不验证未改变的时间区间。若扩展到循环事件时间更新，需要正确处理DURATION/DTEND而不能随手插入DTEND；本次最窄修复优先覆盖明确坏掉的non-time edits，时间更新的产品支持边界须明确。不要把合法provider row称为损坏数据。

回归：snapshot begin>0/end=0，title-only及location-only更新通过且ContentValues无DTSTART/DTEND；普通事件的实际时间调整仍拒绝倒置区间。

## T7 — P3 — 同时间戳位置选择了精度更差的坐标（code-confirmed）

位置：`feature/tools/access/src/main/kotlin/app/amber/feature/tools/LocationAccessTools.kt:74`。

`maxWithOrNull(compareBy{time}.thenByDescending{-accuracy})`同时做负号和descending两次反向，等time下max选择accuracy更大的location（例如50m胜过5m）。同timestamp的多个provider缓存返回时，location_current得到更差坐标。不是freshness与accuracy权衡：timestamp完全相等。

最小修复：`thenByDescending { it.accuracy }`或`thenBy { -it.accuracy }`只做一次反向。

回归：两个location同time、不同accuracy，断言5m胜过50m；更新time仍胜过旧time。

## 复核排除项

- SSH输出reader已使用InputStreamReader维护UTF-8状态，未报同一乱码问题。
- DeepRead在创建context时的异常曾看似会永久卡在PLANNING；production Worker失败路径会保存failureOutput，UI亦按WorkManager lifecycle gated generating，不据此报“永久加载”。
- terminal install generic package的`command -v ... || true`不能据此认定安装失败假成功：包名不等同binary名，真正package manager在set -e下退出；未把此项当成bug。
- Contacts按phone/email两表搜索、Media all按不同type配额和Location“无current时回last known”可视为既有定义/权衡，未按主观改良建议报问题。
