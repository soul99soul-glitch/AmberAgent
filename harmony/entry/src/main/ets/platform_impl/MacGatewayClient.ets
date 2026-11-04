// MacGatewayClient — 与 Mac 上 amber-gateway 守护进程配对、查询任务状态、上报 Push Token
//
// 协议对齐 amber-gateway DaemonAPI(与 iOS MacGatewayClient / Android MacGatewayClient 同构):
//   二维码 amber://gateway/pair?p=<base64url JSON {v,id,name,addrs,port,fp,s}>
//   自签证书:信任锚是二维码里的 SPKI SHA-256(fp),RCP 跳过 CA 校验 + certificatePinning 公钥锁定;
//   地址按序尝试(.local / 局域网 IP / Tailscale),记住首个可达地址。

import { rcp } from '@kit.RemoteCommunicationKit';
import { util } from '@kit.ArkTS';
import { pushService } from '@kit.PushKit';
import { BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getChatKvStore } from '../di/AppContainer.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'MacGateway';
const CONNECTION_KEY: string = 'mac_gateway_connection_v1';
export const CLOUD_DIALOGUE_ENABLED_KEY: string = 'mac_gateway_cloud_dialogue_enabled_v1';
export const CLOUD_DIALOGUE_STATE_KEY: string = 'mac_gateway_cloud_dialogue_state_v1';
const MAX_LINK_LENGTH: number = 4096;
const CLOUD_REQUEST_ID_MAX_LEN: number = 128;
const CLOUD_TEXT_MAX_LEN: number = 24000;
// RCP 公钥锁定不匹配(curl CURLE_SSL_PINNEDPUBKEYNOTMATCH=90)
const PIN_MISMATCH_CODE: number = 1007900090;
const PUSH_NOT_CONFIGURED_CODE: number = 1000900010;

export interface MacGatewayPairingPayload {
  v: number;
  id: string;
  name: string;
  addrs: string[];
  port: number;
  fp: string;
  s: string;
}

export interface MacGatewayConnection {
  gatewayId: string;
  name: string;
  addrs: string[];
  port: number;
  fingerprint: string;
  deviceId: string;
  token: string;
}

export interface MacGatewayGateway {
  id: string;
  name: string;
}

export interface MacGatewayDevice {
  id: string;
  name: string;
  pushRegistered: boolean;
}

export interface MacGatewayHealth {
  sampledAt: number;
  diskFreeBytes?: number;
  batteryPercent?: number;
  onBattery?: boolean;
  memoryPressure: string;
  thermal: string;
}

export interface MacGatewaySession {
  key: string;
  agent: string;
  subject: string;
  origin: string;
  // Mac 上承载该会话的应用,如 Synara
  host?: string;
  state: string;
  waitReason?: string;
  abnormal: boolean;
  monitored: boolean;
  updatedAt: number;
}

export interface MacGatewayStatus {
  gateway: MacGatewayGateway;
  device: MacGatewayDevice;
  serverTime: number;
  health?: MacGatewayHealth;
  // 在外网回复/确认 Synara 任务的网页地址,用浏览器打开
  synaraURL?: string;
  sessions: MacGatewaySession[];
  // C-SOUL-V1:可选的云端形象人设修订号,大于本地已同步 revision 时拉取 /v2/soul
  soulRevision?: number;
}

export type CloudAgentSoulWriteSource = 'harmony_ui' | 'harmony_chat';

export interface CloudAgentSoulHistoryItem {
  id: string;
  displayName: string;
  tagline: string;
  soulMarkdown: string;
  updatedAtMs: number;
  source: string;
}

export interface CloudAgentSoulPayload {
  revision: number;
  updatedAtMs: number;
  displayName: string;
  tagline: string;
  soulMarkdown: string;
  isDefault: boolean;
  source: string;
  history: CloudAgentSoulHistoryItem[];
}

export interface CloudAgentSoulPutRequest {
  displayName: string;
  tagline: string;
  soulMarkdown: string;
  isDefault: boolean;
  updatedAtMs: number;
  source: CloudAgentSoulWriteSource;
}

export type CloudDialogueInputState = 'received' | 'consumed' | 'cancelled';

