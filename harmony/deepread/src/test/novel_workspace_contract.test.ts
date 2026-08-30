import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  NOVEL_WORKSPACE_FORMAT, NOVEL_WORKSPACE_VERSION,
  parseNovelWorkspaceManifest, serializeNovelWorkspaceManifest,
  validateNovelWorkspacePath, chapterFileName,
} from '../main/ets/novel/workspace_contract.ts';

test('workspace manifest v1 roundtrips required identity and active branch', () => {
  const yaml = serializeNovelWorkspaceManifest({
    format: NOVEL_WORKSPACE_FORMAT,
    version: NOVEL_WORKSPACE_VERSION,
    projectId: 'cultivation',
    title: '修仙日常',
    activeBranch: 'main',
    createdAt: 1_000,
    updatedAt: 2_000,
  });
  const parsed = parseNovelWorkspaceManifest(yaml);
  assert.equal(parsed.format, 'amber.novel.workspace');
  assert.equal(parsed.version, 1);
  assert.equal(parsed.projectId, 'cultivation');
  assert.equal(parsed.title, '修仙日常');
  assert.equal(parsed.activeBranch, 'main');
  assert.equal(parsed.createdAt, 1_000);
  assert.equal(parsed.updatedAt, 2_000);
});

test('workspace manifest rejects unsupported or incomplete documents', () => {
  assert.throws(() => parseNovelWorkspaceManifest('format: other\nversion: 1\n'));
  assert.throws(() => parseNovelWorkspaceManifest(
    'format: amber.novel.workspace\nversion: 2\nproject_id: p\ntitle: x\nactive_branch: main\n',
  ));
  assert.throws(() => parseNovelWorkspaceManifest(
    'format: amber.novel.workspace\nversion: 1\nproject_id: p\ntitle: x\n',
  ));
});

test('workspace paths reject escape, hidden, ambiguous and platform-specific paths', () => {
  const invalid = [
    '', '/setting/world.md', '\\setting\\world.md', 'setting//world.md',
    'setting/./world.md', 'setting/../world.md', '../project.md',
    '.amber/jobs/a.json', 'setting/.secret/world.md', 'C:/world.md',
  ];
  invalid.forEach(path => assert.throws(() => validateNovelWorkspacePath(path), path));
  assert.equal(validateNovelWorkspacePath('branches/main/chapters/001-开端.md'),
    'branches/main/chapters/001-开端.md');
});

test('chapter filenames use stable three-digit ASCII ordinals', () => {
  assert.equal(chapterFileName(1, ' 初见 / 重逢 '), '001-初见-重逢.md');
  assert.equal(chapterFileName(31, '第三十一章'), '031-第三十一章.md');
  assert.throws(() => chapterFileName(0, '坏'));
  assert.throws(() => chapterFileName(1_000, '坏'));
});
