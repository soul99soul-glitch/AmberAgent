// stream_checkpoint — 流式 checkpoint 纯逻辑(Phase 2)
//
// Android 基准: StreamCheckpoints.kt(streamContentLength / streamPartsHash /
//   toolStateSnapshots)+ ChatStreamCheckpointRecorder(512 字符合并信号)
//
// 裁剪(与计划 KV-checkpoint 方案不同,按 Phase 2 收口要求):
//   - 本文件只提供轻量判定纯函数(内容长度 / hash / 节流判定);
//   - 实际 checkpoint 落库 = 把可恢复的 Conversation 部分快照投影后经
//     ConversationRepository.save 保存(ChatPage 接线) — 不是只存
//     hash/KV 元数据。checkpointConversationTail 用 updateCurrentMessages
//     (同 id 原位替换、新消息追加),保证多次投影不重复 append 节点。

import type { Conversation } from './conversation.ts';
import { toMessageNode, updateCurrentMessages } from './conversation.ts';
import type { UIMessage, UIMessagePart } from './message.ts';
import { nowIso } from './ids.ts';

// ChatStreamCheckpointRecorder 的合并信号与周期:
//   512 字符合并阈值(streamContentLength() 语义);
//   周期优先 10s + 变化检查(Android STREAM_CHECKPOINT 节流参考)
export const STREAM_CHECKPOINT_INTERVAL_MS: number = 10_000;
export const STREAM_CHECKPOINT_MIN_GROWTH_CHARS: number = 512;

// UIMessage.streamContentLength():总流式内容字符数(text + reasoning +
//   tool name/input;其余 part 不计数,对齐 Android)
export const streamContentLength = (msg: UIMessage): number => {
  let total: number = 0;
  for (const p of msg.parts) {
    switch (p.type) {
      case 'text': total += p.text.length; break;
      case 'reasoning': total += p.reasoning.length; break;
      case 'tool': total += p.toolName.length + p.input.length; break;
      default: break;
    }
  }
  return total;
};

// streamPartsCanonical:Android StreamCheckpoints.kt 的 canonical 序列化
//   (t|/r|/c|.../i|/else,part 间 \u0000);哈希输入,跨进程稳定
export const streamPartsCanonical = (msg: UIMessage): string => {
  let canonical: string = '';
  for (const p of msg.parts) {
    switch (p.type) {
      case 'text':
        canonical += `t|${p.text}`;
        break;
      case 'reasoning':
        canonical += `r|${p.reasoning}`;
        break;
      case 'tool':
        canonical += `c|${p.toolCallId}|${p.toolName}|${p.input}|`
          + `${(p as { approvalState: { type: string } }).approvalState.type}|${p.output.length}`;
        break;
      case 'image':
        canonical += `i|${p.url}`;
        break;
      default:
        canonical += `${p.type}|`;
        break;
    }
    canonical += '\u0000';
  }
  return canonical;
};

// 默认哈希:FNV-1a 32-bit hex(SDK-free、跨 Node/ArkTS 确定性稳定);
// 真机可注入 SHA-256(sha256HexUtf8)以获得与 Android 同型的 32-hex 指纹。
export const defaultStreamPartsHash = (input: string): string => {
  let h: number = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0');
};

// streamPartsHash:内容指纹(默认 FNV;可注入 SHA-256)
export const streamPartsHash = (
  msg: UIMessage, hashHex: (input: string) => string = defaultStreamPartsHash,
): string => hashHex(streamPartsCanonical(msg));

// shouldCheckpoint:周期 checkpoint 判定(10s + 增长阈值)
//   - 无增长 → false
//   - 无基线(生成首段内容)→ true(首段即落一次,防早期崩溃丢内容)
//   - 距上次 >= 10s 且有增长 → true
//   - 增长 >= 512 字符 → true(Android 合并信号)
export const shouldCheckpoint = (
  nowMs: number, lastCheckpointAtMs: number, prevContentLength: number, curContentLength: number,
): boolean => {
  if (curContentLength <= prevContentLength) return false;
  if (lastCheckpointAtMs <= 0) return true;
  const elapsed: number = nowMs - lastCheckpointAtMs;
  if (elapsed >= STREAM_CHECKPOINT_INTERVAL_MS) return true;
  return (curContentLength - prevContentLength) >= STREAM_CHECKPOINT_MIN_GROWTH_CHARS;
};

// checkpointConversationTail:把 raw 快照投影到会话尾部(updateCurrentMessages:
//   同 id 原位替换、越界新消息追加 — 重复投影不重复 append 节点)。
//   返回可经 ConversationRepository.save 落库的「部分快照」Conversation。
export const checkpointConversationTail = (
  conv: Conversation, snapshot: UIMessage[],
): Conversation => updateCurrentMessages(conv, snapshot);

// regenerate 的 raw snapshot 与页面上的完整 Conversation 不是同一结构：
// user 节点再生会先截断目标之后的节点；assistant 节点再生会在目标位置放一个
// 临时 assistant 占位。checkpoint 必须按目标节点语义投影，否则进程被杀时会把
// 新消息写进错误分支，或把已经截断的旧节点重新带回。
export const checkpointRegenerateConversation = (
  conv: Conversation, targetNodeId: string, snapshot: UIMessage[],
): Conversation => {
  const targetIndex: number = conv.messageNodes.findIndex(
    (node): boolean => node.id === targetNodeId);
  if (targetIndex < 0 || snapshot.length === 0) return conv;

  const targetNode = conv.messageNodes[targetIndex];
  const targetMessage = targetNode.messages[targetNode.selectIndex];
  if (targetMessage.role === 'user') {
    const prefix = conv.messageNodes.slice(0, targetIndex + 1);
    const generated = snapshot.slice(targetIndex + 1).map(toMessageNode);
    return {
      ...conv,
      messageNodes: [...prefix, ...generated],
      updateAt: nowIso(),
    };
  }

  const generated = snapshot[targetIndex];
  if (generated === undefined || generated.role !== 'assistant') return conv;
  const existingIndex: number = targetNode.messages.findIndex(
    (message): boolean => message.id === generated.id);
  const messages: UIMessage[] = [...targetNode.messages];
  let selectIndex: number = targetNode.selectIndex;
  if (existingIndex >= 0) {
    messages[existingIndex] = generated;
    selectIndex = existingIndex;
  } else {
    messages.push(generated);
    selectIndex = messages.length - 1;
  }
  const trailing = snapshot.slice(targetIndex + 1).map(toMessageNode);
  return {
    ...conv,
    messageNodes: [
      ...conv.messageNodes.slice(0, targetIndex),
      { ...targetNode, messages, selectIndex },
      ...trailing,
      ...conv.messageNodes.slice(targetIndex + 1),
    ],
    updateAt: nowIso(),
  };
};
