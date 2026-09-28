// One host fixture group: actual RecipeStore/AtomicJsonFile/domain source,
// real temporary files; Harmony SDK file/crypto calls use Node equivalents.
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const test = require('node:test');
const repo = path.resolve(__dirname, '../../..');
const ts = require(path.join(repo, 'harmony/chat/node_modules/typescript'));
const domainDir = path.join(repo, 'harmony/chat/src/main/ets/chat');
const platformDir = path.join(repo, 'harmony/entry/src/main/ets/platform_impl');

function loadSource(filename, kits, cache = new Map()) {
  if (!fs.existsSync(filename)) filename += '.ets';
  filename = path.resolve(filename);
  if (cache.has(filename)) return cache.get(filename);
  const module = { exports: {} }; cache.set(filename, module.exports);
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const requireSource = name => {
    if (kits[name] !== undefined) return kits[name];
    if (name.startsWith('.')) return loadSource(path.resolve(path.dirname(filename), name), kits, cache);
    throw new Error('Unexpected module: ' + name);
  };
  vm.runInNewContext(code, { module, exports: module.exports, require: requireSource,
    Uint8Array, ArrayBuffer, Error, Promise, Map, Set, Math, Number, JSON, String, Date,
    RegExp, Object, console, setTimeout, clearTimeout });
  return module.exports;
}

function access(p) {
  try { fs.accessSync(p); return true; }
  catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}

