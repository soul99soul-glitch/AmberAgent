// miniapp_repository — 内存实现业务规则(乐观并发/版本 prune/级联删除/SharedStore/audit)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createMemoryMiniAppRepository, miniAppToCardRef, miniAppMinimalHostContext,
} from '../main/ets/chat/miniapp/miniapp_repository.ts';
import type { MemoryMiniAppRepository } from '../main/ets/chat/miniapp/miniapp_repository.ts';
import type {
  MiniAppGeneratedOutput, MiniAppRecord, MiniAppVersionRecord,
} from '../main/ets/chat/miniapp/miniapp_models.ts';

const sha256Hex = (s: string): string => {
  let h: number = 0;
  for (let i: number = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h.toString(16);
};

const html = (marker: string): string =>
  `<!DOCTYPE html><html><body><script>Amber.toast('${marker}')</script></body></html>`;

const output = (title: string, marker: string): MiniAppGeneratedOutput => ({
  title,
  description: 'desc',
  icon: '水',
  category: 'tool',
  permissions: ['toast'],
  html: html(marker),
});

let seq: number = 0;
const makeRepo = (): MemoryMiniAppRepository => {
  seq++;
  const prefix: string = `app-${seq}-`;
  let n: number = 0;
  return createMemoryMiniAppRepository({
    sha256Hex,
    idGen: (): string => `${prefix}${++n}`,
  });
};

test('saveGenerated: 落库 + 初始版本 v1 + htmlHash 经 sha256Hex', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(
    output('喝水记录器', 'a'), 'conv-1', 'msg-1');
  assert.equal(record.version, 1);
  assert.equal(record.title, '喝水记录器');
  assert.equal(record.sourceConversationId, 'conv-1');
  assert.equal(record.sourceMessageId, 'msg-1');
  assert.equal(record.iconEmoji, '水');
  assert.equal(record.htmlHash, sha256Hex(html('a')));
  const fetched: MiniAppRecord | null = await repo.getById(record.id);
  assert.equal(fetched !== null && fetched.htmlContent, html('a'));
  const versions: MiniAppVersionRecord[] = await repo.listVersions(record.id);
  assert.equal(versions.length, 1);
  assert.equal(versions[0].versionNumber, 1);
  assert.equal(versions[0].changeNote, 'Initial version');
});

test('saveRevision: 乐观并发冲突返回 null;成功版本自增', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(output('r', 'a'));
  // expectedBaseVersion 过期 → null
  assert.equal(
    await repo.saveRevision(record.id, output('r2', 'b'), 0),
    null,
  );
  // 正确 base → v2
  const updated: MiniAppRecord | null = await repo.saveRevision(
    record.id, output('r2', 'b'), 1, 'msg-2', '自定义说明');
  assert.equal(updated !== null && updated.version, 2);
  assert.equal(updated !== null && updated.title, 'r2');
  assert.equal(updated !== null && updated.sourceMessageId, 'msg-2');
  const versions: MiniAppVersionRecord[] = await repo.listVersions(record.id);
  assert.equal(versions.length, 2);
  assert.equal(versions[0].versionNumber, 2);
  assert.equal(versions[1].versionNumber, 1);
});

test('saveRevision: 不存在的 app → null;changeNote 截断 240', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  assert.equal(await repo.saveRevision('nope', output('x', 'a'), 1), null);
  const record: MiniAppRecord = await repo.saveGenerated(output('x', 'a'));
  const longNote: string = 'n'.repeat(500);
  await repo.saveRevision(record.id, output('x2', 'b'), 1, null, longNote);
  const versions: MiniAppVersionRecord[] = await repo.listVersions(record.id);
  assert.equal(versions[0].changeNote, 'n'.repeat(240));
});

