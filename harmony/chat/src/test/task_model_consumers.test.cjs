// Actual Entry OCR/image consumers and domain transformer; controlled DI/provider/SDK host.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

test('typed OCR/image choices share their health target and isolate duplicate UUID OCR caches', async () => {
  const domain = await import('../main/ets/index.ts');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'e8-task-consumers-'));
  const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
  const kv = domain.createMemoryKeyValueStore();
  const uuid = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const legacyUuid = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  const choices = new Map(), resolves = [], recognitions = [], images = [], files = [];
  for (const id of ['a', 'b']) {
    // Both overrides have the same effective ID; the original container remains distinct.
    const provider = domain.makeProviderSettingOpenAIVariant({ id: 'effective', enabled: true,
      baseUrl: `https://${id}.example.test/v1`, apiKey: `controlled-${id}` });
    for (const type of ['chat', 'image']) choices.set(`${id}:${type}`, {
      provider, providerContainerId: id, model: domain.makeProviderModel({ id: uuid,
        modelId: `${type}-${id}`, type, inputModalities: ['text', 'image'] }),
    });
  }
  const app = {
    TASK_IMAGE_MODEL_KEY: 'image_model_id',
    getChatKvStore: () => kv,
    readTaskModelReference: async key => domain.decodeTaskModelReference(await kv.get(key)),
    resolveConfiguredTaskModel: async (ref, type) => {
      resolves.push({ ref, type });
      // Existing generic Chat legacy resolver skips unauthenticated A; image's raw-first resolver does not.
      if (ref.kind === 'legacy') return type === 'chat' ? choices.get('legacy-b:chat') : null;
      const choice = ref.kind === 'fixed' ? choices.get(`${ref.pair.providerId}:${type}`) : null;
      return choice?.model.id === ref.pair.modelId ? choice : null;
    },
    prepareProviderApi: async provider => ({ generateText: async (messages, params) => {
      recognitions.push({ provider, messages, params });
      return { choices: [{ message: domain.makeAssistantMessage(provider.baseUrl) }] };
    } }),
    getAppContainer: () => ({ httpClient: { fetch: async request => {
      images.push(request);
      return { status: 200, body: JSON.stringify({ data: [{ b64_json: 'AQID' }] }) };
    } } }),
  };
  const fileIo = {
    OpenMode: { READ_WRITE: 1, CREATE: 2, TRUNC: 4 },
    accessSync: fs.existsSync, readTextSync: file => fs.readFileSync(file, 'utf8'),
    mkdirSync: (file, recursive) => fs.mkdirSync(file, { recursive }),
    openSync: file => ({ fd: fs.openSync(file, 'w') }),
    writeSync: (fd, value) => fs.writeSync(fd, typeof value === 'string' ? value : Buffer.from(value)),
    closeSync: file => fs.closeSync(typeof file === 'number' ? file : file.fd),
  };
  const imports = { '@amber/chat-domain': domain, '../di/AppContainer.ets': app,
    '@kit.CoreFileKit': { fileIo }, '@kit.PerformanceAnalysisKit': { hilog: { error: () => {} } },
    '@kit.NetworkKit': { http: {} }, '@kit.ArkTS': { util: { Base64Helper: class {
      decodeSync(value) { return new Uint8Array(Buffer.from(value, 'base64')); }
    } } }, './ManagedFileStore.ets': { getFilesRepository: () => ({ insert: async row => files.push(row) }) } };
  const loaded = new Map();
  const load = name => {
    if (imports[name]) return imports[name];
    if (loaded.has(name)) return loaded.get(name);
    const exports = {}; loaded.set(name, exports);
    const filename = path.join(entry, name);
    const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
    } }).outputText;
    vm.runInNewContext(code, { exports, require: load, AppStorage: { get: () => ({ cacheDir: directory, filesDir: directory }) },
      Error, Promise, Map, JSON, String, Date, Uint8Array, ArrayBuffer }, { filename });
    return exports;
  };
  try {
    const selection = load('./TaskModelSelectionSupport.ets'), ocr = load('./OcrSupport.ets');
    const image = load('./ImageGenTool.ets');
    const message = domain.makeUIMessage('user', [{ type: 'image', url: 'file:///same-image.png', metadata: null }]);
    for (const id of ['a', 'b']) {
      await selection.storeTaskModelPair(kv, ocr.OCR_MODEL_KEY, { providerId: id, modelId: uuid });
      const seed = await ocr.loadOcrSeed(kv);
      assert.equal(seed.ocrChoice.providerContainerId, id);
      assert.equal(seed.providers.length, 1);
      assert.equal((await ocr.probeEntryOcrHealth(seed)).kind, 'available');
      const transformed = await ocr.createEntryOcrTransformer(seed, false).transform({}, [message]);
      assert.match(domain.toText(transformed[0]), new RegExp(`https://${id}\\.example\\.test/v1`));
      // Repeated recognition uses only this target's existing cache.
      await ocr.createEntryOcrTransformer(seed, false).transform({}, [message]);
    }
    assert.equal(recognitions.length, 4); // One actual recognition and one health request per provider.
    assert.deepEqual(recognitions.map(call => call.provider.baseUrl), [
      'https://a.example.test/v1', 'https://a.example.test/v1',
      'https://b.example.test/v1', 'https://b.example.test/v1',
    ]);
    const tool = image.createImageGenTool({ conversationId: 'controlled', sourceImageResolver: async () => null });
    for (const id of ['b', 'a']) {
      await selection.storeTaskModelPair(kv, app.TASK_IMAGE_MODEL_KEY, { providerId: id, modelId: uuid });
      assert.equal(await image.imageGenToolAvailable(), true);
      assert.equal((await image.resolveImageGenChoice()).providerContainerId, id);
      const parts = await tool.execute({ prompt: '小图', mode: 'create' });
      assert.equal(parts[0].type, 'image');
      assert.equal(images.at(-1).url, `https://${id}.example.test/v1/images/generations`);
      assert.equal(images.at(-1).headers.Authorization, `Bearer controlled-${id}`);
      assert.equal(JSON.parse(images.at(-1).body).model, `image-${id}`);
      assert.equal(fs.readFileSync(parts[0].url.slice(7)).toString('hex'), '010203');
    }
    assert.equal(files.length, 2);
    await selection.storeTaskModelPair(kv, app.TASK_IMAGE_MODEL_KEY, { providerId: 'removed', modelId: uuid });
    assert.equal(await image.imageGenToolAvailable(), false);
    await assert.rejects(tool.execute({ prompt: '不能换其它目标' }), /未配置生图模型/);
    assert.equal(images.length, 2);
    assert.equal(resolves.every(call => call.ref.kind === 'fixed' && ['chat', 'image'].includes(call.type)), true);

    const raw = ['legacy-a', 'legacy-b'].map(id => domain.makeProviderSettingOpenAIVariant({ id,
      enabled: true, apiKey: id === 'legacy-a' ? '' : 'controlled-legacy', models: [
        domain.makeProviderModel({ id: legacyUuid, type: 'image' }),
      ] }));
    await domain.saveProviders(kv, raw);
    choices.set('legacy-b:image', { provider: raw[1], providerContainerId: raw[1].id, model: raw[1].models[0] });
    await kv.put(app.TASK_IMAGE_MODEL_KEY, legacyUuid);
    const legacyImage = await image.resolveImageGenChoice();
    raw.forEach(provider => { provider.models = [domain.makeProviderModel({ id: legacyUuid,
      type: 'chat', inputModalities: ['text', 'image'] })]; });
    await domain.saveProviders(kv, raw);
    choices.set('legacy-b:chat', { provider: raw[1], providerContainerId: raw[1].id, model: raw[1].models[0] });
    await kv.put(ocr.OCR_MODEL_KEY, legacyUuid);
    const legacyOcr = await ocr.loadOcrSeed(kv);
    assert.deepEqual({ imageContainer: legacyImage?.providerContainerId ?? null,
      ocrContainer: legacyOcr.ocrChoice?.providerContainerId ?? null },
    { imageContainer: 'legacy-b', ocrContainer: null });
    assert.equal(resolves.at(-1).ref.pair.providerId, 'legacy-a');
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
