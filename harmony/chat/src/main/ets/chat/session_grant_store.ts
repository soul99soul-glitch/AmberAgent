// session_grant_store — SessionAccessGrantStore 全文移植(D-058)
//
// Android 基准: feature/history SessionAccessGrantStore.kt 全文(70 行)
//   + SessionHistoryModels.kt SessionAccessGrant(:6-27)
//   - 内存 ConcurrentHashMap;create/validate/recordUse/get
//   - 签发方 = history subagent(未移植,P1)— 本切片仅 store 本体,
//     无签发路径时 validate 任何 grant 恒 Denied('Unknown or expired...')
//     (与 Android 空 store 行为一致)

import { newId } from './ids.ts';

// ===== SessionAccessGrant(SessionHistoryModels.kt:6-27) =====

export interface SessionAccessGrant {
  grantId: string;
  sessionIds: string[]; // Set → 去重数组(保序)
  queryScope: string; // 默认 'selected_sessions'
  maxSessions: number;
  maxChars: number;
  purpose: string;
  expiresAt: number; // epoch ms
  sourceConversationId: string;
  assignedSubagentRunId: string | null;
  usedChars: number;
}

export type GrantValidation =
  | { kind: 'allowed'; grant: SessionAccessGrant; allowedChars: number }
  | { kind: 'denied'; reason: string };

export const GRANT_DEFAULT_TTL_MS: number = 30 * 60_000;
export const GRANT_MAX_GRANT_CHARS: number = 120_000;
export const GRANT_MAX_SESSIONS: number = 24;

export interface SessionGrantStoreOpts {
  now?: () => number;
  idGen?: () => string;
}

export class SessionAccessGrantStore {
  private readonly grants: Map<string, SessionAccessGrant> = new Map();
  private readonly now: () => number;
  private readonly idGen: () => string;

  constructor(opts: SessionGrantStoreOpts = {}) {
    this.now = opts.now ?? Date.now;
    this.idGen = opts.idGen ?? newId;
  }

  create(
    sessionIds: string[], maxChars: number, purpose: string,
    sourceConversationId: string,
    assignedSubagentRunId: string | null = null,
    ttlMs: number = GRANT_DEFAULT_TTL_MS,
  ): SessionAccessGrant {
    const seen: Set<string> = new Set<string>();
    const distinctIds: string[] = [];
    for (const raw of sessionIds) {
      const id: string = raw.trim();
      if (id.length === 0 || seen.has(id)) continue;
      seen.add(id);
      distinctIds.push(id);
      if (distinctIds.length >= GRANT_MAX_SESSIONS) break;
    }
    if (distinctIds.length === 0) {
      throw new Error('SessionAccessGrant requires at least one session id.');
    }
    const nowMs: number = this.now();
    const grant: SessionAccessGrant = {
      grantId: this.idGen(),
      sessionIds: distinctIds,
      queryScope: 'selected_sessions',
      maxSessions: distinctIds.length,
      maxChars: Math.min(Math.max(maxChars, 1_000), GRANT_MAX_GRANT_CHARS),
      purpose: purpose.slice(0, 1_000),
      expiresAt: nowMs + Math.min(Math.max(ttlMs, 1), GRANT_DEFAULT_TTL_MS),
      sourceConversationId,
      assignedSubagentRunId,
      usedChars: 0,
    };
    this.grants.set(grant.grantId, grant);
    return grant;
  }

  validate(grantId: string, sessionId: string, requestedChars: number): GrantValidation {
    const grant: SessionAccessGrant | undefined = this.grants.get(grantId);
    if (grant === undefined) {
      return { kind: 'denied', reason: 'Unknown or expired session access grant.' };
    }
    if (this.now() > grant.expiresAt) {
      this.grants.delete(grantId);
      return { kind: 'denied', reason: 'Session access grant has expired.' };
    }
    if (!grant.sessionIds.includes(sessionId)) {
      return { kind: 'denied', reason: 'Session is outside the grant scope.' };
    }
    const remaining: number = grant.maxChars - grant.usedChars;
    if (remaining <= 0) {
      return { kind: 'denied', reason: 'Session access grant character budget is exhausted.' };
    }
    return {
      kind: 'allowed',
      grant,
      allowedChars: Math.min(requestedChars, remaining),
    };
  }

  recordUse(grantId: string, chars: number): void {
    const grant: SessionAccessGrant | undefined = this.grants.get(grantId);
    if (grant === undefined) return;
    grant.usedChars = Math.min(
      grant.usedChars + Math.max(chars, 0), grant.maxChars);
  }

  // 撤销:subagent admission 被拒或初始化失败时,不留无主授权
  revoke(grantId: string): void {
    this.grants.delete(grantId);
  }

  get(grantId: string): SessionAccessGrant | null {
    return this.grants.get(grantId) ?? null;
  }
}
