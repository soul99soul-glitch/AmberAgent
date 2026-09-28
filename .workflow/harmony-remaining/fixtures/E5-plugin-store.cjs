// One actual-source host group; real temporary files and Node equivalents for SDK file/crypto APIs.
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
  if (!fs.existsSync(filename)) filename += fs.existsSync(filename + '.ts') ? '.ts' : '.ets';
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
    Uint8Array, ArrayBuffer, Error, Promise, Map, Set, Math, Number, BigInt, JSON, String, Date,
    RegExp, Object, URL, console, setTimeout, clearTimeout });
  return module.exports;
}
function access(p) {
  try { fs.accessSync(p); return true; }
  catch (e) { if (e.code === 'ENOENT') return false; throw e; }
}
async function setup() {
  const temporary = await fsp.mkdtemp('/tmp/e5-plugin-store-');
  const filesDir = path.join(temporary, 'private');
  const workspaceRoot = path.join(temporary, 'workspace');
  await fsp.mkdir(filesDir); await fsp.mkdir(workspaceRoot);
  const faults = { pointer: false, read: false }; const handles = new Map(); let writes = 0;
  const listLimits = [];
  const fileIo = {
    OpenMode: { READ_ONLY: fs.constants.O_RDONLY, READ_WRITE: fs.constants.O_RDWR,
      CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
    accessSync: access, access: async p => access(p),
    lstatSync: p => { try { return fs.lstatSync(p); } catch (e) { if (e.code === 'ENOENT') e.code = 13900002; throw e; } }, statSync: p => fs.statSync(p),
    listFileSync: (p, options) => { const names = fs.readdirSync(p); listLimits.push(options?.listNum ?? 0);
      return options?.listNum ? names.slice(0, options.listNum) : names; },
    mkdir: (p, recursive) => fsp.mkdir(p, { recursive }), mkdirSync: (p, recursive) => fs.mkdirSync(p, { recursive }),
    readText: p => { if (faults.read) throw new Error('fixture read failure'); return fsp.readFile(p, 'utf8'); },
    readTextSync: p => fs.readFileSync(p, 'utf8'),
    open: async (p, flags) => { const h = await fsp.open(p, flags); handles.set(h.fd, h); return { fd: h.fd }; },
    openSync: (p, flags) => ({ fd: fs.openSync(p, flags) }),
    readSync: (fd, bytes, options) => { if (faults.read) throw Object.assign(new Error('fixture read failure'), { code: 13900005 }); return fs.readSync(fd, Buffer.from(bytes), 0, options?.length ?? bytes.byteLength, options?.offset ?? null); },
    write: async (fd, bytes) => { writes++; return (await handles.get(fd).write(Buffer.from(bytes))).bytesWritten; },
    writeSync: (fd, bytes) => { writes++; return fs.writeSync(fd, Buffer.from(bytes).subarray(0, 7)); },
    close: async file => { const h = handles.get(file.fd); handles.delete(file.fd); await h.close(); },
    closeSync: file => fs.closeSync(file.fd ?? file),
    renameSync: (from, to) => { if (faults.pointer && to.endsWith('/current.json')) throw new Error('fixture pointer failure'); fs.renameSync(from, to); },
    unlinkSync: p => fs.unlinkSync(p), rmdirSync: p => fs.rmSync(p, { recursive: true }),
  };
  const kits = {
    '@kit.AbilityKit': {}, '@kit.BasicServicesKit': {}, '@kit.CoreFileKit': { fileIo },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
    '@kit.ArkTS': { util: {
      generateRandomUUID: () => crypto.randomUUID(),
      TextEncoder: { create: () => ({ encodeInto: s => new Uint8Array(Buffer.from(s, 'utf8')) }) },
      TextDecoder: { create: (encoding, options) => ({ decodeToString: b => new TextDecoder(encoding, options).decode(b) }) },
      Base64Helper: class { decodeSync(s) { return new Uint8Array(Buffer.from(s, 'base64')); }
        encodeToStringSync(b) { return Buffer.from(b).toString('base64'); } },
    } },
    '@kit.CryptoArchitectureKit': { cryptoFramework: {
      AsyKeySpecType: { PUBLIC_KEY_SPEC: 1 },
      createMd: () => { const md = crypto.createHash('sha256'); return {
        updateSync: blob => md.update(blob.data), digestSync: () => ({ data: new Uint8Array(md.digest()) }),
      }; },
      createAsyKeyGeneratorBySpec: spec => ({ generatePubKey: async () => {
        const raw = Buffer.from(spec.pk.toString(16).padStart(64, '0'), 'hex');
        return crypto.createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), raw]), format: 'der', type: 'spki' });
      } }),
      createVerify: () => { let key; return { init: async k => { key = k; },
        verify: async (data, signature) => crypto.verify(null, data.data, key, signature.data) }; },
    } },
  };
  const domain = {};
  for (const relative of ['recipes/models.ts', 'recipes/validation.ts', 'plugins/models.ts', 'plugins/validation.ts', 'workspace.ts'])
    Object.assign(domain, loadSource(path.join(domainDir, relative), kits));
  kits['@amber/chat-domain'] = domain;
  kits['./WorkspaceStore'] = { workspaceRootAbs: () => workspaceRoot };
  const store = await loadSource(path.join(platformDir, 'PluginStore.ets'), kits).createEntryPluginStore({ filesDir }, async () => primitives);
  const makeAgentTool = loadSource(path.join(domainDir, 'tool.ts'), kits).makeAgentTool;
  const primitives = [makeAgentTool({ name: 'file_write', description: 'fixture writer', needsApproval: true,
    execute: async () => { throw new Error('Store never executes primitives'); } })];
  return { temporary, filesDir, workspaceRoot, store, primitives, faults, handles, listLimits,
    writeCount: () => writes, cleanup: () => fsp.rm(temporary, { recursive: true, force: true }) };
}
function swiftFixture(temporary, packageDirectory) {
  const iosDir = path.resolve(repo, '../amberagent-ios/iosApp/iosApp');
  const model = fs.readFileSync(path.join(iosDir, 'IOSRecipeModels.swift'), 'utf8');
  const command = fs.readFileSync(path.join(iosDir, 'IOSPluginCommand.swift'), 'utf8');
  const schema = fs.readFileSync(path.join(iosDir, 'IOSPluginJSONSchema.swift'), 'utf8');
  const sources = model.slice(0, model.indexOf('// MARK: - Catalog lookup'))
    + model.slice(model.indexOf('enum IOSRecipeValidationCode'), model.indexOf('struct IOSRecipeValidationResult'))
    + command.slice(0, command.indexOf('/// The source and its manifest'))
    + model.slice(model.indexOf('enum IOSPluginLimits'), model.indexOf('enum IOSPluginToolImplementation')) + schema;
  const script = path.join(temporary, 'plugin-fixture.swift');
  fs.writeFileSync(script, sources + `
import CryptoKit
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let paths = ["assets/结果.txt", "assets/汉.txt", "assets/😀.txt", "assets/\ue000.txt", "plugin.json", "recipes/write.json", "scripts/read.js"]
var hash = SHA256(); hash.update(data: IOSPluginLimits.packageHashDomain)
for path in paths.sorted() {
 var data = try Data(contentsOf: root.appendingPathComponent(path))
 if path == "plugin.json" { data = try IOSPluginManifest.decode(data).canonicalJSONData() }
 if path.hasPrefix("recipes/") { data = try IOSRecipeManifest.decode(data).canonicalJSONData() }
 var pathLength = UInt64(path.utf8.count).bigEndian; var fileLength = UInt64(data.count).bigEndian
 hash.update(data: Data(bytes: &pathLength, count: 8)); hash.update(data: Data(path.utf8))
 hash.update(data: Data(bytes: &fileLength, count: 8)); hash.update(data: data)
 if path == "plugin.json" { print(data.base64EncodedString()) }
}
print(hash.finalize().map { String(format: "%02x", $0) }.joined())
`);
  const [canonical, hash] = execFileSync('/usr/bin/swift', [script, packageDirectory], {
    encoding: 'utf8', timeout: 60000,
    env: { ...process.env, CLANG_MODULE_CACHE_PATH: path.join(temporary, 'swift-cache') },
  }).trim().split('\n');
  return { canonical: Buffer.from(canonical, 'base64').toString('utf8'), hash };
}

