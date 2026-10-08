import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import ts from 'typescript';

interface ExportFilesAPI {
  saveMarkdownFile(context: object, name: string, content: string): Promise<boolean>;
  shareMarkdownFile(context: object, name: string, content: string): Promise<void>;
}

test('Markdown save and share write actual UTF-8 content using SDK-supported file input', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-markdown-export-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const destination = path.join(root, 'saved.md');
  const content = '# 导出验收\n\n中文、Markdown **正文**与 emoji 😀\n';
  let shared = false;
  const fileIo = {
    OpenMode: { READ_WRITE: fs.constants.O_RDWR, CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
    openSync: (name: string, flags: number) => ({ fd: fs.openSync(name, flags) }),
    writeSync: (fd: number, value: ArrayBuffer | string) => {
      // Harmony SDK accepts ArrayBuffer|string; Node additionally accepting typed arrays hid the bug.
      assert.ok(typeof value === 'string' || value instanceof ArrayBuffer, 'SDK writeSync rejects Uint8Array');
      return typeof value === 'string' ? fs.writeSync(fd, value, undefined, 'utf8')
        : fs.writeSync(fd, new Uint8Array(value));
    },
    closeSync: fs.closeSync,
    unlinkSync: fs.unlinkSync,
  };
  const imports: Record<string, unknown> = {
    '@kit.AbilityKit': {},
    '@kit.CoreFileKit': { fileIo, fileUri: { getUriFromPath: (name: string) => `file://app${name}` },
      picker: { DocumentViewPicker: class { async save() { return [destination]; } } } },
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: (text: string) => new TextEncoder().encode(text) }) } } },
    '@kit.ArkData': { uniformTypeDescriptor: { UniformDataType: { MARKDOWN: 'general.markdown' } } },
    '@kit.ShareKit': { systemShare: {
      SharedData: class { constructor(public record: { utd: string; uri: string; title: string }) {} },
      ShareController: class {
        constructor(public data: { record: { utd: string; uri: string; title: string } }) {}
        async show(context: { cacheDir: string }) {
          assert.equal(this.data.record.uri, `file://app${path.join(context.cacheDir, 'shared.md')}`);
          assert.equal(fs.readFileSync(path.join(context.cacheDir, 'shared.md'), 'utf8'), content);
          shared = true;
        }
      },
    } },
  };
  const source = fs.readFileSync(new URL('../../../entry/src/main/ets/platform_impl/ChatExportFiles.ets', import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  new Function('require', 'exports', 'module', compiled)((id: string) => {
    assert.ok(id in imports, `unexpected import ${id}`);
    return imports[id];
  }, module.exports, module);
  const api = module.exports as ExportFilesAPI;
  const context = { cacheDir: root };
  assert.equal(await api.saveMarkdownFile(context, 'saved.md', content), true);
  assert.equal(fs.readFileSync(destination, 'utf8'), content);
  await api.shareMarkdownFile(context, 'shared.md', content);
  assert.equal(shared, true);
});
