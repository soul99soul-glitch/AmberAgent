# P1 当前生产能力核对后的阶段任务

来源：独立 subagent 逐项查当前 iOS NovelCreation/DeepReadApp target 的生产 caller 与当前鸿蒙实现；不能只按旧矩阵“后置”筛选。已等效的实际能力沿现有事务/任务/设置路径，不重造。

| Phase | 任务组及矩阵 ID | 实际缺口及最小实现边界 |
| --- | --- | --- |
| P1 | 独立入口与配置 | 保存动作边距、返回箭头居中；小说设置返回保留润色草稿且刷新真实模型默认；逐模型override可用性与执行一致；Provider菜单按产品，深读隐藏议会模型库；配置启动失败持久化并保护原输入/旧文章 |
| P2 | A04/A10/A16/D01 | 项目creationMode/quickStartSeed元数据与原种子复用；本轮override不覆盖原种子；项目润色字段先落schema供P5消费 |
| P2 | A13/A14/A15 | 复用现commit snapshots实现历史列表、checkpoint fork、多步撤回；高级分支改名/设主线/删除可达；章节恢复受后章限制时另开分支选择 |
| P2 | A05/A06/A08/A12 | 删除时间排序、有效旧快照只读浏览、导入reject/replace/keepBoth；先停止活动运行再切换的明确确认，不开第二条事务链 |
| P3 | C02/C04/C05/A16/C06 | 人物别名、关系/自定义分类和标签；项目共享资料base+branch override和恢复继承；提案/编辑/公开格式消费同字段 |
| P3 | C07/C09/C10 | always/smart/off、确定性相关性规则、单次强制入/排、实际模型预算与入选凭据；发送与UI预览同一planner，不加模型分类服务 |
| P4 | B06/B09/B10/B11 | 专属工具目录到执行器；按段改稿、多章删除及最近N章回退先产生现CAS proposal/范围确认，复用既有作者审批 |
| P4 | C03/C11/C13/E03/E04 | 结构证据事件和人物经历投影、身份澄清；状态救援重建进度/重试及专用推理参数/协议版本。普通手改继续确定性提交，不额外调用LLM |
| P4 | D02/D03/D04/D05 | structured chapter contract(draft/confirmed、goal/must/mustNot/endingHook/visibleFacts/digest)、一键计划提案与最多8条upcomingArc；现prompt/runner真实消费 |
| P4 | C16/D07/D10 | 连续性修复生成带审计证据/digest的正文patch proposal；作者brief进入暂停代笔返修；明确允许改事实的重生成与仅润色语义区分 |
| P5 | E05/D12/D13 | 每项目润色偏好在job开始冻结；逐章成功/漂移/失败/未处理结果，失败继续及失败集合定向重试，沿同durable job |
| P5 | B17/B19/B20/B23/F02 | 已收录内容克隆lineage、归档折叠、收录后同步反馈；普通运行精确持久重试/部分稿；支持的Responses GET同请求游标恢复，不用POST伪装resume |
| P5 | A09/A10/A11/F01/F03/F04 | 新字段公开/native格式真实往返；修正polishPreference误映射成写作preferences；实际保存/分享入口；通知/任务/离页恢复链验证 |
| P6 | DR-01/02 | 补当前iOS缺的9源（HN/HF/GitHub AI/ithome/sspai/juejin/coolapk/xueqiu/hupu），设置分类与刷新可达；无链接热点可读且榜单摘要成为持久来源 |
| P6 | DR-03/08/09/10/11 | 当次模板选择+每文章模板归属及PDF同源；来源可点击；真实TXT文件保存分享；深读独立20MiB；新HTML模板有合法starter |
| P6 | DR-04/05/06/07 | Google开关进入深读实际搜索；首选服务决定真实请求集合；普通非tool聊天模型生成完整分段结构化文章；库停留时实时响应仓库/运行状态，复用订阅 |
| P7 | UI与产品体验 | 长分支名/人物别名/标题、180%字号、键盘、弹层确认/footer、浅暗色、来源/模板预览实际AX边界；深读真实阶段Newsroom小组件、随机阅读明确入口；不加后台传感器或虚假进度 |
| P8 | 最终退出 | 阶段独立review逐条闭环；当前最终源码指纹、测试、构建签名、独立安装冷启动；刷新80矩阵/DR11条/每项证据。云服务和系统限制不冒充已通过 |

## 当前已有能力

- 小说普通generation → actual interactive adapter/provider → checkpoint/repository；workspace proposal/CAS作者审批；durable ghostwrite/polish、正文/剧情原子提交、材料提案、归档、公开/native备份、分块审计与逐字证据；查找替换已实际验证。
- 深读发现缓存/关注/翻译设置、输入资料和文本解析、来源状态/错误、页面离开任务继续、四节持久输出/旧文保护、阅读字号字体、模板编辑与AI、Markdown/PDF系统出口均可复用。
- iOS未发现阅读库可达删除，因此不凭惯例新增删库能力。扫描PDF无OCR是两端共同边界。

## P2审查发现的P4精确待办

当前repository.workspaceProposals和status.pendingProposalCount合并各分支pending，分叉后会展示旧分支提案；resolveProposal的CAS已拒跨branch写入，但UI列表与计数应按expectedCas.branchId===activeBranchId投影。P4补现投影，不增加提案存储。

P3查到P5明确旁路：repository.startPolishJob冻结人物资料仍仅enabled筛选，未用classifyNovelMaterials且丢aliases/tags；P5冻结job捕获偏好和角色保护时复用实际资料选择规则，具体覆盖off但branch override受保护的事实。
