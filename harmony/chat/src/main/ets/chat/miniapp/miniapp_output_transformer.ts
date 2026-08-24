// miniapp_output_transformer — onGenerationFinish 解析 → 保存 → 改写 assistant 消息
//
// Android 基准: app/.../core/ai/transformers/MiniAppOutputTransformer.kt(全文 122 行)
// 偏差:
//   - ctx.settings.agentRuntime.miniApp.enabled → deps.enabled()(getter 注入)
//   - repository 注入 → deps.repository(MiniAppRepository Port)
//   - UIMessagePart.MiniApp 构造字段与 message.ts:72-83 UIMessagePartMiniApp 一致
//     (鸿蒙 part 有 metadata 字段,Android 无 → 置 null)
//   - sourceMessageId: Android message.id.toString() → 鸿蒙 message.id(已是 string)
//   - 流式 visualTransform 不做(Android 同)

import type {
  UIMessage, UIMessagePart, UIMessagePartMiniApp, UIMessagePartText,
} from '../message.ts';
import type { OutputMessageTransformer, TransformerContext } from '../transformer_pipeline.ts';
import { MiniAppOutputParser } from './miniapp_output_parser.ts';
import type { MiniAppGeneratedOutput, MiniAppRecord } from './miniapp_models.ts';
import { isExplicitMiniAppRequest, revisionAppId, revisionVersion } from './miniapp_prompt_transformer.ts';
import type { MiniAppRepository } from './miniapp_repository.ts';
import { miniAppToCardRef } from './miniapp_repository.ts';

export interface MiniAppOutputTransformerDeps {
  enabled: () => boolean;
  repository: MiniAppRepository;
}

// MiniAppOutputTransformer.kt:86-88
export const mightContainMiniApp = (text: string): boolean =>
  text.includes('"html"') && text.includes('"title"') && text.includes('"description"');

// MiniAppOutputTransformer.kt:90-99(修订 change note 提取)
export const revisionChangeNote = (text: string): string => {
  const marker: string = '用户修改意见：';
  const after: string = text.includes(marker)
    ? text.substring(text.indexOf(marker) + marker.length)
    : text;
  const lines: string[] = [];
  for (const line of after.split('\n')) {
    if (line.startsWith('请基于')) break;
    lines.push(line);
  }
  const joined: string = lines.join('\n').trim();
  return (joined.length === 0 ? 'MiniApp revision' : joined).slice(0, 240);
};

const buildStatusText = (revision: boolean, record: MiniAppRecord): string =>
  revision ? `已更新小应用：${record.title} v${record.version}` : `已生成小应用：${record.title}`;

const buildMiniAppPart = (record: MiniAppRecord): UIMessagePartMiniApp => {
  const ref = miniAppToCardRef(record);
  return {
    type: 'mini_app',
    appId: ref.appId,
    title: ref.title,
    description: ref.description,
    iconEmoji: ref.iconEmoji,
    category: ref.category,
    permissions: ref.permissions,
    htmlHash: ref.htmlHash,
    version: ref.version,
    metadata: null,
  };
};

const buildFailureTextPart = (textPart: UIMessagePartText): UIMessagePartText => ({
  type: 'text',
  text: '小应用更新失败：目标小应用不存在，或已经被更新。请打开最新的小应用卡片后重新点击「修改」。',
  metadata: textPart.metadata,
});

// ===== Transformer =====

export const createMiniAppOutputTransformer = (deps: MiniAppOutputTransformerDeps): OutputMessageTransformer => {
  const parser: MiniAppOutputParser = new MiniAppOutputParser();

  return {
    async onGenerationFinish(
      ctx: TransformerContext, messages: UIMessage[],
    ): Promise<UIMessage[]> {
      if (!deps.enabled()) return messages;
      let assistantIndex: number = -1;
      for (let i: number = messages.length - 1; i >= 0; i--) {
        if (messages[i].role === 'assistant') {
          assistantIndex = i;
          break;
        }
      }
      if (assistantIndex < 0) return messages;
      let lastUserIndex: number = -1;
      for (let i: number = assistantIndex - 1; i >= 0; i--) {
        if (messages[i].role === 'user') {
          lastUserIndex = i;
          break;
        }
      }
      const lastUserText: string = lastUserIndex >= 0
        ? messages[lastUserIndex].parts
          .filter((p: UIMessagePart): p is UIMessagePartText => p.type === 'text')
          .map((p: UIMessagePartText): string => p.text)
          .join('\n')
        : '';
      if (!isExplicitMiniAppRequest(lastUserText)) return messages;
      const message: UIMessage = messages[assistantIndex];
      if (message.parts.some((p: UIMessagePart): boolean => p.type === 'mini_app')) return messages;
      let textPartIndex: number = -1;
      for (let i: number = message.parts.length - 1; i >= 0; i--) {
        if (message.parts[i].type === 'text') {
          textPartIndex = i;
          break;
        }
      }
      if (textPartIndex < 0) return messages;
      const textPart: UIMessagePartText = message.parts[textPartIndex] as UIMessagePartText;
      if (!mightContainMiniApp(textPart.text)) return messages;
      const output: MiniAppGeneratedOutput | null = parser.parseOrNull(textPart.text);
      if (output === null) return messages;
      const appId: string | null = revisionAppId(lastUserText);
      const version: number | null = revisionVersion(lastUserText);
      const record: MiniAppRecord | null = appId !== null
        ? await deps.repository.saveRevision(
          appId,
          output,
          version,
          message.id,
          revisionChangeNote(lastUserText),
        )
        : await deps.repository.saveGenerated(output, null, message.id);
      if (record === null) {
        // revisionFailed(MiniAppOutputTransformer.kt:101-121)
        const failedParts: UIMessagePart[] = message.parts.map(
          (part: UIMessagePart, index: number): UIMessagePart => {
            if (index === textPartIndex && part.type === 'text') {
              return buildFailureTextPart(part);
            }
            return part;
          });
        const failed: UIMessage = { ...message, parts: failedParts };
        return messages.map((m: UIMessage, index: number): UIMessage =>
          index === assistantIndex ? failed : m);
      }
      const statusText: string = buildStatusText(appId !== null, record);
      const updatedParts: UIMessagePart[] = [];
      message.parts.forEach((part: UIMessagePart, index: number): void => {
        if (index === textPartIndex) {
          updatedParts.push({ type: 'text', text: statusText, metadata: textPart.metadata });
          updatedParts.push(buildMiniAppPart(record));
        } else {
          updatedParts.push(part);
        }
      });
      const updated: UIMessage = { ...message, parts: updatedParts };
      return messages.map((m: UIMessage, index: number): UIMessage =>
        index === assistantIndex ? updated : m);
    },
  };
};
