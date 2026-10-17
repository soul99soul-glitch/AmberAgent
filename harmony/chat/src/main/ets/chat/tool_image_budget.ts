// tool_image_budget — 请求期的工具图片预算。
//
// 每个模型步骤都会重发整段历史;手机/网页截图是 data URL,不设预算会让
// 50 步任务每步重复上传全部截图。规则(对齐 iOS IOSToolImageRequestBudget):
//   - 只保留最近 keepRecent 个带真实图片的工具结果;
//   - 更早结果里的图片换成稳定文本占位,同一位置的文字输出保留;
//   - 缺失 blob(文件丢失)一律换成文本,不占保留窗口;
//   - 占位结果继续计入步号,保证前缀稳定,不会每轮重新编号;
//   - 用户附件/生成图片属于顶层 message part,不改。
//
// 只改请求副本,不写回持久化消息。

import type {
  UIMessage, UIMessagePart, UIMessagePartImage, UIMessagePartTool,
} from './message.ts';
import { isMissingBlobImage } from './conversation_blobs.ts';

export const TOOL_IMAGE_KEEP_RECENT: number = 2;
export const TOOL_IMAGE_PLACEHOLDER_PREFIX: string = '[较早的截图已省略';
export const MISSING_TOOL_IMAGE_TEXT: string = '[截图文件缺失]';

export const toolImagePlaceholderText = (step: number): string =>
  `${TOOL_IMAGE_PLACEHOLDER_PREFIX}：第 ${step} 步]`;

const isPlaceholderText = (part: UIMessagePart): boolean =>
  part.type === 'text' && part.text.startsWith(TOOL_IMAGE_PLACEHOLDER_PREFIX);

const scanToolImages = (output: UIMessagePart[]): {
  real: number; missing: number; dropped: boolean;
} => {
  let real: number = 0;
  let missing: number = 0;
  let dropped: boolean = false;
  for (const part of output) {
    if (part.type === 'image') {
      if (isMissingBlobImage(part)) missing++;
      else real++;
    } else if (isPlaceholderText(part)) {
      dropped = true;
    }
  }
  return { real, missing, dropped };
};

type LocatedKind = 'image' | 'missing' | 'dropped';

interface LocatedToolResult {
  messageIndex: number;
  partIndex: number;
  kind: LocatedKind;
  hasMissing: boolean;
}

const transformToolOutput = (
  output: UIMessagePart[], placeholder: string | null,
): UIMessagePart[] => {
  let changed: boolean = false;
  const next: UIMessagePart[] = output.map((part: UIMessagePart): UIMessagePart => {
    if (part.type !== 'image') return part;
    changed = true;
    if (isMissingBlobImage(part)) {
      return { type: 'text', text: MISSING_TOOL_IMAGE_TEXT, metadata: null };
    }
    if (placeholder === null) return part;
    return { type: 'text', text: placeholder, metadata: null };
  });
  return changed ? next : output;
};

const budgetToolPart = (
  tool: UIMessagePartTool, placeholder: string | null, stripMissing: boolean,
): UIMessagePartTool => {
  const nextOutput: UIMessagePart[] = transformToolOutput(
    tool.output, stripMissing ? null : placeholder);
  if (nextOutput === tool.output) return tool;
  return {
    type: 'tool', toolCallId: tool.toolCallId, toolName: tool.toolName,
    input: tool.input, output: nextOutput, approvalState: tool.approvalState, metadata: tool.metadata,
  };
};

export const applyToolImageRequestBudget = (
  messages: UIMessage[], keepRecent: number = TOOL_IMAGE_KEEP_RECENT,
): UIMessage[] => {
  const located: LocatedToolResult[] = [];
  for (let messageIndex: number = 0; messageIndex < messages.length; messageIndex++) {
    const parts: UIMessagePart[] = messages[messageIndex].parts;
    for (let partIndex: number = 0; partIndex < parts.length; partIndex++) {
      const part: UIMessagePart = parts[partIndex];
      if (part.type !== 'tool') continue;
      const scan = scanToolImages(part.output);
      if (scan.real > 0) {
        located.push({ messageIndex, partIndex, kind: 'image', hasMissing: scan.missing > 0 });
      } else if (scan.missing > 0) {
        located.push({ messageIndex, partIndex, kind: 'missing', hasMissing: true });
      } else if (scan.dropped) {
        located.push({ messageIndex, partIndex, kind: 'dropped', hasMissing: false });
      }
    }
  }
  const imageOrdinals: number[] = [];
  for (let index: number = 0; index < located.length; index++) {
    if (located[index].kind === 'image') imageOrdinals.push(index);
  }
  const keep: Set<number> = new Set<number>(
    imageOrdinals.slice(Math.max(imageOrdinals.length - Math.max(keepRecent, 0))));
  const replacements: Map<string, string | null> = new Map<string, string | null>();
  for (let index: number = 0; index < located.length; index++) {
    const entry: LocatedToolResult = located[index];
    const key: string = `${entry.messageIndex}:${entry.partIndex}`;
    if (entry.kind === 'image' && !keep.has(index)) {
      replacements.set(key, toolImagePlaceholderText(index + 1));
    } else if (entry.kind === 'image' && entry.hasMissing) {
      replacements.set(key, null);
    } else if (entry.kind === 'missing') {
      replacements.set(key, null);
    }
  }
  if (replacements.size === 0) return messages;
  return messages.map((message: UIMessage, messageIndex: number): UIMessage => {
    let changed: boolean = false;
    const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart, partIndex: number): UIMessagePart => {
      if (part.type !== 'tool') return part;
      const key: string = `${messageIndex}:${partIndex}`;
      if (!replacements.has(key)) return part;
      changed = true;
      return budgetToolPart(part, replacements.get(key) ?? null, replacements.get(key) === null);
    });
    if (!changed) return message;
    return {
      id: message.id, role: message.role, parts, annotations: message.annotations,
      createdAt: message.createdAt, finishedAt: message.finishedAt,
      modelId: message.modelId, usage: message.usage, translation: message.translation,
    };
  });
};

export const countToolImageParts = (messages: UIMessage[]): number => {
  let count: number = 0;
  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type !== 'tool') continue;
      for (const child of part.output) {
        if (child.type === 'image' && !isMissingBlobImage(child as UIMessagePartImage)) count++;
      }
    }
  }
  return count;
};