test('one actual-file PluginStore group: cross-Swift canonical/hash, CAS publication, archive signature and live health', async () => {
  const s = await setup();
  try {
    const directory = path.join(s.workspaceRoot, 'candidate');
    for (const child of ['assets', 'recipes', 'scripts']) await fsp.mkdir(path.join(directory, child), { recursive: true });
    const manifest = { schema: 'amber.plugin.v1', id: 'unicode_fixture', name: '中文🙂插件', version: '1.0',
      description: '结果/output.json Unicode fixture', backgroundAllowed: false,
      capabilities: { workspaceReadPrefixes: [], workspaceWritePrefixes: ['/workspace/结果'], networkDomains: [], webMountActions: [] },
      tools: [{ name: 'write', recipe: 'recipes/write.json' },
        { name: 'inspect', script: 'scripts/read.js', inputs: { text: 'string' }, output: 'object',
          output_schema: { type: 'object', enum: [{ '\ue000': 1, '😀': 2, '汉': 3 }] } }],
      directory: { publisher: '中文开发者🙂', homepage_url: 'https://example.com/路径/info' } };
    const recipe = { schema: 'amber.recipe.v1', name: 'write_fixture', version: '1.0', description: '写入🙂',
      inputs: { text: 'string' }, steps: [{ id: 'write', tool: 'file_write', arguments: {
        path: '结果/output.json', content: '${input.text}', literal: { z: [true, null, 1.5], a: '中\n文🙂' } } }],
      outputs: { saved_path: '${step.write.output.path}' } };
    await fsp.writeFile(path.join(directory, 'plugin.json'), JSON.stringify(manifest));
    await fsp.writeFile(path.join(directory, 'recipes/write.json'), JSON.stringify(recipe));
    await fsp.writeFile(path.join(directory, 'scripts/read.js'), 'return { text: input.text };\n');
    const asset = path.join(directory, 'assets/结果.txt'); await fsp.writeFile(asset, '保持/原始🙂');
    for (const name of ['汉', '😀', '\ue000']) await fsp.writeFile(path.join(directory, `assets/${name}.txt`), name);
    const source = { kind: 'directory', workspacePath: 'candidate' };
    assert.equal((await s.store.listInstalled()).length, 0);
    const preview = await s.store.prepareImport(source, s.primitives, false);
    assert.equal(preview.baseHash, null); assert.equal(preview.enable, false);
    assert.equal(s.writeCount(), 0); assert.equal(access(path.join(s.filesDir, 'plugins')), false);
    if (process.env.E5_STORE_CHECK === 'missing-package') {
      const first = await s.store.applyImport(preview, s.primitives);
      const published = path.join(s.filesDir, `plugins/${first.id}/packages/${first.hash}`);
      await fsp.rm(published, { recursive: true });
      const invalid = (await s.store.listInstalled())[0];
      assert.equal(invalid.package, null); assert.equal(invalid.currentHash, first.hash);
      assert.equal(invalid.configuredEnabled, false); assert.equal(invalid.enabled, false);
      assert.equal(invalid.errorCode, 'missing_package');
      await s.store.remove(first.id, first.hash);
      assert.equal((await s.store.listInstalled()).length, 0);
      return;
    }
    const swift = swiftFixture(s.temporary, directory);
    const canonical = Buffer.from(preview.candidate.files.find(f => f.path === 'plugin.json').data).toString();
    assert.equal(canonical, swift.canonical); assert.equal(preview.candidate.hash, swift.hash);
    assert.equal(Buffer.from(preview.candidate.files.find(f => f.path === 'assets/结果.txt').data).toString(), '保持/原始🙂');
    const first = await s.store.applyImport(preview, s.primitives);
    const pointerFile = path.join(s.filesDir, 'plugins/unicode_fixture/current.json');
    assert.equal(JSON.parse(await fsp.readFile(pointerFile)).hash, first.hash);
    assert.equal((await s.store.listInstalled())[0].enabled, false);
    await s.store.setEnabled(first.id, first.hash, true);
    const missingOracle = (await s.store.listInstalled([]))[0];
    assert.equal(missingOracle.package, null); assert.equal(missingOracle.configuredEnabled, true);
    assert.equal(missingOracle.errorCode, 'invalid_package');
    s.faults.read = true; await assert.rejects(s.store.listInstalled(), /fixture read failure/); s.faults.read = false;
    manifest.version = '2.0'; await fsp.writeFile(path.join(directory, 'plugin.json'), JSON.stringify(manifest));
    const secondPreview = await s.store.prepareImport(source, s.primitives, true);
    await fsp.writeFile(asset, 'changed after approval');
    const before = s.writeCount();
    await assert.rejects(s.store.applyImport(secondPreview, s.primitives), e => e.code === 'stale_candidate');
    assert.equal(s.writeCount(), before); assert.equal(JSON.parse(await fsp.readFile(pointerFile)).hash, first.hash);
    await fsp.writeFile(asset, '保持/原始🙂');
    const secondFixed = await s.store.prepareImport(source, s.primitives, true);
    const concurrent = await Promise.allSettled([s.store.applyImport(secondFixed, s.primitives), s.store.applyImport(secondFixed, s.primitives)]);
    assert.equal(concurrent.filter(r => r.status === 'fulfilled').length, 1);
    assert.equal(concurrent.filter(r => r.status === 'rejected' && r.reason.code === 'stale_base').length, 1);
    const second = concurrent.find(r => r.status === 'fulfilled').value;
    await assert.rejects(s.store.setEnabled(first.id, first.hash, false), e => e.code === 'stale_base');
    await assert.rejects(s.store.remove(first.id, first.hash), e => e.code === 'stale_base');
    const pointerBeforeFault = await fsp.readFile(pointerFile, 'utf8');
    manifest.version = '3.0'; await fsp.writeFile(path.join(directory, 'plugin.json'), JSON.stringify(manifest));
    const third = await s.store.prepareImport(source, s.primitives, false); s.faults.pointer = true;
    await assert.rejects(s.store.applyImport(third, s.primitives), /fixture pointer failure/);
    s.faults.pointer = false;
    assert.equal(await fsp.readFile(pointerFile, 'utf8'), pointerBeforeFault); assert.equal(s.handles.size, 0);
    assert.equal(access(pointerFile + '.tmp'), false);
    const rolled = await s.store.rollback(first.id, second.hash);
    assert.equal(rolled.hash, first.hash); assert.equal(rolled.enabled, true);
    await assert.rejects(s.store.rollback(first.id, first.hash), e => e.code === 'no_previous');
    const archiveBytes = await s.store.exportArchive(first.id, first.hash);
    const archive = JSON.parse(Buffer.from(archiveBytes).toString());
    const keys = crypto.generateKeyPairSync('ed25519');
    const publicKey = keys.publicKey.export({ format: 'der', type: 'spki' }).subarray(-32);
    const fingerprint = crypto.createHash('sha256').update(publicKey).digest('hex');
    archive.signature = { algorithm: 'ed25519', keyId: fingerprint.slice(0, 16), publicKeyBase64: publicKey.toString('base64'),
      signatureBase64: crypto.sign(null, Buffer.from('amber.plugin.signature.v1\0' + first.hash), keys.privateKey).toString('base64') };
    const archivePath = path.join(s.workspaceRoot, 'signed.amberplugin'); await fsp.writeFile(archivePath, JSON.stringify(archive));
    const archiveSource = { kind: 'archive', workspacePath: 'signed.amberplugin' };
    const signed = await s.store.prepareImport(archiveSource, s.primitives, true);
    assert.equal(signed.trust.tier, 'signed'); assert.equal(signed.trust.publisherTrusted, false);
    await s.store.applyImport(signed, s.primitives);
    await s.store.addTrustedKey(archive.signature.publicKeyBase64, '开发者🙂');
    assert.equal((await s.store.listInstalled())[0].trust.publisherTrusted, true);
    const preserved = JSON.parse(Buffer.from(await s.store.exportArchive(first.id, first.hash)).toString());
    assert.deepEqual(preserved.signature, archive.signature); assert.deepEqual(preserved.files, archive.files);
    await s.store.removeTrustedKey(fingerprint); assert.equal((await s.store.listInstalled())[0].trust.publisherTrusted, false);
    archive.files.find(f => f.path === 'assets/结果.txt').dataBase64 = Buffer.from('tampered').toString('base64');
    await fsp.writeFile(archivePath, JSON.stringify(archive));
    await assert.rejects(s.store.readPackage(archiveSource, s.primitives), e => e.code === 'signature_invalid');
    preserved.files.push(preserved.files[0]); await fsp.writeFile(archivePath, JSON.stringify(preserved));
    await assert.rejects(s.store.readPackage(archiveSource, s.primitives), e => e.code === 'archive_invalid');
    await fsp.writeFile(archivePath, ' '.repeat(2 * 1024 * 1024 + 1));
    await assert.rejects(s.store.readPackage(archiveSource, s.primitives), e => e.code === 'file_budget_exceeded');
    await fsp.symlink(directory, path.join(s.workspaceRoot, 'linked-parent'));
    await assert.rejects(s.store.readPackage({ kind: 'directory', workspacePath: 'linked-parent' }, s.primitives), e => e.code === 'invalid_path');
    await fsp.symlink(asset, path.join(directory, 'assets/link.txt'));
    await assert.rejects(s.store.readPackage(source, s.primitives), e => e.code === 'invalid_path');
    await fsp.unlink(path.join(directory, 'assets/link.txt'));
    for (let i = 0; i < 29; i++) await fsp.writeFile(path.join(directory, `assets/extra${i}`), '');
    await assert.rejects(s.store.readPackage(source, s.primitives), e => e.code === 'file_budget_exceeded');
    assert.equal(s.listLimits.filter(n => n > 0).every(n => n <= 257), true);
    await s.store.recordFailure(first.id, first.hash, 'write', 'exception', '第一次');
    await s.store.recordSuccess(first.id, first.hash);
    for (let i = 0; i < 3; i++) await s.store.recordFailure(first.id, first.hash, 'write', 'schema', '🙂'.repeat(510));
    const quarantined = (await s.store.listInstalled())[0];
    assert.equal(quarantined.configuredEnabled, true); assert.equal(quarantined.enabled, false);
    assert.equal(quarantined.health.consecutiveFailures, 3); assert.equal(Array.from(quarantined.health.diagnostics.at(-1).detail).length, 500);
    await assert.rejects(s.store.restore(first.id, second.hash), e => e.code === 'stale_base');
    await s.store.restore(first.id, first.hash); assert.equal((await s.store.listInstalled())[0].enabled, true);
    const healthWrites = s.writeCount(); await s.store.recordFailure(first.id, second.hash, 'write', 'exception', 'old pinned completion');
    assert.equal(s.writeCount(), healthWrites); assert.equal((await s.store.listInstalled())[0].health.consecutiveFailures, 0);
    const liveAsset = path.join(s.filesDir, `plugins/unicode_fixture/packages/${first.hash}/assets/结果.txt`);
    await fsp.writeFile(liveAsset, 'installed corruption');
    const invalid = (await s.store.listInstalled())[0];
    assert.equal(invalid.package, null); assert.equal(invalid.currentHash, first.hash); assert.equal(invalid.enabled, false);
    assert.equal(invalid.errorCode, 'package_hash_mismatch');
    await s.store.remove(first.id, first.hash); assert.equal((await s.store.listInstalled()).length, 0);
    console.log('Cross-Swift plugin canonical bytes=' + Buffer.byteLength(canonical) + ' hash=' + first.hash);
  } finally { await s.cleanup(); }
});
