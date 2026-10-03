# P3 独立 UI 与调用链审查

本审查 agent 只读源码，独占本报告；不重复已有 context/domain review。范围为资料编辑与元数据、资料卡、采纳 sheet、Workspace 材料入口、作用域和 CAS、隐藏共享资料恢复，以及上下文预览的入口、返回token和下一次普通generate。

## 调用链与状态审查

没有新确认业务断链；构建阶段已发现一项命名编译阻断，修复与安装截图验证见下方。

- Workspace新建／编辑先 `readWorkspaceSnapshot`；shared编辑读真实 `baseMaterials`，branch编辑读当前有效资料，保存带该快照CAS。打开请求token在hide/disappear/取消时失效，迟到读取不能重开sheet。CAS冲突保留编辑页和错误，不把旧草稿重绑新分支。
- Editor draft字段整体赋新对象，kind/title/content/scope和aliases/tags/customKind/injectionMode经同一draft提交。共享新建会由makeNovelMaterial生成非空id；shared保存保留分支override，branch保存进入当前分支事务。采纳更新读取目标真实metadata，未指定的别名／标签／off状态不会被候选建议重置。
- material card明确显示继承／覆盖／本分支来源；删除共享来源只在当前branch隐藏，不删共享底稿；HiddenSharedMaterials仍提供恢复继承入口。恢复前确认范围，提交使用捕获CAS，成功reload。
- Editor取消、背景点击与系统返回统一走closeRequest/cancel，IME字段先stopEditing，下一回合检查规范化draft；未保存修改弹“继续编辑／放弃”，回调受inputToken和alive约束。保存也先提交IME字段，旧draft替换后不会触发迟到保存。
- Metadata子组件返回commit回调用于同步IME提交，拆词只影响规范化字段；输入变更立即publish canonical fields，保存UI和repository同一字段事实。Adoption显式选择更新目标，不自动按同名覆盖；目标切换重建metadata draft，确认传完整编辑后的字段。
- ContextPreview入口携带当前作者输入、compose mode及当前one-run选择。页面只读snapshot与previewContext，不写material；选择项受branchId和previewToken约束，迟到回执不覆盖新选择。未配置模型时显示实际解析错误，不宣称有预算回执。
- Preview apply返回project/branch/receiptToken及两组id；Workspace先reload当前branch再consume，仅同project/branch一次。普通discuss／continue／whole_chapter将override传给实际generate，接受后清除；模型校验失败保留作者输入和未消费override，quick_start保持override供下一次普通创作。切branch后旧override清除。

## P2 输入动作响应模式复查

- Editor保存 `enabled` 在build表达式直接读取`draft.title`及blocked，未把变化谓词作为primitive传给Action builder。
- Adoption actions在builder内部读取canConfirm，canConfirm读取实际@State title/body/target，没有沿用P2已确认缺陷模式。
- Metadata模式按钮直接在ForEach UI表达式读取mode；Preview动作直接读取busy和branchId，选择文本色直接读取forceIncludes/forceExcludes。

## UI 布局源码核查

- Editor与Adoption均是88%高度的局部sheet，主体Scroll占剩余空间、底部双动作44vp固定，父Workspace使用RESIZE；左右20vp，底部按导航栏留白。长字段主体可滚，Footer不混入正文滚动。
- Editor分类和metadata模式使用Flex.Wrap，44vp热区。正文150vp、别名／标签72vp、自定义分类44vp；卡片正文按内容扩高，长操作标签可换行到下一行而不挤压标题。
- ContextPreview左右20vp、正文行高、长材料名容器宽度均明确，三个短选择动作min44vp。页面Scroll承载资料与预算块，apply min48vp。

以上源码尺寸与下方最终安装图联合审查；providerconfig旧图不作为新P3材料页证据。

## 实际方法验证

独立运行 `novel_material_ui.test.cjs` + `novel_context_override_ui.test.cjs`（tsx loader + chat tsconfig）：12/12通过。

验证实际UI方法连接真实repository：base与override编辑加载、metadata保存与恢复、branch切换后CAS拒绝重绑、隐藏后恢复入口、采纳保留未指定metadata、IME提交与旧回调失效；以及同owner返回一次、异owner拒绝、三种普通generate完整参数、模型失败保留输入和one-run选择。

## 最终安装图与 AX 独立复查

独立通过 `view_image` 目测 `device/p3/` 的11张新页面图：`p3-material-keyboard`、`p3-material-metadata`、`p3-shared-card`、`p3-branch-override`、`p3-inheritance-confirmed`、`p3-real-context-preview`、`p3-preview-excluded`、`p3-preview-bottom`、`p3-applied-workspace`、`p3-sent-excluded`、`p3-sent-next-default`；同时遍历对应AX JSON的bounds/enabled/opacity。未确认需修复的错位、重叠、文字挤压、边距或按钮大小问题。

- 键盘开启时资料sheet正文仍可输入；取消 `[70,1505][639,1659]`、保存 `[681,1505][1250,1659]`，两者154px高，对应44vp，均enabled=true且完整位于应用窗口底1757px上方。正文随Scroll截断是可滚动内容，固定footer未受遮挡。
- metadata图别名与标签输入、分类字段、三种模式和底部动作均可达。模式三个154px高热区无重叠；左右70px对应20vp。标题顶部在滚动位置有裁剪，位于主体Scroll内，未侵入固定sheet标题。
- 共享卡片实际显示别名、标签、正文和来源。共享操作为一行；分支覆盖新增“恢复继承”后移除操作换至下一行，均154px高；两行对齐卡片内容起点，无文字或点击区重叠。恢复后的来源状态回到“继承共享底稿”。设备此步骤没有成功替换为不同正文，因此只证明覆盖／继承状态转换和布局；不同正文正确加载由实际方法测试证明。
- Preview显示真实当前模型fixture-context-16k；窗口16384减输出4096得到输入预算12288。默认预计输入1526，排除后1501，选中颜色与排除说明一致。资料三个选择热区均154px高，无P2 primitive参数导致的enabled停滞。
- `preview-excluded`初始应用动作在屏幕底部分裁剪（可见bounds至2856）；`preview-bottom`滚动后完整为 `[70,2506][1250,2674]`，168px即48vp，enabled=true，并实际成功返回原Workspace。因此属于正常页面Scroll，不报错位bug。
- 返回后原作者输入保留，提示“包含0项，排除1项，仅下一次普通创作有效”与清除动作没有挤压。两次发送图均显示对应作者输入和完成消息，第一次发送后单次提示消失；输入区、模式行及发送控件未重叠。

独立读取 `request-receipts.json`：两次均到实际 `/v1/chat/completions`、同fixture模型；排除轮 `materialBodyPresent=false`，下一默认轮为true且捕获 `[人物] Rhea`、`Aliases: CaptainRhea`、真实资料正文。第一轮aliasPresent=true来自作者输入，不能当作排除失败，也不能声称第一轮包含资料别名字段。固定fixture回复文本不作为模型理解资料的证明。此证据关闭设备入口→预览→一次选择→实际发送→下一次默认的链路，不代表外部云服务质量。

## 当前状态

- 构建命名问题已关闭：最终prop为`materialEnabled`，`resetDraft`及Editor调用点使用相同名称，未与CustomComponent.enabled冲突；父agent已完成两包构建签名安装。
- 最终源码重新独立运行实际方法测试12/12通过，无额外确认逻辑finding。
- P3本范围device UI gate关闭，可推进下一phase。Adoption的目标切换及字段保留由源码与实际方法测试覆盖，本批无Adoption新设备截图；未将其伪称设备视觉验证。跨主题和大字号综合验收仍属于P7范围。