export type CloudDialogueEventKind =
  | 'user_message'
  | 'followup'
  | 'stop_requested'
  | 'assistant_reply'
  | 'input_consumed'
  | 'input_cancelled'
  | 'process_event'
  | 'task_result';

export type CloudDialogueEventRole = 'user' | 'assistant' | 'system';

export interface CloudDialogueReceipt {
  eventId: string;
  requestId: string;
  topicId?: string | null;
  workItemIds: string[];
  state: CloudDialogueInputState;
  replayed: boolean;
}

export interface CloudDialogueEvent {
  seq: number;
  id: string;
  kind: CloudDialogueEventKind;
  role: CloudDialogueEventRole;
  text: string;
  topicId?: string | null;
  workItemId?: string | null;
  outcome?: string | null;
}

export interface CloudDialogueEventsPage {
  items: CloudDialogueEvent[];
  nextCursor: number;
  // C-SOUL-V1:轮询顺带返回最新 soulRevision
  soulRevision?: number;
}

export interface CloudDialogueClientLike {
  messages(requestId: string, text: string): Promise<CloudDialogueReceipt>;
  inputs(workItemId: string, requestId: string, text: string): Promise<CloudDialogueReceipt>;
  stop(workItemId: string, requestId: string): Promise<CloudDialogueReceipt>;
  receipts(requestId: string): Promise<CloudDialogueReceipt>;
  events(after?: number, limit?: number): Promise<CloudDialogueEventsPage>;
  timeline?(limit?: number): Promise<CloudDialogueEventsPage>;
  getSoul?(): Promise<CloudAgentSoulPayload>;
  putSoul?(req: CloudAgentSoulPutRequest): Promise<CloudAgentSoulPayload>;
}

export const isValidCloudRequestId = (requestId: string): boolean => {
  return requestId.length >= 1
    && requestId.length <= CLOUD_REQUEST_ID_MAX_LEN
    && /^[A-Za-z0-9._:-]+$/.test(requestId);
};

export const validateCloudDialogueText = (text: string): string | null => {
  if (text.trim().length === 0) return '消息内容不能为空';
  if (text.length > CLOUD_TEXT_MAX_LEN) return '消息长度超过上限（24000 字符）';
  return null;
};

interface PairResponse {
  deviceId: string;
  token: string;
  gatewayId: string;
  gatewayName: string;
}

interface ErrorBody {
  error?: string;
}

export type MacGatewayErrorKind = 'unreachable' | 'certificate' | 'unauthorized' | 'server' | 'invalid';

export class MacGatewayError extends Error {
  kind: MacGatewayErrorKind;
  statusCode: number;
  errorCode: string;

  constructor(kind: MacGatewayErrorKind, message: string, statusCode: number = 0, errorCode: string = '') {
    super(message);
    this.kind = kind;
    this.statusCode = statusCode;
    this.errorCode = errorCode;
  }
}

export const describeMacGatewayError = (e: Error): string => {
  if (!(e instanceof MacGatewayError)) return `操作失败：${e.message}`;
  switch (e.kind) {
    case 'unreachable': return '无法连接 Mac：确认 Mac 未睡眠，且网络可达（同一局域网、Tailscale 或公网地址）';
    case 'certificate': return 'Mac 的证书与配对时不一致（可能重装过 amber-gateway），请取消配对后重新扫码';
    case 'unauthorized': return '此设备已在 Mac 上被吊销，请重新配对';
    case 'invalid': return 'Mac Gateway 返回了无法识别的数据';
    default:
      if (e.errorCode === 'request_id_conflict') return '请求 ID 冲突，请重新发送';
      if (e.errorCode === 'task_terminal') return '该云任务已结束，无法继续补充或停止';
      if (e.errorCode === 'task_not_found') return '未找到对应的云任务';
      if (e.errorCode === 'receipt_not_found') return '未找到对应的请求回执';
      if (e.errorCode === 'invalid_cursor') return '事件游标无效';
      if (e.errorCode === 'invalid_request') return '请求参数无效（请检查内容长度与格式）';
      if (e.statusCode === 404) return '连接的 Gateway 不支持云主对话（需升级 amber-gateway 至支持 /v2/dialogue 的版本）';
      return e.message;
  }
};

