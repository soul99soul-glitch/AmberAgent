# HarmonyOS 精准修复交付

日期：2026-09-13。基线：`harmony-preview` / `bdce551dd373f3d7e4c393b194997cae19289c40`，包含原52个未提交修改。本次按已批准计划实施，未提交/推送、未升级依赖、未改系统设置、未调用真实模型或删除真实用户文件。

## 结果摘要

合并报告的**50个目标（48确认缺陷+2潜伏安全缺陷）全部完成处置**，但**不宣称50项都已设备验收**：

- 47项完成针对性代码修复。其中17项已完成领域/隔离逻辑验证或编译验证；30项因含UI、SDK生命周期或网络行为，仍标“已实现，待设备验收”。
- 1项Cron能力限定闭环：已接真实无头工具循环（时间、工作区、搜索、MCP），页面专属工具不暴露；待审批明确停止，不假报成功，不自动放开外部写。
- 1项显示设置部分支持：6项已接渲染；`sendOnEnter` / `pasteLongTextAsFile` 无现有安全输入管线，明确显示未支持、不再假保存。
- 1项Slides诚实降级：没有实际消费者的字体设置入口下线，旧页面明确不支持；**未声称实现Slides字体功能**。

逐项状态及对应证据见 [repair-status.json](repair-status.json)。设备待验意味着改动已编译/静态或隔离验证，但没有真机截图证明交互表现。

## 关键修复

### 数据保护
- 新备份v2保留完整分支、节点索引与元数据，v1读取兼容但不虚构旧备份已丢的信息。
- 结构/时间/ID/节点索引/消息完整校验；会话批量恢复一笔RDB事务，设置为明确的第二阶段并保留失败回滚信息。
- 运行/恢复同会话互斥；恢复后版本变化使仍存活ChatPage在返回及变更前重载，避免旧内存快照覆盖恢复结果。
- RDB不可用时拒绝持久化写并提示重试，不再把临时内存成功当已保存。
- Memory/ManagedFile/Favorite的损坏/读错误不再作为空基准覆写；隔离标记保留。
- 文件清理重新检查登记健康、聊天库健康与会话引用；确认后在文件变更临界段再核验，拒非法路径，保护仍在引用的附件。

### 模型、授权与自动化
- kernel按真实取消信号识别取消并保留最近已保存快照；普通错误正文中的abort不再抹去新user。
- 四Provider拒绝零SSE事件的200响应；stream与调用模式保持一致；DONE/error经shouldStop清理底层流。
- 工具结果按原input part身份回填；审批定位消息/part，不按空ID全匹配；模糊“继续…”不再批准pending工具。
- Cron运行锁、原due时间复核、长delay分段、当前run取消与删除abort；待审批不标成功。
- SubAgent待审批为明确中断，可从任务看板取消；活跃cancel回调不被cleanup误删。

### WebMount与深读
- 标准URL解析拒userinfo/非HTTP scheme，JS动态值统一编码，click保持mandatory审批；站点撤销立即刷新gate。
- 只有真实controller attached才标mounted，销毁对称解绑；保留“全局或助手单独启用”的既有语义。
- 深读取消贯穿实际entry搜索/HTTP/规划；重进在跑文章仅订阅，取消/部分文章需明确继续，继续只补非READY段。
- 阅读偏好读取独立于完整缓存短路；Web模板消费字号/字体参数；通知等待真正publish并按run token处理取消。

### UI接线与性能
- 历史多选、MCP空名称反馈、子代理/议会显式保存与脏草稿返回、小说二级页签与自绘层返回。
- 字号/模型名图标/气泡/代码折叠行号传到全部聊天消息行；再生预览仅变换一次；文档data/file预览有输入/输出上限，不扩PDF引擎。
- 有效模型展示/选择与请求同resolver；助手设置锁内读最新再改字段。
- 热力图阈值一次计算、列表预览8-worker限并发和一次聚合、静止头像不跑blink、隐藏页停止问候动画、阶段标签不按墙钟伪造。

## 实际验证

|验证|最终结果|记录|
|---|---|---|
|Chat全量单测|**2124/2124通过，0失败/跳过**|[chat-regression-final.log](chat-regression-final.log)|
|DeepRead全量单测|**782/782通过，0失败/跳过**|[deepread-regression-final.log](deepread-regression-final.log)|
|两域TypeScript|均exit0|`tsc-chat-release.log` / `tsc-deepread-release.log`|
|ArkTS编译|**CompileArkTS通过**|[build-release-check.log](build-release-check.log)|
|HAP打包|**PackageHap通过**|同上|
|项目既有AGC签名|**失败**：原配置storeFile指向本机不存在材料；未更改配置|同上，hvigor实际exit255|
|独立离线签名|**签名及verify-app通过，exit0**|[sign-final.log](sign-final.log)|
|11个真实代码隔离probe|**全部exit0**|[isolated-final-results.json](isolated-final-results.json)、[isolated-final.log](isolated-final.log)|
|改动文件lint|未见error；主ChatPage有2项warning，部分工具报告内部incomplete|各batch及`lint-chatpage-final.log` / `lint-container-final.log`|
|git diff --check|通过|最终执行记录|
|hdc设备|`[Empty]`|未安装、未截图、未更改设备状态|

