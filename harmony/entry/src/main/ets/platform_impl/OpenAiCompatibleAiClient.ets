// OpenAiCompatibleAiClient — AiClient 契约的真实实现
// OpenAI 兼容 HTTP。对照设计 §4.8/§8.5。ArkTS 兼容:class implements AiClient。
// 调真实 LLM(需配 API key)。无 key 时返回空 → deepread 走 fallback。

import type { AiClient, GenerateTextParams, HttpClient, Storage, HttpRequest, HttpResponse, UIMessage } from '@amber/deepread-domain';
import { makeAssistantMessage } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.AiClient';

// 显式 interface(ArkTS 不允许对象字面量类型)
interface OpenAiMessage {
  role: string;
  content: string;
}

interface AiConfig {
  baseUrl: string;
  apiKey: string;
}

interface OpenAiChoice {
  message: OpenAiMessage;
}

interface OpenAiResponse {
  choices: OpenAiChoice[];
}

class OpenAiCompatibleAiClient implements AiClient {
  private http: HttpClient;
  private storage: Storage;

  constructor(http: HttpClient, storage: Storage) {
    this.http = http;
    this.storage = storage;
  }

  private async getConfig(): Promise<AiConfig> {
    const baseUrl: string = await this.storage.get<string>('ai_base_url', 'https://api.deepseek.com');
    const apiKey: string = await this.storage.get<string>('ai_api_key', '');
    const config: AiConfig = { baseUrl: baseUrl, apiKey: apiKey };
    return config;
  }

  private messagesToOpenAi(messages: UIMessage[]): OpenAiMessage[] {
    const result: OpenAiMessage[] = [];
    for (let i = 0; i < messages.length; i++) {
      const m: UIMessage = messages[i];
      let text: string = '';
      for (let j = 0; j < m.parts.length; j++) {
        const part = m.parts[j];
        if (part.kind === 'text') {
          text += part.text;
        }
      }
      const role: string = m.role === 'assistant' ? 'assistant' : m.role === 'user' ? 'user' : 'system';
      const msg: OpenAiMessage = { role: role, content: text };
      result.push(msg);
    }
    return result;
  }

  async generateText(params: GenerateTextParams): Promise<UIMessage[]> {
    const config: AiConfig = await this.getConfig();
    if (config.apiKey.length === 0) {
      hilog.info(DOMAIN, TAG, 'no API key, returning empty (fallback)');
      return [];
    }
    const oaiMessages: OpenAiMessage[] = this.messagesToOpenAi(params.messages);
    // 手动序列化(避免对象字面量 JSON.stringify 问题)
    const parts: string[] = [];
    for (let i = 0; i < oaiMessages.length; i++) {
      parts.push(`{"role":"${oaiMessages[i].role}","content":${JSON.stringify(oaiMessages[i].content)}}`);
    }
    const bodyStr: string = `{"model":"${params.model}","messages":[${parts.join(',')}],"stream":false}`;

    const req: HttpRequest = {
      url: `${config.baseUrl}/v1/chat/completions`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.apiKey}`,
      },
      body: bodyStr,
    };
    const resp: HttpResponse = await this.http.fetch(req);
    if (resp.status !== 200) {
      throw new Error(`AiClient ${resp.status}: ${resp.body.substring(0, 200)}`);
    }
    const data: OpenAiResponse = JSON.parse(resp.body) as OpenAiResponse;
    const content: string = data.choices.length > 0 ? data.choices[0].message.content : '';
    const result: UIMessage[] = [...params.messages, makeAssistantMessage([{ kind: 'text', text: content }])];
    return result;
  }
}

export const createOpenAiCompatibleAiClient = (http: HttpClient, storage: Storage): AiClient => {
  return new OpenAiCompatibleAiClient(http, storage);
};