/** 接受完整链接(扫码/粘贴)或裸 p 值 */
export const parsePairingLink = (text: string): MacGatewayPairingPayload | null => {
  const trimmed: string = text.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_LINK_LENGTH) return null;
  const match: RegExpMatchArray | null = trimmed.match(/[?&]p=([A-Za-z0-9_-]+)/);
  const encoded: string = match !== null ? match[1] : trimmed;
  if (!/^[A-Za-z0-9_-]+$/.test(encoded)) return null;
  try {
    let b64: string = encoded.replace(/-/g, '+').replace(/_/g, '/');
    while (b64.length % 4 !== 0) b64 += '=';
    const bytes: Uint8Array = new util.Base64Helper().decodeSync(b64);
    const json: string = util.TextDecoder.create('utf-8').decodeToString(bytes);
    const p: MacGatewayPairingPayload = JSON.parse(json) as MacGatewayPairingPayload;
    const valid: boolean = p.v === 1 && Array.isArray(p.addrs) && p.addrs.length > 0
      && typeof p.port === 'number' && p.port > 0 && p.port <= 65535
      && typeof p.fp === 'string' && p.fp.length > 0 && typeof p.s === 'string' && p.s.length > 0
      && typeof p.name === 'string';
    return valid ? p : null;
  } catch (_e) {
    return null;
  }
};

export class MacGatewayClient implements CloudDialogueClientLike {
  private addrs: string[];
  private port: number;
  private fingerprint: string;
  private token: string | null;
  private preferred: string | null = null;

  constructor(addrs: string[], port: number, fingerprint: string, token: string | null) {
    this.addrs = addrs;
    this.port = port;
    this.fingerprint = fingerprint;
    this.token = token;
  }

  static of(c: MacGatewayConnection): MacGatewayClient {
    return new MacGatewayClient(c.addrs, c.port, c.fingerprint, c.token);
  }

  async pair(secret: string, deviceName: string): Promise<PairResponse> {
    const body: Record<string, string> = { 'secret': secret, 'platform': 'harmony', 'deviceName': deviceName };
    return JSON.parse(await this.send('POST', '/v1/pair', JSON.stringify(body))) as PairResponse;
  }

  async status(): Promise<MacGatewayStatus> {
    const text: string = await this.send('GET', '/v1/status', undefined);
    try {
      return JSON.parse(text) as MacGatewayStatus;
    } catch (_e) {
      throw new MacGatewayError('invalid', 'invalid response');
    }
  }

  async setMonitored(key: string, monitored: boolean): Promise<void> {
    const body: Record<string, boolean> = { 'monitored': monitored };
    await this.send('POST', `/v1/tasks/${encodeURIComponent(key)}/monitor`, JSON.stringify(body));
  }

  async registerPushToken(pushToken: string): Promise<void> {
    const body: Record<string, string> = { 'token': pushToken, 'platform': 'harmony' };
    await this.send('POST', '/v1/push-token', JSON.stringify(body));
  }

  async testPush(): Promise<void> {
    await this.send('POST', '/v1/test-push', '{}');
  }

  async unpair(): Promise<void> {
    await this.send('POST', '/v1/unpair', '{}');
  }

  async messages(requestId: string, text: string): Promise<CloudDialogueReceipt> {
    if (!isValidCloudRequestId(requestId)) {
      throw new MacGatewayError('server', 'invalid_request', 400, 'invalid_request');
    }
    const textError: string | null = validateCloudDialogueText(text);
    if (textError !== null) {
      throw new MacGatewayError('server', textError, 400, 'invalid_request');
    }
    const body: Record<string, string> = { 'requestId': requestId, 'text': text };
    return this.parseJson<CloudDialogueReceipt>(await this.send('POST', '/v2/dialogue/messages', JSON.stringify(body)));
  }

  async inputs(workItemId: string, requestId: string, text: string): Promise<CloudDialogueReceipt> {
    const targetId: string = workItemId.trim();
    if (targetId.length === 0 || !isValidCloudRequestId(requestId)) {
      throw new MacGatewayError('server', 'invalid_request', 400, 'invalid_request');
    }
    const textError: string | null = validateCloudDialogueText(text);
    if (textError !== null) {
      throw new MacGatewayError('server', textError, 400, 'invalid_request');
    }
    const body: Record<string, string> = { 'requestId': requestId, 'text': text };
    const path: string = `/v2/work-items/${encodeURIComponent(targetId)}/inputs`;
    return this.parseJson<CloudDialogueReceipt>(await this.send('POST', path, JSON.stringify(body)));
  }