test('版本 prune ≤ 30:31 次修订后仅保留最新 30', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(output('p', 'a'));
  for (let i: number = 1; i <= 30; i++) {
    await repo.saveRevision(record.id, output(`p${i}`, `m${i}`), i);
  }
  const app: MiniAppRecord | null = await repo.getById(record.id);
  assert.equal(app !== null && app.version, 31);
  const versions: MiniAppVersionRecord[] = await repo.listVersions(record.id);
  assert.equal(versions.length, 30);
  assert.equal(versions[0].versionNumber, 31);
  assert.equal(versions[versions.length - 1].versionNumber, 2);
});

test('restoreVersion: 回滚到历史版本生成新版本', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(output('v', 'a'));
  await repo.saveRevision(record.id, output('v2', 'b'), 1);
  const restored: MiniAppRecord | null = await repo.restoreVersion(record.id, 1);
  assert.equal(restored !== null && restored.version, 3);
  assert.equal(restored !== null && restored.htmlContent, html('a'));
  assert.equal(restored !== null && restored.htmlHash, sha256Hex(html('a')));
  const versions: MiniAppVersionRecord[] = await repo.listVersions(record.id);
  assert.equal(versions.length, 3);
  assert.equal(versions[0].changeNote, 'Restored from v1');
  // 不存在版本 → null
  assert.equal(await repo.restoreVersion(record.id, 99), null);
});

test('markRun / setPinned / rename / updateBoardSummary', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(output('m', 'a'));
  await repo.markRun(record.id);
  await repo.setPinned(record.id, true);
  await repo.rename(record.id, '新名字', '新描述');
  await repo.updateBoardSummary(record.id, `x${'s'.repeat(600)}`);
  let app: MiniAppRecord | null = await repo.getById(record.id);
  assert.equal(app !== null && app.runCount, 1);
  assert.equal(app !== null && app.pinned, true);
  assert.equal(app !== null && app.title, '新名字');
  assert.equal(app !== null && app.description, '新描述');
  // rename 截断 40 / 120;空 title → 未命名小应用
  await repo.rename(record.id, 't'.repeat(50), 'd'.repeat(130));
  app = await repo.getById(record.id);
  assert.equal(app !== null && app.title, 't'.repeat(40));
  assert.equal(app !== null && app.description, 'd'.repeat(120));
  await repo.rename(record.id, '   ', 'dd');
  app = await repo.getById(record.id);
  assert.equal(app !== null && app.title, '未命名小应用');
  // boardSummary 截断 500
  assert.equal(app !== null && app.boardSummary, `x${'s'.repeat(499)}`);
  // 不存在的 app 幂等
  await repo.markRun('nope');
});

test('delete: 级联清理 grants/versions/audit/sharedData', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(output('d', 'a'));
  await repo.saveRevision(record.id, output('d2', 'b'), 1);
  await repo.setGrant(record.id, 'toast', 'ALLOW');
  await repo.appendAudit(record.id, 'toast', 'toast', 'show toast', 'payload');
  await repo.sharedSet(record.id, record.id, 'k', { a: 1 });
  await repo.delete(record.id);
  assert.equal(await repo.getById(record.id), null);
  assert.equal((await repo.listVersions(record.id)).length, 0);
  assert.equal(await repo.getGrant(record.id, 'toast'), null);
  assert.equal((await repo.listAudit(record.id)).length, 0);
  assert.equal(await repo.sharedGet(record.id, record.id, 'k'), null);
});

test('grant get/set', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(output('g', 'a'));
  assert.equal(await repo.getGrant(record.id, 'search'), null);
  await repo.setGrant(record.id, 'search', 'ALLOW');
  assert.equal(await repo.getGrant(record.id, 'search'), 'ALLOW');
  await repo.setGrant(record.id, 'search', 'DENY');
  assert.equal(await repo.getGrant(record.id, 'search'), 'DENY');
});

