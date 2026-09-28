# E5-A 模型、Schema 与包校验收口

2026-09-28。仅负责新 domain 四文件及本报告、E5-contract；shared index/DI/dispatcher/UI、Store、backend 与设备不属本次修改。已加载 harmony-arkts skill。工作与其他 owners 并行，未回退他人编辑。

## 冻结接口

- `chat/plugins/models.ts:1` 保留 `amber.plugin.v1`、iOS wire 字段、4种 handler、真正 Python 平台扩展、文件/包/归档预算、完整 package/descriptor/envelope/生命周期/信任/诊断 DTO。
- `models.ts:81` InstalledPlugin 明确 currentHash/package nullable 与具体错误、configuredEnabled/实际 enabled、health/trust；坏包仍可按有效指针hash精确CAS恢复，IO错误不装空列表。
- `models.ts:99` plugin_v1 checkpoint 固定 descriptor/input/executionId/精确pending child/callId/私有 Recipe 状态；candidate_test 的 expectedProvided 区分未提供与显式 null；无原生句柄。
- `models.ts:108` JS request executionId/source/inputJson/hostTools/timeoutMs/maxOutputChars；事件 type=host_call/finished/failed，sessionId=executionId。HTTP返回完整body，mayHaveApplied错误保留未知副作用。
- `ports.ts:11` Store 原子CAS导入/enable/remove、一槽rollback、restore、真实archive导出、hash版健康与trusted key；listInstalled(primitives?)明确当轮助手oracle，冷载provider由平台factory异步提供。
- `ports.ts:36` JS start/reply/reject 到下一事件，cancel/hasSession同步；execution/loop直接alias Recipe ports；WM withScope第三requireCurrent可选，MCP固定身份resolver，hash/signature注入平台。
- `.workflow/harmony-remaining/packets/E5-contract.md` 已及时发送 root/Store/native/runner/HTTP/UI owner，各层消费实际公共文件，没有另造私有 DTO。

## 实现

`json_schema.ts:16/52/96` 支持真实最小 JSON Schema 词汇 type/properties/required/items/additionalProperties/enum/description；拒绝未知关键词/无效结构；输入必须明确object/properties，旧三类型输入全必填且禁止额外字段。嵌套数组/对象、integer/null、有限数值、required own-key 与精确enum均同一验证器，JSON深比较不靠字符串或键序。

`validation.ts:66/94/107/183` decode缺省保持iOS一致；规范/NFC包路径、普通文件字节预算/重复路径、严格UTF8、handler唯一/文件真实存在与非空、注册冲突、timeout/output budgets、runtime和schema兼容。Workspace scope以真实规范绝对前缀声明；JS/私有Recipe宿主能力消费runner-owned broker同一白名单，无双份清单。Python source绑定现有python_execute，MCP按实际mcpTarget而非alias猜测，OpenAPI固定HTTPS域/method并派生现有http_request审批包络。

`validation.ts:274/291/302` canonical重建已解码manifest，sorted keys/slash转义、默认字段及空localRuntimes省略；`json_schema.ts:125`统一Unicode scalar key/path比较，私有Recipe先复用现有schema重建再按同scalar规则排序，其余脚本/assets保留字节；hash domain+排序路径及uint64BE长度+全部内容，逐文件hash由平台注入。任何校验/非canonical Recipe错误均为PluginValidationError，不吞IO或输出空包。

`validation.ts:329/338` 输入移除已有协议display_title后严格schema校验，输出先coarse类型再output_schema；失败不能强转/截断成功JSON。candidate_test expected/null比对使用`pluginJSONEqual`。

## 必要聚焦验证

- owned-only临时tsconfig严格`tsc --noEmit`通过；全project当时另owner runner/tools尚在实施，未把其暂时缺文件或测试shape诊断列成本任务错误，也未改其文件。
- `/private/tmp/E5-model-schema-check.mts` 六组通过：canonical/default/content identity；路径/UTF8/byte/budget；schema/null/enum/deep equality；handler/输入/宿主能力；固定Python/MCP/HTTP；私有Recipe契约/禁止裸runtime旁路。没有跑全suite，未追加仓库测试fixture。
- 四owned文件ArkTS lint：0 error、596 warn；codelinter未解析domain TS跨文件类型产生unsafe警告，源码没有`any`兜底。dup host_tools与stdin type数组精确匹配的小修通过tsc与同六组；scalar key/path两文件最终lint为0 error/605 warn。
- Swift跨端canonical/hash fixture唯一由E5-B Store owner维护。fixture真实证实默认JS UTF16 key/path排序差异；修复后同一组包含汉字/emoji/私用区路径及output_schema.enum Unicode keys，canonical pluginJSON为774bytes，Swift与Store共同hash `7fb48ff8186653bafd7179e5b5dd66ed1a40825619f3082e8bb20c97c8afeccb`。Store owner报告 `/tmp/e5-store-fixture.log` 1/1 PASS，本任务复用其结果、不另建fixture。
- 独立review指出孤立surrogate路径在UTF8编码时别名；`validation.ts:91`三行helper仅拒D800–DFFF孤立scalar，正常成对emoji继续允许。`/private/tmp/E5-surrogate-regression.mts`精确重建旧谓词：两个不同path均被允许且UTF8 bytes相等；同场真实新谓词两个拒绝、emoji允许，输出`/private/tmp/E5-surrogate-regression.log`。随后只跑此affected regression、必要types/单validationlint，不重复已绿canonical组。
- 孤立surrogate最后单validationlint为0 error/541 warn。root确认实际Harmony declarations无全局URL后，validation改消费broker唯一`pluginURLHost`纯ASCII hostname parser（scheme=https），平台再以官方parseURL核最终请求；不引SDK或新依赖。全局URL删除的唯一affected regression由broker owner验证；本任务最终owned strict types通过、单validationlint为0 error/537 warn，没有重跑已绿六组/Swiftfixture。

后续属于root整合/独立review与设备门：真正JSVM/逐次host审批/四backend执行、StoreIO/签名/生命周期、中心UI、shared曝光与审批回路。模型层检查不宣称上述产品阶段已经通过。