async function setup() {
  const temporary = await fsp.mkdtemp('/tmp/e4-recipe-store-');
  const filesDir = path.join(temporary, 'private');
  const workspaceRoot = path.join(temporary, 'workspace');
  await fsp.mkdir(filesDir); await fsp.mkdir(workspaceRoot);
  const faults = { rename: false, read: false };
  const handles = new Map(); let writes = 0;
  const fileIo = {
    OpenMode: { READ_WRITE: fs.constants.O_RDWR, CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
    accessSync: access, statSync: p => fs.statSync(p), listFileSync: p => fs.readdirSync(p),
    access: async p => access(p), mkdir: (p, recursive) => fsp.mkdir(p, { recursive }),
    readText: p => {
      if (faults.read) return Promise.reject(Object.assign(new Error('fixture read failure'), { code: 'EIO' }));
      return fsp.readFile(p, 'utf8');
    },
    open: async (p, flags) => { const h = await fsp.open(p, flags); handles.set(h.fd, h); return { fd: h.fd }; },
    write: async (fd, bytes) => { writes++; return (await handles.get(fd).write(Buffer.from(bytes))).bytesWritten; },
    close: async file => { const h = handles.get(file.fd); handles.delete(file.fd); await h.close(); },
    renameSync: (from, to) => {
      if (faults.rename) throw Object.assign(new Error('fixture rename failure'), { code: 'EIO' });
      fs.renameSync(from, to);
    },
    unlinkSync: p => fs.unlinkSync(p), rmdirSync: p => fs.rmSync(p, { recursive: true }),
  };
  const kits = {
    '@kit.AbilityKit': {}, '@kit.CoreFileKit': { fileIo },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
    '@kit.ArkTS': { util: {
      TextEncoder: { create: () => ({ encodeInto: text => new Uint8Array(Buffer.from(text, 'utf8')) }) },
      TextDecoder: { create: (encoding, options) => ({ decodeToString: bytes => new TextDecoder(encoding, options).decode(bytes) }) },
    } },
    '@kit.CryptoArchitectureKit': { cryptoFramework: {
      createMd: () => { const md = crypto.createHash('sha256'); return {
        updateSync: blob => md.update(blob.data), digestSync: () => ({ data: new Uint8Array(md.digest()) }),
      }; },
    } },
  };
  const models = loadSource(path.join(domainDir, 'recipes/models.ts'), kits);
  const validation = loadSource(path.join(domainDir, 'recipes/validation.ts'), kits);
  const workspaceDomain = loadSource(path.join(domainDir, 'workspace.ts'), kits);
  kits['@amber/chat-domain'] = { ...models, ...validation, ...workspaceDomain };
  const workspace = new workspaceDomain.PosixWorkspaceManager({ rootAbs: workspaceRoot, port: {
    exists: access, isDirectory: p => fs.statSync(p).isDirectory(),
    isFile: p => access(p) && fs.statSync(p).isFile(), fileSize: p => fs.statSync(p).size,
    readBytes: p => new Uint8Array(fs.readFileSync(p)),
  } });
  const store = await loadSource(path.join(platformDir, 'RecipeStore.ets'), kits).createEntryRecipeStore({ filesDir }, workspace);
  const makeAgentTool = loadSource(path.join(domainDir, 'tool.ts'), kits).makeAgentTool;
  const primitives = [makeAgentTool({ name: 'file_write', description: 'fixture writer', needsApproval: true,
    execute: async () => { throw new Error('Store must never execute a primitive'); } })];
  return { temporary, filesDir, workspaceRoot, store, primitives, faults, handles,
    writeCount: () => writes, cleanup: () => fsp.rm(temporary, { recursive: true, force: true }) };
}

function swiftFixture(temporary, input) {
  const ios = path.resolve(repo, '../amberagent-ios/iosApp/iosApp/IOSRecipeModels.swift');
  const source = fs.readFileSync(ios, 'utf8');
  const model = source.slice(0, source.indexOf('// MARK: - Catalog lookup'));
  const issues = source.slice(source.indexOf('enum IOSRecipeValidationCode'), source.indexOf('struct IOSRecipeValidationResult'));
  const script = path.join(temporary, 'hash-fixture.swift');
  fs.writeFileSync(script, model + issues + `
import CryptoKit
let manifest = try IOSRecipeManifest.decode(Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
let canonical = try manifest.canonicalJSONData()
var length = UInt64(canonical.count).bigEndian
var hash = SHA256()
hash.update(data: IOSRecipeLimits.packageHashDomain)
hash.update(data: Data(bytes: &length, count: MemoryLayout<UInt64>.size))
hash.update(data: canonical)
let hex = hash.finalize().map { String(format: "%02x", $0) }.joined()
print(canonical.base64EncodedString())
print(hex)
`);
  const [canonical, hash] = execFileSync('/usr/bin/swift', [script, input], {
    encoding: 'utf8', timeout: 60000,
    env: { ...process.env, CLANG_MODULE_CACHE_PATH: path.join(temporary, 'swift-cache') },
  }).trim().split('\n');
  return { canonical: Buffer.from(canonical, 'base64').toString('utf8'), hash };
}

test('one real-file RecipeStore group: Unicode hash, no-write preview, double CAS and lifecycle/failure', async () => {
  const s = await setup();
  try {
    const raw = { schema: 'amber.recipe.v1', name: 'unicode_fixture', version: '1.0', description: '中文🙂 Unicode fixture',
      inputs: { text: 'string' }, steps: [{ id: 'write', tool: 'file_write', arguments: {
        content: '${input.text}', path: '结果/output.json', literal: { z: [true, null, 1.5], a: '中\n文🙂' },
      } }], outputs: { saved_path: '${step.write.output.path}' }, ignoredUnknown: 'not canonical' };
    const input = path.join(s.workspaceRoot, 'import.json');
    await fsp.writeFile(input, JSON.stringify(raw, null, 2));
    assert.equal((await s.store.listInstalled()).length, 0);
    const preview = await s.store.prepareImport('/workspace/import.json', s.primitives);
    assert.equal(preview.workspacePath, 'import.json'); assert.equal(preview.baseHash, null);
    assert.equal(s.writeCount(), 0); assert.equal(access(path.join(s.filesDir, 'recipes')), false);
    assert.equal(preview.candidate.canonicalJSON.includes('ignoredUnknown'), false);
    const swift = swiftFixture(s.temporary, input);
    assert.equal(preview.candidate.canonicalJSON, swift.canonical); assert.equal(preview.candidate.hash, swift.hash);
    const first = await s.store.applyImport(preview, s.primitives);
    const directory = path.join(s.filesDir, 'recipes/unicode_fixture');
    const installedFile = path.join(directory, 'recipe.json');
    assert.equal(await fsp.readFile(installedFile, 'utf8'), first.canonicalJSON);
    assert.equal((await s.store.listInstalled())[0].enabled, true);
    await s.store.setEnabled(raw.name, first.hash, false);

    raw.version = '2.0'; await fsp.writeFile(input, JSON.stringify(raw));
    const secondPreview = await s.store.prepareImport('import.json', s.primitives);
    raw.description = 'changed after preview'; await fsp.writeFile(input, JSON.stringify(raw));
    const writeCount = s.writeCount();
    await assert.rejects(s.store.applyImport(secondPreview, s.primitives), e => e.code === 'stale_candidate');
    assert.equal(s.writeCount(), writeCount); assert.equal(await fsp.readFile(installedFile, 'utf8'), first.canonicalJSON);
    raw.description = '中文🙂 Unicode fixture'; await fsp.writeFile(input, JSON.stringify(raw));
    const fresh = await s.store.prepareImport('import.json', s.primitives);
    // Both import approvals freeze the same base; the per-name mutex permits only one update.
    const concurrent = await Promise.allSettled([s.store.applyImport(fresh, s.primitives), s.store.applyImport(fresh, s.primitives)]);
    assert.equal(concurrent.filter(r => r.status === 'fulfilled').length, 1);
    assert.equal(concurrent.filter(r => r.status === 'rejected' && r.reason.code === 'stale_base').length, 1);
    const second = concurrent.find(r => r.status === 'fulfilled').value;
    assert.equal((await s.store.listInstalled())[0].enabled, false);
    await assert.rejects(s.store.setEnabled(raw.name, first.hash, true), e => e.code === 'stale_base');
    await assert.rejects(s.store.remove(raw.name, first.hash), e => e.code === 'stale_base');
    assert.equal(await fsp.readFile(installedFile, 'utf8'), second.canonicalJSON);

    raw.version = '3.0'; await fsp.writeFile(input, JSON.stringify(raw));
    const failing = await s.store.prepareImport('import.json', s.primitives);
    s.faults.rename = true;
    await assert.rejects(s.store.applyImport(failing, s.primitives), /fixture rename failure/);
    s.faults.rename = false;
    assert.equal(await fsp.readFile(installedFile, 'utf8'), second.canonicalJSON);
    assert.equal(access(installedFile + '.tmp'), false); assert.equal(s.handles.size, 0);
    assert.equal((await s.store.listInstalled())[0].enabled, false);
    s.faults.read = true;
    await assert.rejects(s.store.listInstalled(), /fixture read failure/);
    await assert.rejects(s.store.prepareImport('import.json', s.primitives), /fixture read failure/);
    s.faults.read = false;
    assert.equal(await fsp.readFile(installedFile, 'utf8'), second.canonicalJSON);
    await fsp.writeFile(installedFile, '{broken');
    await assert.rejects(s.store.listInstalled());
    await assert.rejects(s.store.applyImport(failing, s.primitives));
    assert.equal(await fsp.readFile(installedFile, 'utf8'), '{broken');
    await fsp.writeFile(installedFile, second.canonicalJSON);
    await s.store.setEnabled(raw.name, second.hash, true);
    assert.equal((await s.store.listInstalled())[0].enabled, true);
    await s.store.remove(raw.name, second.hash); assert.equal((await s.store.listInstalled()).length, 0);
    assert.equal(access(directory), false);
    console.log(JSON.stringify({ swiftCanonicalBytes: Buffer.byteLength(swift.canonical), swiftJSHash: swift.hash,
      doubleCAS: 'stale_candidate/stale_base', updatePreservedDisabled: true, realFiles: true }));
  } finally { await s.cleanup(); }
});
