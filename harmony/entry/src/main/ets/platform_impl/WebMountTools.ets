// WebMountTools — WebMount 工具集(命名对齐 Android wm_* 全家)
//
// 现有 21 件:wm_open/state/extract/click/type/scroll/screenshot/eval(审批)、
//   wm_stations/site_add/site_remove(审批)、wm_find/wait/back/forward/reload/
//   keys/select/tab_list/tab_open/tab_select/tab_close/read_page;
//   bot/hand 租约:hand(用户接管)下写操作拒绝,只读放行(guardPage mutating)。
// D6-1 落地:signed_fetch(站点会话 Cookie 凭据请求)、network_inspect(主框架
//   导航日志)、observe(页面签名变更观察)、site_memory(按站点 Agent 笔记)。
// 未移植(登记):tap(≈click,触摸事件变体)、oauth_connect/refresh(通用 OAuth
//   需站点级应用配置面)、site_adapter(逐站提取适配器数据)、profile_synthesize、
//   recipe_candidates(依赖 Recipes 子系统)、fetch_replay(依赖完整 HAR)、
//   run_goal(浏览器内多步目标循环)。
//
// 本批安全收口:
//   L01 — 所有动态值只经 JSON 字符串编码注入,不再拼入脚本文本(error 文案里的
//         target 二次拼接已删除);wm_click 恢复 mandatoryApproval=true /
//         allowsAutoApproval=false(与 wm_eval 同合同),关闭 wm_eval 也不能借
//         click 执行任意表达式。
//   L02 — 所有 URL 走 WebMountSites 官方标准解析(http(s)/拒 userinfo);
//         每次 open/页面工具前刷新站点 gate,onLoadIntercept 逐导航(含重定向)
//         拦截;revoke 站点后立即复位当前页面。

import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  AgentTool, JsonValue, JsonObject, UIMessagePart, InputSchemaObj, KeyValueStore,
  WebMountNetworkSummary, WebMountOAuthApplication,
} from '@amber/chat-domain';
import { makeAgentTool, makeInputSchemaObj, fetchWebMountRequest, webMountOAuthAllowsApiOrigin } from '@amber/chat-domain';
import type { WebMountSession } from './WebMountSession.ets';
import {
  loadWebMountSites, saveWebMountSites, makeUserSite, siteAllowsUrl, sitesDeniedMessage,
  checkWebMountUrl, makeSiteGate,
} from './WebMountSites.ets';
import type { UserSiteEntry, WebMountUrlCheck } from './WebMountSites.ets';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { webview } from '@kit.ArkWeb';
import { webMountTemplateSummaries } from './WebMountTemplateSupport.ets';
import { loadWebMountOAuthApplication, loadWebMountUsableAccessToken } from './WebMountOAuthRuntime.ets';
import { webMountOAuthBearerFetch } from './WebMountRestrictedHttp.ets';
import type { WebMountOAuthToken } from '@amber/chat-domain';

const SESSION_ID: string = 'main';

// ArkTS 工具入参读取(obj[key] as string;缺省回退)
export const strOf = (input: JsonValue, key: string, fallback: string): string => {
  if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
    const v: JsonValue | undefined = (input as JsonObject)[key];
    if (typeof v === 'string') return v;
  }
  return fallback;
};

export const numOf = (input: JsonValue, key: string, fallback: number): number => {
  if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
    const v: JsonValue | undefined = (input as JsonObject)[key];
    if (typeof v === 'number') return v;
  }
  return fallback;
};

export const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

export const errorPart = (message: string): UIMessagePart[] =>
  textPart({ ok: false, error: message } as JsonObject);

// L01:唯一允许的动态注入方式 — JSON 字符串字面量(并转义 U+2028/2029,防脚本换行)
export const jsonString = (value: string): string => JSON.stringify(value)
  .replace(/\u2028/g, '\\u2028')
  .replace(/\u2029/g, '\\u2029');

// D6-1:observe 页面签名(标题/URL/交互元素数/文本 hash/前 8 标题)
const OBSERVE_JS: string = `(function(){
  const text = (document.body && document.body.innerText) || '';
  let hash = 0;
  for (let i = 0; i < text.length; i++) { hash = ((hash << 5) - hash + text.charCodeAt(i)) | 0; }
  const headings = [];
  document.querySelectorAll('h1,h2,h3').forEach(function(h){ if (headings.length < 8) headings.push((h.textContent||'').trim().substring(0,80)); });
  return { title: document.title, url: location.href,
    elements: document.querySelectorAll('a,button,input,select,textarea').length,
    hash: hash, headings: headings };
})()`;
let lastObserveSnapshot: string = '';

// 统一:evalJson 结果字符串 → text part(ok/error 两形态)
const evalToPart = async (session: WebMountSession, expr: string): Promise<UIMessagePart[]> => {
  const raw: string = await session.evalJson(expr);
  return [{ type: 'text', text: raw, metadata: null }];
};

// 全局开关值(P2-1):从 KV 读取
const loadWebMountGlobalEnabled = async (kv: KeyValueStore): Promise<boolean> => {
  try {
    const raw: string | null = await kv.get('webmount_global_enabled');
    return raw === 'true';
  } catch (_e) {
    return false;
  }
};