  async stop(workItemId: string, requestId: string): Promise<CloudDialogueReceipt> {
    const targetId: string = workItemId.trim();
    if (targetId.length === 0 || !isValidCloudRequestId(requestId)) {
      throw new MacGatewayError('server', 'invalid_request', 400, 'invalid_request');
    }
    const body: Record<string, string> = { 'requestId': requestId };
    const path: string = `/v2/work-items/${encodeURIComponent(targetId)}/stop`;
    return this.parseJson<CloudDialogueReceipt>(await this.send('POST', path, JSON.stringify(body)));
  }

  async receipts(requestId: string): Promise<CloudDialogueReceipt> {
    if (!isValidCloudRequestId(requestId)) {
      throw new MacGatewayError('server', 'invalid_request', 400, 'invalid_request');
    }
    const path: string = `/v2/dialogue/receipts/${encodeURIComponent(requestId)}`;
    return this.parseJson<CloudDialogueReceipt>(await this.send('GET', path, undefined));
  }

  async events(after: number = 0, limit: number = 100): Promise<CloudDialogueEventsPage> {
    const safeAfter: number = Math.max(0, Math.floor(after));
    const safeLimit: number = Math.max(1, Math.min(200, Math.floor(limit)));
    const path: string = `/v2/dialogue/events?after=${safeAfter}&limit=${safeLimit}`;
    return this.parseJson<CloudDialogueEventsPage>(await this.send('GET', path, undefined));
  }

  async timeline(limit: number = 50): Promise<CloudDialogueEventsPage> {
    const safeLimit: number = Math.max(1, Math.min(200, Math.floor(limit)));
    const path: string = `/v2/dialogue/timeline?limit=${safeLimit}`;
    return this.parseJson<CloudDialogueEventsPage>(await this.send('GET', path, undefined));
  }

  async getSoul(): Promise<CloudAgentSoulPayload> {
    return this.parseJson<CloudAgentSoulPayload>(await this.send('GET', '/v2/soul', undefined));
  }

  async putSoul(req: CloudAgentSoulPutRequest): Promise<CloudAgentSoulPayload> {
    return this.parseJson<CloudAgentSoulPayload>(
      await this.send('PUT', '/v2/soul', JSON.stringify(req)),
    );
  }

  private parseJson<T>(raw: string): T {
    try {
      return JSON.parse(raw) as T;
    } catch (_e) {
      throw new MacGatewayError('invalid', 'invalid response');
    }
  }

  private ordered(): string[] {
    const preferred: string | null = this.preferred;
    if (preferred === null) return this.addrs;
    return [preferred].concat(this.addrs.filter((a: string): boolean => a !== preferred));
  }

