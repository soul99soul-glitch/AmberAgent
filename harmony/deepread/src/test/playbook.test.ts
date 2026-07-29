// playbook 测试 — D-127 DeepReadPlaybookRepository 钉住
// Android 锚点:DeepReadPlaybookRepository.kt(行号见 playbook.ts 头注)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { DeepReadPlaybookRepository } from '../main/ets/agent/playbook.ts';
import type {
  DeepReadPlaybookResult, DeepReadPlaybookSnapshot,
} from '../main/ets/agent/playbook.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type { FileStore } from '../main/ets/platform/files.ts';

const sha256Hex = (text: string): string =>
  createHash('sha256').update(text, 'utf8').digest('hex');

const DEFAULT_MD: string = '  # Deep Read Playbook\n\n默认编辑规则。\n';

interface Fixture {
  store: FileStore;
  repo: DeepReadPlaybookRepository;
  clock: { now: number };
}

const makeRepo = (defaultMd: string = DEFAULT_MD): Fixture => {
  const store: FileStore = createMemoryFileStore();
  const clock = { now: 1700000000000 };
  const repo: DeepReadPlaybookRepository = new DeepReadPlaybookRepository({
    store,
    sha256Hex,
    nowMs: (): number => clock.now,
    loadDefaultMarkdown: (): Promise<string> => Promise.resolve(defaultMd),
  });
  return { store, repo, clock };
};

const BIG_MD: string = '# 新规则\n\n' + '正文内容。'.repeat(120); // > 400 字符

test('read:首调初始化默认(assets trim)+ revision=sha256.take(16)', async () => {
  const f: Fixture = makeRepo();
  const snap: DeepReadPlaybookSnapshot = await f.repo.read();
  assert.equal(snap.markdown, DEFAULT_MD.trim());
  assert.equal(snap.revision, sha256Hex(DEFAULT_MD.trim()).substring(0, 16));
  // updatedAt = 文件 mtime(内存仓写入序号 1)
  assert.equal(snap.updatedAt, 1);
  // 幂等:二次 read 不重写
  const snap2: DeepReadPlaybookSnapshot = await f.repo.read();
  assert.deepEqual(snap2, snap);
});

test('update:base_revision 冲突 → rejected 文案逐字', async () => {
  const f: Fixture = makeRepo();
  const cur: DeepReadPlaybookSnapshot = await f.repo.read();
  const r: DeepReadPlaybookResult = await f.repo.update('deadbeefcafe0000', 's', BIG_MD);
  assert.equal(r.ok, false);
  if (r.ok) throw new Error('rejected');
  assert.equal(
    r.error,
    `Playbook revision conflict: current=${cur.revision}, base=deadbeefcafe0000`);
});

test('update:过短(trim 后 <400)/过大(>40000 字节)→ rejected 文案逐字', async () => {
  const f: Fixture = makeRepo();
  const cur: DeepReadPlaybookSnapshot = await f.repo.read();
  const short: DeepReadPlaybookResult = await f.repo.update(cur.revision, 's', '  太短  ');
  assert.deepEqual(short, { ok: false, snapshot: null, error: 'Playbook markdown is too short' });
  const huge: DeepReadPlaybookResult =
    await f.repo.update(cur.revision, 's', 'x'.repeat(40001));
  assert.deepEqual(huge, { ok: false, snapshot: null, error: 'Playbook markdown is too large' });
});

