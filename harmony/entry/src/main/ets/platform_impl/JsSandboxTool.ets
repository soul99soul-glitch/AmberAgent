// JsSandboxTool — javascript_execute 工具(隐藏隔离 Web 沙箱;无网络无文件)
//
// 沙箱模型(WebMountSession 同款隐藏 Web 模式):
//   - 独立空白页 about:blank;执行前遮蔽 fetch/XMLHttpRequest/WebSocket(无网络);
//   - console.log/warn/error 收集回传;表达式结果 String 化(≤8k 截断);
//   - 超时(默认 10s,上限 30s)经 Promise.race + controller.stop() 尽力终止;
//   - 共享单 Web,执行串行(队列锁);无 DOM 持久状态(每次 fresh wrapper 环境)。
// 门:assistant.localTools 勾选 'javascript_engine'(既有 localTools 门控位)。

import { webview } from '@kit.ArkWeb';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { AgentTool, JsonValue, UIMessagePart, InputSchemaObj, JsonObject } from '@amber/chat-domain';
import { makeAgentTool, makeInputSchemaObj, inputStringValue } from '@amber/chat-domain';

const DEFAULT_TIMEOUT_MS: number = 10000;
const MAX_TIMEOUT_MS: number = 30000;
const MAX_RESULT_CHARS: number = 8192;
const MAX_CONSOLE_LINES: number = 40;

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.JsSandbox';

interface SettleWaiter {
  id: number;
  settle: () => void;
}

class JsSandboxSession {
  private controller: webview.WebviewController = new webview.WebviewController();
  private mounted: boolean = false;
  private mountWaiters: SettleWaiter[] = [];
  private nextWaiterId: number = 1;
  private lock: Promise<void> = Promise.resolve();

  public getController(): webview.WebviewController {
    return this.controller;
  }

  public markAttached(): void {
    this.mounted = true;
    const waiters: SettleWaiter[] = this.mountWaiters;
    this.mountWaiters = [];
    for (const w of waiters) w.settle();
  }

  public markDetached(): void {
    this.mounted = false;
  }

  private waitForMount(): Promise<void> {
    if (this.mounted) return Promise.resolve();
    return new Promise<void>((resolve: () => void, reject: (e: Error) => void): void => {
      const id: number = this.nextWaiterId;
      this.nextWaiterId = id + 1;
      this.mountWaiters.push({ id, settle: resolve });
      setTimeout((): void => {
        const idx: number = this.mountWaiters.findIndex((w: SettleWaiter): boolean => w.id === id);
        if (idx >= 0) {
          this.mountWaiters.splice(idx, 1);
          reject(new Error('JsSandbox host mount timeout'));
        }
      }, 5000);
    });
  }

  // 串行执行(共享单 Web 实例)
  public evaluate(code: string, timeoutMs: number): Promise<string> {
    const next: Promise<string> = this.lock.then((): Promise<string> => this.evaluateNow(code, timeoutMs));
    this.lock = next.then((): void => {}, (): void => {});
    return next;
  }

  private async evaluateNow(code: string, timeoutMs: number): Promise<string> {
    await this.waitForMount();
    const wrapped: string = wrapScript(code);
    const timeout: Promise<string> = new Promise<string>((_res, reject: (e: Error) => void): void => {
      setTimeout((): void => { reject(new Error(`执行超时(${timeoutMs}ms)`)); }, timeoutMs);
    });
    let raw: string;
    try {
      raw = await Promise.race([this.controller.runJavaScript(wrapped), timeout]);
    } catch (e) {
      // 超时/异常:尽力复位(stop 中断当前执行),下一条不受影响
      try { this.controller.stop(); } catch { /* 已销毁 */ }
      throw e instanceof Error ? e : new Error(String(e));
    }
    return raw;
  }
}

// L01 同规:动态代码只经 JSON 字符串字面量注入
const jsonString = (value: string): string => JSON.stringify(value)
  .replace(/\u2028/g, '\\u2028')
  .replace(/\u2029/g, '\\u2029');

const wrapScript = (code: string): string => `(function(){
  const __console = [];
  const push = (level, args) => {
    if (__console.length >= ${MAX_CONSOLE_LINES}) return;
    __console.push(level + ': ' + args.map((a) => {
      try { return typeof a === 'object' ? JSON.stringify(a) : String(a); } catch (_e) { return String(a); }
    }).join(' ').slice(0, 500));
  };
  console = {
    log: (...a) => push('log', a),
    warn: (...a) => push('warn', a),
    error: (...a) => push('error', a),
    info: (...a) => push('info', a),
  };
  // 无网络:遮蔽全部联网 API(同 origin 空白页,无凭据)
  window.fetch = undefined;
  window.XMLHttpRequest = undefined;
  window.WebSocket = undefined;
  window.EventSource = undefined;
  window.navigator.sendBeacon = undefined;
  try {
    const __r = eval(${jsonString(code)});
    const __s = __r === undefined ? null
      : (typeof __r === 'object' ? JSON.stringify(__r) : String(__r));
    return {
      ok: true,
      result: __s === null ? null : __s.slice(0, ${MAX_RESULT_CHARS}),
      console: __console,
    };
  } catch (e) {
    return { ok: false, error: String(e), console: __console };
  }
})()`;

let globalSandbox: JsSandboxSession | null = null;

export const getJsSandboxSession = (): JsSandboxSession => {
  if (globalSandbox === null) {
    globalSandbox = new JsSandboxSession();
    hilog.info(DOMAIN, TAG, 'sandbox session created');
  }
  return globalSandbox;
};

// JsSandboxHost — 沙箱宿主(1x1 移出屏;不需要真实渲染,JS 与渲染树无关)
@Component
export struct JsSandboxHost {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  private session: JsSandboxSession = getJsSandboxSession();

  build(): void {
    Web({ src: 'about:blank', controller: this.session.getController() })
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width(1).height(1)
      .position({ x: -2000, y: -2000 })
      .onControllerAttached((): void => {
        this.session.markAttached();
      })
      .onDetach((): void => {
        this.session.markDetached();
      })
  }
}

export const createJavascriptExecuteTool = (): AgentTool =>
  makeAgentTool({
    name: 'javascript_execute',
    description: 'Execute a JavaScript expression in a sandboxed blank page: no network '
      + '(fetch/XHR/WebSocket disabled), no file access, no persistent state between calls. '
      + 'Returns the expression result and captured console output. Use for quick data '
      + 'transformation, computation, or JSON shaping — not for long-running programs.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'code': { type: 'string', description: 'JavaScript expression or statements to evaluate.' },
      'timeout_ms': { type: 'number', description: 'Max execution time in ms (default 10000, max 30000).' },
    } as JsonObject, ['code']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const code: string = (inputStringValue(input, 'code') ?? '').trim();
      if (code.length === 0) throw new Error('code is required');
      const timeoutRaw: string | null = inputStringValue(input, 'timeout_ms');
      const timeoutParsed: number = timeoutRaw !== null ? Number(timeoutRaw) : DEFAULT_TIMEOUT_MS;
      const timeoutMs: number = Math.min(
        Math.max(Number.isFinite(timeoutParsed) ? Math.trunc(timeoutParsed) : DEFAULT_TIMEOUT_MS, 100),
        MAX_TIMEOUT_MS);
      const raw: string = await getJsSandboxSession().evaluate(code, timeoutMs);
      return [{ type: 'text', text: raw, metadata: null }];
    },
  });
