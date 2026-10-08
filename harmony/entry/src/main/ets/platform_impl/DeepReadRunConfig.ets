import type { Storage } from '@amber/deepread-domain';
import type { AiConfig } from './OpenAiCompatibleAiClient.ets';

export interface DeepReadRunConfig extends AiConfig {
  readonly model: string;
  readonly playbookMarkdown: string;
}

// 每个任务捕获一次配置；下一轮读取新设置，已有工具循环使用自己的快照。
export const captureDeepReadRunConfig = async (
  storage: Storage,
  readPlaybook: () => Promise<string>,
): Promise<DeepReadRunConfig> => {
  const boardModel: string = await storage.get<string>('deepread_model_id', '');
  const model: string = boardModel.length > 0
    ? boardModel
    : await storage.get<string>('ai_model', 'deepseek-chat');
  const baseUrl: string = await storage.get<string>('ai_base_url', 'https://api.deepseek.com');
  const apiKey: string = await storage.get<string>('ai_api_key', '');
  const playbookMarkdown: string = await readPlaybook();
  return { model, baseUrl, apiKey, playbookMarkdown };
};
