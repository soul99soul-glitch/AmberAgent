// agent_soul — 跨端统一「形象人设」(AgentSoul)存储、快照历史与常驻自塑工具(C-SOUL-V1)
//
// 职责边界:
//   - 与底层诚实执行守则(DEFAULT_AGENT_SOUL_MARKDOWN / agents.md)严格解耦;
//   - 持久化当前形象人设(displayName / tagline / soulMarkdown / isDefault / revision /
//     updatedAtMs / source / localLastSyncedRevision)与最近 8 条历史人设快照;
//   - 提供常驻工具 agent_soul_get 与 agent_soul_update(无需审批、不可被 tool_search 隐藏);
//   - 提供与 amberd /v2/soul 对齐的纯状态合并函数(防回环 PUT + revision==0 首次播种判定)。

import type { JsonObject, JsonValue } from './json.ts';
import { newId } from './ids.ts';
import type { KeyValueStore } from './kv_store.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

export const AGENT_SOUL_STORAGE_KEY: string = 'agent_soul_store_v1';
export const DEFAULT_AGENT_SOUL_NAME: string = 'Amber';
export const MAX_AGENT_SOUL_HISTORY: number = 8;
export const MAX_AGENT_SOUL_NAME_CHARS: number = 32;
export const MAX_AGENT_SOUL_TAGLINE_CHARS: number = 160;
export const MAX_AGENT_SOUL_MARKDOWN_CHARS: number = 24000;

export const AGENT_SOUL_GET_TOOL_NAME: string = 'agent_soul_get';
export const AGENT_SOUL_UPDATE_TOOL_NAME: string = 'agent_soul_update';

export interface AgentSoulHistoryEntry {
  id: string;
  displayName: string;
  tagline: string;
  soulMarkdown: string;
  updatedAtMs: number;
  source: string;
}

export interface RemoteAgentSoulPayload {
  revision: number;
  updatedAtMs: number;
  displayName: string;
  tagline: string;
  soulMarkdown: string;
  isDefault: boolean;
  source: string;
  history: AgentSoulHistoryEntry[];
}

export interface AgentSoulState {
  revision: number;
  updatedAtMs: number;
  displayName: string;
  tagline: string;
  soulMarkdown: string;
  isDefault: boolean;
  source: string;
  history: AgentSoulHistoryEntry[];
  localLastSyncedRevision: number;
}

export interface AgentSoulUpdatePatch {
  displayName?: string | null;
  tagline?: string | null;
  soulMarkdown?: string | null;
  source: string;
  nowMs?: number;
}

export interface AgentSoulMutationResult {
  state: AgentSoulState;
  changed: boolean;
}

export interface AgentSoulRemoteApplyResult {
  state: AgentSoulState;
  applied: boolean;
  shouldSeedRemote: boolean;
}

export interface AgentSoulCoreToolsDeps {
  getState: () => Promise<AgentSoulState>;
  updateFromChat: (patch: AgentSoulUpdatePatch) => Promise<AgentSoulMutationResult>;
  resetFromChat: () => Promise<AgentSoulMutationResult>;
}

