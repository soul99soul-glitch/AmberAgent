const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const filename = path.resolve(__dirname, '../../../entry/src/main/ets/components/home/HomeContinueCard.ets');
const source = fs.readFileSync(filename, 'utf8');
const paneSource = fs.readFileSync(path.resolve(__dirname, '../../../entry/src/main/ets/components/HomeListPane.ets'), 'utf8');
const supportSource = fs.readFileSync(path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/home/HomeContinueSupport.ets'), 'utf8');
function block(start) {
  assert.ok(start >= 0);
  const open = source.indexOf('{', start);
  let depth = 1, end = open + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end);
}
const method = name => block(source.search(new RegExp('^  (?:private )?' + name + '\\(', 'm')));
const row = method('build');
const parameters = row.slice(row.indexOf('(') + 1, row.indexOf(')'));
const displayExpressions = ['continueTitle', 'continueMeta', 'continueCta', 'continueIcon'].map(name => {
  const call = row.match(new RegExp(name + '\\([^)]*\\)'));
  assert.ok(call, `${name} must come from the actual row consumer`);
  return call[0];
});
const clickStart = source.indexOf('.onClick(', source.indexOf(row)) + '.onClick('.length;
const click = block(clickStart);

function harness() {
  const module = { exports: {} };
  const supportAst = ts.createSourceFile('HomeContinueSupport.ets', supportSource, ts.ScriptTarget.Latest, true);
  const displayNames = ['continueTitle', 'continueMeta', 'continueCta', 'continueIcon'];
  const displayDeclarations = supportAst.statements.filter(node => ts.isVariableStatement(node)
    && node.declarationList.declarations.some(declaration => displayNames.includes(declaration.name.getText(supportAst))));
  assert.equal(displayDeclarations.length, displayNames.length, 'Use the real continue presentation functions');
  const paneRouteStart = paneSource.search(/^  private openContinue\(/m);
  const paneRouteEnd = paneSource.indexOf('\n  private ', paneRouteStart + 1);
  const openContinue = paneSource.slice(paneRouteStart, paneRouteEnd);
  const code = `${displayDeclarations.map(node => node.getText(supportAst).replace(/^export /, '')).join('\n')}
  module.exports = class {
    bind(${parameters}) {
      return { render: () => [${displayExpressions.join(', ')}], tap: ${click} };
    }
    ${openContinue}
    onOpen(item) { this.openContinue(item); }
    pushOnce(options) { this.opened = options; }
  };`;
  vm.runInNewContext(ts.transpileModule(code, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { module }, { filename });
  const page = new module.exports();
  let current, tracking = false, rowReadsState = false;
  Object.defineProperty(page, 'candidate', {
    get: () => { if (tracking) rowReadsState = true; return current; },
    set: value => { current = value; },
  });
  page.candidate = candidate('agents', 'Agents300K', false);
  // The real V1 compiler registers these render/click closures once in the non-null If branch.
  // Evaluate the actual row expressions, collecting reads only inside its element callbacks.
  const callbacks = page.bind(page.candidate);
  tracking = true;
  let visible = callbacks.render();
  tracking = false;
  return {
    page, tap: callbacks.tap,
    visible: () => Array.from(visible),
    commit(value) {
      page.candidate = value;
      if (rowReadsState) visible = callbacks.render();
    },
  };
}
function candidate(id, title, running) {
  return { sourceKind: 'deepread', sourceId: id, route: { kind: 'deepread', topicId: id, title },
    title, summary: running ? '正在生成' : '生成失败，可重试', lastUpdatedAt: 1,
    status: running ? 'running' : 'failed_resumable', priority: 0, isRunning: running };
}

test('an existing non-null Continue row refreshes its title, status and CTA after the candidate is replaced', () => {
  const h = harness();
  assert.deepEqual(h.visible(), ['Agents300K', '深度阅读 · 生成失败，可重试', '继续', 'ph_bookOpen']);
  h.commit(candidate('infoq', 'InfoQ', true));
  assert.deepEqual(h.visible(), ['InfoQ', '深度阅读 · 正在生成', '查看', 'ph_bookOpen']);
  h.commit(candidate('infoq', 'InfoQ', false));
  assert.deepEqual(h.visible(), ['InfoQ', '深度阅读 · 生成失败，可重试', '继续', 'ph_bookOpen']);
});

test('the existing Continue click opens the currently displayed candidate rather than its initial object', () => {
  const h = harness();
  h.commit(candidate('infoq', 'InfoQ', true));
  h.tap();
  assert.deepEqual(JSON.parse(JSON.stringify(h.page.opened)), {
    url: 'pages/DeepReadArticlePage', params: { topicId: 'infoq', title: 'InfoQ' },
  });
});
