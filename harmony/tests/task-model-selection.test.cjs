const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../chat/node_modules/typescript');
require('../chat/node_modules/tsx/dist/cjs/index.cjs');
const refs = require('../chat/src/main/ets/chat/task_model_reference.ts');
const tasks = require('../chat/src/main/ets/chat/task_model.ts');
const providers = require('../chat/src/main/ets/chat/provider_settings.ts');
const { createMemoryKeyValueStore } = require('../chat/src/main/ets/chat/kv_store.ts');

test('Task selection preserves legacy keys, exact provider pairs and memory fallback identities', async () => {
  const source = fs.readFileSync(path.resolve(__dirname,
    '../entry/src/main/ets/platform_impl/TaskModelSelectionSupport.ets'), 'utf8');
  const output = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const selection = {};
  new Function('require', 'exports', output)((name) => {
    assert.equal(name, '@amber/chat-domain');
    return { ...refs, ...tasks };
  }, selection);
  const kv = createMemoryKeyValueStore();
  const key = 'compress_model_id';
  const uuid = 'b0c2058b-142d-4c83-8a41-68fe2fe17381';
  await kv.put(key, uuid);
  const legacy = await selection.loadTaskModelReference(kv, key);
  assert.deepEqual(legacy, { kind: 'legacy', modelId: uuid });
  assert.equal(await kv.get(key), uuid);
  const pair = { providerId: 'container-b', modelId: uuid };
  await selection.storeTaskModelPair(kv, key, pair);
  assert.equal(await kv.get(key), JSON.stringify(pair));
  const fixed = await selection.loadTaskModelReference(kv, key);
  assert.deepEqual(fixed, { kind: 'fixed', pair });
  assert.equal(refs.taskModelReferenceId(fixed), uuid);
  assert.equal(refs.encodeTaskModelReference(fixed), JSON.stringify(pair));
  assert.equal(kv.entries.size, 1);

  const override = providers.makeProviderSettingOpenAIVariant({ id: 'effective-provider', apiKey: 'fixture-key' });
  const a = providers.makeProviderSettingOpenAIVariant({ id: 'container-a', models: [
    providers.makeProviderModel({ id: uuid, modelId: 'api-model-a' }),
  ] });
  const b = providers.makeProviderSettingOpenAIVariant({ id: pair.providerId, apiKey: 'fixture-b-key', models: [
    providers.makeProviderModel({ id: uuid, modelId: 'api-model-b', providerOverwrite: override }),
  ] });
  assert.equal(refs.findTaskProviderModel([a, b], legacy, 'chat').provider, a);
  const match = selection.findTaskProviderModel([a, b], fixed, 'chat');
  assert.equal(match.provider, b);
  assert.equal(match.model.modelId, 'api-model-b');
  assert.equal(match.model.providerOverwrite.id, 'effective-provider');
  assert.equal(refs.findTaskProviderModel([a], fixed, 'chat'), null);
  assert.equal(refs.findTaskProviderModel([a, b], fixed, 'image'), null);
  assert.equal(refs.findTaskProviderModel([a, b], refs.taskModelReferenceFromId('api-model-b', b.id)), null);

  // Execute the actual DI resolver/factory slices without loading its UI and SDK graph.
  const extract = (file, names) => {
    const text = fs.readFileSync(path.resolve(__dirname, '../entry/src/main/ets', file), 'utf8');
    const parsed = ts.createSourceFile(file, text, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS);
    const statements = parsed.statements.filter((statement) => ts.isVariableStatement(statement)
      && statement.declarationList.declarations.some((declaration) => names.includes(declaration.name.getText(parsed))));
    assert.equal(statements.length, names.length, `Required production declarations in ${file}`);
    return statements.map((statement) => statement.getText(parsed)).join('\n');
  };
  const diText = extract('platform_impl/OcrSupport.ets', ['findModelByIdInProviders']) + '\n'
    + extract('di/AppContainer.ets', ['effectiveProvider', 'makeProviderChoice', 'resolveChatProviderChoice',
      'resolveConfiguredTaskChatModel', 'resolveConfiguredTaskModel', 'resolveTaskModelReference',
      'taskModelReferenceForChoice']);
  const diOutput = ts.transpileModule(diText, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const c = providers.makeProviderSettingOpenAIVariant({ id: 'container-c', apiKey: 'fixture-c-key', models: [
    providers.makeProviderModel({ id: 'global-uuid', modelId: 'api-model-c' }),
  ] });
  const di = {};
  const dependencies = { exports: di, loadProviders: async () => [a, b, c], getChatKvStore: () => kv,
    getChatModelSelection: async () => ({ providerId: c.id, modelId: c.models[0].modelId }),
    hasUsableAuth: providers.hasUsableAuth, copyProviderSettingWithModels: providers.copyProviderSettingWithModels,
    findTaskProviderModel: refs.findTaskProviderModel, taskModelReferenceFromId: refs.taskModelReferenceFromId };
  new Function(...Object.keys(dependencies), diOutput)(...Object.values(dependencies));
  assert.equal((await di.resolveChatProviderChoice()).providerContainerId, c.id);
  const legacyChoice = await di.resolveTaskModelReference(legacy);
  assert.equal(legacyChoice.providerContainerId, b.id, 'Legacy UUID skips unauthenticated A and still selects B');
  assert.equal(await di.resolveConfiguredTaskModel(refs.taskModelReferenceFromId(uuid, a.id), 'chat'), null);
  const fixedChoice = await di.resolveConfiguredTaskModel(fixed, 'chat');
  assert.equal(fixedChoice.provider.id, override.id);
  assert.equal(fixedChoice.providerContainerId, b.id);
  assert.deepEqual(di.taskModelReferenceForChoice(fixedChoice), fixed);

  await kv.put(key, '{"providerId":"container-b","modelId":3}');
  await assert.rejects(selection.loadTaskModelReference(kv, key), /任务模型配置无效/);
  await selection.clearTaskModelReference(kv, key);
  assert.equal(await kv.get(key), tasks.DEFAULT_AUTO_MODEL_ID);
  assert.deepEqual(await selection.loadTaskModelReference(kv, key), { kind: 'auto' });
  await selection.clearTaskModelReference(kv, key, '');
  assert.equal(await kv.get(key), '');
  assert.deepEqual(await selection.loadTaskModelReference(kv, key), { kind: 'auto' });

  const chat = refs.taskModelReferenceFromId(uuid, a.id);
  const worker = { ...tasks.DEFAULT_TASK_MODEL_WORKER_GATE, modelId: uuid, providerId: b.id,
    daydreamFollowCompressModel: false };
  assert.deepEqual(tasks.memoryWorkerModelReferences(worker, fixed, chat), [fixed, chat]);
  assert.deepEqual(tasks.daydreamModelReferences(worker, fixed, chat), [fixed, fixed, chat]);
  assert.deepEqual(tasks.memoryWorkerModelReferences(tasks.DEFAULT_TASK_MODEL_WORKER_GATE, fixed, chat), [fixed, chat]);
  const dream = { ...worker, daydreamModelId: uuid, daydreamProviderId: a.id };
  assert.deepEqual(tasks.daydreamModelReferences(dream, fixed, chat), [chat, fixed, chat]);
  const oldWorker = { ...worker, providerId: null };
  assert.deepEqual(tasks.memoryWorkerModelReferences(oldWorker, fixed, chat), [legacy, chat]);
  const noFollow = { ...tasks.DEFAULT_TASK_MODEL_WORKER_GATE,
    followCompressModel: false, daydreamFollowCompressModel: false };
  assert.deepEqual(tasks.daydreamModelReferences(noFollow, fixed, chat), [chat, fixed, chat]);
  assert.equal(tasks.pickMemoryWorkerModelId(worker, uuid, uuid), uuid);
});
