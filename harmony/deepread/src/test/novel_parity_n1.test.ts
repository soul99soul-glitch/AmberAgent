// 小说 N1 单测:PromptCatalog / 分支与版本 mutations / 润色哨兵
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  novelPromptTemplate, stripPolishSentinel, hasPolishSentinel, POLISH_COMPLETION_SENTINEL,
} from '../main/ets/novel/prompt_catalog.ts';
import type { NovelRunKind } from '../main/ets/novel/prompt_catalog.ts';
import {
  saveChapterVersion, restoreChapterVersion, setChapterDiscarded,
  forkBranch, selectBranch, renameBranch,
} from '../main/ets/novel/mutations.ts';
import { makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';

const KINDS: NovelRunKind[] = ['quick_start', 'discussion', 'prose_continuation',
  'prose_whole_chapter', 'polish', 'regenerate'];

test('PromptCatalog: 6 种 RunKind 均有非空模板', () => {
  for (const k of KINDS) {
    const t = novelPromptTemplate(k);
    assert.equal(t.kind, k);
    assert.ok(t.systemText.length > 20, `${k} 模板过短`);
  }
});

test('discussion prompt requires native ask_user and novel_workspace_write tools', () => {
  const discussion = novelPromptTemplate('discussion').systemText;
  assert.ok(discussion.includes('ask_user 工具'));
  assert.ok(discussion.includes('novel_workspace_write 工具'));
  assert.ok(discussion.includes('proposal_id'));
  assert.ok(discussion.includes('明确批准'));
  assert.ok(!discussion.includes('```ask_user'));
  assert.ok(!discussion.includes('```setting_proposals'));
  assert.ok(!discussion.includes('setting_proposals'));
});

test('润色哨兵: 剥离/检测', () => {
  const out = `润色后的正文。\n\n${POLISH_COMPLETION_SENTINEL}`;
  assert.equal(hasPolishSentinel(out), true);
  assert.equal(stripPolishSentinel(out), '润色后的正文。');
  assert.equal(hasPolishSentinel('无哨兵'), false);
  assert.equal(stripPolishSentinel('无哨兵'), '无哨兵');
});

const now = Date.now();
const mkProject = () => {
  let p = makeNovelProject({ name: '测试项目', now: now });
  const ch = makeNovelChapter({ title: '第一章', content: '第一章正文内容。', now: now });
  p = { ...p, chapters: [ch] };
  return p;
};

test('saveChapterVersion + restoreChapterVersion: 存档/恢复且当前内容先备份', () => {
  let p = mkProject();
  const chapterId = p.chapters[0].id;
  const v1 = saveChapterVersion(p, chapterId, 'manual', now);
  p = v1.project;
  assert.equal(p.chapterVersions.length, 1);
  // 恢复到 v1(当前内容会先备份一版 manual)
  const r = restoreChapterVersion(p, v1.value.id, now + 1);
  p = r.project;
  assert.equal(p.chapters[0].content, '第一章正文内容。');
  assert.equal(p.chapterVersions.length, 2);
  assert.equal(p.chapterVersions[1].kind, 'manual');
});

test('setChapterDiscarded: 标记翻转', () => {
  let p = mkProject();
  const d = setChapterDiscarded(p, p.chapters[0].id, true, now);
  assert.equal(d.value.discarded, true);
  const u = setChapterDiscarded(d.project, p.chapters[0].id, false, now + 1);
  assert.equal(u.value.discarded, false);
});

test('分支: fork → select → rename', () => {
  let p = mkProject();
  const f = forkBranch(p, '支线 A', now);
  p = f.project;
  assert.equal(p.branches.length, 1);
  assert.equal(p.branches[0].isMain, false);
  const s = selectBranch(p, f.value.id, now + 1);
  p = s.project;
  assert.equal(p.branches.find(b => b.id === f.value.id)?.isMain, true);
  const r = renameBranch(p, f.value.id, '支线 B', now + 2);
  assert.equal(r.value.name, '支线 B');
});
