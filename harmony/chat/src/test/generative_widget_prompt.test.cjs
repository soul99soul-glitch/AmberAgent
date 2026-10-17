const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');

test('actual run prompt follows the declared-content switch even when recent-chat references are disabled', async () => {
  const domain = await import('../main/ets/index.ts');
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/ConversationRunDependencies.ets');
  const source = fs.readFileSync(file, 'utf8');
  const begin = source.indexOf('const extraSystemBlocks =');
  const end = source.indexOf('const memoryReranker =', begin);
  assert.ok(begin > 0 && end > begin);
  const actual = ts.transpileModule(source.slice(begin, end), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const run = new Function('resolveGenerativeWidgetInstruction', 'getChatRepository', actual + '\nreturn extraSystemBlocks;')(
    domain.resolveGenerativeWidgetInstruction,
    () => { throw new Error('recent-chat repository must not be queried'); },
  );
  for (const enabled of [true, false]) {
    const blocks = await run({ seed: { generativeWidgetsEnabled: enabled,
      agentRuntime: { enableRecentChatsReference: false } } });
    assert.deepEqual(blocks, enabled ? [domain.resolveGenerativeWidgetInstruction('declared-content-v1')] : []);
  }
  assert.match(domain.GENERATIVE_WIDGET_INSTRUCTION, /renderer:"threejs"/);
  assert.match(domain.GENERATIVE_WIDGET_INSTRUCTION, /cylinder=\[radius,height\]/);
});

test('declared content prompt is opt-in, state-first, and matches the native block protocols', async () => {
  const prompt = await import('../main/ets/chat/generative_widget_prompt.ts');
  const response = await import('../main/ets/chat/response_content.ts');
  assert.equal(prompt.resolveGenerativeWidgetInstruction(), prompt.GENERATIVE_WIDGET_INSTRUCTION);
  const declared = prompt.resolveGenerativeWidgetInstruction('declared-content-v1');
  assert.equal(declared, prompt.DECLARED_CONTENT_INSTRUCTION);
  for (const type of ['column', 'text', 'choice', 'input', 'button', 'asset']) {
    assert.match(declared, new RegExp(`type\\":\\"${type}\\"`), type);
  }
  assert.match(declared, /state comes first/i);
  assert.match(declared, /state-after-dependent-node|state.*before.*node/i);
  assert.match(declared, /visibleWhen/);
  assert.match(declared, /root-detail/);
  assert.match(declared, /server-detail/);
  assert.match(declared, /equals.*finite number.*boolean literal/i);
  assert.match(declared, /temperature_celsius/);
  assert.match(declared, /location.*temperature_celsius.*condition.*observed_at/);
  assert.match(declared, /explicit unknown/i);
  assert.match(declared, /16 text bindings/);
  assert.match(declared, /ref.*stable internal id/i);
  assert.match(declared, /证书链图示/);
  assert.match(declared, /amber-ui v=1 id=/);
  assert.match(declared, /amber-svg v=1 id=/);
  assert.match(declared, /amber-three v=1 id=/);
  assert.match(declared, /MiniApp remains a separate explicit path/);
  assert.match(declared, /passwords, API keys, access tokens/);
  assert.match(declared, /scripts|JavaScript/);
  assert.match(declared, /same answer|separate declared blocks/);
  assert.match(declared, /one state may bind to only one choice or input/i);
  assert.doesNotMatch(declared, /automatically execute/i);

  const ui = '```amber-ui v=1 id=controls\n' +
    '{"op":"state","id":"selected","initial":"root"}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"selected","options":[{"value":"root","label":"根证书"}]}\n' +
    '{"op":"node","id":"diagram","parent":"panel","type":"asset","ref":"chain"}\n' +
    '{"op":"end"}\n```';
  const svg = '```amber-svg v=1 id=chain\n<svg viewBox="0 0 20 20"><rect width="20" height="20"/></svg>\n```';
  const three = '```amber-three v=1 id=space\n' +
    '{"objects":[{"id":"core","type":"sphere","color":"#b8623a","position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1],"size":[1]}]}\n```';
  const segments = response.parseResponseContent(`${ui}\n${svg}\n${three}`, false);
  assert.deepEqual(segments.filter((item) => item.type !== 'text').map((item) => item.type), ['ui', 'svg', 'three']);
  assert.equal(segments.find((item) => item.type === 'ui').status, 'ready');
  assert.equal(segments.find((item) => item.type === 'svg').status, 'partial');
  assert.ok(segments.find((item) => item.type === 'svg').artifact.snapshot);
  assert.equal(segments.find((item) => item.type === 'three').status, 'ready');
});

test('rich task example advertised to the model uses the production protocol and preserves entered constraints', async () => {
  const domain = await import('../main/ets/index.ts');
  const prompt = domain.resolveGenerativeWidgetInstruction('declared-content-v1');
  const start = prompt.indexOf('```amber-ui v=1 id=task-options');
  const end = prompt.indexOf('\n```', start) + 4;
  assert.ok(start >= 0 && end > start);
  const segment = domain.parseResponseContent(prompt.slice(start, end), false).find((item) => item.type === 'ui');
  assert.ok(segment);
  assert.ok(segment.ui);
  assert.equal(segment.ui.status, 'ready', JSON.stringify(segment.ui.diagnostics));
  const document = segment.ui;
  const values = document.states.map((state) => ({ id: state.id, value: state.initial }));
  assert.equal(domain.smartUiContinuationSnapshot(document, values), null, 'required constraint is still unanswered');
  values.find((item) => item.id === 'note').value = '必须离线可用';
  const snapshot = domain.smartUiContinuationSnapshot(document, values);
  assert.match(snapshot, /本地存储/);
  assert.match(snapshot, /每月预算.*50/);
  assert.match(snapshot, /额外约束.*必须离线可用/);
  assert.match(snapshot, /选择存储方案/);
  assert.equal(domain.SMART_UI_COMPONENT_KINDS.length, 80);
  for (const kind of domain.SMART_UI_COMPONENT_KINDS) {
    assert.ok(prompt.includes(kind), `${kind} must be discoverable in the same prompt`);
  }
});