正式单测从2872增至2906，**净增34条**，主要复用已有测试文件（额外Cron回归文件一份）；不是每个UI项建测试套件。另11个隔离脚本验证真实.ets/平台Port的合成边界，不作为设备运行证明，不把脚本内断言数叠加到正式测试数。

构建使用DevEco已有JRE，通过单命令`JAVA_HOME/PATH`配置，不安装Java、不改全局环境。第一次尝试直接CompileArkTS task使用了不适用命令，已改已知assembleHap入口；一次隔离probe入口路径拼错MODULE_NOT_FOUND已更正，均为执行错误，未算产品失败。最终shell包装器打印后的exit0不等于hvigor成功，报告以上游打印的**hvigor exit255（仅SignHap）**为准。

## 构建产物

- [最终未签名HAP](../../../harmony/entry/build/default/outputs/default/entry-default-unsigned.hap)
- [最终离线调试签名HAP](../../../harmony/entry/build/default/outputs/default/repair-signed-final/entry-default-signed.hap)

离线自签路线用于调试/模拟器；**不是可直接替代零售真机AGC签名的承诺**。没有在线设备，所以没有安装验收结果。产物大小/SHA-256见[repair-integrity.json](repair-integrity.json)。

## 独立复核与修正

- [批0/1数据与加密](review-batch-01.md)：消息缺ID/时间宽容解析、降级删除假成功、GC错误读引用等均回修。
- [批2/3协议工具](review-batch-23.md)：审批中断与cleanup的交界、locator miss新对象假成功回修；HTTP status0担忧被真实传输gate反证。
- [批4/5生命周期UI](review-batch-45.md)：revoke即时生效、继续只补缺段、通知吞错等已回修；global/assistant OR语义二次纠正。
- [最终共享集成](review-final-integration.md)：编辑/辅助写运行占用、恢复后旧页面失效、无效助手/模型、Want三类意图；最后补reserve时版本门、手动标题guard、重载失败可见重试、regenerate/branch异常catch。

复核报告保留提出时快照；“未修”描述可能已由后续batch回修，最终以本报告、repair-status与当前源码为准。没有为消警告大规模重构或统一格式。

## 明确未完成/接受残余

1. **设备验收未完成**：暗色/键盘/安全区/触控命中/大字体、WebMount controller/重定向、真实SDK GCM、通知时序、后台调度和文档手势。原报告10组UI待验和体验建议不根据猜测批量改。
2. **真实服务未调用**：Provider兼容、OAuth兑换、MCP外部副作用、WebDAV读写均未实测；没有收费模型、真实token或用户文件操作。
3. **能力边界**：Cron仍应用存活期，不实现系统唤醒/跨进程补跑；Responses完整续传、跨端KDF、全附件文件树迁移仍未扩展。
4. **明确降级**：显示设置两个输入能力、Slides字体；PDF预览仍不支持并明示。不是用删除入口掩盖已实现能力损坏，而是停止宣称不存在的能力。
5. **已有相邻缺口**：`createEntryDocumentReader`发送路径仍只接file://，picker的data文档预览已支持，但发送内联数据URL的旧缺陷未借F44偷偷扩大改动；另记后续应优先处理。
6. **损坏隔离保护**：损坏Memory/ManagedFile存储会拒写，需要显式恢复有效数据；当前没有新增恢复管理工作台，不自动清隔离标记重新覆写原库。
7. **协议错误详情**：非SSE200现会明确失败，但平台未保留某些2xx非SSE原始正文时错误信息可能只有通用描述，不再静默成功。
8. **基线保留**：本次改动92个已跟踪文件（含与原修改叠加）及若干新helper/测试。原52个修改中36个完全未碰，16个在当前内容上追加修复；未reset/覆盖回HEAD，无已跟踪文件删除。原审查报告不改。

## 文件导航

- [50项目标台账](repair-status.json)
- [主代理集成说明](main-integration.md)
- `batch-*.md`：各实施负责人按文件与ID记录
- `review-*.md`：独立反证与回修依据
- [基线](baseline.txt)、`baseline-hashes.json`、[完整性/产物指纹](repair-integrity.json)

本次交付的是已落地且经过编译、领域回归、隔离验证与独立复核的修复，不是所有设备/服务场景的零Bug保证。没有commit/push。