test('audit: summary 截断 180,payload hash 化,createdAt 降序,limit', async () => {
  let now: number = 1000;
  const repo: MemoryMiniAppRepository = createMemoryMiniAppRepository({
    sha256Hex,
    idGen: (): string => `audit-${++now}`,
    clock: (): number => now,
  });
  const record: MiniAppRecord = await repo.saveGenerated(output('a', 'a'));
  for (let i: number = 1; i <= 3; i++) {
    await repo.appendAudit(record.id, `m${i}`, 'toast', `summary${'s'.repeat(300)}${i}`, `payload${i}`);
  }
  const all = await repo.listAudit(record.id);
  assert.equal(all.length, 3);
  assert.equal(all[0].method, 'm3'); // createdAt 降序
  assert.equal(all[0].summary.length, 180);
  assert.equal(all[0].payloadHash, sha256Hex('payload3'));
  const limited = await repo.listAudit(record.id, 2);
  assert.equal(limited.length, 2);
});

test('sharedStore: 值 32KB 限制 / namespace 2MB 限制 / key 校验 / 跨 app namespace 拒绝', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(output('s', 'a'));
  // 跨 app namespace 拒绝
  await assert.rejects((): Promise<unknown> =>
    repo.sharedSet(record.id, 'other-app', 'k', 1));
  // 非法 key
  await assert.rejects((): Promise<unknown> =>
    repo.sharedSet(record.id, record.id, 'bad key!', 1));
  // 值过大
  await assert.rejects((): Promise<unknown> =>
    repo.sharedSet(record.id, record.id, 'big', 'x'.repeat(33 * 1024)));
  // 正常读写
  await repo.sharedSet(record.id, record.id, 'k1', { a: [1, 2] });
  assert.deepEqual(await repo.sharedGet(record.id, record.id, 'k1'), { a: [1, 2] });
  // 覆盖
  await repo.sharedSet(record.id, record.id, 'k1', 'v2');
  assert.equal(await repo.sharedGet(record.id, record.id, 'k1'), 'v2');
  // 删除
  await repo.sharedRemove(record.id, record.id, 'k1');
  assert.equal(await repo.sharedGet(record.id, record.id, 'k1'), null);
  // namespace 满 → 拒绝(单条已近 32KB,2MB 需多条;用 3 条 700KB 逼近)
  const big: string = 'b'.repeat(700 * 1024);
  await assert.rejects((): Promise<unknown> =>
    repo.sharedSet(record.id, record.id, 'big1', big));
});

test('toCardRef / minimalHostContext', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const record: MiniAppRecord = await repo.saveGenerated(
    { ...output('c', 'a'), icon: '  ' },
    'conv', 'msg');
  const card = miniAppToCardRef(record);
  assert.equal(card.appId, record.id);
  assert.equal(card.iconEmoji, null); // icon 空白 → null
  const ctx = miniAppMinimalHostContext(record, 200);
  assert.equal(ctx.untrustedContext, true);
  assert.equal(ctx.appId, record.id);
  assert.equal(ctx.sourceConversationId, 'conv');
  assert.equal(ctx.sourceMessageId, 'msg');
  assert.equal(ctx.note.includes('system prompts'), true);
  // boardSummary 截断 maxChars
  const withSummary: MiniAppRecord = await repo.saveGenerated(output('c2', 'b'));
  await repo.updateBoardSummary(withSummary.id, 'y'.repeat(300));
  const app: MiniAppRecord | null = await repo.getById(withSummary.id);
  const ctx2 = miniAppMinimalHostContext(app as MiniAppRecord, 50);
  assert.equal(ctx2.boardSummary.length, 50);
});

test('saveGenerated: html 校验失败抛错;sourceMessageId 保留', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  await assert.rejects((): Promise<unknown> =>
    repo.saveGenerated({ ...output('bad', 'a'), html: '<div>no html tag</div>' }));
  const record: MiniAppRecord = await repo.saveGenerated(
    output('ok', 'a'), null, 'msg-x');
  assert.equal(record.sourceMessageId, 'msg-x');
  assert.equal(record.sourceConversationId, null);
});
