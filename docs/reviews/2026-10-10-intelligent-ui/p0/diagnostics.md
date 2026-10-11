# P0 终态取证改动

确认的缺口是 OpenAI 兼容解析器丢弃 delta/message 均为空但带 finish_reason 的终止帧。新增回归检查先复现 length 丢失，再修复为保留 finish-only choice；没有终止原因的 keep-alive 仍不生成 choice。原始消息和 usage 合并行为不变。

宿主复用 wrapStreamProvider 记录 runId、conversationId、正文字符数、finishReason 和 finished/failed/cancelled；不写正文、请求头或密钥。此证据不能证明用户原消息的失败原因，原消息取证仍单列。

验证：相关 71 项 Node 检查通过；改动文件 lint 为 0 error（75 warnings）；鸿蒙 assembleApp 构建通过。ArkTS 的 throw 限制已根据构建反馈修复，保留 Error 对象传播。该阶段没有视觉布局变更，UI 截图检查在协议卡片落地后执行。
