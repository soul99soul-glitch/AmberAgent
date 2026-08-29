// WebMountTools — WebMount 工具集(MVP 9 件,命名对齐 Android wm_* 全家)
//
// Android 基准: feature/webmount/tools(30+ 工具,多 session/tab);鸿蒙 MVP 单会话:
//   wm_open / wm_state / wm_extract / wm_click / wm_type / wm_scroll /
//   wm_screenshot / wm_eval。未移植件(tap/keys/select/find/wait/back/forward/
//   tab_*/visual_*/signed_fetch/network_inspect/stations/site_*)后续梯队;
//   session_id 参数在单会话下接受并回显为 'main'。

import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool, JsonValue, JsonObject, UIMessagePart, InputSchemaObj } from '@amber/chat-domain';
import { makeAgentTool, makeInputSchemaObj } from '@amber/chat-domain';
import type { WebMountSession } from './WebMountSession.ets';

const SESSION_ID: string = 'main';

// ArkTS 工具入参读取(obj[key] as string;缺省回退)
const strOf = (input: JsonValue, key: string, fallback: string): string => {
  if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
    const v: JsonValue | undefined = (input as JsonObject)[key];
    if (typeof v === 'string') return v;
  }
  return fallback;
};

const numOf = (input: JsonValue, key: string, fallback: number): number => {
  if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
    const v: JsonValue | undefined = (input as JsonObject)[key];
    if (typeof v === 'number') return v;
  }
  return fallback;
};

const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

// 统一:evalJson 结果字符串 → text part(ok/error 两形态)
const evalToPart = async (session: WebMountSession, expr: string): Promise<UIMessagePart[]> => {
  const raw: string = await session.evalJson(expr);
  return [{ type: 'text', text: raw, metadata: null }];
};

// 页面 outline + 交互元素 ref 标注(click 以 [data-wm-ref=N] 命中)
const EXTRACT_JS: string = `(function(){
  const els = [];
  const seen = new Set();
  const all = document.querySelectorAll('a[href], button, input, textarea, select, [onclick], [role=button]');
  let i = 0;
  for (const el of all) {
    if (els.length >= 200) break;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    const style = window.getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    const ref = 'wm-' + (i++);
    el.setAttribute('data-wm-ref', ref);
    const text = (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 80);
    const tag = el.tagName.toLowerCase();
    const href = tag === 'a' ? el.getAttribute('href') || '' : '';
    els.push({ ref, tag, text, href });
  }
  const bodyText = (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').trim().slice(0, 5000);
  return { url: location.href, title: document.title, text: bodyText, elements: els };
})()`;

export const createWebMountTools = (session: WebMountSession, includeEval: boolean): AgentTool[] => {
  const openTool: AgentTool = makeAgentTool({
    name: 'wm_open',
    description: 'Open a URL in the WebMount browser session and wait for the page to load. '
      + 'Returns page outline with interactive element refs for wm_click.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'url': { type: 'string', description: 'Absolute http(s) URL to open.' },
    } as JsonObject, ['url']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const url: string = strOf(input, 'url', '');
      if (url.length === 0) throw new Error('url is required');
      await session.open(url);
      return evalToPart(session, EXTRACT_JS);
    },
  });

  const stateTool: AgentTool = makeAgentTool({
    name: 'wm_state',
    description: 'Get current WebMount session state (url, page ready).',
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const payload: JsonObject = {
        session_id: SESSION_ID,
        url: session.getUrl(),
        ready: session.isReady(),
      };
      return textPart(payload);
    },
  });

  const extractTool: AgentTool = makeAgentTool({
    name: 'wm_extract',
    description: 'Extract current page outline: title, visible text (up to 5000 chars) and '
      + 'interactive elements with refs; wm_click accepts the returned ref.',
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      if (!session.isReady()) throw new Error('no page loaded; call wm_open first');
      return evalToPart(session, EXTRACT_JS);
    },
  });

  const clickTool: AgentTool = makeAgentTool({
    name: 'wm_click',
    description: 'Click an element in the WebMount session. Prefer target = ref returned by '
      + 'wm_extract; a CSS selector fallback is also accepted.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'target': { type: 'string', description: 'Element ref from wm_extract (e.g. wm-3) or CSS selector.' },
    } as JsonObject, ['target']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const target: string = strOf(input, 'target', '');
      if (target.length === 0) throw new Error('target is required');
      const selector: string = target.startsWith('wm-')
        ? `[data-wm-ref="${target}"]` : target;
      return evalToPart(session, `(function(){
        const el = document.querySelector(${JSON.stringify(selector)});
        if (el === null) return { clicked: false, error: 'element not found: ${target}' };
        el.scrollIntoView({ block: 'center' });
        el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
        el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
        el.click();
        return { clicked: true, url: location.href };
      })()`);
    },
  });

  const typeTool: AgentTool = makeAgentTool({
    name: 'wm_type',
    description: 'Type text into an input/textarea element (sets value and fires input/change).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'selector': { type: 'string', description: 'CSS selector of the input element.' },
      'text': { type: 'string', description: 'Text to type.' },
    } as JsonObject, ['selector', 'text']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const selector: string = strOf(input, 'selector', '');
      const text: string = strOf(input, 'text', '');
      if (selector.length === 0) throw new Error('selector is required');
      return evalToPart(session, `(function(){
        const el = document.querySelector(${JSON.stringify(selector)});
        if (el === null) return { typed: false, error: 'element not found' };
        el.focus();
        el.value = ${JSON.stringify(text)};
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return { typed: true };
      })()`);
    },
  });

  const scrollTool: AgentTool = makeAgentTool({
    name: 'wm_scroll',
    description: 'Scroll the WebMount page by pixel deltas.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'dx': { type: 'number', description: 'Horizontal pixels (default 0).' },
      'dy': { type: 'number', description: 'Vertical pixels (default 600).' },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const dx: number = numOf(input, 'dx', 0);
      const dy: number = numOf(input, 'dy', 600);
      return evalToPart(session, `(function(){
        window.scrollBy(${dx}, ${dy});
        return { scrolled: true, x: window.scrollX, y: window.scrollY };
      })()`);
    },
  });

  const screenshotTool: AgentTool = makeAgentTool({
    name: 'wm_screenshot',
    description: 'Capture a PNG screenshot of the WebMount session viewport (base64 data URL image).',
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      if (!session.isReady()) throw new Error('no page loaded; call wm_open first');
      const dataUrl: string = await session.screenshotDataUrl();
      return [{ type: 'image', url: dataUrl, metadata: null }];
    },
  });

  // wm_eval:任意 JS(同 origin 会话内)— 强制逐次人工确认(Tool.kt:19-24 语义)
  const evalTool: AgentTool = makeAgentTool({
    name: 'wm_eval',
    description: 'Evaluate arbitrary JavaScript inside the WebMount session page origin '
      + '(can read cookies / storage and make same-origin credentialed requests). '
      + 'Every call requires explicit human approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'code': { type: 'string', description: 'JavaScript expression to evaluate.' },
    } as JsonObject, ['code']),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue, _signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const code: string = strOf(input, 'code', '');
      if (code.length === 0) throw new Error('code is required');
      return evalToPart(session, code);
    },
  });

  return includeEval
    ? [openTool, stateTool, extractTool, clickTool, typeTool, scrollTool, screenshotTool, evalTool]
    : [openTool, stateTool, extractTool, clickTool, typeTool, scrollTool, screenshotTool];
};
