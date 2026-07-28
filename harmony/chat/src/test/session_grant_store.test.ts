// session_grant_store.test.ts — SessionAccessGrantStore(D-058 TDD)
//
// Android 基准: feature/history SessionAccessGrantStore.kt 全文
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  SessionAccessGrantStore, GRANT_DEFAULT_TTL_MS, GRANT_MAX_GRANT_CHARS, GRANT_MAX_SESSIONS,
} from '../main/ets/chat/session_grant_store.ts';

const makeStore = (now: number = 1_000_000): { store: SessionAccessGrantStore; tick: (ms: number) => void } => {
  let t = now;
  let idSeq = 0;
  const store = new SessionAccessGrantStore({ now: () => t, idGen: () => `g-${++idSeq}` });
  return { store, tick: (ms: number): void => { t += ms; } };
};

describe('create', () => {
  it('常量: TTL 30min / 字符上限 120000 / 会话上限 24', () => {
    assert.equal(GRANT_DEFAULT_TTL_MS, 30 * 60_000);
    assert.equal(GRANT_MAX_GRANT_CHARS, 120_000);
    assert.equal(GRANT_MAX_SESSIONS, 24);
  });
  it('去空白去重 + 上限 24;空集 → require 失败(文案逐字)', () => {
    const { store } = makeStore();
    const g = store.create([' s1 ', '', 's1', 's2'], 5000, '目的', 'conv-1');
    assert.deepEqual(g.sessionIds, ['s1', 's2']);
    assert.equal(g.maxSessions, 2);
    assert.equal(g.queryScope, 'selected_sessions');
    assert.equal(g.usedChars, 0);
    assert.throws(
      () => store.create([], 5000, 'p', 'c'),
      (e: Error): boolean => e.message === 'SessionAccessGrant requires at least one session id.',
    );
  });
  it('maxChars coerce 1000..120000;purpose take(1000);expiresAt = now + ttl coerce', () => {
    const { store } = makeStore(1_000_000);
    const g = store.create(['s1'], 10, 'p', 'c');
    assert.equal(g.maxChars, 1_000);
    assert.equal(g.expiresAt, 1_000_000 + GRANT_DEFAULT_TTL_MS);
    const g2 = store.create(['s1'], 999_999, 'p', 'c', null, 1);
    assert.equal(g2.maxChars, GRANT_MAX_GRANT_CHARS);
    assert.equal(g2.expiresAt, 1_000_000 + 1);
  });
});

describe('validate / recordUse', () => {
  it('未知 grant → Denied(文案逐字)', () => {
    const { store } = makeStore();
    const v = store.validate('nope', 's1', 1000);
    assert.deepEqual(v, { kind: 'denied', reason: 'Unknown or expired session access grant.' });
  });
  it('过期 → 移除 + Denied', () => {
    const { store, tick } = makeStore();
    const g = store.create(['s1'], 5000, 'p', 'c');
    tick(GRANT_DEFAULT_TTL_MS + 1);
    const v = store.validate(g.grantId, 's1', 1000);
    assert.deepEqual(v, { kind: 'denied', reason: 'Session access grant has expired.' });
    assert.equal(store.get(g.grantId), null);
  });
  it('会话越界 → Denied;预算耗尽 → Denied', () => {
    const { store } = makeStore();
    const g = store.create(['s1'], 5000, 'p', 'c');
    assert.deepEqual(
      store.validate(g.grantId, 'other', 1000),
      { kind: 'denied', reason: 'Session is outside the grant scope.' });
    store.recordUse(g.grantId, 5000);
    assert.deepEqual(
      store.validate(g.grantId, 's1', 1000),
      { kind: 'denied', reason: 'Session access grant character budget is exhausted.' });
  });
  it('allowed → min(requested, remaining);recordUse 累计且封顶 maxChars', () => {
    const { store } = makeStore();
    const g = store.create(['s1'], 5000, 'p', 'c');
    store.recordUse(g.grantId, 3000);
    const v = store.validate(g.grantId, 's1', 4000);
    assert.ok(v.kind === 'allowed');
    assert.equal((v as { allowedChars: number }).allowedChars, 2000);
    store.recordUse(g.grantId, 99999);
    assert.equal(store.get(g.grantId)?.usedChars, 5000);
  });
});
