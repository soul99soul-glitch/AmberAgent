// NovelModelAdapter — NovelModelRunning 的真实实现(流式 OpenAI 兼容)
// 复用共享链路 fetchStream → createOpenAiTextStream;经 ModelRegistry 解析 modelId(null→首个)。
// start 立即返回订阅句柄,生成异步跑(首个 emit 在首次 await 之后,保证调用方先订阅)。

import type {
  HttpClient, HttpRequest, HttpResponse, ModelRegistry, ModelConfig,
  NovelModelRunning, NovelModelRequest, NovelModelEvent, NovelModelStream, AbortSignalLike,
} from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.NovelModel';

interface OpenAiMessage {
  role: string;
  content: string;
}

// 非流式响应形态(chat/completions stream:false)
interface NonStreamMessage {
  content?: string | null;
}
interface NonStreamChoice {
  message?: NonStreamMessage;
}
interface NonStreamResp {
  choices?: NonStreamChoice[];
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

class NonStreamNovelModelAdapter implements NovelModelRunning {
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
      // N1:fetchStream 在 HarmonyOS 7.0.102 NetStack 层静默挂死(深读 P8 实证),
      // 小说生成统一走非流式;事件端口不变(delta 不再逐字,一次性 replace)。
      let body: string = `{"model":${JSON.stringify(model.model)},"messages":[${parts.join(',')}],"stream":false`;
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

      hilog.info(DOMAIN, TAG, 'novel generate start (non-stream) model=%{public}s', model.model);
      const resp: HttpResponse = await this.http.fetch(httpReq, { signal: handle.signal });
      if (handle.signal.aborted) {
        emit({ kind: 'failed', message: 'cancelled' });
        return;
      }
      if (resp.status < 200 || resp.status >= 300) {
        emit({ kind: 'failed', message: `HTTP ${resp.status}: ${resp.body.substring(0, 160)}` });
        return;
      }
      const parsed: NonStreamResp = JSON.parse(resp.body) as NonStreamResp;
      const content: string = parsed.choices !== undefined && parsed.choices.length > 0
        && parsed.choices[0].message !== undefined
        && parsed.choices[0].message.content !== null
        ? parsed.choices[0].message.content as string : '';
      if (content.length === 0) {
        emit({ kind: 'failed', message: '模型返回空内容' });
        return;
      }
      emit({ kind: 'delta', text: content });
      emit({ kind: 'replace', text: content });
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
  return new NonStreamNovelModelAdapter(http, registry);
};
