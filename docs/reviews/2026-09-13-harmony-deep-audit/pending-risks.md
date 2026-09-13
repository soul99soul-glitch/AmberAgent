# 待验证风险、接受残余与明确盲区

这些条目**不计入正式确认Bug数量**。没有完成验证不代表没有问题；已登记限制也不代表产品风险消失。已证实但端到端受阻的WebMount L01/L02独立列在findings.md，不与普通假设混写。

|ID|风险与当前证据|缺什么/具体阻塞|最小验证方法|
|---|---|---|---|
|U01|主题令牌为模块可变值，部分页面未实际依赖darkMode；旧页可能不刷新|无设备且构建失败，未确认ArkUI依赖/子树刷新实际粒度|先修构建，前台系统换色+设置换色+返回旧页，逐页截图；禁止只按export let数量断言受影响页数|
|U02|预算链未完全统一：prepareContext未传promptOverheadTokens，resume缺finalTokenBudget，fit保留system|正常send/regenerate已有fit；初始probe用20k记忆超生产上限，不能用于证明常态memory溢窗；真实tokenizer与模型窗口未测|使用真实memory builder（maxPromptChars≤12000）、中文/英文/agentSoul、多次工具续跑，捕获最终请求估算；明确不可裁system超窗时是产品策略还是应本地报错|
|U03|恢复与在途对话无版本协调，最后全量save可能覆盖另一边|队列仅保证串行非合并；页面push/hide与generation时序未设备复现|受控Provider延迟+打开恢复+相同conversation ID，双向乱序完成断言保留/冲突提示；不使用用户真实数据|
|U04|备份不携带附件文件树、也不覆盖全部配置域|file引用跨机缺失可能存在，当前UI能力边界说明需产品核对；未设备迁移|合成file附件/inline图片/记忆/设置，换隔离profile做完整清单比对；不能把仅会话导出声称整机迁移|
|U05|MiniApp图片预取只校验初始URL；DNS先验与HTTP解析未地址钉住|API12 NetworkKit重定向/解析实际行为无设备；RCP bridge逐跳保护不能替代图片路径|受控公网→私网重定向与双DNS解答，使用非敏感测试服务记录访问；需明确授权后执行|
|U06|MiniApp网络先收全体再检查大小，bridge入队无总量上限；技能多文件Map缺累计上限|没有实机峰值/耗时，SDK可能另有响应限制；不能直接断言OOM|受控chunked服务/大量小桥请求/多条目技能，记录内存队列峰值，避免真实数据及无界压力|
|U07|MiniApp图片预取成功会refresh，初始化脚本可重跑|真实Web loadData→refresh是否重跑桥脚本、session状态及写副作用未验证|合成小应用初始化storage计数，插入可代理图片，记录加载次数；无外部写|
|U08|Streamable MCP GET EOF不触发close/error，不自动重连|合成transport已确认，POST工具仍能工作；真实服务器notifications损失/恢复行为未验|fake长连工具列表变更通知+EOF，确认manager后续是否重开；不要误报全部MCP工具不可用|
|U09|OpenAI error:null会被抛成null；Responses只data.type不带event的终止判定|真实支持网关是否这样发无样本；官方标准路径未据此判错；Android JsonNull也会抛，不作反证|用供应商公开协议fixture或经授权脱敏报文验证；不能拿随意畸形payload当服务兼容故障|
|U10|Provider终止后的readTimeout/心跳与destroy语义|R18机制已确认；最长等待和SDK空闲/总响应时限未确认|受控终止后idle/heartbeat/用户Stop，记录真实请求释放及UI恢复；不声称60ms probe证明无限挂死|
|U11|RDB写事务中的并发读、文件write-close-rename缺fsync、空间不足/权限撤销|没有Harmony RDB/FileIO设备语义实证|隔离数据库并发read/save/delete/故障注入、合法路径文件消失/写满，记录错误与原数据完整性|
|U12|真实SyncCrypto GCM/KDF/随机熵与API12能力|当前API调用无法编译；域测试使用合成crypto，不证明加密实现正确|官方向量、不同密码/IV/tag/截断/大数据真实SDK roundtrip；跨端SHA256-ITERATED与PBKDF2不互通已登记|
|U13|WebMount导航/实际URL/移除站点后权限不重查、多ChatPage共controller|R07当前挡首次open，实际controller绑定和pageEnd尚无设备证据|与L01/L02一起修后，重定向/跨域链接/revoke/退出重开/双页面；先隔离合成站点，不读真实cookie|
|U14|OAuth仅手动code/URL粘贴，页面销毁会丢PKCE状态，URL percent解码未统一|未接自动回调listener，不能按自动回调功能审；真实飞书code字符与恢复流程未验证|fake token endpoint+合成回调，state不匹配/空state/percent编码/返回页面已销毁的说明闭环|
|U15|历史grant由模型选择session IDs并自动创建|TTL/字符/会话数有界，父会话本可读历史；是否应由用户签发属产品信任契约|明确跨助手/会话历史的授权模型，以合成会话测试；不读取真实用户历史|
|U16|Provider新建依赖一次性模块PENDING_PROVIDER，进程恢复可能空表单|实际系统是否恢复到isNew路由未知；正常同步交接非响应式Bug|隔离测试新建页进程回收/恢复、表单说明与保存按钮，不操作真实key|
|U17|Council本地outputBudgetChars不限制服务端max_tokens；终结后监听Map|不能默认本地字符预算承诺服务端token成本；订阅清理后是否仍留闭包/实际规模未验|fake长输出计数及反复进入已终结任务后的listeners大小/引用堆，先确认UI预算语义|
|U18|真实错误/用户编辑正则导致长文本性能异常，流式拷贝/markdown渲染成本|无实机profiling；代码长度/线性复制本身不足以确诊卡死|合成1000/10000节点、长单条、50ms流刷新，测CPU/内存/帧时间，确认阈值与瓶颈|
|U19|真正副作用完成但客户端丢响应后的重试幂等|现有逻辑只能证取消检查/状态记录，不能证明所有远程工具天然幂等|有授权的fake MCP写工具保存调用ID，模拟执行后丢响应/重试/重启；检查恢复不会重复写|