test('update:成功 — trim 落盘 + 快照文件头格式 + revision 变更', async () => {
  const f: Fixture = makeRepo();
  const cur: DeepReadPlaybookSnapshot = await f.repo.read();
  const r: DeepReadPlaybookResult = await f.repo.update(cur.revision, '改 规则\n\n  细节 ', ` ${BIG_MD} `);
  if (!r.ok || r.snapshot === null) throw new Error('should succeed'); const snap: DeepReadPlaybookSnapshot = r.snapshot;
  assert.equal(snap.markdown, BIG_MD); // trim 后落盘
  assert.equal(snap.revision, sha256Hex(BIG_MD).substring(0, 16));
  assert.notEqual(snap.revision, cur.revision);
  // 快照文件:${nowMs}_${revision}_${safeSummary}.md,头两行 + 空行 + 原 markdown
  //   (summary '改 规则 细节' 非 blank → safe = 7 个 '_',ifBlank 不触发,
  //     Android safeFileName 同 quirk)
  const names: string[] = await f.store.list('deep_read_playbook/snapshots');
  assert.equal(names.length, 1);
  assert.equal(names[0], `${f.clock.now}_${cur.revision}_${'_'.repeat(7)}.md`);
  const snapText: string = await f.store.readText(`deep_read_playbook/snapshots/${names[0]}`) as string;
  assert.equal(
    snapText,
    `revision: ${cur.revision}\nchange_summary: 改 规则 细节\n\n${cur.markdown}`);
  // 读回 = 新内容
  const after: DeepReadPlaybookSnapshot = await f.repo.read();
  assert.equal(after.markdown, BIG_MD);
});

test('restoreDefault:快照 summary=restore_default + 内容回默认', async () => {
  const f: Fixture = makeRepo();
  const cur: DeepReadPlaybookSnapshot = await f.repo.read();
  await f.repo.update(cur.revision, 's', BIG_MD);
  const snap: DeepReadPlaybookSnapshot = await f.repo.restoreDefault();
  assert.equal(snap.markdown, DEFAULT_MD.trim());
  const names: string[] = await f.store.list('deep_read_playbook/snapshots');
  assert.equal(names.length, 2);
  const texts: Array<string | null> = await Promise.all(names.map(
    (n: string): Promise<string | null> => f.store.readText(`deep_read_playbook/snapshots/${n}`)));
  assert.equal(
    texts.some((t: string | null): boolean =>
      t !== null && t.startsWith(`revision: ${sha256Hex(BIG_MD).substring(0, 16)}\nchange_summary: restore_default\n`)),
    true);
});

test('restorePrevious:无快照 → rejected 逐字;有快照 → 取最新(mtime)剥头恢复', async () => {
  const f: Fixture = makeRepo();
  const empty: DeepReadPlaybookResult = await f.repo.restorePrevious();
  assert.deepEqual(empty, { ok: false, snapshot: null, error: 'No previous playbook snapshot' });
  const cur: DeepReadPlaybookSnapshot = await f.repo.read();
  await f.repo.update(cur.revision, 's', BIG_MD);
  const r: DeepReadPlaybookResult = await f.repo.restorePrevious();
  if (!r.ok || r.snapshot === null) throw new Error('should succeed'); const snap: DeepReadPlaybookSnapshot = r.snapshot;
  // 恢复快照 = 更新前的默认内容(快照体 = '\n\n' 之后)
  assert.equal(snap.markdown, DEFAULT_MD.trim());
  // restore_previous 自身也存一份快照(当前 BIG_MD 被存)
  const names: string[] = await f.store.list('deep_read_playbook/snapshots');
  assert.equal(names.length, 2);
  // 再恢复一次 → 回到 BIG_MD(latest by mtime = 后写的 restore_previous 快照)
  const r2: DeepReadPlaybookResult = await f.repo.restorePrevious();
  if (!r2.ok || r2.snapshot === null) throw new Error('should succeed'); const snap2: DeepReadPlaybookSnapshot = r2.snapshot;
  assert.equal(snap2.markdown, BIG_MD);
});

test('saveSnapshot:summary 归一(\r\n→空格/\\s+ 折叠/take 160)+ safeSummary 非 ASCII → _ take 48', async () => {
  const f: Fixture = makeRepo();
  const cur: DeepReadPlaybookSnapshot = await f.repo.read();
  const longSummary: string = '中'.repeat(200); // take(160) → 160 字
  await f.repo.update(cur.revision, longSummary, BIG_MD);
  const names: string[] = await f.store.list('deep_read_playbook/snapshots');
  assert.equal(names.length, 1);
  // safe = 160 个 '_' 再 take(48) → 48 个 '_'
  assert.equal(
    names[0],
    `${f.clock.now}_${cur.revision}_${'_'.repeat(48)}.md`);
  const text: string = await f.store.readText(`deep_read_playbook/snapshots/${names[0]}`) as string;
  assert.equal(text.split('\n')[1], `change_summary: ${'中'.repeat(160)}`);
});
