// WebMountParityTools — iOS 对齐的五件 WebMount 工具(Harmony ArkWeb 实现)
//
// iOS 基准:ai-core Tool.kt(wm_get/wm_visual_snapshot/wm_visual_read/
//   wm_clear_session/wm_act 声明与参数)+ IOSLocalToolExecutor.swift
//   (getResult/visualSnapshotResult/visualReadResult/clearSessionResult/actResult)。
// 纯逻辑(页内脚本/入参校验/act 计划与判定/cookie 目标/PNG 尺寸)在 chat 侧
//   webmount/parity.ts(有单测);本文件只做 WebView/截图/cookie/模型调用接线。
//
// 安全合同:
//   - 全部沿用 guardPage(全局 OR 助手开关、站点授权、hand 租约)与单会话 'main';
//   - wm_visual_read / wm_clear_session:needsApproval + mandatoryApproval(手动批准,
//     或显式"高风险自动批准"),对齐 iOS requiresUserAction + needsApproval=true;
//   - wm_act 直接调用未经包装的单工具 execute(避免 ConversationBrowserGuard 的
//     inFlight 自锁),因此批量本身静态声明 mandatoryApproval(批内可能含 wm_click/
//     wm_tap,两者在 Harmony 上都是逐次强制人工确认);tool_policy 可按入参细化。
//   - 动态值只经 JSON 字面量注入页内脚本(chat 侧 webMountJsonLiteral)。
//   - wm_clear_session 只清单站点(host/www 孪生/其子域),无 scope=all;hand 租约下拒绝。
//   - wm_act 含 click/tap/type/keys/select 时必须带 document_id + revision(snapshot_required)。

import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  AgentTool, InputSchemaObj, JsonObject, JsonValue, KeyValueStore, ProviderModel, ProviderSetting,
  UIMessage, UIMessagePart, UIMessagePartText, WebMountActPlan, WebMountActStep, WebMountClearTargets,
  WebMountGetRequest, WebMountImageSize, WebMountRedaction, WebMountStepOutcome,
  WebMountActSnapshotCheck, WebMountVisionScale,
} from '@amber/chat-domain';
import {
  makeAgentTool, makeInputSchemaObj, makeUIMessage, webMountRedactedDisplayUrl,
  parseWebMountGetRequest, buildWebMountGetScript, buildWebMountVisualSnapshotScript,
  buildWebMountFindRefScript, planWebMountAct, buildWebMountActStepArgs, webMountActPlainWaitMs,
  webMountActStepMutates, webMountActStepTarget, classifyWebMountStepOutput, webMountTargetSelector,
  parseWebMountCookieNames, webMountCookieExpiryValues, webMountClearTargets,
  webMountRedactCredentials, webMountPngSize, webMountVisualReadSystemPrompt, webMountVisualReadUserText,
  webMountJsonLiteral,
  checkWebMountActSnapshot, webMountActStepChangesPage, webMountActTapSelector, webMountThrownErrorCode,
  webMountVisionTargetSize,
  WEBMOUNT_ACT_MAX_STEPS, WEBMOUNT_VISUAL_READ_DEFAULT_QUESTION, WEBMOUNT_VISUAL_READ_MAX_QUESTION,
  WEBMOUNT_VISUAL_READ_MAX_ANALYSIS, WEBMOUNT_VISION_MAX_EDGE,
} from '@amber/chat-domain';
import type { WebMountSession } from './WebMountSession.ets';
import {
  createWebMountTools, errorPart, guardPage, numOf, strOf, syncEnabled, textPart,
} from './WebMountTools.ets';
import { createWebMountAdvancedTools } from './WebMountAdvancedTools.ets';
import { hostOf, loadWebMountSites } from './WebMountSites.ets';
import type { UserSiteEntry } from './WebMountSites.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import type { ChatProviderChoice } from '../di/AppContainer.ets';
import { generateVisionText, loadOcrSeed } from './OcrSupport.ets';
import type { OcrSeed } from './OcrSupport.ets';
import { webview } from '@kit.ArkWeb';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { deviceInfo } from '@kit.BasicServicesKit';
import type { BusinessError } from '@kit.BasicServicesKit';

const SESSION_ID: string = 'main';
const VISION_TIMEOUT_MS: number = 90000;
const VISION_JPEG_QUALITY: number = 85;
const ACT_SETTLE_MS: number = 250;
const ABORT_POLL_MS: number = 100;
// 与 WebMountTools.guardPage 的 hand 租约拒绝文案一致
const HAND_MODE_MESSAGE: string =
  'user has control of the WebMount session (hand mode); agent actions are paused until control is returned';

export interface WebMountParityToolsDeps {
  session: WebMountSession;
  // 助手自身是否勾选 webmount(OR 语义:global || assistantEnabled)
  assistantEnabled: boolean;
  // 当前会话浏览器 owner 断言(ConversationBrowserGuard);页面动作前调用
  assertOwner: () => void;
  // 当前聊天模型(wm_visual_read 优先用它读图;仅当其原生支持图像输入)
  currentChoice: ChatProviderChoice | null;
  currentModelSupportsImage: boolean;
}

interface EvalJsonEnvelope { ok: boolean; value: JsonValue | null; error: string | null; }

interface DocToken { documentId: string; revision: number; }

interface VisionReader { provider: ProviderSetting; model: ProviderModel; source: string; }

const errorMessage = (e: BusinessError): string =>
  typeof e.message === 'string' && e.message.length > 0 ? e.message : String(e);

const sleep = (ms: number): Promise<void> =>
  new Promise<void>((resolve: () => void): void => { setTimeout(resolve, ms); });

const isJsonObject = (value: JsonValue | null | undefined): boolean =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const evalValue = async (session: WebMountSession, expr: string): Promise<JsonValue | null> => {
  const raw: string = await session.evalJson(expr);
  const parsed: EvalJsonEnvelope = JSON.parse(raw) as EvalJsonEnvelope;
  if (!parsed.ok) throw new Error(parsed.error ?? 'WebMount page evaluation failed');
  return parsed.value;
};