// OR 语义(LocalTools.kt:161-166):global || assistant 勾选 webmount。
//   设置页显式关闭全局只终止**当前**会话;下一次合法建 tools / 页面操作时重新按 OR
//   求值,助手单独勾选时仍可继续。
export const syncEnabled = async (
  session: WebMountSession, kv: KeyValueStore, assistantEnabled: boolean,
): Promise<boolean> => {
  const global: boolean = await loadWebMountGlobalEnabled(kv);
  const effective: boolean = global || assistantEnabled;
  session.setEnabled(effective);
  return effective;
};

// L02:页面操作前重新载入站点并刷新 gate;返回 null 表示当前页面仍被授权。
//   P2-1:按 OR 语义同步 enabled;仅当 global||assistant 都为假才拒绝
//   (覆盖生成中途用户关闭全局、且助手未单独勾选)。
//   revoke 之后再次调用也会拒绝并复位页面(即便实时通知已复位,这里仍兜底)。
//   bot/hand:hand 租约(用户接管)下,mutating 操作一律拒绝(bot-only),
//   只读操作(state/extract/screenshot/find)经 mutating=false 放行。
export const guardPage = async (
  session: WebMountSession, kv: KeyValueStore, assistantEnabled: boolean,
  mutating: boolean = true,
): Promise<string | null> => {
  const enabled: boolean = await syncEnabled(session, kv, assistantEnabled);
  if (!enabled) return 'WebMount is disabled for this conversation';
  if (mutating && session.getLease() === 'hand') {
    return 'user has control of the WebMount session (hand mode); agent actions are paused until control is returned';
  }
  const sites: UserSiteEntry[] = await loadWebMountSites(kv);
  session.setUrlGate(makeSiteGate(sites));
  const current: string = session.getUrl();
  if (current.length === 0 || !session.isReady()) return 'no page loaded; call wm_open first';
  if (!siteAllowsUrl(sites, current)) {
    session.resetToBlank();
    return `current page is no longer allowed by WebMount Stations: ${current}`;
  }
  return null;
};

// E12(goal 子步骤):可选 document_id/revision 绑定 — 宿主 assertDocument 前置 +
//   脚本在同一 JS turn 复核 revision;普通旧调用不带绑定,行为不变
interface DocumentBinding { documentId: string; revision: number; }

const readDocumentBinding = (input: JsonValue): DocumentBinding | null => {
  const documentId: string = strOf(input, 'document_id', '');
  if (documentId.length === 0) return null;
  const revision: number = numOf(input, 'revision', -1);
  if (!Number.isInteger(revision) || revision < 0) {
    throw new Error('revision must be a non-negative integer when document_id is provided');
  }
  return { documentId, revision };
};

const assertBinding = async (session: WebMountSession, binding: DocumentBinding | null): Promise<string | null> => {
  if (binding === null) return null;
  try {
    await session.assertDocument({ sessionId: SESSION_ID, documentId: binding.documentId, revision: binding.revision });
    return null;
  } catch (_e) {
    return 'document_changed: the page changed since this action was planned; observe again';
  }
};

const revisionGuard = (binding: DocumentBinding | null): string => binding === null ? ''
  : `if (!window.__wmDoc || window.__wmDoc.revision !== ${binding.revision}) return { error: 'document_changed' };\n  `;

