// NovelModelAdapter — NovelModelRunning 的真实实现(流式 OpenAI 兼容)
// 复用共享链路 fetchStream → createOpenAiTextStream;经 ModelRegistry 解析 modelId(null→首个)。
// start 立即返回订阅句柄,生成异步跑(首个 emit 在首次 await 之后,保证调用方先订阅)。

import type {
  HttpClient, HttpRequest, HttpResponse, ModelRegistry, ModelConfig,
  NovelModelRunning, NovelModelRequest, NovelModelEvent, NovelModelStream, AbortSignalLike,
  OpenAiTextStreamSink, DecodeChunk,
} from '@amber/deepread-domain';
import { createOpenAiTextStream } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.NovelModel';

// ArkTS 应用层无全局 TextDecoder,用 @kit.ArkTS 的 util.TextDecoder 注入到纯逻辑层
const arktsDecode: DecodeChunk = (chunk: ArrayBuffer): string => {
  const td: util.TextDecoder = util.TextDecoder.create('utf-8');
  return td.decodeToString(new Uint8Array(chunk));
};

interface OpenAiMessage {
  role: string;
  content: string;
}

// 每次运行的中止信号
class NovelCancelSignal implements AbortSignalLike {
  aborted: boolean = false;
  private cbs: Array<() => void> = [];
  addEventListener(_type: string, listener: () => void): void {
    this.cbs.push(listener);
  }
  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    for (let i = 0; i < this.cbs.length; i++) this.cbs[i]();
  }
}

interface RunHandle {
  subs: Set<(e: NovelModelEvent) => void>;
  signal: NovelCancelSignal;
}

class StreamNovelModelAdapter implements NovelModelRunning {
  private http: HttpClient;
  private registry: ModelRegistry;
  private runs: Map<string, RunHandle> = new Map();

  constructor(http: HttpClient, registry: ModelRegistry) {
    this.http = http;
    this.registry = registry;
  }

  private async resolveModel(modelId: string | null): Promise<ModelConfig | null> {
    if (modelId !== null && modelId.length > 0) {
      const hit: ModelConfig | null = await this.registry.get(modelId);
      if (hit !== null) return hit;
    }
    const list: ModelConfig[] = await this.registry.list();
    return list.length > 0 ? list[0] : null;
  }

  async validate(modelId: string | null): Promise<void> {
    const model: ModelConfig | null = await this.resolveModel(modelId);
    if (model === null) throw new Error('尚未配置模型，请到 设置 → 模型库 添加');
    if (model.apiKey.length === 0) throw new Error('模型缺少 API Key');
  }

  start(request: NovelModelRequest): NovelModelStream {
    const subs = new Set<(e: NovelModelEvent) => void>();
    const signal: NovelCancelSignal = new NovelCancelSignal();
    const handle: RunHandle = { subs: subs, signal: signal };
    this.runs.set(request.runId, handle);
    const emit = (e: NovelModelEvent): void => {
      subs.forEach(cb => { cb(e); });
    };
    void this.run(request, handle, emit);
    return {
      subscribe: (cb: (e: NovelModelEvent) => void): () => void => {
        subs.add(cb);
        return (): void => { subs.delete(cb); };
      },
    };
  }

  cancel(runId: string): void {
    const handle: RunHandle | undefined = this.runs.get(runId);
    if (handle !== undefined) {
      handle.signal.abort();
    }
  }

  private async run(
    request: NovelModelRequest, handle: RunHandle, emit: (e: NovelModelEvent) => void,
  ): Promise<void> {
    try {
      const model: ModelConfig | null = await this.resolveModel(request.modelId);
      if (model === null) {
        emit({ kind: 'failed', message: '尚未配置模型' });
        return;
      }
      if (model.apiKey.length === 0) {
        emit({ kind: 'failed', message: '模型缺少 API Key' });
        return;
      }

      const messages: OpenAiMessage[] = [
        { role: 'system', content: request.systemPrompt },
        { role: 'user', content: request.userPrompt },
      ];
      const parts: string[] = [];
      for (let i = 0; i < messages.length; i++) {
        parts.push(`{"role":"${messages[i].role}","content":${JSON.stringify(messages[i].content)}}`);
      }
      let body: string = `{"model":${JSON.stringify(model.model)},"messages":[${parts.join(',')}],"stream":true`;
      if (request.maxOutputTokens !== null) {
        body += `,"max_tokens":${request.maxOutputTokens}`;
      }
      body += '}';

      const httpReq: HttpRequest = {
        url: `${model.baseUrl}/v1/chat/completions`,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${model.apiKey}`,
        },
        body: body,
      };

      hilog.info(DOMAIN, TAG, 'novel generate start model=%{public}s', model.model);
      const sink: OpenAiTextStreamSink = createOpenAiTextStream(
        (deltaText: string, _reasoningText: string): void => {
          if (deltaText.length > 0) emit({ kind: 'delta', text: deltaText });
        },
        undefined,
        arktsDecode,
      );

      const resp: HttpResponse = await this.http.fetchStream(httpReq, {
        onChunk: (chunk: ArrayBuffer, end: boolean): void => {
          sink.feed(chunk);
          if (end) sink.flush();
        },
        signal: handle.signal,
      });

      // fetchStream 已 resolve 说明流正常结束(dataEnd 已到)。此时即便 signal
      // 在此刻被 abort(极窄窗口),内容也已完整,按 completed 处理,避免误标 interrupted。
      if (resp.status !== 200 && resp.status !== 0) {
        emit({ kind: 'failed', message: `HTTP ${resp.status}` });
        return;
      }
      emit({ kind: 'completed' });
    } catch (e) {
      // fetchStream reject(被 abort 触发 destroy / 网络/HTTP 错误)走这里
      if (handle.signal.aborted) emit({ kind: 'failed', message: 'cancelled' });
      else emit({ kind: 'failed', message: String(e) });
    } finally {
      this.runs.delete(request.runId);
    }
  }
}

export const createNovelModelAdapter = (http: HttpClient, registry: ModelRegistry): NovelModelRunning => {
  return new StreamNovelModelAdapter(http, registry);
};
