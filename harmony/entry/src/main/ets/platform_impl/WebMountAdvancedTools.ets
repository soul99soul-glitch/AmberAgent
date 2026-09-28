// WebMountAdvancedTools — E12 新增 WebMount 工具(E12 冻结合同 B)
//
// wm_site_adapter:九个固定只读 DOM profile 的真实提取(可见文本/公开链接);
// wm_tap:DOM synthetic touch(touchstart/end + 至多一次默认 click,非 trusted);
// wm_fetch_replay:观察台账/导入 HAR 的同源 GET/HEAD template 页内重放;
// wm_oauth_connect/refresh:站点 OAuth 状态机入口(真实 pending/connected/failed)。
// 全部沿用现有站点/bot-hand/owner gate;tap 与 replay 由 root 的 resolver
// 窄准入保证任何设置组合下都需人工批准。

import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  AgentTool, InputSchemaObj, JsonObject, JsonValue, KeyValueStore, UIMessagePart,
  WebMountDocumentToken, WebMountReplayTemplate, WebMountSiteProfile,
} from '@amber/chat-domain';
import {
  makeAgentTool, makeInputSchemaObj, webMountSiteProfile, webMountSiteMatchesUrl,
  WEBMOUNT_READONLY_SITE_PROFILES, buildWebMountSiteAdapterScript,
  webMountReplayAllowed, webMountRedactedDisplayUrl,
  WEBMOUNT_SITE_ADAPTER_DEFAULT_LIMIT, WEBMOUNT_SITE_ADAPTER_MAX_LIMIT,
} from '@amber/chat-domain';
import type { WebMountSession } from './WebMountSession.ets';
import {
  errorPart, guardPage, jsonString, numOf, strOf, syncEnabled, textPart,
} from './WebMountTools.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  getWebMountOAuthRuntime, loadWebMountOAuthApplication,
} from './WebMountOAuthRuntime.ets';
import { collectWebMountReplayTemplates } from './WebMountTemplateSupport.ets';
import type { WebMountOAuthApplication } from '@amber/chat-domain';

const SESSION_ID: string = 'main';
const REPLAY_DEFAULT_MAX_CHARS: number = 60000;
const REPLAY_CAP_MAX_CHARS: number = 200000;

export interface WebMountAdvancedToolsDeps {
  session: WebMountSession;
  assistantEnabled: boolean;
  // 当前会话浏览器 owner 断言(ConversationBrowserGuard);每次页面前后调用
  assertOwner: () => void;
}

interface EvalJsonEnvelope { ok: boolean; value: JsonValue | null; error: string | null; }

const evalValue = async (session: WebMountSession, expr: string): Promise<JsonValue | null> => {
  const raw: string = await session.evalJson(expr);
  const parsed: EvalJsonEnvelope = JSON.parse(raw) as EvalJsonEnvelope;
  if (!parsed.ok) throw new Error(parsed.error ?? 'WebMount page evaluation failed');
  return parsed.value;
};

const currentRevision = async (session: WebMountSession): Promise<number> => {
  const value: JsonValue | null = await evalValue(
    session, `(function(){ return window.__wmDoc ? window.__wmDoc.revision : -1; })()`);
  return typeof value === 'number' ? value : -1;
};

const currentToken = async (session: WebMountSession): Promise<WebMountDocumentToken> => ({
  sessionId: SESSION_ID,
  documentId: session.getDocumentId(),
  revision: await currentRevision(session),
});

