// ModelCouncilRunner — ModelCouncilTextRunner 的真实实现(流式 OpenAI 兼容)
// 复用共享链路:HttpClient.fetchStream → createOpenAiTextStream(SSE→文本)。
// onChunk 回传累积文本,按 ~32ms 合并,结束强制 emit。ArkTS:class implements。

import type {
  HttpClient, HttpRequest, HttpResponse,
  ModelCouncilTextRunner, CouncilGenerateRequest, ModelCouncilTextResult,
  OpenAiTextStreamSink, DecodeChunk,
} from '@amber/deepread-domain';
import { createOpenAiTextStream } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.CouncilRunner';
const LIVE_EMIT_INTERVAL_MS: number = 32;

// SseAssembler already retains incomplete UTF-8 byte sequences between feed
// calls; this decoder only receives complete sequences.
const arktsDecode: DecodeChunk = (chunk: ArrayBuffer): string => {
  const td: util.TextDecoder = util.TextDecoder.create('utf-8');
  return td.decodeToString(new Uint8Array(chunk));
};

interface OpenAiMessage {
  role: string;
  content: string;
}

class StreamModelCouncilRunner implements ModelCouncilTextRunner {
  private http: HttpClient;

  constructor(http: HttpClient) {
    this.http = http;
  }

  async generate(req: CouncilGenerateRequest): Promise<ModelCouncilTextResult> {
    const baseUrl: string = req.model.baseUrl;
    const apiKey: string = req.model.apiKey;
    const warnings: string[] = [];
    if (apiKey.length === 0) {
      // 无 key 是配置错误,不是空产出:必须抛错(manager 会把空文本记 completed)
      throw new Error(`model ${req.model.id} has no API key`);
    }

    const messages: OpenAiMessage[] = [
      { role: 'system', content: req.systemPrompt },
      { role: 'user', content: req.userPrompt },
    ];
    // 手动拼 JSON body(规避 ArkTS 对象字面量 stringify 问题)
    const parts: string[] = [];
    for (let i = 0; i < messages.length; i++) {
      parts.push(`{"role":"${messages[i].role}","content":${JSON.stringify(messages[i].content)}}`);
    }
    let body: string = `{"model":${JSON.stringify(req.model.model)},"messages":[${parts.join(',')}],"stream":true`;
    if (req.temperature !== null) {
      body += `,"temperature":${req.temperature}`;
    }
    body += '}';

    const httpReq: HttpRequest = {
      url: `${baseUrl}/v1/chat/completions`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`,
      },
      body: body,
    };

    let cumulative: string = '';
    let lastEmit: number = 0;

    const sink: OpenAiTextStreamSink = createOpenAiTextStream(
      (deltaText: string, _reasoningText: string): void => {
        cumulative += deltaText;
        const now: number = Date.now();
        if (now - lastEmit >= LIVE_EMIT_INTERVAL_MS) {
          lastEmit = now;
          req.onChunk(cumulative);
        }
      },
      undefined,
      arktsDecode,
    );

    hilog.info(DOMAIN, TAG, 'generate start model=%{public}s', req.model.model);
    const resp: HttpResponse = await this.http.fetchStream(httpReq, {
      onChunk: (chunk: ArrayBuffer, end: boolean): void => {
        sink.feed(chunk);
        if (end) {
          sink.flush();
          req.onChunk(cumulative); // 结束强制 emit 最终全文
        }
      },
      signal: req.signal ?? undefined,
    });

    if (resp.status !== 200 && resp.status !== 0) {
      // 非 2xx 是请求失败:返回空文本会被 manager 记为 completed 空产出
      hilog.warn(DOMAIN, TAG, 'generate non-200: %{public}d', resp.status);
      throw new Error(`HTTP ${resp.status}`);
    }
    req.onChunk(cumulative);
    return { text: cumulative, warnings: warnings };
  }
}

export const createModelCouncilRunner = (http: HttpClient): ModelCouncilTextRunner => {
  return new StreamModelCouncilRunner(http);
};