## U20 / 原R21：聊天与深读的搜索开关契约未明确

SearchRegistry恒注册DDG/HN/Jina而聊天栈消费searchBuiltin开关的代码事实已核验；但ChatSearchSettingsPage总开关明示“允许聊天调用search_web和scrape_web”，深读有独立设置面。缺少用户承诺深读也遵从该开关的证据，终审不把平台/模块差异直接计Bug。需产品确认统一或独立配置语义，再用fake registry验证。原R21移至此处，初始化ready竞态仍未证。

## 已登记能力边界（不是“无问题”）

- Cron是应用进程存活期setTimeout，不具备系统级持久唤醒，错过时点启动不补跑；见PARITY_DEBT PD011。R12/R16/R17是该实现内另有的具体错误。
- Responses resume Port存在但runId未接，完整GET续传属于后续能力。resume save异常属于接线前必须处置的潜伏债务，不算当前用户Bug。
- 无后台双向同步、冲突合并、同步删除传播；WebDAV仅上传已接，list/mkcol死代码不当活跃功能审。跨端KDF互通未完成。
- 空预抓用标题降级写文为PD001已登记决策，本轮没有证据把有意降级改判Bug；必须在产品验收时决定展示可信度。
- 受限ACL/未声明权限返回Denied，special access Unsupported；未移植短信/系统控制能力不虚构越权路径。
- 实际密钥/令牌/签名材料没有被读取验证。沙箱明文配置/LRU键名包含apiKey是代码事实，但没有外部可读攻击链，不计真实泄露。备份选“无密码”派生空口令是无保密性的模式，不因encrypted=true宣称有密码安全；UI默认密码开启。

## 测试与工具缺口

- entry无平台/页面自动化测试；2872条域测试不能覆盖ArkTS/SDK/设备生命周期。
- CLI文档工具缺失；Huawei网页返回壳，本地SDK声明和官方OpenHarmony状态文档可读。未获得自动化全源API兼容扫描。
- 有界lint报内部错误，退出码0也未接受为通过；未全仓lint。
- 没有在线hdc设备，未装机/截图/模拟用户操作。所有主题/键盘/安全区/大字体/滚动/触控结论未实际验收。
- Android/iOS仅必要契约对照，未全面审计；source-only/native spikes与不可达旧页未作为当前用户路径深挖。