const buildTapScript = (revision: number, ref: string | null, x: number | null, y: number | null): string => {
  const target: string = ref !== null
    ? `const el = document.querySelector('[data-wm-ref=' + ${jsonString(ref)} + ']');\n  const usePoint = false; const px = 0; const py = 0;`
    : `const el = document.elementFromPoint(${String(x ?? 0)}, ${String(y ?? 0)});\n  const usePoint = true; const px = ${String(x ?? 0)}; const py = ${String(y ?? 0)};`;
  return `(function(){
  if (!window.__wmDoc || window.__wmDoc.revision !== ${String(revision)}) return { dispatched: false, error: 'document_changed' };
  ${target}
  if (!el) return { dispatched: false, error: 'no_target' };
  const r = el.getBoundingClientRect();
  const style = window.getComputedStyle(el);
  if (r.width <= 0 || r.height <= 0 || style.display === 'none' || style.visibility === 'hidden') {
    return { dispatched: false, error: 'not_visible' };
  }
  const cx = usePoint ? px : (r.left + r.width / 2);
  const cy = usePoint ? py : (r.top + r.height / 2);
  if (typeof Touch !== 'function' || typeof TouchEvent !== 'function') {
    return { dispatched: false, error: 'touch_event_unavailable' };
  }
  const before = location.href;
  let defaultPrevented = false;
  try {
    const touch = new Touch({ identifier: 1, target: el, clientX: cx, clientY: cy });
    const start = new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [touch], targetTouches: [touch], changedTouches: [touch] });
    el.dispatchEvent(start);
    const end = new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [], targetTouches: [], changedTouches: [touch] });
    el.dispatchEvent(end);
    defaultPrevented = start.defaultPrevented || end.defaultPrevented;
  } catch (e) {
    return { dispatched: false, error: 'touch_event_unavailable' };
  }
  let clicked = false;
  if (!defaultPrevented) { el.click(); clicked = true; }
  return { event_mode: 'dom_touch', synthetic: true, dispatched: true, default_click: clicked,
    page_changed: location.href !== before, goal_verified: false };
})()`;
};