const stripControlSingleLine = (raw: string): string =>
  raw.replace(/[\u0000-\u001f\u007f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim();

export const normalizeAgentSoulDisplayName = (raw: string): string => {
  const cleaned: string = stripControlSingleLine(raw);
  if (cleaned.length === 0) return DEFAULT_AGENT_SOUL_NAME;
  const chars: string[] = Array.from(cleaned);
  return chars.length <= MAX_AGENT_SOUL_NAME_CHARS
    ? cleaned
    : chars.slice(0, MAX_AGENT_SOUL_NAME_CHARS).join('');
};

export const normalizeAgentSoulTagline = (raw: string): string => {
  const cleaned: string = stripControlSingleLine(raw);
  if (cleaned.length === 0) return '';
  const chars: string[] = Array.from(cleaned);
  return chars.length <= MAX_AGENT_SOUL_TAGLINE_CHARS
    ? cleaned
    : chars.slice(0, MAX_AGENT_SOUL_TAGLINE_CHARS).join('');
};

export const normalizeAgentSoulMarkdown = (raw: string): string => {
  const normalized: string = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim();
  if (normalized.length === 0) return '';
  const chars: string[] = Array.from(normalized);
  return chars.length <= MAX_AGENT_SOUL_MARKDOWN_CHARS
    ? normalized
    : chars.slice(0, MAX_AGENT_SOUL_MARKDOWN_CHARS).join('');
};

export const computeIsAgentSoulDefault = (
  displayName: string,
  tagline: string,
  soulMarkdown: string,
): boolean =>
  displayName === DEFAULT_AGENT_SOUL_NAME && tagline.length === 0 && soulMarkdown.length === 0;

export const defaultAgentSoulState = (): AgentSoulState => ({
  revision: 0,
  updatedAtMs: 0,
  displayName: DEFAULT_AGENT_SOUL_NAME,
  tagline: '',
  soulMarkdown: '',
  isDefault: true,
  source: 'default',
  history: [],
  localLastSyncedRevision: 0,
});

const cloneHistoryEntry = (entry: AgentSoulHistoryEntry): AgentSoulHistoryEntry => ({
  id: entry.id,
  displayName: entry.displayName,
  tagline: entry.tagline,
  soulMarkdown: entry.soulMarkdown,
  updatedAtMs: entry.updatedAtMs,
  source: entry.source,
});

export const cloneAgentSoulState = (state: AgentSoulState): AgentSoulState => ({
  revision: state.revision,
  updatedAtMs: state.updatedAtMs,
  displayName: state.displayName,
  tagline: state.tagline,
  soulMarkdown: state.soulMarkdown,
  isDefault: state.isDefault,
  source: state.source,
  history: state.history.map(cloneHistoryEntry),
  localLastSyncedRevision: state.localLastSyncedRevision,
});

const samePersonaContent = (
  aName: string, aTagline: string, aMarkdown: string,
  bName: string, bTagline: string, bMarkdown: string,
): boolean =>
  aName === bName && aTagline === bTagline && aMarkdown === bMarkdown;

const normalizeHistoryList = (rawList: AgentSoulHistoryEntry[] | null | undefined): AgentSoulHistoryEntry[] => {
  if (!Array.isArray(rawList)) return [];
  const result: AgentSoulHistoryEntry[] = [];
  for (const item of rawList) {
    if (item === null || item === undefined || typeof item !== 'object') continue;
    const displayName: string = normalizeAgentSoulDisplayName(
      typeof item.displayName === 'string' ? item.displayName : DEFAULT_AGENT_SOUL_NAME,
    );
    const tagline: string = normalizeAgentSoulTagline(typeof item.tagline === 'string' ? item.tagline : '');
    const soulMarkdown: string = normalizeAgentSoulMarkdown(
      typeof item.soulMarkdown === 'string' ? item.soulMarkdown : '',
    );
    if (computeIsAgentSoulDefault(displayName, tagline, soulMarkdown)) continue;
    const duplicate: boolean = result.some((existing: AgentSoulHistoryEntry): boolean =>
      samePersonaContent(
        existing.displayName, existing.tagline, existing.soulMarkdown,
        displayName, tagline, soulMarkdown,
      ));
    if (duplicate) continue;
    const updatedAtMs: number = typeof item.updatedAtMs === 'number' && Number.isFinite(item.updatedAtMs)
      ? Math.max(0, Math.floor(item.updatedAtMs))
      : 0;
    const id: string = typeof item.id === 'string' && item.id.trim().length > 0
      ? item.id.trim()
      : `soul-hist-${updatedAtMs}-${newId().slice(0, 8)}`;
    const source: string = typeof item.source === 'string' && item.source.trim().length > 0
      ? item.source.trim()
      : 'harmony_ui';
    result.push({
      id,
      displayName,
      tagline,
      soulMarkdown,
      updatedAtMs,
      source,
    });
    if (result.length >= MAX_AGENT_SOUL_HISTORY) break;
  }
  return result;
};

const pushCurrentToHistory = (
  existingHistory: AgentSoulHistoryEntry[],
  current: AgentSoulState,
  excludeContent?: { displayName: string; tagline: string; soulMarkdown: string },
): AgentSoulHistoryEntry[] => {
  const next: AgentSoulHistoryEntry[] = [];
  if (!current.isDefault) {
    const matchesExcluded: boolean = excludeContent !== undefined && samePersonaContent(
      current.displayName, current.tagline, current.soulMarkdown,
      excludeContent.displayName, excludeContent.tagline, excludeContent.soulMarkdown,
    );
    if (!matchesExcluded) {
      const ts: number = current.updatedAtMs > 0 ? current.updatedAtMs : Date.now();
      next.push({
        id: `soul-hist-${ts}-${newId().slice(0, 8)}`,
        displayName: current.displayName,
        tagline: current.tagline,
        soulMarkdown: current.soulMarkdown,
        updatedAtMs: ts,
        source: current.source,
      });
    }
  }
  for (const entry of existingHistory) {
    if (next.length >= MAX_AGENT_SOUL_HISTORY) break;
    if (excludeContent !== undefined && samePersonaContent(
      entry.displayName, entry.tagline, entry.soulMarkdown,
      excludeContent.displayName, excludeContent.tagline, excludeContent.soulMarkdown,
    )) {
      continue;
    }
    const dup: boolean = next.some((e: AgentSoulHistoryEntry): boolean =>
      samePersonaContent(
        e.displayName, e.tagline, e.soulMarkdown,
        entry.displayName, entry.tagline, entry.soulMarkdown,
      ));
    if (!dup) next.push(cloneHistoryEntry(entry));
  }
  return next.slice(0, MAX_AGENT_SOUL_HISTORY);
};

interface PersistedAgentSoulJson {
  revision?: number;
  updatedAtMs?: number;
  displayName?: string;
  tagline?: string;
  soulMarkdown?: string;
  isDefault?: boolean;
  source?: string;
  history?: AgentSoulHistoryEntry[];
  localLastSyncedRevision?: number;
}

export const parseAgentSoulState = (raw: string | null): AgentSoulState => {
  if (raw === null || raw.trim().length === 0) return defaultAgentSoulState();
  try {
    const parsed: PersistedAgentSoulJson = JSON.parse(raw) as PersistedAgentSoulJson;
    if (parsed === null || typeof parsed !== 'object') return defaultAgentSoulState();
    const displayName: string = normalizeAgentSoulDisplayName(
      typeof parsed.displayName === 'string' ? parsed.displayName : DEFAULT_AGENT_SOUL_NAME,
    );
    const tagline: string = normalizeAgentSoulTagline(typeof parsed.tagline === 'string' ? parsed.tagline : '');
    const soulMarkdown: string = normalizeAgentSoulMarkdown(
      typeof parsed.soulMarkdown === 'string' ? parsed.soulMarkdown : '',
    );
    const isDefault: boolean = computeIsAgentSoulDefault(displayName, tagline, soulMarkdown);
    const revision: number = typeof parsed.revision === 'number' && Number.isFinite(parsed.revision)
      ? Math.max(0, Math.floor(parsed.revision))
      : 0;
    const updatedAtMs: number = typeof parsed.updatedAtMs === 'number' && Number.isFinite(parsed.updatedAtMs)
      ? Math.max(0, Math.floor(parsed.updatedAtMs))
      : 0;
    const localLastSyncedRevision: number = typeof parsed.localLastSyncedRevision === 'number'
      && Number.isFinite(parsed.localLastSyncedRevision)
      ? Math.max(0, Math.floor(parsed.localLastSyncedRevision))
      : revision;
    const source: string = typeof parsed.source === 'string' && parsed.source.trim().length > 0
      ? parsed.source.trim()
      : (isDefault ? 'default' : 'harmony_ui');
    return {
      revision,
      updatedAtMs,
      displayName,
      tagline,
      soulMarkdown,
      isDefault,
      source,
      history: normalizeHistoryList(parsed.history),
      localLastSyncedRevision,
    };
  } catch (_e) {
    return defaultAgentSoulState();
  }
};

export const serializeAgentSoulState = (state: AgentSoulState): string => JSON.stringify(state);

export class AgentSoulStore {
  private readonly kv: KeyValueStore;
  private readonly storageKey: string;
  private cached: AgentSoulState | null = null;

  constructor(kv: KeyValueStore, storageKey: string = AGENT_SOUL_STORAGE_KEY) {
    this.kv = kv;
    this.storageKey = storageKey;
  }

  async load(forceReload: boolean = false): Promise<AgentSoulState> {
    if (this.cached !== null && !forceReload) {
      return cloneAgentSoulState(this.cached);
    }
    const raw: string | null = await this.kv.get(this.storageKey);
    const state: AgentSoulState = parseAgentSoulState(raw);
    this.cached = state;
    return cloneAgentSoulState(state);
  }

  peekCached(): AgentSoulState | null {
    return this.cached !== null ? cloneAgentSoulState(this.cached) : null;
  }

  private async saveInternal(next: AgentSoulState): Promise<AgentSoulState> {
    this.cached = cloneAgentSoulState(next);
    await this.kv.put(this.storageKey, serializeAgentSoulState(this.cached));
    return cloneAgentSoulState(this.cached);
  }

  async updateLocal(patch: AgentSoulUpdatePatch): Promise<AgentSoulMutationResult> {
    const current: AgentSoulState = await this.load();
    const nextDisplayName: string = patch.displayName !== undefined && patch.displayName !== null
      ? normalizeAgentSoulDisplayName(patch.displayName)
      : current.displayName;
    const nextTagline: string = patch.tagline !== undefined && patch.tagline !== null
      ? normalizeAgentSoulTagline(patch.tagline)
      : current.tagline;
    const nextSoulMarkdown: string = patch.soulMarkdown !== undefined && patch.soulMarkdown !== null
      ? normalizeAgentSoulMarkdown(patch.soulMarkdown)
      : current.soulMarkdown;

    if (samePersonaContent(
      current.displayName, current.tagline, current.soulMarkdown,
      nextDisplayName, nextTagline, nextSoulMarkdown,
    )) {
      return { state: current, changed: false };
    }

    const nextIsDefault: boolean = computeIsAgentSoulDefault(nextDisplayName, nextTagline, nextSoulMarkdown);
    const nowMs: number = patch.nowMs !== undefined ? Math.max(0, Math.floor(patch.nowMs)) : Date.now();
    const nextHistory: AgentSoulHistoryEntry[] = pushCurrentToHistory(
      current.history,
      current,
      { displayName: nextDisplayName, tagline: nextTagline, soulMarkdown: nextSoulMarkdown },
    );
    const next: AgentSoulState = {
      revision: current.revision,
      updatedAtMs: nowMs,
      displayName: nextDisplayName,
      tagline: nextTagline,
      soulMarkdown: nextSoulMarkdown,
      isDefault: nextIsDefault,
      source: patch.source.trim().length > 0 ? patch.source.trim() : 'harmony_ui',
      history: nextHistory,
      localLastSyncedRevision: current.localLastSyncedRevision,
    };
    const saved: AgentSoulState = await this.saveInternal(next);
    return { state: saved, changed: true };
  }

  async restoreHistory(
    snapshotId: string,
    source: string = 'harmony_ui',
    nowMs?: number,
  ): Promise<AgentSoulMutationResult> {
    const current: AgentSoulState = await this.load();
    const targetId: string = snapshotId.trim();
    const found: AgentSoulHistoryEntry | undefined = current.history.find(
      (item: AgentSoulHistoryEntry): boolean => item.id === targetId,
    );
    if (found === undefined) {
      throw new Error('未找到对应的历史形象人设');
    }
    if (samePersonaContent(
      current.displayName, current.tagline, current.soulMarkdown,
      found.displayName, found.tagline, found.soulMarkdown,
    )) {
      return { state: current, changed: false };
    }
    const remaining: AgentSoulHistoryEntry[] = current.history.filter(
      (item: AgentSoulHistoryEntry): boolean => item.id !== targetId,
    );
    const nextHistory: AgentSoulHistoryEntry[] = pushCurrentToHistory(
      remaining,
      current,
      { displayName: found.displayName, tagline: found.tagline, soulMarkdown: found.soulMarkdown },
    );
    const nextIsDefault: boolean = computeIsAgentSoulDefault(
      found.displayName, found.tagline, found.soulMarkdown,
    );
    const ts: number = nowMs !== undefined ? Math.max(0, Math.floor(nowMs)) : Date.now();
    const next: AgentSoulState = {
      revision: current.revision,
      updatedAtMs: ts,
      displayName: found.displayName,
      tagline: found.tagline,
      soulMarkdown: found.soulMarkdown,
      isDefault: nextIsDefault,
      source: source.trim().length > 0 ? source.trim() : 'harmony_ui',
      history: nextHistory,
      localLastSyncedRevision: current.localLastSyncedRevision,
    };
    const saved: AgentSoulState = await this.saveInternal(next);
    return { state: saved, changed: true };
  }

  async resetToDefault(
    source: string = 'harmony_ui',
    nowMs?: number,
  ): Promise<AgentSoulMutationResult> {
    const current: AgentSoulState = await this.load();
    if (current.isDefault) {
      return { state: current, changed: false };
    }
    const nextHistory: AgentSoulHistoryEntry[] = pushCurrentToHistory(current.history, current);
    const ts: number = nowMs !== undefined ? Math.max(0, Math.floor(nowMs)) : Date.now();
    const next: AgentSoulState = {
      revision: current.revision,
      updatedAtMs: ts,
      displayName: DEFAULT_AGENT_SOUL_NAME,
      tagline: '',
      soulMarkdown: '',
      isDefault: true,
      source: source.trim().length > 0 ? source.trim() : 'harmony_ui',
      history: nextHistory,
      localLastSyncedRevision: current.localLastSyncedRevision,
    };
    const saved: AgentSoulState = await this.saveInternal(next);
    return { state: saved, changed: true };
  }

  /**
   * 应用服务端 GET /v2/soul 返回的状态(C-SOUL-V1):
   *   - 若服务端 revision == 0 且本地已有非默认自定义人设,不覆盖本地并标记 shouldSeedRemote=true;
   *   - 否则直接应用远端状态并更新 localLastSyncedRevision = remote.revision(绝不回环触发 PUT)。
   */
  async applyRemoteSoul(remote: RemoteAgentSoulPayload): Promise<AgentSoulRemoteApplyResult> {
    const current: AgentSoulState = await this.load();
    const remoteRevision: number = typeof remote.revision === 'number' && Number.isFinite(remote.revision)
      ? Math.max(0, Math.floor(remote.revision))
      : 0;
    if (remoteRevision === 0) {
      if (!current.isDefault) {
        return { state: current, applied: false, shouldSeedRemote: true };
      }
      return { state: current, applied: false, shouldSeedRemote: false };
    }

    const displayName: string = normalizeAgentSoulDisplayName(
      typeof remote.displayName === 'string' ? remote.displayName : DEFAULT_AGENT_SOUL_NAME,
    );
    const tagline: string = normalizeAgentSoulTagline(typeof remote.tagline === 'string' ? remote.tagline : '');
    const soulMarkdown: string = normalizeAgentSoulMarkdown(
      typeof remote.soulMarkdown === 'string' ? remote.soulMarkdown : '',
    );
    const computedDefault: boolean = computeIsAgentSoulDefault(displayName, tagline, soulMarkdown);
    const isDefault: boolean = typeof remote.isDefault === 'boolean' && computedDefault
      ? remote.isDefault
      : computedDefault;
    const updatedAtMs: number = typeof remote.updatedAtMs === 'number' && Number.isFinite(remote.updatedAtMs)
      ? Math.max(0, Math.floor(remote.updatedAtMs))
      : current.updatedAtMs;
    const remoteHistory: AgentSoulHistoryEntry[] = normalizeHistoryList(remote.history);
    const mergedHistory: AgentSoulHistoryEntry[] = pushCurrentToHistory(
      remoteHistory.concat(current.history),
      current,
      { displayName, tagline, soulMarkdown },
    );
    const next: AgentSoulState = {
      revision: remoteRevision,
      updatedAtMs,
      displayName,
      tagline,
      soulMarkdown,
      isDefault,
      source: typeof remote.source === 'string' && remote.source.trim().length > 0 ? remote.source.trim() : 'cloud',
      history: mergedHistory,
      localLastSyncedRevision: remoteRevision,
    };
    const saved: AgentSoulState = await this.saveInternal(next);
    return { state: saved, applied: true, shouldSeedRemote: false };
  }

  /**
   * 记录本地发起 PUT /v2/soul 成功后服务端返回的新 revision 与历史。
   */
  async markSyncedFromPut(remote: RemoteAgentSoulPayload): Promise<AgentSoulState> {
    const current: AgentSoulState = await this.load();
    const remoteRevision: number = typeof remote.revision === 'number' && Number.isFinite(remote.revision)
      ? Math.max(current.revision, Math.floor(remote.revision))
      : current.revision;
    const remoteHistory: AgentSoulHistoryEntry[] = normalizeHistoryList(remote.history);
    const mergedHistory: AgentSoulHistoryEntry[] = normalizeHistoryList(
      remoteHistory.concat(current.history),
    );
    const next: AgentSoulState = {
      revision: remoteRevision,
      updatedAtMs: typeof remote.updatedAtMs === 'number' && remote.updatedAtMs > 0
        ? Math.floor(remote.updatedAtMs)
        : current.updatedAtMs,
      displayName: current.displayName,
      tagline: current.tagline,
      soulMarkdown: current.soulMarkdown,
      isDefault: current.isDefault,
      source: current.source,
      history: mergedHistory,
      localLastSyncedRevision: remoteRevision,
    };
    return this.saveInternal(next);
  }
}

// ===== 常驻工具实现(agent_soul_get / agent_soul_update) =====

const asJsonObject = (input: JsonValue): JsonObject => {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) return {};
  return input as JsonObject;
};

const pickOptionalString = (obj: JsonObject, keys: string[]): string | undefined => {
  for (const key of keys) {
    const val: JsonValue | undefined = obj[key];
    if (typeof val === 'string') return val;
  }
  return undefined;
};

const pickOptionalBoolean = (obj: JsonObject, keys: string[]): boolean | undefined => {
  for (const key of keys) {
    const val: JsonValue | undefined = obj[key];
    if (typeof val === 'boolean') return val;
    if (typeof val === 'string') {
      const lower: string = val.trim().toLowerCase();
      if (lower === 'true') return true;
      if (lower === 'false') return false;
    }
  }
  return undefined;
};

const serializeSoulForTool = (state: AgentSoulState, includeHistory: boolean = true): JsonObject => {
  const payload: JsonObject = {
    status: 'ok',
    display_name: state.displayName,
    displayName: state.displayName,
    tagline: state.tagline,
    soul_markdown: state.soulMarkdown,
    soulMarkdown: state.soulMarkdown,
    is_default: state.isDefault,
    isDefault: state.isDefault,
    revision: state.revision,
    updated_at_ms: state.updatedAtMs,
    updatedAtMs: state.updatedAtMs,
    source: state.source,
    history_count: state.history.length,
  };
  if (includeHistory) {
    payload['history'] = state.history.map((h: AgentSoulHistoryEntry): JsonObject => ({
      id: h.id,
      display_name: h.displayName,
      displayName: h.displayName,
      tagline: h.tagline,
      soul_markdown: h.soulMarkdown,
      updated_at_ms: h.updatedAtMs,
      source: h.source,
    }));
  }
  return payload;
};

const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

export const createAgentSoulCoreTools = (deps: AgentSoulCoreToolsDeps): AgentTool[] => [
  makeAgentTool({
    name: AGENT_SOUL_GET_TOOL_NAME,
    description: 'Read the current Agent Soul persona (形象人设: 角色称呼 display_name, 一句话签名 tagline, '
      + '灵魂设定正文 soul_markdown, default status, revision, and recent history snapshots). '
      + 'Always available in main chat without approval.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      include_history: {
        type: 'boolean',
        description: 'Include recent persona history snapshots (up to 8). Defaults to true.',
      },
    }),
    needsApproval: false,
    allowsAutoApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = asJsonObject(input);
      const includeHistory: boolean = pickOptionalBoolean(obj, ['include_history', 'includeHistory']) ?? true;
      const state: AgentSoulState = await deps.getState();
      return textPart(serializeSoulForTool(state, includeHistory));
    },
  }),
  makeAgentTool({
    name: AGENT_SOUL_UPDATE_TOOL_NAME,
    description: 'Persistently update or reset the Agent Soul persona (形象人设: 角色称呼 display_name, '
      + '一句话签名 tagline, 灵魂设定正文 soul_markdown, or reset_to_default=true to restore default Amber). '
      + 'Call this tool directly whenever the user asks to change persona, character identity, personality, '
      + 'speaking tone, or name/title — never refuse or ask the user to manually edit SOUL.md.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      display_name: {
        type: 'string',
        description: 'Character display name / 角色称呼 (max 32 chars, e.g. "苏轼", "Jarvis", "Amber"). Omit to keep current name.',
      },
      tagline: {
        type: 'string',
        description: 'One-line persona signature / 一句话签名 (max 160 chars). Pass "" to clear or omit to keep current tagline.',
      },
      soul_markdown: {
        type: 'string',
        description: 'Markdown persona definition / 灵魂设定正文 describing identity, personality, speaking tone, and interaction style. Omit to keep current soul_markdown.',
      },
      reset_to_default: {
        type: 'boolean',
        description: 'Set true to reset 形象人设 back to the default Amber persona (archiving the previous custom persona in history).',
      },
    }),
    needsApproval: false,
    allowsAutoApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = asJsonObject(input);
      const resetToDefault: boolean = pickOptionalBoolean(
        obj, ['reset_to_default', 'resetToDefault', 'reset'],
      ) ?? false;
      if (resetToDefault) {
        const resetRes: AgentSoulMutationResult = await deps.resetFromChat();
        const payload: JsonObject = serializeSoulForTool(resetRes.state, false);
        payload['changed'] = resetRes.changed;
        payload['action'] = 'reset_to_default';
        return textPart(payload);
      }

      const displayName: string | undefined = pickOptionalString(obj, ['display_name', 'displayName', 'name']);
      const tagline: string | undefined = pickOptionalString(obj, ['tagline', 'signature']);
      const soulMarkdown: string | undefined = pickOptionalString(
        obj, ['soul_markdown', 'soulMarkdown', 'soul_prompt', 'soulPrompt'],
      );
      if (displayName === undefined && tagline === undefined && soulMarkdown === undefined) {
        throw new Error(
          'agent_soul_update requires at least one of display_name, tagline, soul_markdown, or reset_to_default=true.',
        );
      }
      const updateRes: AgentSoulMutationResult = await deps.updateFromChat({
        displayName,
        tagline,
        soulMarkdown,
        source: 'harmony_chat',
      });
      const payload: JsonObject = serializeSoulForTool(updateRes.state, false);
      payload['changed'] = updateRes.changed;
      payload['action'] = 'updated';
      return textPart(payload);
    },
  }),
];
