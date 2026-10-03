const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const zlib = require('node:zlib');
const domain = require('../../../deepread/src/main/ets/index.ts');
const chat = require('../../../chat/src/main/ets/index.ts');
const sourceRoot = path.resolve(__dirname, '../main/ets/platform_impl');
function load(name, imports) {
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(sourceRoot, name), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: spec => imports[spec] || {}, Promise, Error, Map, Set, Date, Uint8Array, JSON }, { filename: name });
  return exports;
}
const archive = load('NovelWorkspaceArchiveCodec.ets', {
  '@amber/chat-domain': chat,
  './ArtifactPorts.ets': { createDeflateRawPort: () => async bytes => new Uint8Array(zlib.deflateRawSync(bytes)) },
  './EntryDocumentParser.ets': { inflateRawEntry: async bytes => new Uint8Array(zlib.inflateRawSync(bytes)) },
});
const serviceExports = load('NovelWorkspaceService.ets', {
  '@amber/deepread-domain': domain, './NovelWorkspaceArchiveCodec.ets': archive,
});
async function setup() {
  const files = domain.createMemoryFileStore();
  const repo = domain.createFileNovelRepository(files);
  await repo.createProject({ ...domain.makeNovelProject({ id: 'p', name: '原作', now: 1000 }),
    chapters: [domain.makeNovelChapter({ id: 'c', title: '正文', content: '第一版', now: 1000 })] });
  const creation = domain.createNovelCreation({ repository: repo, modelRunning: {} });
  const service = serviceExports.createNovelWorkspaceService(repo, creation);
  return { files, repo, service };
}
for (const kind of ['native', 'public']) {
  test(`${kind} actual ZIP preview rejects, copies, and explicitly replaces without losing local text`, async () => {
    const { files, repo, service } = await setup();
    const bytes = kind === 'native' ? await service.exportNativeBackupZip('p') : await service.exportWorkspaceZip('p');
    assert.equal(new TextDecoder().decode(bytes.slice(0, 2)), 'PK');
    const preview = await service.inspectImport(bytes);
    assert.equal(preview.collision, true); assert.equal(preview.projectId, 'p');
    await assert.rejects(service.confirmImport(preview), /保留两份/);
    const copy = await service.confirmImport(preview, 'keepBoth');
    assert.notEqual(copy.id, 'p'); assert.equal(copy.chapters[0].content, '第一版');
    await repo.commitProject('p', (await repo.workspaceStatus('p')).cas, 'edit', 'manual_edit', p =>
      ({ ...p, chapters: p.chapters.map(c => ({ ...c, content: '保留现场' })) }));
    await assert.rejects(service.confirmImport(preview, 'replace'), /重新预览/);
    const fresh = await service.inspectImport(bytes);
    const replacement = await service.confirmImport(fresh, 'replace');
    assert.equal(replacement.chapters[0].content, '第一版');
    const retained = await files.list('amberagent/novel-workspace/.retained/p');
    assert.equal(await files.readText(`amberagent/novel-workspace/.retained/p/${retained[0]}/branches/main/chapters/001-正文.md`), '保留现场');
    assert.equal((await repo.listProjects()).length, 2);
  });
}