  private async send(method: rcp.HttpMethod, path: string, body: string | undefined): Promise<string> {
    const headers: rcp.RequestHeaders = { 'content-type': 'application/json' };
    if (this.token !== null) headers['authorization'] = `Bearer ${this.token}`;
    const pin: rcp.CertificatePinning = { kind: 'public-key', publicKeyHash: this.fingerprint, hashAlgorithm: 'SHA-256' };
    const config: rcp.Configuration = {
      security: { remoteValidation: 'skip', certificatePinning: pin },
      // test-push 在请求内等华为推送,Mac 20s 内必答;传输超时过短会换地址重发导致推送两次
      transfer: { autoRedirect: false, timeout: { connectMs: 5000, transferMs: 25000 } },
    };
    let sawPinMismatch: boolean = false;
    for (const address of this.ordered()) {
      const host: string = address.indexOf(':') >= 0 ? `[${address}]` : address;
      const session: rcp.Session = rcp.createSession();
      let response: rcp.Response;
      try {
        response = await session.fetch(new rcp.Request(`https://${host}:${this.port}${path}`, method, headers, body,
          undefined, undefined, config));
      } catch (e) {
        const err: BusinessError = e as BusinessError;
        hilog.warn(DOMAIN, TAG, 'request %{public}s failed: %{public}d %{public}s', address, err.code, err.message);
        if (err.code === PIN_MISMATCH_CODE) sawPinMismatch = true;
        continue;
      } finally {
        session.close();
      }
      this.preferred = address;
      const text: string = response.body === undefined ? ''
        : util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(response.body));
      if (response.statusCode >= 200 && response.statusCode < 300) return text;
      if (response.statusCode === 401) throw new MacGatewayError('unauthorized', 'unauthorized', 401, 'unauthorized');
      let message: string = `HTTP ${response.statusCode}`;
      let errorCode: string = '';
      try {
        const parsed: ErrorBody = JSON.parse(text) as ErrorBody;
        if (parsed.error !== undefined) {
          message = parsed.error;
          errorCode = parsed.error;
        }
      } catch (_e) {
        // 非 JSON 错误体沿用 HTTP 状态码
      }
      throw new MacGatewayError('server', message, response.statusCode, errorCode);
    }
    throw sawPinMismatch ? new MacGatewayError('certificate', 'certificate mismatch')
      : new MacGatewayError('unreachable', 'unreachable');
  }
}

export const loadMacGatewayConnection = async (): Promise<MacGatewayConnection | null> => {
  const raw: string | null = await getChatKvStore().get(CONNECTION_KEY);
  if (raw === null || raw.length === 0) return null;
  try {
    return JSON.parse(raw) as MacGatewayConnection;
  } catch (_e) {
    return null;
  }
};

export const forgetMacGatewayConnection = async (): Promise<void> => {
  await getChatKvStore().delete(CONNECTION_KEY);
  await getChatKvStore().delete(CLOUD_DIALOGUE_ENABLED_KEY);
  await getChatKvStore().delete(CLOUD_DIALOGUE_STATE_KEY);
};

export const loadCloudDialogueEnabled = async (): Promise<boolean> => {
  const raw: string | null = await getChatKvStore().get(CLOUD_DIALOGUE_ENABLED_KEY);
  return raw === 'true';
};

export const setCloudDialogueEnabled = async (enabled: boolean): Promise<void> => {
  await getChatKvStore().put(CLOUD_DIALOGUE_ENABLED_KEY, enabled ? 'true' : 'false');
};

export const pairMacGateway = async (p: MacGatewayPairingPayload): Promise<MacGatewayConnection> => {
  const response: PairResponse = await new MacGatewayClient(p.addrs, p.port, p.fp, null).pair(p.s, deviceInfo.marketName);
  const connection: MacGatewayConnection = {
    gatewayId: response.gatewayId,
    name: response.gatewayName,
    addrs: p.addrs,
    port: p.port,
    fingerprint: p.fp,
    deviceId: response.deviceId,
    token: response.token,
  };
  await getChatKvStore().put(CONNECTION_KEY, JSON.stringify(connection));
  return connection;
};

/** 需 AGC 开通推送服务并在 module.json5 配置 client_id,否则 getToken 报 1000900010 */
export const uploadMacGatewayPushToken = async (c: MacGatewayConnection): Promise<void> => {
  let pushToken: string;
  try {
    pushToken = await pushService.getToken();
  } catch (e) {
    const err: BusinessError = e as BusinessError;
    throw new MacGatewayError('server', err.code === PUSH_NOT_CONFIGURED_CODE
      ? '此安装包未开通华为推送（需在 AppGallery Connect 开通推送服务并配置 client_id），暂时无法接收推送'
      : `无法获取华为推送 Token：${err.message}`);
  }
  await MacGatewayClient.of(c).registerPushToken(pushToken);
};

/** 启动时补报(Push Token 可能因重装等变化);未配对或失败均静默 */
export const syncMacGatewayPushTokenOnLaunch = (): void => {
  loadMacGatewayConnection().then((c: MacGatewayConnection | null): Promise<void> => {
    return c === null ? Promise.resolve() : uploadMacGatewayPushToken(c);
  }).catch((e: Error): void => {
    hilog.warn(DOMAIN, TAG, 'push token sync on launch failed: %{public}s', e.message);
  });
};
