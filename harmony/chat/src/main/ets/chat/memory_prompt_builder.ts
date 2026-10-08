// memory_prompt_builder — MemoryPromptBuilder.kt 逐字移植
// Android 锚点:core/memory/prompt/MemoryPromptBuilder.kt 全文(buildMemoryContext)
// 安全过滤:core/memory/safety/MemoryContentSafety.kt 全文(internal → 导出供后续
//   extraction 切片使用)

import { isMemoryActive, memoryDateLabel, memoryLocalDate } from './memory_lifecycle.ts';
import type { MemoryRecord } from './memory_models.ts';

// buildMemoryContext(MemoryPromptBuilder.kt:8-40)— buildString 逐 append 忠实:
//   "<memory_context>\n" + 引导行\n + 逐条 "- [scope/kind(/pinned)] content"
//   (debug 追加 " (id=N, confidence=%.2f[, details])";非末条换行)+ "\n</memory_context>"
export const buildMemoryContext = (
  records: MemoryRecord[],
  debug: boolean = false,
  debugDetails: Record<number, string> = {},
  now: number = Date.now(),
): string => {
  records = records.filter((record: MemoryRecord): boolean => isMemoryActive(record, now));
  if (records.length === 0) return '';
  let out: string = '';
  out += '<memory_context>\n';
  out += `今天是 ${memoryLocalDate(now)}。相对时间以记忆的记录日期为准。\n`;
  out += '以下是与当前请求相关的记忆；若与当前用户消息冲突，以当前用户消息为准。\n';
  for (let index = 0; index < records.length; index++) {
    const record: MemoryRecord = records[index];
    out += '- ';
    out += '[';
    out += record.scope;
    out += '/';
    out += record.kind;
    if (record.pinned) out += '/pinned';
    out += '] ';
    // Kotlin replace("\n"," ") = 全量替换(JS replace 仅首个 → split/join)
    out += record.content.trim().split('\n').join(' ');
    out += ` [memory:${record.id}] (${memoryDateLabel(record)})`;
    if (debug) {
      out += ' (id=';
      out += String(record.id);
      out += ', confidence=';
      out += record.confidence.toFixed(2); // "%.2f".format
      const details: string | undefined = debugDetails[record.id];
      if (details !== undefined) {
        out += ', ';
        out += details;
      }
      out += ')';
    }
    if (index !== records.length - 1) out += '\n';
  }
  out += '\n使用某条记忆时附上它的 [[memory:编号]] 标记，只引用实际用于回答的记忆。\n';
  out += '</memory_context>';
  return out;
};

// ===== MemoryContentSafety.kt 全文 =====

export const SENSITIVE_MEMORY_TERMS: readonly string[] = Object.freeze([
  '身份证', '护照', '银行卡', '密码', '宗教', '政治观点',
  'criminal', 'password', 'passport', 'credit card', 'religion', 'sexual',
]);

export const isSensitiveMemoryContent = (content: string): boolean => {
  const lower: string = content.toLowerCase();
  for (const term of SENSITIVE_MEMORY_TERMS) {
    if (lower.indexOf(term) >= 0) return true;
  }
  return false;
};