const evalObject = async (session: WebMountSession, expr: string): Promise<JsonObject> => {
  const value: JsonValue | null = await evalValue(session, expr);
  if (!isJsonObject(value)) throw new Error('WebMount page script returned no result');
  return value as JsonObject;
};

const currentRevision = async (session: WebMountSession): Promise<number> => {
  const value: JsonValue | null = await evalValue(
    session, `(function(){ return window.__wmDoc ? window.__wmDoc.revision : -1; })()`);
  return typeof value === 'number' ? value : -1;
};

const currentToken = async (session: WebMountSession): Promise<DocToken> => ({
  documentId: session.getDocumentId(),
  revision: await currentRevision(session),
});

const sessionIdError = (input: JsonValue): string | null =>
  strOf(input, 'session_id', SESSION_ID) !== SESSION_ID ? 'unknown session_id (Harmony WebMount has a single session: main)' : null;

// 可选 document_id/revision 观察绑定(与 wm_click 同口径);不带则不校验
const assertOptionalBinding = async (session: WebMountSession, input: JsonValue): Promise<string | null> => {
  const documentId: string = strOf(input, 'document_id', '');
  if (documentId.length === 0) return null;
  const revision: number = numOf(input, 'revision', -1);
  if (!Number.isInteger(revision) || revision < 0) {
    return 'revision must be a non-negative integer when document_id is provided';
  }
  try {
    await session.assertDocument({ sessionId: SESSION_ID, documentId: documentId, revision: revision });
    return null;
  } catch (_e) {
    return 'stale_snapshot';
  }
};

const staleSnapshotPart = (session: WebMountSession, tool: string): UIMessagePart[] => textPart({
  ok: false, tool: tool, session_id: SESSION_ID, error_code: 'stale_snapshot', requires_reobserve: true,
  document_id: session.getDocumentId(),
  reason: 'The page changed since the supplied document_id/revision was observed. Re-observe before retrying.',
} as JsonObject);

const firstText = (parts: UIMessagePart[]): string => {
  for (const part of parts) {
    if (part.type === 'text') return (part as UIMessagePartText).text;
  }
  return '';
};

const withTimeout = (work: Promise<string | null>, ms: number): Promise<string | null> =>
  new Promise<string | null>((resolve: (value: string | null) => void, reject: (reason: Error) => void): void => {
    const timer: number = setTimeout((): void => { reject(new Error('vision model timeout')); }, ms);
    work.then((value: string | null): void => {
      clearTimeout(timer);
      resolve(value);
    }, (error: Error): void => {
      clearTimeout(timer);
      reject(error);
    });
  });

// 让 Stop 立即生效:work 与 AbortSignal 竞速(事件 + 轮询双通道,signal 的
//   addEventListener 是可选成员)。注意:取消只让本工具立即返回,底层 provider
//   请求不可中断(generateVisionText 不接受 signal),会在后台跑到完成/超时后被丢弃。
const raceAbort = (work: Promise<string | null>, signal?: AbortSignalLike): Promise<string | null> => {
  if (signal === undefined) return work;
  const s: AbortSignalLike = signal;
  return new Promise<string | null>((resolve: (value: string | null) => void, reject: (reason: Error) => void): void => {
    let settled: boolean = false;
    let poll: number = -1;
    let listener: (() => void) | null = null;
    const cleanup = (): void => {
      if (poll >= 0) clearInterval(poll);
      const registered: (() => void) | null = listener;
      if (registered !== null) s.removeEventListener?.('abort', registered);
    };
    const onAbort = (): void => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(new Error('WebMount cancelled'));
    };
    if (s.aborted) {
      onAbort();
      return;
    }
    listener = onAbort;
    s.addEventListener?.('abort', onAbort);
    poll = setInterval((): void => { if (s.aborted) onAbort(); }, ABORT_POLL_MS);
    work.then((value: string | null): void => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(value);
    }, (error: Error): void => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(error);
    });
  });
};

// 可取消的延时(wm_act 纯 wait 步最长 10s;Stop 后 ≤ ABORT_POLL_MS 返回)
const sleepAbortable = async (ms: number, signal?: AbortSignalLike): Promise<boolean> => {
  const deadline: number = Date.now() + ms;
  while (Date.now() < deadline) {
    if (signal !== undefined && signal.aborted) return false;
    await sleep(Math.min(ABORT_POLL_MS, Math.max(0, deadline - Date.now())));
  }
  return !(signal !== undefined && signal.aborted);
};

const stripHash = (url: string): string => url.split('#')[0];

interface VisionImage { dataUrl: string; width: number; height: number; encoding: string; }

// 截图 PNG → 长边 ≤ WEBMOUNT_VISION_MAX_EDGE 的 JPEG(ArtifactPorts.convert 同款
//   createImageSource → PixelMap.scale → packToData,三层 release)。解码/编码失败时
//   退回原 PNG 并以 encoding='png_original' 如实标注。
const prepareVisionImage = async (pngDataUrl: string, original: WebMountImageSize | null): Promise<VisionImage> => {
  const fallback: VisionImage = {
    dataUrl: pngDataUrl, width: original !== null ? original.width : 0,
    height: original !== null ? original.height : 0, encoding: 'png_original',
  };
  const comma: number = pngDataUrl.indexOf(',');
  if (comma < 0) return fallback;
  try {
    const bytes: Uint8Array = new util.Base64Helper().decodeSync(pngDataUrl.substring(comma + 1));
    const buf: ArrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    const source: image.ImageSource = image.createImageSource(buf);
    try {
      const pixelMap: image.PixelMap = await source.createPixelMap();
      try {
        const info: image.ImageInfo = await pixelMap.getImageInfo();
        const target: WebMountVisionScale =
          webMountVisionTargetSize(info.size.width, info.size.height, WEBMOUNT_VISION_MAX_EDGE);
        if (target.scale < 1) {
          await pixelMap.scale(target.width / info.size.width, target.height / info.size.height,
            image.AntiAliasingLevel.HIGH);
        }
        const packer: image.ImagePacker = image.createImagePacker();
        try {
          const packed: ArrayBuffer = await packer.packToData(
            pixelMap, { format: 'image/jpeg', quality: VISION_JPEG_QUALITY });
          const b64: string = new util.Base64Helper().encodeToStringSync(new Uint8Array(packed));
          return { dataUrl: `data:image/jpeg;base64,${b64}`, width: target.width, height: target.height, encoding: 'jpeg' };
        } finally {
          await packer.release();
        }
      } finally {
        await pixelMap.release();
      }
    } finally {
      await source.release();
    }
  } catch (_e) {
    return fallback;
  }
};

