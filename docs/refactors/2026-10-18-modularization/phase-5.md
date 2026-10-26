# Phase 5：能力、原生适配与公开入口

状态：阶段职责、独立代码与已测UI验收通过。Phase4 固定包明暗核心 UI 限定 GO 后启动；其输入冻结及未测门保留，未假定根因。职责准备见任务证据 phase5-capability-boundaries.md、phase5-platform-boundaries.md、phase5-retained-boundaries.md。九份源快照在 phase5-before/；chat 公共入口基线为 2262 个导出符号，记录名称及 type/value 身份。

六个独立实现包分别持有 Skills、PromptConfig、Artifacts、DeviceSchedule、SectionWriter、WebMount OAuth；root 持有 Cron、能力导出清单、共享依赖守卫及全局整合。保持唯一缓存/锁/写入队列/生命周期，旧公开入口兼容，工具名、顺序、schema、格式和prompt文本保持。实现后一次有限维护审查，冻结后只修复确认的审查/SDK/UI问题。

当前内聚的 MemoryStore、Council、context_compact、Board、Article、Projects、PageTemplate、ChapterReader 保留，理由见 file-dispositions.md 和上述边界证据；不恢复已清理功能、不为了行数分裂协调状态。

## root 整理与验证

Cron 的表达式/时区计算（191行）及工具协议（163行）与任务 manager（760行）分离；manager 的锁、落盘、状态与 scheduler 仍在原 owner。迁出的实现代码块与基线逐字一致，原 public path 重导出保持，tools 对 manager 为 type-only import。Memory 与 Smart UI 的公开符号归入 public 能力清单，主 index1553→1407；清单无业务行为，不创建状态。

root 六份源 lint 0 errors/239 warnings（原TS动态JSON/Intl等建议保留）；递归依赖守卫加入 webmount_settings 后通过。阶段开始时再核原有19份Harmony之外dirty文件哈希，均与恢复快照一致；不将此表述为已验证全部未跟踪WIP内容。

## 冻结实现与独立 review

| 职责 | 最终结构 | 独立验证 |
| --- | --- | --- |
| Skills | metadata、package事务、唯一library/cache、mobile prompt、tools；private deps穿透替换成read/collect/list三个业务能力 | 50/50，7文件及受保护catalog WIP哈希匹配 |
| PromptConfig | 46行兼容入口、393行definitions、228行codec、362行repo、215行tool；六消费者直接依赖角色定义 | 186/186，12文件哈希匹配，29旧export和canonical身份保持 |
| Artifacts | 56行组合入口，ZIP118/archive386/HTTP184/document158/image164及内部tracker/metadata | 32/32含真实Novel ZIP/EPUB adapter往返，10文件哈希匹配 |
| DeviceSchedule | domain契约/codec/三工具组；Entry权限、Calendar、agent notification+alarm两个原生生命周期；跨实例记录锁唯一 | 82/82含8项真实adapter MockSDK合同，12文件哈希匹配 |
| SectionWriter | factory532、codec241/fallback87/visuals185，唯一output/update/双计数闭包 | 58/58，5文件哈希匹配，内部VERIFYING/外层COMPLETE差异保持 |
| WebMount OAuth | page876、session195/view193；页持有观察实例和订阅，其他区域原样 | 45/45，4文件哈希匹配，25非OAuth方法+build逐字相同 |
| Cron/公开入口 | manager760、expression191、tools163；Memory/SmartUI纯能力导出清单 | 28/28，2262公开符号名称/type-value完全一致，6文件哈希匹配 |

七个独立只读审查报告均无未解决确认问题，原始证据为任务目录 phase5-{skills,prompt,artifact,device,section-writer,webmount,cron-public}-independent-review.md。所有包分别完成一次有限维护检查；未添加通用控制器、注册框架、额外重试/权限或新队列。

WebMount先从真实session复现load/save晚到会在dispose后重新订阅，限定alive/refresh序号修复；回归保持dispose不cancel授权。其他迁出算法、工具协议和持久合同按原实现保留，新增失败测试没有削弱原断言。

## 整合门

冻结977份生产TS/ETS，Chat与DeepRead最终tsc均exit0；所有本阶段修改生产文件scoped lint均0error，保留具体warning（不称无新增warning）。SDK与完整Node门顺序执行：完整门4699/4699、167suites、0fail/cancel/skip，42.928秒；CompileArkTS18.074秒、PackageHap通过，原SignHap仍11014003。未改原超时或断言。

固定unsigned为 phase5-ui-final/phase5-debug-unsigned.hap，SHA256 `23b56f6e9a8b7ea8e78b24245079070a14265a0a98b9461c27d7e656601f4ac1`；977源码构建前后无差异，artifact.json保留全表。既有AGC签名/verify/保数据安装/start通过；signed SHA256为 `842b2afac926932487a8aedca7cee941e39c2b4332c93e5db854a03505960c01`。浅暗OAuth空表单、站点/HAR/Cookie/飞书区域、Back重进及Skills/Cron只读路径对照通过。临时Site ID phase5-ui-check即时显示，真实软键盘可见，Back离页重进后草稿清除；没有保存、授权、联网或业务开关更改。没有已登记OAuth卡，因此cancelOauth端侧未测，不能用Back代替。PID473本轮始末未变，可得240行日志无相关RuntimeError/输入阻塞，fault目录最新仍Phase4记录；977源末检0差异。证据phase5-ui-review.md与phase5-ui-final/README.md。旧包UI只读对照见 phase5-ui-before/README.md。