// 页面 outline + 交互元素 ref 标注(click 以 [data-wm-ref=N] 命中)
// 脱敏:password 与 token/secret 命名的 input 不回传 value
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
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute('type') || '').toLowerCase();
    const name = (el.getAttribute('name') || '').toLowerCase();
    const isSecret = type === 'password' || /token|secret|api[_-]?key|password/.test(name);
    let text = (el.innerText || el.value || el.getAttribute('aria-label') || '').trim().slice(0, 80);
    if (isSecret) text = '***';
    const href = tag === 'a' ? el.getAttribute('href') || '' : '';
    els.push({ ref, tag, text, href, type: type || undefined });
  }
  const bodyText = (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').trim().slice(0, 5000);
  return { url: location.href, title: document.title, text: bodyText, elements: els };
})()`;

// click:selector 只作为 JSON 字符串字面量进入 script;错误文案为静态常量,
//   不再把 target 二次拼进单引号字符串(L01)。
const clickScript = (selector: string, binding: DocumentBinding | null = null): string => `(function(){
  ${revisionGuard(binding)}const sel = ${jsonString(selector)};
  const el = document.querySelector(sel);
  if (el === null) return { clicked: false, error: 'element not found' };
  el.scrollIntoView({ block: 'center' });
  el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  el.click();
  return { clicked: true, url: location.href };
})()`;

const typeScript = (selector: string, text: string, binding: DocumentBinding | null = null): string => `(function(){
  ${revisionGuard(binding)}const sel = ${jsonString(selector)};
  const el = document.querySelector(sel);
  if (el === null) return { typed: false, error: 'element not found' };
  el.focus();
  el.value = ${jsonString(text)};
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return { typed: true };
})()`;

// assistantEnabled:助手自身是否勾选 webmount(rt.assistant.localTools 含 'webmount');
//   OR 语义 = global || assistantEnabled。参数尾置且默认 false,既有调用不受影响。
export const createWebMountTools = (
  session: WebMountSession, includeEval: boolean, assistantEnabled: boolean = false,
): AgentTool[] => {
  const openTool: AgentTool = makeAgentTool({
    name: 'wm_open',
    description: 'Open a URL in the WebMount browser session and wait for the page to load. '
      + 'Returns page outline with interactive element refs for wm_click.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'url': { type: 'string', description: 'Absolute http(s) URL to open.' },
    } as JsonObject, ['url']),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const raw: string = strOf(input, 'url', '');
      if (raw.length === 0) throw new Error('url is required');
      const kv: KeyValueStore = getChatKvStore();
      // OR 语义:global || assistant 勾选;两者皆假才拒绝
      const enabled: boolean = await syncEnabled(session, kv, assistantEnabled);
      if (!enabled) return errorPart('WebMount is disabled for this conversation');
      // 授权边界:标准解析(http(s)/拒 userinfo)+ 仅允许已注册站点
      const check: WebMountUrlCheck = checkWebMountUrl(raw);
      const sites: UserSiteEntry[] = await loadWebMountSites(kv);
      if (!check.ok || !siteAllowsUrl(sites, check.url)) {
        return [{ type: 'text', text: sitesDeniedMessage(sites, raw), metadata: null }];
      }
      session.setUrlGate(makeSiteGate(sites));
      await session.open(check.url, signal);
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
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled, false);
      if (blocked !== null) return errorPart(blocked);
      return evalToPart(session, EXTRACT_JS);
    },
  });

  // wm_click:脚本注入边界(executes same-origin DOM actions)→ 强制逐次人工确认,
  //   不参与自动批准(L01;对齐 wm_eval 的审批合同)
  const clickTool: AgentTool = makeAgentTool({
    name: 'wm_click',
    description: 'Click an element in the WebMount session. Prefer target = ref returned by '
      + 'wm_extract; a CSS selector fallback is also accepted. Every call requires '
      + 'explicit human approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'target': { type: 'string', description: 'Element ref from wm_extract (e.g. wm-3) or CSS selector.' },
      'document_id': { type: 'string', description: 'Optional observed document id this click was planned against.' },
      'revision': { type: 'number', description: 'Optional observed document revision (required with document_id).' },
    } as JsonObject, ['target']),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const target: string = strOf(input, 'target', '');
      if (target.length === 0) throw new Error('target is required');
      if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const binding: DocumentBinding | null = readDocumentBinding(input);
      const stale: string | null = await assertBinding(session, binding);
      if (stale !== null) return errorPart(stale);
      const selector: string = target.startsWith('wm-')
        ? `[data-wm-ref="${target}"]` : target;
      return evalToPart(session, clickScript(selector, binding));
    },
  });

  const typeTool: AgentTool = makeAgentTool({
    name: 'wm_type',
    description: 'Type text into an input/textarea element (sets value and fires input/change).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'selector': { type: 'string', description: 'CSS selector of the input element.' },
      'text': { type: 'string', description: 'Text to type.' },
      'document_id': { type: 'string', description: 'Optional observed document id this input was planned against.' },
      'revision': { type: 'number', description: 'Optional observed document revision (required with document_id).' },
    } as JsonObject, ['selector', 'text']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const selector: string = strOf(input, 'selector', '');
      const text: string = strOf(input, 'text', '');
      if (selector.length === 0) throw new Error('selector is required');
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const binding: DocumentBinding | null = readDocumentBinding(input);
      const stale: string | null = await assertBinding(session, binding);
      if (stale !== null) return errorPart(stale);
      return evalToPart(session, typeScript(selector, text, binding));
    },
  });

  const scrollTool: AgentTool = makeAgentTool({
    name: 'wm_scroll',
    description: 'Scroll the WebMount page by pixel deltas.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'dx': { type: 'number', description: 'Horizontal pixels (default 0).' },
      'dy': { type: 'number', description: 'Vertical pixels (default 600).' },
      'document_id': { type: 'string', description: 'Optional observed document id this scroll was planned against.' },
      'revision': { type: 'number', description: 'Optional observed document revision (required with document_id).' },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const dx: number = numOf(input, 'dx', 0);
      const dy: number = numOf(input, 'dy', 600);
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const binding: DocumentBinding | null = readDocumentBinding(input);
      const stale: string | null = await assertBinding(session, binding);
      if (stale !== null) return errorPart(stale);
      return evalToPart(session, `(function(){
        ${revisionGuard(binding)}window.scrollBy(${dx}, ${dy});
        return { scrolled: true, x: window.scrollX, y: window.scrollY };
      })()`);
    },
  });

  const screenshotTool: AgentTool = makeAgentTool({
    name: 'wm_screenshot',
    description: 'Capture a PNG screenshot of the WebMount session viewport (base64 data URL image).',
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled, false);
      if (blocked !== null) return errorPart(blocked);
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
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const code: string = strOf(input, 'code', '');
      if (code.length === 0) throw new Error('code is required');
      if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      return evalToPart(session, code);
    },
  });

  // 站点管理三件(WebMountSiteTools.kt 对齐;add/remove 需审批 — 变更授权边界)
  const stationsTool: AgentTool = makeAgentTool({
    name: 'wm_stations',
    description: 'List every website in the user\'s WebMount Stations list — the sites the '
      + 'agent is allowed to open and operate via wm_open/wm_click/wm_type.',
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const sites: UserSiteEntry[] = await loadWebMountSites(getChatKvStore());
      return textPart({
        stations: sites.map((s: UserSiteEntry): JsonObject =>
          ({ id: s.id, name: s.name, url: s.url } as JsonObject)),
      });
    },
  });

  const siteAddTool: AgentTool = makeAgentTool({
    name: 'wm_site_add',
    description: 'Add a website to the user\'s WebMount Stations list so wm_open can operate '
      + 'it. Requires approval; the user manages the same list on the WebMount settings page.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'name': { type: 'string', description: 'Display name of the site.' },
      'url': { type: 'string', description: 'Homepage URL of the site (host used for matching).' },
    } as JsonObject, ['name', 'url']),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const name: string = strOf(input, 'name', '');
      const url: string = strOf(input, 'url', '');
      if (name.length === 0 || url.length === 0) throw new Error('name and url are required');
      const check: WebMountUrlCheck = checkWebMountUrl(url);
      if (!check.ok) return errorPart(`invalid site url: ${check.error}`);
      const kv = getChatKvStore();
      const sites: UserSiteEntry[] = await loadWebMountSites(kv);
      const entry: UserSiteEntry = makeUserSite(name, check.url);
      const next: UserSiteEntry[] = [...sites, entry];
      // saveWebMountSites 成功后经单槽回调刷新 session gate(P2-1),无需显式 setUrlGate
      await saveWebMountSites(kv, next);
      return textPart({ added: true, id: entry.id, name: entry.name, url: entry.url });
    },
  });

  const siteRemoveTool: AgentTool = makeAgentTool({
    name: 'wm_site_remove',
    description: 'Remove a website from the user\'s WebMount Stations list (by id from '
      + 'wm_stations). Requires approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'id': { type: 'string', description: 'Site id from wm_stations.' },
    } as JsonObject, ['id']),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const id: string = strOf(input, 'id', '');
      if (id.length === 0) throw new Error('id is required');
      const kv = getChatKvStore();
      const sites: UserSiteEntry[] = await loadWebMountSites(kv);
      const next: UserSiteEntry[] = sites.filter((s: UserSiteEntry): boolean => s.id !== id);
      if (next.length === sites.length) return textPart({ removed: false, error: 'site not found' });
      // 先判定当前页面是否将失权(save 成功后的回调会立即 reset,故先算)
      const current: string = session.getUrl();
      const pageReset: boolean = current.length > 0 && !siteAllowsUrl(next, current);
      // saveWebMountSites 成功后经单槽回调 applySitesSnapshot:换 gate + 失权即 resetToBlank
      await saveWebMountSites(kv, next);
      return textPart({ removed: true, id: id, page_reset: pageReset });
    },
  });

  // wait:轮询 eval 直到条件满足或超时(Android wm_wait 语义子集)
  const waitProbeJs = (
    urlIncludes: string, titleIncludes: string, selector: string, textIncludes: string,
  ): string => `(function(){
    const u = ${jsonString(urlIncludes)};
    const t = ${jsonString(titleIncludes)};
    const s = ${jsonString(selector)};
    const x = ${jsonString(textIncludes)};
    if (u.length > 0 && location.href.indexOf(u) < 0) return { matched: false, reason: 'url' };
    if (t.length > 0 && document.title.indexOf(t) < 0) return { matched: false, reason: 'title' };
    if (s.length > 0 && document.querySelector(s) === null) return { matched: false, reason: 'selector' };
    if (x.length > 0 && (document.body ? document.body.innerText : '').indexOf(x) < 0) {
      return { matched: false, reason: 'text' };
    }
    return { matched: true, url: location.href, title: document.title };
  })()`;

  const waitTool: AgentTool = makeAgentTool({
    name: 'wm_wait',
    description: 'Wait until a page condition holds: url_includes / title_includes / '
      + 'selector_visible / text_includes. Polls until timeoutMs (default 10000).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'url_includes': { type: 'string', description: 'Substring expected in location.href.' },
      'title_includes': { type: 'string', description: 'Substring expected in document.title.' },
      'selector': { type: 'string', description: 'CSS selector that must exist.' },
      'text_includes': { type: 'string', description: 'Substring expected in body text.' },
      'timeout_ms': { type: 'number', description: 'Max wait in ms (default 10000, max 60000).' },
    } as JsonObject, null),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const urlIncludes: string = strOf(input, 'url_includes', '');
      const titleIncludes: string = strOf(input, 'title_includes', '');
      const selector: string = strOf(input, 'selector', '');
      const textIncludes: string = strOf(input, 'text_includes', '');
      if (urlIncludes.length === 0 && titleIncludes.length === 0
        && selector.length === 0 && textIncludes.length === 0) {
        throw new Error('at least one wait condition is required');
      }
      const timeoutMs: number = Math.min(Math.max(numOf(input, 'timeout_ms', 10000), 0), 60000);
      const probe: string = waitProbeJs(urlIncludes, titleIncludes, selector, textIncludes);
      const started: number = Date.now();
      for (;;) {
        // 取消信号贯穿轮询等待(最小):Stop 后立即终止并清理由此产生的挂起
        if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
        const raw: string = await session.evalJson(probe);
        let matched: boolean = false;
        try {
          const parsedObj: JsonValue = JSON.parse(raw) as JsonValue;
          if (typeof parsedObj === 'object' && parsedObj !== null && !Array.isArray(parsedObj)) {
            const obj: JsonObject = parsedObj as JsonObject;
            const val: JsonValue | undefined = obj['value'];
            if (typeof val === 'object' && val !== null && !Array.isArray(val)) {
              matched = obj['ok'] === true && (val as JsonObject)['matched'] === true;
            }
          }
        } catch {
          matched = false;
        }
        const waited: number = Date.now() - started;
        if (matched) {
          return textPart({ matched: true, waited_ms: waited, url: session.getUrl() });
        }
        if (waited >= timeoutMs) {
          return textPart({ matched: false, waited_ms: waited, url: session.getUrl() });
        }
        await new Promise<void>((r: () => void): void => { setTimeout(r, 200); });
      }
    },
  });

  const backTool: AgentTool = makeAgentTool({
    name: 'wm_back',
    description: 'Navigate back in the WebMount session history.',
    execute: async (): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const ok: boolean = session.back();
      return textPart({ went_back: ok, url: session.getUrl() });
    },
  });

  const forwardTool: AgentTool = makeAgentTool({
    name: 'wm_forward',
    description: 'Navigate forward in the WebMount session history.',
    execute: async (): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const ok: boolean = session.forward();
      return textPart({ went_forward: ok, url: session.getUrl() });
    },
  });

  const reloadTool: AgentTool = makeAgentTool({
    name: 'wm_reload',
    description: 'Reload the current WebMount page and wait for load end.',
    execute: async (_input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      await session.reload(signal);
      return textPart({ reloaded: true, url: session.getUrl() });
    },
  });

  const tabListTool: AgentTool = makeAgentTool({
    name: 'wm_tab_list',
    description: 'List WebMount logical tabs and the active tab id.',
    execute: async (): Promise<UIMessagePart[]> => {
      return textPart({
        active: session.getActiveTabId(),
        tabs: session.listTabs().map((t): JsonObject =>
          ({ id: t.id, title: t.title, url: t.url } as JsonObject)),
      });
    },
  });

  const tabOpenTool: AgentTool = makeAgentTool({
    name: 'wm_tab_open',
    description: 'Create (or reuse) a logical tab id. Call wm_open afterwards to load a URL into it.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'tab_id': { type: 'string', description: 'Stable tab id (e.g. search, docs).' },
    } as JsonObject, ['tab_id']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const tabId: string = strOf(input, 'tab_id', '');
      if (tabId.length === 0) throw new Error('tab_id is required');
      const tab = session.openTab(tabId);
      session.selectTab(tabId);
      return textPart({ opened: true, tab: { id: tab.id, url: tab.url } });
    },
  });

  const tabSelectTool: AgentTool = makeAgentTool({
    name: 'wm_tab_select',
    description: 'Select a logical tab. If it has a url, reloads that url in the shared WebView.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'tab_id': { type: 'string', description: 'Tab id from wm_tab_list.' },
    } as JsonObject, ['tab_id']),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const tabId: string = strOf(input, 'tab_id', '');
      if (tabId.length === 0) throw new Error('tab_id is required');
      const tab = session.selectTab(tabId);
      if (tab.url.length > 0) {
        const check: WebMountUrlCheck = checkWebMountUrl(tab.url);
        const sites: UserSiteEntry[] = await loadWebMountSites(getChatKvStore());
        if (!check.ok || !siteAllowsUrl(sites, check.url)) {
          return textPart({ selected: true, tab_id: tabId, warning: 'tab url no longer allowed' });
        }
        session.setUrlGate(makeSiteGate(sites));
        await session.open(check.url, signal);
      }
      return textPart({ selected: true, tab_id: tabId, url: session.getUrl() });
    },
  });

  const tabCloseTool: AgentTool = makeAgentTool({
    name: 'wm_tab_close',
    description: 'Close a logical tab (main cannot be closed).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'tab_id': { type: 'string', description: 'Tab id to close.' },
    } as JsonObject, ['tab_id']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const tabId: string = strOf(input, 'tab_id', '');
      if (tabId.length === 0) throw new Error('tab_id is required');
      const closed: boolean = session.closeTab(tabId);
      return textPart({ closed, tab_id: tabId, active: session.getActiveTabId() });
    },
  });

  // wm_tab_* 与 wait 导航组:始终暴露(对齐 Android tab/wait 工具面)
  // 视觉读页：DOM 大纲 + 截图 image part（供 vision 模型 OCR）
  const readPageTool: AgentTool = makeAgentTool({
    name: 'wm_read_page',
    description: 'Read the current page: returns DOM outline (wm_extract) plus a screenshot '
      + 'image part for vision/OCR models. Use when text is canvas-rendered or extract is insufficient.',
    execute: async (): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const outline: UIMessagePart[] = await evalToPart(session, EXTRACT_JS);
      let shot: UIMessagePart | null = null;
      try {
        const dataUrl: string = await session.screenshotDataUrl();
        shot = { type: 'image', url: dataUrl, metadata: null };
      } catch (e) {
        shot = {
          type: 'text',
          text: `screenshot unavailable: ${e instanceof Error ? e.message : String(e)}`,
          metadata: null,
        };
      }
      return shot === null ? outline : [...outline, shot];
    },
  });

  // wm_find(只读):selector 计数 + 文本存在性,不回传整页大纲
  const findTool: AgentTool = makeAgentTool({
    name: 'wm_find',
    description: 'Check whether a CSS selector exists (match count) and/or a text is present '
      + 'on the current page. Read-only, no page outline returned.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'selector': { type: 'string', description: 'CSS selector to count (optional).' },
      'text': { type: 'string', description: 'Text to search in body (optional).' },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const selector: string = strOf(input, 'selector', '');
      const text: string = strOf(input, 'text', '');
      if (selector.length === 0 && text.length === 0) {
        throw new Error('selector or text is required');
      }
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled, false);
      if (blocked !== null) return errorPart(blocked);
      return evalToPart(session, `(function(){
        const sel = ${jsonString(selector)};
        const t = ${jsonString(text)};
        const count = sel.length > 0 ? document.querySelectorAll(sel).length : undefined;
        const bodyText = (document.body ? document.body.innerText : '');
        const found = t.length > 0 ? bodyText.indexOf(t) >= 0 : undefined;
        return { count, text_found: found, url: location.href };
      })()`);
    },
  });

  // wm_keys:键盘输入(文本逐字 + {Enter}/{Tab}/{Escape}/{Backspace} 特殊键)
  const keysTool: AgentTool = makeAgentTool({
    name: 'wm_keys',
    description: 'Send keyboard input to an element (or the focused element): literal text '
      + 'plus special keys written as {Enter} {Tab} {Escape} {Backspace}.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'keys': { type: 'string', description: 'Text to type; embed {Enter}/{Tab}/{Escape}/{Backspace} for special keys.' },
      'selector': { type: 'string', description: 'CSS selector of the target element (optional; defaults to focused element).' },
    } as JsonObject, ['keys']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const keys: string = strOf(input, 'keys', '');
      if (keys.length === 0) throw new Error('keys is required');
      const selector: string = strOf(input, 'selector', '');
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      return evalToPart(session, `(function(){
        const sel = ${jsonString(selector)};
        const el = sel.length > 0 ? document.querySelector(sel) : document.activeElement;
        if (!el) return { sent: false, error: 'no target element' };
        el.focus();
        const input = ${jsonString(keys)};
        const special = (name) => {
          el.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true }));
          el.dispatchEvent(new KeyboardEvent('keyup', { key: name, bubbles: true }));
        };
        let sent = 0;
        let buf = '';
        let i = 0;
        const flush = () => {
          if (buf.length === 0) return;
          for (const ch of buf) {
            el.dispatchEvent(new KeyboardEvent('keydown', { key: ch, bubbles: true }));
          }
          if (document.activeElement === el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
            const start = el.selectionStart == null ? el.value.length : el.selectionStart;
            const end = el.selectionEnd == null ? start : el.selectionEnd;
            el.value = el.value.slice(0, start) + buf + el.value.slice(end);
            el.selectionStart = el.selectionEnd = start + buf.length;
            el.dispatchEvent(new Event('input', { bubbles: true }));
          }
          sent += buf.length;
          buf = '';
        };
        while (i < input.length) {
          if (input[i] === '{') {
            const end = input.indexOf('}', i);
            if (end > i) {
              flush();
              special(input.substring(i + 1, end));
              i = end + 1;
              continue;
            }
          }
          buf += input[i];
          i++;
        }
        flush();
        return { sent: true, count: sent };
      })()`);
    },
  });

  // wm_select:下拉框选择(value 精确匹配;change 事件)
  const selectTool: AgentTool = makeAgentTool({
    name: 'wm_select',
    description: 'Select an option (by value) of a <select> element and fire change.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'selector': { type: 'string', description: 'CSS selector of the select element.' },
      'value': { type: 'string', description: 'Option value to select.' },
      'document_id': { type: 'string', description: 'Optional observed document id this selection was planned against.' },
      'revision': { type: 'number', description: 'Optional observed document revision (required with document_id).' },
    } as JsonObject, ['selector', 'value']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const selector: string = strOf(input, 'selector', '');
      const value: string = strOf(input, 'value', '');
      if (selector.length === 0) throw new Error('selector is required');
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled);
      if (blocked !== null) return errorPart(blocked);
      const binding: DocumentBinding | null = readDocumentBinding(input);
      const stale: string | null = await assertBinding(session, binding);
      if (stale !== null) return errorPart(stale);
      return evalToPart(session, `(function(){
        ${revisionGuard(binding)}const sel = ${jsonString(selector)};
        const value = ${jsonString(value)};
        const el = document.querySelector(sel);
        if (el === null) return { selected: false, error: 'element not found' };
        if (el.tagName.toLowerCase() !== 'select') return { selected: false, error: 'not a select element' };
        el.value = value;
        el.dispatchEvent(new Event('change', { bubbles: true }));
        return { selected: el.value === value };
      })()`);
    },
  });

  // D6-1:signed_fetch — 站点会话 Cookie 凭据请求(仅已授权站点;hand 租约禁写)
  //   E12:可选 oauth_site_id — 由宿主在精确已配置 API origin 注入站点 OAuth Bearer,
  //   逐跳核验,绝不把旧 Bearer/Cookie 送往跨 origin 目标
  const signedFetchTool: AgentTool = makeAgentTool({
    name: 'wm_signed_fetch',
    description: 'Fetch a URL with the current WebMount session cookies attached (credentialed '
      + 'request). URL must belong to an authorized WebMount site. Optional oauth_site_id injects '
      + 'the site OAuth Bearer instead, restricted to the configured API origins.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'url': { type: 'string', description: 'Absolute http(s) URL on an authorized site.' },
      'method': { type: 'string', description: 'HTTP method (default GET).' },
      'body': { type: 'string', description: 'Optional request body (POST/PUT, JSON).' },
      'oauth_site_id': { type: 'string', description: 'Optional site id whose OAuth Bearer is injected (GET/HEAD only).' },
    } as JsonObject, ['url']),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const raw: string = strOf(input, 'url', '');
      if (raw.length === 0) throw new Error('url is required');
      const m: string = strOf(input, 'method', 'GET').toUpperCase();
      if (['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].indexOf(m) < 0) {
        return errorPart(`unsupported method: ${m}`);
      }
      const method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH' =
        m as 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
      const body: string = strOf(input, 'body', '');
      const oauthSiteId: string = strOf(input, 'oauth_site_id', '').trim();
      const kv: KeyValueStore = getChatKvStore();
      const enabled: boolean = await syncEnabled(session, kv, assistantEnabled);
      if (!enabled) return errorPart('WebMount is disabled for this conversation');
      const blocked: string | null = await guardPage(session, kv, assistantEnabled, method !== 'GET');
      if (blocked !== null) return errorPart(blocked);
      const check: WebMountUrlCheck = checkWebMountUrl(raw);
      const sites: UserSiteEntry[] = await loadWebMountSites(kv);
      if (!check.ok || !siteAllowsUrl(sites, check.url)) {
        return [{ type: 'text', text: sitesDeniedMessage(sites, raw), metadata: null }];
      }
      if (oauthSiteId.length > 0) {
        // OAuth Bearer 路线:仅 GET/HEAD;应用必须存在且 URL 命中精确已配置 API origin
        if (m !== 'GET' && m !== 'HEAD') {
          return errorPart('oauth fetch supports GET/HEAD only');
        }
        const application: WebMountOAuthApplication | null = await loadWebMountOAuthApplication(oauthSiteId);
        if (application === null) return errorPart(`oauth_not_configured: no OAuth application for "${oauthSiteId}"`);
        if (!webMountOAuthAllowsApiOrigin(application, check.url)) {
          return errorPart('oauth_origin_denied: URL is not one of the configured API origins for this site');
        }
        const token: WebMountOAuthToken | null = await loadWebMountUsableAccessToken(oauthSiteId);
        if (token === null) return errorPart(`oauth_not_connected: site "${oauthSiteId}" has no usable token; connect first`);
        const result = await webMountOAuthBearerFetch(application, token.accessToken, check.url,
          m === 'HEAD' ? 'HEAD' : 'GET', 30000, signal);
        const bearerText: string = result.text;
        return textPart({
          ok: result.status >= 200 && result.status < 300,
          status: result.status,
          oauth_site_id: oauthSiteId,
          final_url: result.finalUrl,
          followed_redirects: result.followedRedirects,
          stopped_at_cross_origin_redirect: result.stoppedAtCrossOriginRedirect,
          body: bearerText.length > 8000 ? `${bearerText.substring(0, 8000)}…(truncated)` : bearerText,
        } as JsonObject);
      }
      const cookie: string = webview.WebCookieManager.fetchCookieSync(check.url);
      const headers: Record<string, string> = {};
      if (cookie.length > 0) headers['Cookie'] = cookie;
      if (body.length > 0) headers['Content-Type'] = 'application/json';
      const resp = await fetchWebMountRequest(getAppContainer().httpClient, {
        url: check.url, method: method, headers: headers,
        body: body.length > 0 ? body : undefined,
      }, (): Promise<boolean> => syncEnabled(session, kv, assistantEnabled), signal);
      const text: string = typeof resp.body === 'string' ? resp.body : JSON.stringify(resp.body);
      return textPart({
        ok: resp.status >= 200 && resp.status < 300,
        status: resp.status,
        body: text.length > 8000 ? `${text.substring(0, 8000)}…(truncated)` : text,
      } as JsonObject);
    },
  });

  // D6-1:network_inspect — 主框架导航日志(ok/blocked,环形 ≤50)
  //   E12 增量:typed templates(页内台账 + 导入 HAR;脱敏 URL/replayable 资格)
  const networkInspectTool: AgentTool = makeAgentTool({
    name: 'wm_network_inspect',
    description: 'List recent WebMount main-frame navigations plus the observed request templates '
      + '(page fetch/XHR ledger and imported HAR) with their replay eligibility.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'limit': { type: 'number', description: 'Max entries (default 20, max 50).' },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const kv: KeyValueStore = getChatKvStore();
      const enabled: boolean = await syncEnabled(session, kv, assistantEnabled);
      if (!enabled) return errorPart('WebMount is disabled for this conversation');
      const limit: number = Math.min(50, Math.max(1, numOf(input, 'limit', 20)));
      const log: string[] = session.getNavLog();
      const tail: string[] = log.slice(Math.max(0, log.length - limit));
      const lines: string[] = [];
      for (const e of tail) lines.push(e);
      const summaries: WebMountNetworkSummary[] = await webMountTemplateSummaries(session);
      const coverage: string = session.getDocumentId().length > 0 ? 'page_fetch_xhr'
        : summaries.some((s: WebMountNetworkSummary): boolean => s.captureCoverage === 'imported_har')
          ? 'imported_har' : 'none';
      const templates: JsonValue[] = summaries.map((s: WebMountNetworkSummary): JsonValue => ({
        request_template_id: s.requestTemplateId,
        source_id: s.sourceId,
        capture_coverage: s.captureCoverage,
        method: s.method,
        display_url: s.displayUrl,
        status: s.status as JsonValue,
        replayable: s.replayable,
        reason: s.reason as JsonValue,
      }) as JsonValue);
      return textPart({
        count: lines.length, entries: lines.join('\n'),
        capture_coverage: coverage, templates: templates as JsonValue,
      } as JsonObject);
    },
  });

  // D6-1:observe — 页面签名快照与上次比对(first/changed/unchanged)
  const observeTool: AgentTool = makeAgentTool({
    name: 'wm_observe',
    description: 'Snapshot the current page signature (title/url/interactive count/text hash/'
      + 'headings) and report whether it changed since the previous snapshot.',
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), assistantEnabled, false);
      if (blocked !== null) return errorPart(blocked);
      const raw: string = await session.evalJson(OBSERVE_JS);
      const prev: string = lastObserveSnapshot;
      const changed: string = prev.length === 0 ? 'first_snapshot' : (prev !== raw ? 'changed' : 'unchanged');
      lastObserveSnapshot = raw;
      return textPart({ changed: changed, snapshot: JSON.parse(raw) as JsonValue } as JsonObject);
    },
  });

  // D6-1:site_memory — 按站点 Agent 笔记(get/set/append,KV 持久)
  const siteMemoryTool: AgentTool = makeAgentTool({
    name: 'wm_site_memory',
    description: 'Read or write per-site agent notes (site quirks, login hints, extraction tips). '
      + 'Actions: get (default) | set | append.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'host': { type: 'string', description: 'Site host (default: current page host).' },
      'action': { type: 'string', description: 'get | set | append (default get).' },
      'value': { type: 'string', description: 'Note text (required for set/append).' },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const kv: KeyValueStore = getChatKvStore();
      const enabled: boolean = await syncEnabled(session, kv, assistantEnabled);
      if (!enabled) return errorPart('WebMount is disabled for this conversation');
      let host: string = strOf(input, 'host', '');
      if (host.length === 0) {
        const stripped: string = session.getUrl().replace(/^https?:\/\//, '');
        const slash: number = stripped.indexOf('/');
        host = slash > 0 ? stripped.substring(0, slash) : stripped;
      }
      if (host.length === 0) return errorPart('host is required (no page loaded)');
      const key: string = `webmount_site_memory/${host}`;
      const action: string = strOf(input, 'action', 'get');
      if (action === 'get') {
        const raw: string | null = await kv.get(key);
        return textPart({ host: host, notes: raw ?? '' } as JsonObject);
      }
      const value: string = strOf(input, 'value', '');
      if (value.length === 0) throw new Error('value is required for set/append');
      if (action === 'set') {
        await kv.put(key, value);
        return textPart({ host: host, saved: true } as JsonObject);
      }
      if (action === 'append') {
        const raw: string | null = await kv.get(key);
        let next: string = raw === null || raw.length === 0 ? value : `${raw}\n${value}`;
        if (next.length > 4000) next = next.substring(next.length - 4000);
        await kv.put(key, next);
        return textPart({ host: host, saved: true } as JsonObject);
      }
      return errorPart(`unknown action: ${action}`);
    },
  });

  const extras: AgentTool[] = [
    findTool, waitTool, backTool, forwardTool, reloadTool, keysTool, selectTool,
    tabListTool, tabOpenTool, tabSelectTool, tabCloseTool, readPageTool,
    signedFetchTool, networkInspectTool, observeTool, siteMemoryTool,
  ];

  return includeEval
    ? [openTool, stateTool, extractTool, clickTool, typeTool, scrollTool, screenshotTool,
      stationsTool, siteAddTool, siteRemoveTool, ...extras, evalTool]
    : [openTool, stateTool, extractTool, clickTool, typeTool, scrollTool, screenshotTool,
      stationsTool, siteAddTool, siteRemoveTool, ...extras];
};