const pngSizeOfDataUrl = (dataUrl: string): WebMountImageSize | null => {
  const comma: number = dataUrl.indexOf(',');
  if (comma < 0) return null;
  try {
    const head: Uint8Array = new util.Base64Helper().decodeSync(dataUrl.substring(comma + 1, comma + 1 + 44));
    return webMountPngSize(head);
  } catch (_e) {
    return null;
  }
};

const navLogUrls = (session: WebMountSession): string[] => {
  const urls: string[] = [];
  const current: string = session.getUrl();
  if (current.length > 0) urls.push(current);
  for (const entry of session.getNavLog()) {
    const parts: string[] = entry.split('|');
    if (parts.length >= 3) urls.push(parts.slice(2).join('|'));
  }
  return urls;
};

const fetchCookieHeader = (url: string): string => {
  try {
    return webview.WebCookieManager.fetchCookieSync(url);
  } catch (_e) {
    // 17100002:该 URL 无 cookie / URL 不被接受 — 视作空
    return '';
  }
};

const urlHostAndSecure = (url: string): string[] => {
  const match: RegExpExecArray | null = /^(https?):\/\/([^/:?#]+)/i.exec(url);
  return match === null ? ['', 'false'] : [match[2].toLowerCase(), match[1].toLowerCase() === 'https' ? 'true' : 'false'];
};

export const createWebMountParityTools = (deps: WebMountParityToolsDeps): AgentTool[] => {
  const session: WebMountSession = deps.session;
  const enabled: boolean = deps.assistantEnabled;

  // wm_act 委托目标:未经 ConversationBrowserGuard 包装的单工具实例(含 wm_tap)
  const delegates: Map<string, AgentTool> = new Map<string, AgentTool>();
  for (const tool of createWebMountTools(session, false, enabled)) delegates.set(tool.name, tool);
  for (const tool of createWebMountAdvancedTools({
    session: session, assistantEnabled: enabled, assertOwner: deps.assertOwner,
  })) {
    delegates.set(tool.name, tool);
  }

  // ===== wm_get =====
  const getTool: AgentTool = makeAgentTool({
    name: 'wm_get',
    description: 'Read one visible element\'s text, value, checked state, or one non-sensitive attribute from the '
      + 'current WebMount page, plus its bounding box and a safe attribute summary. Address it with target '
      + '(a ref such as wm-3 from wm_extract/wm_observe/wm_visual_snapshot) or a CSS selector. Raw HTML is not '
      + 'available; password/token-like fields never return their value or attributes. kind=text only reads '
      + 'rendered, visible elements; script/style/template/noscript/meta/link/head elements and hidden inputs are '
      + 'refused. The result includes document_id and revision usable as a wm_act snapshot binding.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'session_id': { type: 'string', description: 'WebMount session id (main).' } as JsonObject,
      'target': { type: 'string', description: 'Preferred element ref from the latest observation (e.g. wm-3).' } as JsonObject,
      'selector': { type: 'string', description: 'CSS selector alternative; when target is supplied omit it or give the same element.' } as JsonObject,
      'kind': { type: 'string', enum: ['text', 'value', 'attr'], description: 'What to read (default text).' } as JsonObject,
      'attr_name': { type: 'string', description: 'Attribute name when kind is attr.' } as JsonObject,
      'max_chars': { type: 'integer', description: 'Maximum characters to return (default 20000, max 100000).' } as JsonObject,
      'document_id': { type: 'string', description: 'Optional observed document id to bind the read to.' } as JsonObject,
      'revision': { type: 'number', description: 'Optional observed document revision (required with document_id).' } as JsonObject,
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const badSession: string | null = sessionIdError(input);
      if (badSession !== null) return errorPart(badSession);
      const request: WebMountGetRequest = parseWebMountGetRequest(input);
      if (!request.ok) {
        return textPart({
          ok: false, tool: 'wm_get', session_id: SESSION_ID, denied: true,
          error_code: request.errorCode, reason: request.reason, error: request.reason,
        } as JsonObject);
      }
      const blocked: string | null = await guardPage(session, getChatKvStore(), enabled, false);
      if (blocked !== null) return errorPart(blocked);
      const stale: string | null = await assertOptionalBinding(session, input);
      if (stale === 'stale_snapshot') return staleSnapshotPart(session, 'wm_get');
      if (stale !== null) return errorPart(stale);
      const result: JsonObject = await evalObject(session,
        buildWebMountGetScript(request.selector, request.kind, request.attrName, request.maxChars));
      // 页内拒读:script/style/template/noscript/meta/link/head、hidden input、不可见元素的 text
      if (result['denied'] === true) {
        const code: string = typeof result['error'] === 'string' ? result['error'] as string : 'restricted_element';
        return textPart({
          ok: false, tool: 'wm_get', session_id: SESSION_ID, denied: true, error_code: code,
          target: request.target, document_id: session.getDocumentId(),
          revision: (result['revision'] ?? -1) as JsonValue,
          reason: typeof result['reason'] === 'string' ? result['reason'] as string : 'wm_get refused to read this element.',
        } as JsonObject);
      }
      if (result['found'] !== true) {
        const code: string = typeof result['error'] === 'string' ? result['error'] as string : 'target_not_found';
        return textPart({
          ok: false, tool: 'wm_get', session_id: SESSION_ID, error_code: code,
          target: request.target, document_id: session.getDocumentId(),
          revision: (result['revision'] ?? -1) as JsonValue,
          reason: code === 'invalid_selector' ? 'The selector is not valid CSS.'
            : 'No element matches this target; refs expire when the page changes. Re-observe and retry.',
        } as JsonObject);
      }
      let credentialRedacted: boolean = false;
      for (const key of ['text', 'value', 'attr_value']) {
        const raw: JsonValue | undefined = result[key];
        if (typeof raw === 'string') {
          const redaction: WebMountRedaction = webMountRedactCredentials(raw);
          if (redaction.redacted) {
            result[key] = redaction.text;
            credentialRedacted = true;
          }
        }
      }
      if (typeof result['url'] === 'string') result['url'] = webMountRedactedDisplayUrl(result['url'] as string);
      return textPart({
        ok: true, tool: 'wm_get', session_id: SESSION_ID,
        document_id: session.getDocumentId(), revision: (result['revision'] ?? -1) as JsonValue,
        credential_redacted: credentialRedacted, untrusted_page_content: true, result: result,
      } as JsonObject);
    },
  });

  // ===== wm_visual_snapshot =====
  const visualSnapshotTool: AgentTool = makeAgentTool({
    name: 'wm_visual_snapshot',
    description: 'Return the DOM elements visible in the current WebMount viewport: interactive elements with '
      + 'refs (same numbering as wm_extract/wm_observe) and visual candidates such as image, iframe, canvas, '
      + 'video, SVG and text-block rectangles. Prefer interactive_target_ref when a candidate provides one; do '
      + 'not directly single-click a candidate whose single_click_supported is false. No vision model is called. '
      + 'The result carries document_id and revision; pass both to wm_act for batches that click/tap/type/keys/select.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'session_id': { type: 'string', description: 'WebMount session id (main).' } as JsonObject,
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const badSession: string | null = sessionIdError(input);
      if (badSession !== null) return errorPart(badSession);
      const blocked: string | null = await guardPage(session, getChatKvStore(), enabled, false);
      if (blocked !== null) return errorPart(blocked);
      const result: JsonObject = await evalObject(session, buildWebMountVisualSnapshotScript());
      if (typeof result['url'] === 'string') result['url'] = webMountRedactedDisplayUrl(result['url'] as string);
      return textPart({
        ok: true, tool: 'wm_visual_snapshot', session_id: SESSION_ID,
        document_id: session.getDocumentId(), revision: (result['revision'] ?? -1) as JsonValue,
        state: { url: webMountRedactedDisplayUrl(session.getUrl()), ready: session.isReady() } as JsonObject,
        result: result, vision_model_called: false, untrusted_page_content: true,
        read_more: 'Use wm_get with target for one element; use wm_visual_read (approval) for real visual confirmation.',
      } as JsonObject);
    },
  });

  // ===== wm_visual_read =====
  const resolveVisionReaders: (kv: KeyValueStore) => Promise<VisionReader[]> =
    async (kv: KeyValueStore): Promise<VisionReader[]> => {
    const readers: VisionReader[] = [];
    if (deps.currentModelSupportsImage && deps.currentChoice !== null) {
      readers.push({ provider: deps.currentChoice.provider, model: deps.currentChoice.model, source: 'current_chat_model' });
    }
    try {
      const seed: OcrSeed = await loadOcrSeed(kv);
      const choice: ChatProviderChoice | null = seed.ocrChoice;
      if (choice !== null && choice.model.inputModalities.includes('image')
        && !readers.some((r: VisionReader): boolean => r.model.id === choice.model.id)) {
        readers.push({ provider: choice.provider, model: choice.model, source: 'auxiliary_vision_model' });
      }
    } catch (_e) {
      // OCR 设置不可读 → 仅用当前模型(若有)
    }
    return readers;
  };

  const visualReadTool: AgentTool = makeAgentTool({
    name: 'wm_visual_read',
    description: 'Capture the current WebMount viewport and ask a vision model about what is visibly rendered: '
      + 'the current chat model when it natively supports images, otherwise the configured auxiliary vision '
      + '(OCR) model. Use after navigation or a key browser action when visual confirmation matters; use '
      + 'wm_observe/wm_visual_snapshot for DOM targets. Sends the screenshot to the model provider and requires '
      + 'manual approval or high-risk auto-approval. It never clicks or types. If vision is unavailable, say '
      + 'visual verification has not occurred. ok only means the image was analyzed, not that an action succeeded. '
      + 'The result is stale (ok=false) only if the page navigated; dom_changed=true is advisory (refs may need re-observation).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'session_id': { type: 'string', description: 'WebMount session id (main).' } as JsonObject,
      'question': { type: 'string', description: 'Optional visual verification target, e.g. whether the login form is visible.' } as JsonObject,
    } as JsonObject, null),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const badSession: string | null = sessionIdError(input);
      if (badSession !== null) return errorPart(badSession);
      const asked: string = strOf(input, 'question', '').trim();
      const question: string = asked.length > 0 ? asked : WEBMOUNT_VISUAL_READ_DEFAULT_QUESTION;
      if (question.length > WEBMOUNT_VISUAL_READ_MAX_QUESTION) {
        return textPart({ ok: false, tool: 'wm_visual_read', error_code: 'invalid_arguments',
          reason: `question must be at most ${WEBMOUNT_VISUAL_READ_MAX_QUESTION} characters.` } as JsonObject);
      }
      const kv: KeyValueStore = getChatKvStore();
      const blocked: string | null = await guardPage(session, kv, enabled, false);
      if (blocked !== null) return errorPart(blocked);
      const readers: VisionReader[] = await resolveVisionReaders(kv);
      if (readers.length === 0) {
        return textPart({
          ok: false, tool: 'wm_visual_read', session_id: SESSION_ID, error_code: 'vision_unavailable',
          visual_verified: false, dom_only: true, automatic_retry_allowed: false,
          reason: 'The current chat model does not accept images and no auxiliary vision (OCR) model is configured. Visual verification was not performed.',
        } as JsonObject);
      }
      deps.assertOwner();
      if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
      // 过期判据:document_id + URL(去 hash);DOM revision 只作提示(dom_changed),
      //   动态页面的 MutationObserver 频繁递增 revision,不能据此判视觉读取失效。
      const before: DocToken = await currentToken(session);
      const beforeUrl: string = stripHash(session.getUrl());
      if (before.documentId.length === 0) return errorPart('no committed document; call wm_open first');
      const capturedAt: number = Date.now();
      const rawDataUrl: string = await session.screenshotDataUrl();
      if (session.getDocumentId() !== before.documentId || stripHash(session.getUrl()) !== beforeUrl) {
        return textPart({
          ok: false, tool: 'wm_visual_read', session_id: SESSION_ID, error_code: 'page_changed',
          requires_reobserve: true, visual_verified: false,
          reason: 'The page navigated during capture. Wait for a stable page and retry visual reading.',
        } as JsonObject);
      }
      const afterCapture: DocToken = await currentToken(session).catch((_e: Error): DocToken =>
        ({ documentId: before.documentId, revision: -1 } as DocToken));
      if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
      const originalSize: WebMountImageSize | null = pngSizeOfDataUrl(rawDataUrl);
      const visionImage: VisionImage = await prepareVisionImage(rawDataUrl, originalSize);
      const title: string = await evalValue(session, '(function(){ return document.title; })()')
        .then((v: JsonValue | null): string => typeof v === 'string' ? v : '')
        .catch((_e: Error): string => '');
      const messages: UIMessage[] = [
        makeUIMessage('system', [{ type: 'text', text: webMountVisualReadSystemPrompt(), metadata: null }]),
        makeUIMessage('user', [
          { type: 'text', text: webMountVisualReadUserText(question, session.getUrl(), title), metadata: null },
          { type: 'image', url: visionImage.dataUrl, metadata: null },
        ]),
      ];
      let analysis: string | null = null;
      let source: string = '';
      let modelId: string = '';
      const failures: JsonValue[] = [];
      for (const reader of readers) {
        if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
        try {
          // Stop 立即返回;provider 请求本身不可中断,会在后台结束后被丢弃
          const text: string | null = await raceAbort(withTimeout(
            generateVisionText(reader.provider, reader.model, messages), VISION_TIMEOUT_MS), signal);
          if (text !== null && text.trim().length > 0) {
            analysis = text;
            source = reader.source;
            modelId = reader.model.modelId;
            break;
          }
          failures.push({ source: reader.source, error: 'empty response' } as JsonObject);
        } catch (e) {
          if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
          failures.push({ source: reader.source, error: errorMessage(e as BusinessError).substring(0, 200) } as JsonObject);
        }
      }
      if (signal !== undefined && signal.aborted) throw new Error('WebMount cancelled');
      if (analysis === null) {
        return textPart({
          ok: false, tool: 'wm_visual_read', session_id: SESSION_ID, error_code: 'vision_failed',
          visual_verified: false, dom_only: true, failures: failures,
          reason: 'The vision model did not return an analysis. Visual verification was not performed.',
        } as JsonObject);
      }
      const current: DocToken = await currentToken(session).catch((_e: Error): DocToken =>
        ({ documentId: '', revision: -1 } as DocToken));
      const stale: boolean = session.getDocumentId() !== before.documentId
        || stripHash(session.getUrl()) !== beforeUrl;
      const domChanged: boolean = !stale && (afterCapture.revision !== before.revision
        || current.revision !== before.revision);
      const redacted: WebMountRedaction = webMountRedactCredentials(analysis.substring(0, WEBMOUNT_VISUAL_READ_MAX_ANALYSIS));
      return textPart({
        ok: !stale, tool: 'wm_visual_read', session_id: SESSION_ID,
        document_id: before.documentId, revision: before.revision, captured_at_ms: capturedAt,
        width: originalSize !== null ? originalSize.width : null, height: originalSize !== null ? originalSize.height : null,
        sent_image: { width: visionImage.width, height: visionImage.height, encoding: visionImage.encoding } as JsonObject,
        verification_source: 'screenshot', vision_model_source: source, vision_model_id: modelId,
        observation_only: true, untrusted_page_content: true, requires_reobserve: stale,
        dom_changed: domChanged,
        error_code: stale ? 'stale_visual_read' : '',
        reason: stale ? 'The page navigated while the vision model was reading. This analysis describes an older page; observe again before acting.'
          : (domChanged ? 'The DOM changed after capture (advisory); refs and revision may need re-observation before acting.' : ''),
        analysis: redacted.text,
      } as JsonObject);
    },
  });

  // ===== wm_clear_session =====
  // 只清一个站点:站点 host、www 孪生及其子域(webMountClearTargets)。没有 scope=all ——
  //   WebCookieManager/WebStorage 是应用级共享的(DeepRead 谷歌搜索宿主、JS 沙箱、导入的
  //   cookie 都在其中),全清会波及 WebMount 之外;iOS 同工具也无 scope 参数。
  const clearSessionTool: AgentTool = makeAgentTool({
    name: 'wm_clear_session',
    description: 'Log the embedded browser out of ONE WebMount station: expire the cookies and delete the website '
      + 'storage of that station\'s own host, its www/bare twin, and visited subdomains of it. Parent-domain or '
      + 'sibling-site cookies are never touched and are reported as remaining. It does not clear other stations, '
      + 'other sites, or app-wide browser data. Refused while the user has taken control (hand mode). '
      + 'Requires manual approval or high-risk auto-approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'site_id': { type: 'string', description: 'Station id from wm_stations whose login state should be cleared.' } as JsonObject,
    } as JsonObject, ['site_id']),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const siteId: string = strOf(input, 'site_id', '').trim();
      const scope: string = strOf(input, 'scope', '').trim().toLowerCase();
      if (scope.length > 0 && scope !== 'site') {
        return textPart({ ok: false, tool: 'wm_clear_session', denied: true, error_code: 'invalid_arguments',
          reason: 'wm_clear_session only clears one station; there is no scope parameter.' } as JsonObject);
      }
      const kv: KeyValueStore = getChatKvStore();
      // guardPage(mutating=true) 的前两道门:开关 + hand 租约。不要求已打开页面
      //   (登出不应以先打开该站为前提),因此不直接调用 guardPage。
      if (!await syncEnabled(session, kv, enabled)) return errorPart('WebMount is disabled for this conversation');
      if (session.getLease() === 'hand') return errorPart(HAND_MODE_MESSAGE);
      const sites: UserSiteEntry[] = await loadWebMountSites(kv);
      const site: UserSiteEntry | undefined = sites.find((s: UserSiteEntry): boolean => s.id === siteId);
      if (siteId.length === 0 || site === undefined) {
        return textPart({ ok: false, tool: 'wm_clear_session', error_code: 'site_not_found',
          error: 'wm_clear_session requires a valid site_id from wm_stations' } as JsonObject);
      }
      const host: string = hostOf(site.url);
      if (host.length === 0) return errorPart('station url is not a valid http(s) url');
      const targets: WebMountClearTargets = webMountClearTargets(host, navLogUrls(session));
      const currentHost: string = hostOf(session.getUrl());
      const currentOnSite: boolean = currentHost.length > 0 && targets.hosts.indexOf(currentHost) >= 0;

      // 逐 URL 取 cookie 名 → 写入立即过期的 Set-Cookie 变体(host-only/Domain=该 URL 的 host;从不写父域)
      const canOverwriteHttpOnly: boolean = deviceInfo.sdkApiVersion >= 14
        && canIUse('SystemCapability.Web.Webview.Core');
      const seen: string[] = [];
      for (const url of targets.urls) {
        const header: string = fetchCookieHeader(url);
        if (header.length === 0) continue;
        const hostSecure: string[] = urlHostAndSecure(url);
        for (const name of parseWebMountCookieNames(header)) {
          const key: string = `${hostSecure[0]}|${name}`;
          if (seen.indexOf(key) < 0) seen.push(key);
          for (const value of webMountCookieExpiryValues(name, hostSecure[0], hostSecure[1] === 'true')) {
            try {
              webview.WebCookieManager.configCookieSync(url, value);
            } catch (_e) {
              // 非法 Domain 变体由内核拒绝 — 尽力而为
            }
            if (canOverwriteHttpOnly) {
              try {
                webview.WebCookieManager.configCookieSync(url, value, false, true);
              } catch (_e) {
                // 同上
              }
            }
          }
        }
      }
      try {
        await webview.WebCookieManager.saveCookieAsync();
      } catch (_e) {
        // 落盘失败不影响内存态清理结果
      }
      const remaining: string[] = [];
      for (const url of targets.urls) {
        const hostSecure: string[] = urlHostAndSecure(url);
        for (const name of parseWebMountCookieNames(fetchCookieHeader(url))) {
          const key: string = `${hostSecure[0]}|${name}`;
          if (remaining.indexOf(key) < 0) remaining.push(key);
        }
      }
      // deleteOrigin 无返回值/记录数:这里只统计未抛错的调用次数(origin 无数据时抛 17100011)
      let websiteDataDeleteCalls: number = 0;
      for (const origin of targets.origins) {
        try {
          webview.WebStorage.deleteOrigin(origin);
          websiteDataDeleteCalls++;
        } catch (_e) {
          // 17100011:该 origin 无存储数据
        }
      }
      let pageStorageCleared: boolean = false;
      if (currentOnSite && session.isReady()) {
        try {
          await session.evalJson(`(function(){ try { localStorage.clear(); } catch (e) {}
            try { sessionStorage.clear(); } catch (e) {} return true; })()`);
          pageStorageCleared = true;
        } catch (_e) {
          pageStorageCleared = false;
        }
      }
      // 计数按 "host|name" 键:expired = 清理前见过且清理后不再可见的;remaining = 清理后
      //   在任一目标 URL 仍可见的(含父域共享 cookie、非 / 路径 cookie、无法覆写的 HttpOnly)
      let expired: number = 0;
      for (const key of seen) {
        if (remaining.indexOf(key) < 0) expired++;
      }
      const ok: boolean = remaining.length === 0;
      return textPart({
        ok: ok, tool: 'wm_clear_session', site_id: site.id,
        hosts: targets.hosts as JsonValue,
        deleted_cookie_count: expired,
        remaining_cookie_count: remaining.length,
        website_data_delete_calls: websiteDataDeleteCalls,
        website_data_delete_calls_note: 'Number of per-origin WebStorage.deleteOrigin calls that succeeded; not a count of deleted records.',
        current_page_storage_cleared: pageStorageCleared,
        reload_recommended: currentOnSite,
        error_code: ok ? '' : 'cookies_remaining',
        reason: ok ? '' : (canOverwriteHttpOnly
          ? 'Some cookies are still visible to this station: they belong to a parent domain shared with other sites, '
            + 'use a non-root path, or could not be expired individually. They were intentionally left so other sites stay logged in; '
            + 'the station may still be logged in. Tell the user to sign out on the site itself if needed.'
          : 'This system version cannot overwrite HttpOnly cookies individually, and parent-domain cookies are intentionally '
            + 'left untouched; the station may still be logged in. Tell the user to sign out on the site itself if needed.'),
        redacted: true,
      } as JsonObject);
    },
  });

  // ===== wm_act =====
  const actTool: AgentTool = makeAgentTool({
    name: 'wm_act',
    description: 'Execute a small ordered batch of page actions in one call. Steps run serially through the same '
      + 'site/hand-mode/document gates as the matching single wm_* tool and stop at the first failure. A find '
      + 'step (selector, text, optional role, or locator {text|name, role}) resolves one element ref that a '
      + 'following step without target uses. Any document navigation aborts the remaining steps. Use for 1-3 '
      + 'step tasks like find-then-click or type-then-Enter. Steps: action=find|wait|click|tap|type|keys|scroll|'
      + `select (max ${WEBMOUNT_ACT_MAX_STEPS}). Batches containing click/tap/type/keys/select must pass document_id `
      + 'and revision from the latest wm_visual_snapshot or wm_get result; otherwise the call is refused with '
      + 'snapshot_required, and a changed page is refused with stale_snapshot. Never submits payments, deletes, or '
      + 'logs in on its own. Batches containing click/tap require explicit human approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'session_id': { type: 'string', description: 'WebMount session id (main).' } as JsonObject,
      'document_id': { type: 'string', description: 'Document id from the latest wm_visual_snapshot/wm_get result. Required when any step is click/tap/type/keys/select.' } as JsonObject,
      'revision': { type: 'number', description: 'Document revision from the same observation. Required with document_id.' } as JsonObject,
      'steps': {
        type: 'array',
        description: `Ordered steps (max ${WEBMOUNT_ACT_MAX_STEPS}). Each: action plus fields — find{selector|text|role|locator}, `
          + 'click{target}, tap{target|selector|x,y}, type{target|selector,text}, keys{keys|key,target?}, scroll{by_y|dy,dx,direction}, '
          + 'select{target|selector,value}, wait{url_includes|title_includes|selector|text_includes,timeout_ms | wait_ms}. '
          + 'A step without target uses the ref from the latest find step.',
        items: { type: 'object', description: 'One step, e.g. {"action":"find","text":"More"} then {"action":"click"}.' } as JsonObject,
      } as JsonObject,
    } as JsonObject, ['steps']),
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const badSession: string | null = sessionIdError(input);
      if (badSession !== null) return errorPart(badSession);
      const plan: WebMountActPlan = planWebMountAct(input, WEBMOUNT_ACT_MAX_STEPS);
      if (!plan.ok) {
        return textPart({ ok: false, tool: 'wm_act', session_id: SESSION_ID, denied: true,
          error_code: plan.errorCode, reason: plan.reason } as JsonObject);
      }
      // iOS snapshot_required:含改变页面的步时必须锚定近期观察(document_id + revision)
      const binding: WebMountActSnapshotCheck = checkWebMountActSnapshot(input, plan);
      if (!binding.ok) {
        return textPart({ ok: false, tool: 'wm_act', session_id: SESSION_ID, denied: true,
          error_code: binding.errorCode, requires_reobserve: binding.errorCode === 'snapshot_required',
          reason: binding.reason } as JsonObject);
      }
      const mutating: boolean = plan.steps.some((s: WebMountActStep): boolean => webMountActStepMutates(s.action));
      const blocked: string | null = await guardPage(session, getChatKvStore(), enabled, mutating);
      if (blocked !== null) return errorPart(blocked);
      if (binding.documentId.length > 0) {
        try {
          await session.assertDocument({ sessionId: SESSION_ID, documentId: binding.documentId, revision: binding.revision });
        } catch (_e) {
          return staleSnapshotPart(session, 'wm_act');
        }
      }
      const epochDoc: string = session.getDocumentId();
      if (epochDoc.length === 0) return errorPart('no committed document; call wm_open first');

      let lastRef: string | null = null;
      let completed: number = 0;
      let dispatchedMutations: number = 0;
      let uncertainMutation: boolean = false;
      let pageNavigated: boolean = false;
      const reports: JsonValue[] = [];
      let abort: JsonObject | null = null;

      const finish = async (): Promise<UIMessagePart[]> => {
        const finalRevision: number = await currentRevision(session).catch((_e: Error): number => -1);
        const response: JsonObject = {
          ok: abort === null, tool: 'wm_act', session_id: SESSION_ID,
          executed: completed, total: plan.steps.length,
          may_have_applied: dispatchedMutations > 0 || uncertainMutation,
          steps: reports,
          final: {
            url: webMountRedactedDisplayUrl(session.getUrl()),
            document_id: session.getDocumentId(),
            revision: finalRevision,
            page_navigated: pageNavigated,
          } as JsonObject,
        } as JsonObject;
        const failed: JsonObject | null = abort;
        if (failed !== null) {
          response['aborted'] = failed;
          response['error_code'] = failed['error_code'] ?? 'step_failed';
          if (failed['reason'] !== undefined) response['reason'] = failed['reason'];
          if (failed['retry_hint'] !== undefined) response['retry_hint'] = failed['retry_hint'];
        }
        return textPart(response);
      };

      // 开批前置:owner 断言 + 文档纪元标记。任何异常都折成 aborted 结果(此时未派发任何步)
      try {
        deps.assertOwner();
      } catch (e) {
        abort = { index: -1, action: 'setup', error_code: 'browser_owner_lost',
          reason: errorMessage(e as BusinessError).substring(0, 300) } as JsonObject;
        return finish();
      }
      // 文档纪元标记:新文档不会带这枚标记;beforeunload 置位即导航已起航
      const epochMark: string = `${epochDoc}:${Date.now()}`;
      try {
        const marked: JsonValue | null = await evalValue(session, `(function(){ window.__wmActEpoch = ${webMountJsonLiteral(epochMark)}; window.__wmActUnloading = false;
          if (!window.__wmActHooked) { window.__wmActHooked = true;
            window.addEventListener('beforeunload', function(){ window.__wmActUnloading = true; }); }
          return true; })()`);
        if (marked !== true) throw new Error('epoch mark was not applied');
      } catch (e) {
        const message: string = errorMessage(e as BusinessError);
        const code: string = webMountThrownErrorCode(message);
        abort = { index: -1, action: 'setup', error_code: code === 'step_error' ? 'epoch_mark_failed' : code,
          reason: message.substring(0, 300) } as JsonObject;
        return finish();
      }
      const navigated: () => Promise<boolean> = async (): Promise<boolean> => {
        if (session.getDocumentId() !== epochDoc || !session.isReady()) return true;
        try {
          const v: JsonValue | null = await evalValue(session, `(function(){ return window.__wmActEpoch === ${webMountJsonLiteral(epochMark)}
            && window.__wmActUnloading !== true; })()`);
          return v !== true;
        } catch (_e) {
          return true;
        }
      };

      for (const step of plan.steps) {
        if (signal !== undefined && signal.aborted) {
          abort = { index: step.index, action: step.action, error_code: 'cancelled', reason: 'The batch was cancelled.' } as JsonObject;
          break;
        }
        try {
          deps.assertOwner();
        } catch (e) {
          abort = { index: step.index, action: step.action, error_code: 'browser_owner_lost',
            reason: errorMessage(e as BusinessError).substring(0, 300) } as JsonObject;
          break;
        }
        if (await navigated()) {
          abort = { index: step.index, action: step.action, error_code: 'page_navigated',
            reason: 'Document changed mid-batch; remaining steps were not dispatched.' } as JsonObject;
          pageNavigated = true;
          break;
        }
        const report: JsonObject = { index: step.index, action: step.action, ok: false, dispatched: false } as JsonObject;
        let outcome: WebMountStepOutcome;
        if (step.action === 'find') {
          const locatorRaw: JsonValue | undefined = step.fields['locator'];
          const locator: JsonObject = isJsonObject(locatorRaw) ? locatorRaw as JsonObject : {} as JsonObject;
          const target: string = strOf(step.fields, 'target', '').trim();
          const selector: string = target.length > 0 ? webMountTargetSelector(target) : strOf(step.fields, 'selector', '').trim();
          const text: string = strOf(step.fields, 'text', strOf(locator, 'text', strOf(locator, 'name', strOf(step.fields, 'name', ''))));
          const role: string = strOf(step.fields, 'role', strOf(locator, 'role', ''));
          try {
            const value: JsonObject = await evalObject(session, buildWebMountFindRefScript(selector, text, role));
            outcome = classifyWebMountStepOutput(JSON.stringify(value));
            lastRef = value['found'] === true && typeof value['ref'] === 'string' ? value['ref'] as string : null;
            report['found'] = value['found'] === true;
            report['ref'] = lastRef ?? '';
            report['count'] = (value['count'] ?? 0) as JsonValue;
          } catch (e) {
            const message: string = errorMessage(e as BusinessError);
            outcome = { ok: false, errorCode: webMountThrownErrorCode(message), reason: message, value: null } as WebMountStepOutcome;
          }
        } else {
          const plainWait: number | null = webMountActPlainWaitMs(step);
          if (plainWait !== null) {
            const waited: boolean = await sleepAbortable(plainWait, signal);
            outcome = waited ? { ok: true, errorCode: '', reason: '', value: null } as WebMountStepOutcome
              : { ok: false, errorCode: 'cancelled', reason: 'The batch was cancelled.', value: null } as WebMountStepOutcome;
          } else {
            const usesPoint: boolean = step.action === 'tap' && typeof step.fields['x'] === 'number'
              && typeof step.fields['y'] === 'number';
            const target: string = webMountActStepTarget(step, lastRef);
            const needsTarget: boolean = ['click', 'tap', 'type', 'select'].indexOf(step.action) >= 0 && !usesPoint;
            const delegate: AgentTool | undefined = delegates.get(step.toolName);
            if (needsTarget && target.length === 0) {
              outcome = { ok: false, errorCode: 'target_required',
                reason: 'Step needs target/selector, or a preceding find step that matched.', value: null } as WebMountStepOutcome;
            } else if (delegate === undefined) {
              outcome = { ok: false, errorCode: 'tool_unavailable', reason: `${step.toolName} is not available`, value: null } as WebMountStepOutcome;
            } else {
              let attempted: boolean = false;
              try {
                // wm_tap 只接受 wm-N ref 或坐标:CSS 选择器先经 find 脚本解析成 ref
                let tapRef: string | null = null;
                let resolveFailure: WebMountStepOutcome | null = null;
                const tapSelector: string | null = webMountActTapSelector(step, lastRef);
                if (tapSelector !== null) {
                  const found: JsonObject = await evalObject(session, buildWebMountFindRefScript(tapSelector, '', ''));
                  if (found['found'] === true && typeof found['ref'] === 'string') {
                    tapRef = found['ref'] as string;
                    report['resolved_ref'] = tapRef;
                  } else {
                    const code: string = typeof found['error'] === 'string' ? found['error'] as string : 'target_not_found';
                    resolveFailure = { ok: false, errorCode: code,
                      reason: `tap selector did not resolve to a visible element (${code})`, value: found } as WebMountStepOutcome;
                  }
                }
                if (resolveFailure !== null) {
                  outcome = resolveFailure;
                } else {
                  const revision: number = await currentRevision(session);
                  const args: JsonObject = buildWebMountActStepArgs(step,
                    { lastRef: lastRef, documentId: epochDoc, revision: revision });
                  if (tapRef !== null) args['ref'] = tapRef;
                  attempted = true;
                  const parts: UIMessagePart[] = await delegate.execute(args, signal);
                  outcome = classifyWebMountStepOutput(firstText(parts));
                  report['dispatched'] = outcome.ok && webMountActStepMutates(step.action);
                }
              } catch (e) {
                const message: string = errorMessage(e as BusinessError);
                const code: string = webMountThrownErrorCode(message);
                outcome = { ok: false, errorCode: code, reason: message, value: null } as WebMountStepOutcome;
                // 委托内部异常且非"派发前拒绝"类(文档已变/取消):无法确认页面是否已被改动
                if (attempted && code !== 'document_changed' && code !== 'cancelled'
                  && webMountActStepChangesPage(step.action)) {
                  report['outcome_unknown'] = true;
                  uncertainMutation = true;
                }
              }
            }
          }
        }
        report['ok'] = outcome.ok;
        if (!outcome.ok) report['error_code'] = outcome.errorCode;
        reports.push(report);
        if (!outcome.ok) {
          abort = { index: step.index, action: step.action, error_code: outcome.errorCode,
            reason: outcome.reason.substring(0, 300),
            retry_hint: outcome.errorCode === 'document_changed'
              ? 'The page changed; observe again before retrying.'
              : `Re-issue this step as a single ${step.toolName} call to see its full result.` } as JsonObject;
          break;
        }
        completed++;
        if (step.action === 'find' && lastRef === null) {
          abort = { index: step.index, action: step.action, error_code: 'target_not_found',
            reason: 'find step matched no element; dependent steps were not dispatched.' } as JsonObject;
          break;
        }
        if (webMountActStepMutates(step.action)) {
          dispatchedMutations++;
          await sleep(ACT_SETTLE_MS);
          if (await navigated()) {
            pageNavigated = true;
            report['page_changed'] = true;
            if (step.index < plan.steps.length - 1) {
              abort = { index: step.index, action: step.action, error_code: 'page_navigated',
                reason: 'Document changed mid-batch; remaining steps were not dispatched.' } as JsonObject;
              break;
            }
          }
        }
      }
      return finish();
    },
  });

  return [getTool, visualSnapshotTool, visualReadTool, clearSessionTool, actTool];
};
