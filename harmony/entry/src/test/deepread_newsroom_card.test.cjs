const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/components/DeepReadNewsroomCard.ets'), 'utf8');

// Execute the production helpers and ArkUI builder with a recording UI adapter.
function containers(text) {
  const pattern = /\b(Row|Column)\(/g;
  let result = '', cursor = 0, match;
  while ((match = pattern.exec(text))) {
    let endArgs = pattern.lastIndex, depth = 1;
    for (; depth && endArgs < text.length; endArgs++) {
      if (text[endArgs] === '(') depth++;
      if (text[endArgs] === ')') depth--;
    }
    let open = endArgs;
    while (/\s/.test(text[open])) open++;
    if (text[open] !== '{') continue;
    let close = open + 1; depth = 1;
    for (; depth && close < text.length; close++) {
      if (text[close] === '{') depth++;
      if (text[close] === '}') depth--;
    }
    result += text.slice(cursor, open) + '.children(() => {' + containers(text.slice(open + 1, close - 1)) + '})';
    cursor = close; pattern.lastIndex = close;
  }
  return result + text.slice(cursor);
}
const helpers = source.slice(source.indexOf('export const deepReadNewsroomStageIndex'), source.indexOf('@Component'))
  .replace(/export const/g, 'const');
const build = source.slice(source.indexOf('  build(): void {'), source.lastIndexOf('}'));
const code = ts.transpileModule(helpers + '\nclass Card {\n' + containers(build) + '\n}\nreturn Card;',
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;

function fixture() {
  const nodes = [], keys = [];
  function node(type, value) {
    const record = { type, value, attrs: {} }; nodes.push(record);
    const proxy = new Proxy({}, { get: (_, key) => (...args) => {
      if (key === 'children') args[0](); else record.attrs[key] = args[0];
      return proxy;
    } });
    return proxy;
  }
  const bindings = {
    getProductKind: () => 'agent',
    Text: value => node('Text', value), Column: () => node('Column'), Row: () => node('Row'),
    ForEach: (items, render, key) => items.forEach((item, index) => { keys.push(key(item)); render(item, index); }),
    BG: 'bg', SURFACE: 'surface', SURFACE2: 'surface2', INK: 'ink', INK2: 'ink2', INK3: 'ink3',
    LINE: 'line', ACCENT: 'accent', BODY: 16, META: 12, FONT_SERIF: 'serif',
    FontWeight: { Medium: 'medium' }, TextAlign: { Center: 'center' }, HorizontalAlign: { Start: 'start' },
  };
  const Card = new Function(...Object.keys(bindings), code)(...Object.values(bindings));
  const card = new Card(); Object.assign(card, { run: null, stageNames: ['采访', '整理', '撰稿', '付印'],
    themeEpoch: 0, onOpen() {} });
  function render(run) { nodes.length = 0; keys.length = 0; card.run = run; card.build(); return nodes; }
  return { card, render, nodes, keys };
}
const run = { topicId: 'same-topic', title: '长选题标题 '.repeat(12), startedAt: 100 };

test('same-instance current task progresses through real phases and updates its actual stage label', () => {
  const f = fixture();
  for (const [stage, name, label] of [
    ['COLLECTING', '采访', '读取第 2 个来源'], ['PLANNING', '整理', '规划文章结构'],
    ['WRITING', '撰稿', '撰写深度分析'], ['VERIFYING', '付印', '核对全文'],
  ]) {
    f.render({ ...run, stage, label });
    assert.equal(f.nodes.find(node => node.value === name).attrs.fontColor, 'accent');
    assert.equal(f.nodes.filter(node => node.attrs.fontColor === 'accent' && f.card.stageNames.includes(node.value)).length, 1);
    assert.ok(f.nodes.some(node => node.value === label));
    assert.ok(f.keys.every(key => key.endsWith(':' + stage)));
  }
  f.render({ ...run, stage: 'WRITING', label: '撰写结论' });
  assert.ok(f.nodes.some(node => node.value === '撰写结论'));
  assert.ok(!f.nodes.some(node => node.value === '核对全文'));
});

test('admitted task without a phase shows waiting, while completion only reflects its actual saved phase', () => {
  const f = fixture(); f.render(run);
  assert.ok(f.nodes.some(node => node.value === '任务已开始，等待阶段更新'));
  assert.ok(f.nodes.filter(node => f.card.stageNames.includes(node.value)).every(node => node.attrs.fontColor === 'ink3'));
  f.render({ ...run, stage: 'COMPLETE' });
  assert.ok(f.nodes.some(node => node.value === '文章已保存，正在结束任务'));
  assert.ok(!f.nodes.some(node => typeof node.value === 'string' && /\d+%/.test(node.value)));
  f.render(null); assert.deepEqual(f.nodes, []);
});

test('long title and actionable progress control render without a fixed body height and invoke the current callback', () => {
  const f = fixture(); let opened = 0;
  f.card.onOpen = () => { opened++; }; f.render({ ...run, stage: 'WRITING' });
  const title = f.nodes.find(node => node.value === run.title);
  assert.equal(title.attrs.height, undefined);
  assert.equal(title.attrs.maxLines, undefined);
  const action = f.nodes.find(node => node.value === '查看进度');
  action.attrs.onClick(); assert.equal(opened, 1);
});
