import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { copyNovelWorkspaceImportPlan } from '../main/ets/novel/import_conflicts.ts';
import { buildNovelWorkspaceImportPlan } from '../main/ets/novel/workspace_interop.ts';
import { parseNovelWorkspaceManifest } from '../main/ets/novel/workspace_contract.ts';

const encoder = new TextEncoder();
const file = (path: string, content: string) => ({ path, bytes: encoder.encode(content) });
const md = (id: string, content: string) => `---\nid: "${id}"\n---\n\n${content}`;
const source = () => {
  const manifest = 'format: amber.novel.workspace\nversion: 1\nproject_id: original\ntitle: 小说\nactive_branch: main\n';
  return buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(manifest), [
    file('manifest.yaml', manifest), file('project.md', md('original', '项目描述 original 不改写')),
    file('branches/main/chapters/001-正文.md', md('chapter', 'original 是正文，不是标识')),
    file('branches/alt/chapters/001-正文.md', md('chapter', '支线正文')),
    { path: 'assets/opaque.bin', bytes: new Uint8Array([0, 255, 128, 42]) },
    file('assets/unknown.json', '{"projectId":"original","unknown":true}'),
  ]);
};

test('keepBoth creates a real public workspace with both branches and unchanged opaque bytes', () => {
  const original = source();
  const copied = copyNovelWorkspaceImportPlan(original, 'copy');
  assert.equal(copied.manifest.projectId, 'copy');
  assert.equal(copied.branches.length, 2);
  assert.equal(copied.branches[0].project.name, '小说（副本）');
  assert.ok(copied.branches.every(branch => branch.project.id === 'copy'));
  assert.equal(copied.branches[0].project.chapters[0].id, 'chapter');
  assert.equal(copied.branches[0].project.chapters[0].content, 'original 是正文，不是标识');
  for (const path of ['assets/opaque.bin', 'assets/unknown.json']) {
    assert.deepEqual(copied.files.find(f => f.path === path)!.bytes, original.files.find(f => f.path === path)!.bytes);
  }
  assert.equal(original.manifest.projectId, 'original');
  assert.equal(original.branches[0].project.id, 'original');
});

test('copy rejects same identity and paths instead of overwriting a workspace', () => {
  for (const id of ['original', '../unsafe', 'nested/id', '.private']) {
    assert.throws(() => copyNovelWorkspaceImportPlan(source(), id));
  }
});
