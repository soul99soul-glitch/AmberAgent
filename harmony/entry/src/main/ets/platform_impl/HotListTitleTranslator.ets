import type { UIMessage, UIMessagePart, AbortSignalLike } from '@amber/deepread-domain';
import { makeSystemMessage, makeUserMessage } from '@amber/deepread-domain';
import { getDeepReadTextRuntime } from '../di/AppContainer.ets';

// 翻译只改变展示标题，原始标题、URL 和聚合身份始终保留。
export const translateHotListTitles = async (titles: string[], signal?: AbortSignalLike): Promise<Record<string, string>> => {
  if (titles.length === 0) return {};
  const runtime = await getDeepReadTextRuntime();
  const messages: UIMessage[] = await runtime.aiClient.generateText({
    model: runtime.model,
    messages: [makeSystemMessage('将新闻标题准确翻译成简体中文，保留产品名和专有名词。只返回一个 JSON 字符串数组，顺序和数量与输入一致。不得添加解释或虚构细节。'),
      makeUserMessage(JSON.stringify(titles))],
    maxSteps: 1,
    signal,
  });
  let content: string = '';
  for (const message of messages) {
    if (message.role !== 'assistant') continue;
    content = message.parts.filter((part: UIMessagePart): boolean => part.type === 'text')
      .map((part: UIMessagePart): string => part.type === 'text' ? part.text : '').join('');
  }
  const translated: string[] = JSON.parse(content.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '').trim()) as string[];
  if (!Array.isArray(translated) || translated.length !== titles.length) throw new Error('标题翻译结果格式不正确');
  const result: Record<string, string> = {};
  for (let index: number = 0; index < titles.length; index++) {
    const title: string = translated[index];
    if (typeof title !== 'string' || title.trim().length === 0) throw new Error('标题翻译结果为空');
    result[titles[index]] = title.trim();
  }
  return result;
};
