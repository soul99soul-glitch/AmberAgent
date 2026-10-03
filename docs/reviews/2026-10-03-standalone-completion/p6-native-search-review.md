# P6 独立 native Google / 搜索设置审查

生产源码只读，独占本报告。范围为冻结的DeepReadGoogleSearch、DeepReadGoogleSearchHost、DeepReadSearchSettingsPage、SettingDeepReadPage SourceCard路由及Article宿主挂载点。**该冻结范围 source gate PASS，两项精确owner缺陷已修并独立复验；DI实际3测试通过，最终设备门禁见p6-ui-device-review.md的PASS及Google网络超时边界。** 下文保留缺陷原触发及最终关闭证据。

## 独立实际验证

执行`node --test harmony/entry/src/test/deepread_google_host.test.cjs`，现有actual源码loader及DOM脚本 **5/5通过**。覆盖topic独立controller、旧解绑不删除新owner、导航阶段取消停止当前controller、Google导航gate与HTTP返回结果校验、真实h3脚本去重及Google redirect解码。没有新增实现镜像测试。

宿主为每Article独立new WebMountSession，绑定当前topic；Web实际挂载后onControllerAttached→markControllerAttached，pageEnd→markPageEnd，销毁unbind/stop/detach，复用已有API12 WebSession先例。隐藏Web固定360×720并移到视区外，不占正文的Column布局；真实Google网络/反机器人可用性需设备分项证明，不能由DOM fixture推断。

## P6-NATIVE01：提取期间解绑仍交付旧owner结果

**P2，已关闭 source 缺陷。** 修复前`platform_impl/DeepReadGoogleSearch.ets`在open后校验`hosts.get(topicId)===host`，但`await host.session.evalJson`之后只检查signal.aborted。文章销毁解绑/同topic替换若发生在异步runJavaScript期间，signal可以仍未abort；实际WebMountSession.evalJson仅await controller.runJavaScript，markControllerDetached只终止mount/page等待者，不撤其已在飞的JS结果。Article.aboutToDisappear清observer而不主动abort持久任务，因此不是依赖不存在的自动signal取消。

独立复用现有nativePort actual生产源码loader，以延迟evalJson进入后unbind再返回JSON，得到`DETACHED_EVAL_DELIVERED [{title: Old detached result, url: https://example.com/old, source: Google}]`，确认旧结果被交付，不是推测平台错误。

最小修复：evalJson await返回后，用与open返回后相同的signal与host identity检查拒绝旧owner；在现有loader补解绑/替换发生于eval期间的实际方法回归。不需要新token类、补偿重试或整体WebSession重构。

当前源码第55/57行分别在open/eval await后执行相同host identity与signal检查；独立重跑新增actual delayed-eval-unbind回归确实reject，旧结果不交付。原有owner替换、取消及提取链仍通过；没有增加重试或新store。source关闭。

## P6-NATIVE02：私有会话仍发布Chat全局接管状态

**P2，已关闭 source 缺陷。** 修复前`DeepReadGoogleSearchHost.ets`确实独立controller/hooks，但复用的`WebMountSession.markPageEnd`无条件写AppStorage `webmountUrl`/`webmountReady`。`ChatPage:2050–2051`订阅这两值，4719后的接管条使用其显示站点并决定出现。Google host的真实pageEnd经相同方法会将Chat条全局值改为Google URL/ready，与私有搜索宿主的归属不一致；不声称已经实机同时开启Chat复现。

最小修复：给这个私有session明确关闭上述Chat UI状态发布，现有Chat session默认行为保持；只限制真实共享副作用，不加新的全局controller槽或通用框架。可以用实际markPageEnd方法验证私有session不写两key、默认Chat仍写。

最终源码constructor(publishChatUi=true)保留旧默认，GoogleHost明确new WebMountSession(false)。独立搜该class全部AppStorage写入，共URL/Ready/Lease三项，均受该开关约束；Google隔离同时涵盖setLease可能副作用。实例调用点只有既有全局Chat无参数与Google显式false，没有遗漏旧caller或修改controller/open/eval合同。实际完整class VM执行markPageEnd+setLease证明私有实例不发布任何key、默认Chat实例仍发布原URL/Ready/Lease。source关闭。

## 修复后独立实际复验

独立执行当前Google host actual tests **7/7通过**，当前SearchSelectionUI actual prefs tests **3/3通过**。后者实际调用生产loadSearchPrefs/saveSearchPrefs/updateSearchPrefs，覆盖selected ID与Google flag落盘、其他来源保持、不写legacy key、列表重排按ID定位、已删除选择明示错误、加载禁写及disappear后迟回不覆盖UI。source范围没有剩余确认缺陷。

DI当前只读观察到createRunManager第四options参数、显式templateSnapshot优先cached再读取默认、按topic闭包native Google、writerMode读取模型真实tool能力及新建registry。该观察不替代root正在补的actual DI冻结/优先级测试或runtime真实HTTP回归，不将未完门禁提前关闭。

## 搜索设置与入口已确认部分

- SettingDeepReadPage.SourceCard当前可达pages/DeepReadSearchSettingsPage；管理入口再到现有ChatSearchSettingsPage，不再把新控件写入legacy active_search_source。
- 页面loadSearchPrefs快照受alive/loadToken控制，加载期间与保存期间禁选；saveChoice以当前service ID在updateSearchPrefs串行load→transform→save中定位selected index，删除的ID明示错误，Google开关写实际searchGoogleWebViewFallbackEnabled。不是直接覆盖旧页面快照，没有重造配置存储。
- 首选列表真实展示未启用状态；域registry只选enabled且具可用配置的service，首选不可用使用其余可用。Google补充是免费聚合来源结果不足时读取真实开关。per-run配置冻结与topic闭包由root DI完成后再核，当前不扩大审未冻结部分。
- 源布局为标题栏+TopStart Scroll，水平20vp、顶部18vp、底部32vp，PillSelect trigger44vp，长帮助与错误文本宽100%自然换行；看不到必须依靠键盘的编辑入口。CardToggle及管理Row沿既有组件，未重引入P2按值Builder输入状态模式。以上仅源审，不把尺寸声明冒充JPEG/AX视觉通过。

## 最终设备必要证据

1. 新包从深读设置搜索入口进入，选择已配置首选及开关Google，返回再进仍保持；管理页添加/删除服务后原页刷新实际列表。截长选项、帮助和实际44vp热区。
2. Article实际使用自己的Web host发起Google补充，在HTTP/DOM日志或真实结果列表中区分Google与其他免费来源；网络、空结果、CAPTCHA等未实际触发的状态不冒称验过。
3. 取消Google任务、离开/重开文章时旧提取不交付，其他文章host不被解绑；精确异步owner race由实际loader覆盖，不人为增加设备调试路由。
4. Web结果或普通Article布局不被隐藏host撑开，新增setting页无重叠/左右间距错误，直接读取最新包JPEG+AX后收口。root独占设备，本review不操作设备。
