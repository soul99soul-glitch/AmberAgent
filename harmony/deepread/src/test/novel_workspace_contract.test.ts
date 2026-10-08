import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  parseNovelWorkspaceManifest, validateNovelWorkspacePath, chapterFileName,
} from '../main/ets/novel/workspace_contract.ts';

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
