import test from 'node:test';
import assert from 'node:assert/strict';
import {
  selectedParagraphDraft, draftAfterSelection, canCollectSelection,
  defaultCollectionLocation, composerOwner, canRestoreFailedInput,
  NovelComposerDraftStore,
} from '../main/ets/novel/NovelComposerState';

test('勾选立即更新自动稿，作者修改后的稿件保留到显式重置', () => {
  const paras = ['开场', '不收录的解释', '结尾'];
  assert.equal(draftAfterSelection(paras, [true, false, true], '旧稿', false), '开场\n\n结尾');
  assert.equal(draftAfterSelection(paras, [true, false, true], '作者的修改', true), '作者的修改');
  assert.equal(selectedParagraphDraft(paras, [false, false, true]), '结尾');
});

test('离页保存尚未完成时，重新加载草稿等待同一共享写队列', async () => {
  let stored = '';
  let releaseWrite: () => void = () => {};
  const writeGate = new Promise<void>((resolve) => { releaseWrite = resolve; });
  const store = new NovelComposerDraftStore({
    async get<T>(_key: string, defaultValue: T): Promise<T> {
      return stored.length > 0 ? stored as T : defaultValue;
    },
    async set<T>(_key: string, value: T): Promise<void> {
      await writeGate;
      stored = value as string;
    },
  });
  const owner = composerOwner('book', 'branch');
  const saving = store.save(owner, { text: '离页前最后一次编辑', mode: 'discuss' });
  let loaded = false;
  const loading = store.load(owner).then((draft) => { loaded = true; return draft; });
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(loaded, false);
  releaseWrite();
  await saving;
  assert.deepEqual(await loading, { text: '离页前最后一次编辑', mode: 'discuss' });
});

test('没有勾选段落时，即使编辑稿非空也不可收录', () => {
  assert.equal(canCollectSelection([false, false], '作者的修改'), false);
  assert.equal(canCollectSelection([true, false], '   '), false);
  assert.equal(canCollectSelection([true, false], '正文'), true);
});

test('润色和重生成替换来源章，续写并入当前最后章，整章新开', () => {
  const chapters = [{ id: 'first' }, { id: 'last' }];
  assert.deepEqual(defaultCollectionLocation('regenerate', 'first', 'whole_chapter', chapters),
    { mode: 2, chapterId: 'first' });
  assert.deepEqual(defaultCollectionLocation('polish', 'last', 'whole_chapter', chapters),
    { mode: 2, chapterId: 'last' });
  assert.deepEqual(defaultCollectionLocation('write', 'last', 'continuation', chapters),
    { mode: 1, chapterId: 'last' });
  assert.deepEqual(defaultCollectionLocation('write', null, 'whole_chapter', chapters),
    { mode: 0, chapterId: 'last' });
  assert.deepEqual(defaultCollectionLocation('write', null, 'continuation', []),
    { mode: 0, chapterId: '' });
  assert.deepEqual(defaultCollectionLocation('write', 'first', 'continuation',
    [{ id: 'first' }, { id: 'discarded', discarded: true }]), { mode: 1, chapterId: 'first' });
  // 来源已消失时不能默默将润色变成新章；保持替换待明确选择。
  assert.deepEqual(defaultCollectionLocation('polish', 'gone', 'whole_chapter', chapters),
    { mode: 2, chapterId: '' });
});

test('发送失败只恢复同项目同分支且发送后未编辑的输入', () => {
  const owner = composerOwner('book', 'branch');
  assert.notEqual(owner, composerOwner('book', 'other'));
  assert.notEqual(owner, composerOwner('other', 'branch'));
  assert.equal(canRestoreFailedInput(owner, owner, 5, 5, ''), true);
  assert.equal(canRestoreFailedInput(owner, owner, 5, 6, ''), false);
  assert.equal(canRestoreFailedInput(owner, composerOwner('book', 'other'), 5, 5, ''), false);
  assert.equal(canRestoreFailedInput(owner, owner, 5, 5, '新输入'), false);
});