export const createWebMountAdvancedTools = (deps: WebMountAdvancedToolsDeps): AgentTool[] => {
  const session: WebMountSession = deps.session;
  const enabled: boolean = deps.assistantEnabled;

  const siteAdapterTool: AgentTool = makeAgentTool({
    name: 'wm_site_adapter',
    description: 'Extract readonly structured fields from the current page when it matches one of '
      + 'the nine fixed site profiles (visible text and public links only).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'site_id': { type: 'string', description: 'Profile id (optional; defaults to matching the current URL).' },
      'limit': { type: 'number', description: `Max items per field (default ${WEBMOUNT_SITE_ADAPTER_DEFAULT_LIMIT}, cap ${WEBMOUNT_SITE_ADAPTER_MAX_LIMIT}).` },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const blocked: string | null = await guardPage(session, getChatKvStore(), enabled, false);
      if (blocked !== null) return errorPart(blocked);
      const current: string = session.getUrl();
      const requested: string = strOf(input, 'site_id', '').trim();
      let profile: WebMountSiteProfile | null = null;
      if (requested.length > 0) {
        profile = webMountSiteProfile(requested);
        if (profile === null) return errorPart(`site_adapter_unavailable: no readonly profile for "${requested}"`);
        if (!webMountSiteMatchesUrl(profile, current)) {
          return errorPart(`site_page_unmatched: current URL does not belong to ${profile.siteId}`);
        }
      } else {
        for (const candidate of WEBMOUNT_READONLY_SITE_PROFILES) {
          if (webMountSiteMatchesUrl(candidate, current)) { profile = candidate; break; }
        }
        if (profile === null) {
          return errorPart('site_adapter_unavailable: current page matches no readonly profile');
        }
      }
      const limit: number = Math.max(1, Math.min(
        numOf(input, 'limit', WEBMOUNT_SITE_ADAPTER_DEFAULT_LIMIT), WEBMOUNT_SITE_ADAPTER_MAX_LIMIT));
      const script: string = buildWebMountSiteAdapterScript(profile.siteId, limit);
      const value: JsonValue | null = await evalValue(session, script);
      if (typeof value !== 'string') throw new Error('WebMount site adapter returned no result');
      const parsed: JsonObject = JSON.parse(value) as JsonObject;
      const matched: number = typeof parsed['matched'] === 'number' ? parsed['matched'] as number : 0;
      if (matched === 0) {
        return textPart({
          site_id: profile.siteId, url: current, error: 'site_page_unmatched',
          hint: 'No profile fields matched this page; use wm_extract for a generic outline.',
        } as JsonObject);
      }
      const revision: number = await currentRevision(session);
      return textPart({
        site_id: profile.siteId,
        observation: { sessionId: SESSION_ID, documentId: session.getDocumentId(), revision: revision } as JsonObject,
        url: current,
        fields: (parsed['fields'] ?? {}) as JsonValue,
        matched: matched,
        truncated: parsed['truncated'] === true,
      } as JsonObject);
    },
  });

  const tapTool: AgentTool = makeAgentTool({
    name: 'wm_tap',
    description: 'Dispatch a synthetic DOM touch (touchstart/touchend plus at most one default '
      + 'click) at one observed element ref or one viewport coordinate. This is not a trusted '
      + 'native gesture. Every call requires explicit human approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'session_id': { type: 'string', description: 'WebMount session id (main).' },
      'document_id': { type: 'string', description: 'Document id from the latest observation.' },
      'revision': { type: 'number', description: 'Document revision from the latest observation.' },
      'ref': { type: 'string', description: 'Element ref from the latest observation (e.g. wm-3).' },
      'x': { type: 'number', description: 'Viewport x coordinate (alternative to ref).' },
      'y': { type: 'number', description: 'Viewport y coordinate (alternative to ref).' },
    } as JsonObject, ['session_id', 'document_id', 'revision']),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      if (strOf(input, 'session_id', SESSION_ID) !== SESSION_ID) return errorPart('unknown session_id');
      const documentId: string = strOf(input, 'document_id', '');
      const revision: number = numOf(input, 'revision', -1);
      if (documentId.length === 0 || !Number.isInteger(revision) || revision < 0) {
        return errorPart('document_id and a non-negative integer revision are required');
      }
      const refRaw: string = strOf(input, 'ref', '');
      const hasRef: boolean = refRaw.length > 0;
      const hasX: boolean = typeof (input as JsonObject)['x'] === 'number';
      const hasY: boolean = typeof (input as JsonObject)['y'] === 'number';
      if (hasRef === (hasX && hasY)) return errorPart('provide exactly one of ref or x/y');
      if (hasRef && !/^wm-\d{1,6}$/.test(refRaw)) return errorPart('ref must be an observed element ref (wm-N)');
      const blocked: string | null = await guardPage(session, getChatKvStore(), enabled, true);
      if (blocked !== null) return errorPart(blocked);
      const token: WebMountDocumentToken = { sessionId: SESSION_ID, documentId: documentId, revision: revision };
      await session.assertDocument(token);
      deps.assertOwner();
      if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
      const script: string = buildTapScript(revision, hasRef ? refRaw : null,
        hasX ? numOf(input, 'x', 0) : null, hasY ? numOf(input, 'y', 0) : null);
      const value: JsonValue | null = await evalValue(session, script);
      if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        throw new Error('WebMount tap returned no result');
      }
      const result: JsonObject = value as JsonObject;
      if (result['error'] === 'document_changed') return errorPart('document_changed: page changed before tap; observe again');
      if (typeof result['error'] === 'string') return errorPart(String(result['error']));
      return textPart(result);
    },
  });

  const fetchReplayTool: AgentTool = makeAgentTool({
    name: 'wm_fetch_replay',
    description: 'Replay an observed same-origin GET/HEAD request template inside the page context '
      + 'with the page credentials. POST/write-like/cross-origin templates are refused. '
      + 'Every call requires explicit human approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'session_id': { type: 'string', description: 'WebMount session id (main).' },
      'request_template_id': { type: 'string', description: 'Opaque template id from wm_network_inspect.' },
      'max_chars': { type: 'number', description: `Response text budget (default ${REPLAY_DEFAULT_MAX_CHARS}, cap ${REPLAY_CAP_MAX_CHARS}).` },
    } as JsonObject, ['session_id', 'request_template_id']),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      if (strOf(input, 'session_id', SESSION_ID) !== SESSION_ID) return errorPart('unknown session_id');
      const templateId: string = strOf(input, 'request_template_id', '');
      if (templateId.length === 0) throw new Error('request_template_id is required');
      const blocked: string | null = await guardPage(session, getChatKvStore(), enabled, false);
      if (blocked !== null) return errorPart(blocked);
      const templates: WebMountReplayTemplate[] = await collectWebMountReplayTemplates(session);
      const template: WebMountReplayTemplate | undefined = templates.find(
        (candidate: WebMountReplayTemplate): boolean => candidate.id === templateId);
      if (template === undefined) return errorPart(`request_template_unavailable: ${templateId}`);
      const currentUrl: string = session.getUrl();
      if (template.method !== 'GET' && template.method !== 'HEAD') {
        return errorPart(`replay_not_supported: method ${template.method} (GET/HEAD only)`);
      }
      if (!webMountReplayAllowed(template, currentUrl)) {
        return errorPart('replay_not_supported: template is not a same-origin non-mutating GET/HEAD on the current page');
      }
      const maxChars: number = Math.max(1000, Math.min(
        numOf(input, 'max_chars', REPLAY_DEFAULT_MAX_CHARS), REPLAY_CAP_MAX_CHARS));
      const token: WebMountDocumentToken = await currentToken(session);
      deps.assertOwner();
      const expr: string = `fetch(${jsonString(template.url)}, { method: ${jsonString(template.method)}, credentials: 'include', redirect: 'error', signal: signal })`
        + `.then(function(resp) { return resp.text().then(function(body) {`
        + ` return { status: resp.status, content_type: resp.headers.get('content-type') || '',`
        + ` text: body.slice(0, ${String(maxChars)}), truncated: body.length > ${String(maxChars)} }; }); })`;
      let resultJson: string;
      try {
        resultJson = await session.evalAsyncJson(expr, token, deps.assertOwner, signal, 30000);
      } catch (error) {
        const message: string = error instanceof Error ? error.message : String(error);
        if (/timeout|cancelled|document changed/.test(message)) {
          return errorPart(`outcome_unknown: replay started but its outcome is unconfirmed (${message.substring(0, 120)})`);
        }
        return errorPart(`replay_failed: ${message.substring(0, 200)}`);
      }
      const result: JsonObject = JSON.parse(resultJson) as JsonObject;
      if (typeof result['status'] !== 'number' || (result['status'] as number) <= 0) {
        return errorPart('replay_failed: the page fetch returned no valid status');
      }
      return textPart({
        ok: true,
        request_template_id: template.id,
        source: template.source,
        display_url: webMountRedactedDisplayUrl(template.url),
        status: result['status'] as JsonValue,
        content_type: (result['content_type'] ?? '') as JsonValue,
        text: (result['text'] ?? '') as JsonValue,
        truncated: result['truncated'] === true,
      } as JsonObject);
    },
  });

  const oauthConnectTool: AgentTool = makeAgentTool({
    name: 'wm_oauth_connect',
    description: 'Start the configured site OAuth authorization: opens the system browser after the '
      + 'local callback listener is bound, and reports the truthful pending state. No secrets are '
      + 'returned. Completion is observable via the WebMount settings or wm_oauth_refresh.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'site_id': { type: 'string', description: 'Site id with a saved OAuth application configuration.' },
    } as JsonObject, ['site_id']),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const siteId: string = strOf(input, 'site_id', '').trim();
      if (siteId.length === 0) throw new Error('site_id is required');
      const kv: KeyValueStore = getChatKvStore();
      if (!await syncEnabled(session, kv, enabled)) return errorPart('WebMount is disabled for this conversation');
      const application: WebMountOAuthApplication | null = await loadWebMountOAuthApplication(siteId);
      if (application === null) {
        return errorPart(`oauth_not_configured: no OAuth application saved for "${siteId}"; configure it on the WebMount settings page`);
      }
      const state = await getWebMountOAuthRuntime().connect(siteId, signal);
      return textPart({
        site_id: state.siteId, status: state.status, binding: state.bindingHash !== null,
        error_code: state.errorCode as JsonValue, message: state.message as JsonValue,
      } as JsonObject);
    },
  });

  const oauthRefreshTool: AgentTool = makeAgentTool({
    name: 'wm_oauth_refresh',
    description: 'Refresh the configured site OAuth access token using the saved bound refresh token. '
      + 'Reports the truthful resulting state; reconnect may be required after revocation.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'site_id': { type: 'string', description: 'Site id with a saved OAuth application configuration.' },
    } as JsonObject, ['site_id']),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const siteId: string = strOf(input, 'site_id', '').trim();
      if (siteId.length === 0) throw new Error('site_id is required');
      const kv: KeyValueStore = getChatKvStore();
      if (!await syncEnabled(session, kv, enabled)) return errorPart('WebMount is disabled for this conversation');
      const state = await getWebMountOAuthRuntime().refresh(siteId, signal);
      return textPart({
        site_id: state.siteId, status: state.status, expires_at: state.expiresAtMillis as JsonValue,
        error_code: state.errorCode as JsonValue, message: state.message as JsonValue,
      } as JsonObject);
    },
  });

  return [siteAdapterTool, tapTool, fetchReplayTool, oauthConnectTool, oauthRefreshTool];
};
