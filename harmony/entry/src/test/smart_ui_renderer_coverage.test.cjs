// H3.2 渲染覆盖锁:目录里的每个 kind 都必须被原生渲染器显式接住。
//
// 背景:`NativeSmartUiComponent.Content()` 是一条 includes() 链,末尾以
//   `else { this.Actions(); }` 收口。往 SMART_UI_COMPONENT_KINDS 里加一个 kind 却忘了
//   在这条链里接线,它就会静默落进「操作区」分支,渲染成按钮行而不是自己的形态——
//   真机上只看得出「这卡片长得不对」,看不出根因。这个测试把「目录 ↔ 渲染分派」的一致
//   性钉住,不依赖设备。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  SMART_UI_COMPONENT_KINDS, componentKindAllowsChildren,
} = require('../../../chat/src/main/ets/chat/smart_ui/components.ts');

const rendererPath = path.join(__dirname, '../main/ets/components/NativeSmartUiComponent.ets');
const source = fs.readFileSync(rendererPath, 'utf8');

// 取 Content() 方法体:它是唯一的顶层分派点
const contentBody = (() => {
  const start = source.indexOf('  private Content(): void {');
  assert.ok(start >= 0, 'Content() dispatch not found — update this test with the renderer');
  let depth = 0;
  let end = source.indexOf('{', start);
  for (; end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') {
      depth--;
      if (depth === 0) break;
    }
  }
  return source.slice(start, end);
})();

const dispatchedKinds = new Set(
  (contentBody.match(/'[a-zA-Z]+'/g) || []).map(literal => literal.slice(1, -1)),
);

// 走「子节点容器」分支的 kind 不在这条字符串链里,由 componentKindAllowsChildren 决定
const childContainers = SMART_UI_COMPONENT_KINDS.filter(kind =>
  componentKindAllowsChildren(kind) && !['disclosure', 'tabs', 'accordion'].includes(kind));

// `else { this.Actions(); }` 的默认分支是有意留给这三个按钮形态的;除此之外,
// 任何「既不在显式链里、也不是子节点容器」的 kind 都属于漏接线。
const ACTION_DEFAULT_KINDS = ['button', 'submitButton', 'resetButton'];

const unhandled = SMART_UI_COMPONENT_KINDS.filter(kind =>
  !dispatchedKinds.has(kind) && !childContainers.includes(kind) && !ACTION_DEFAULT_KINDS.includes(kind));

const unknownLiterals = [...dispatchedKinds].filter(kind =>
  !SMART_UI_COMPONENT_KINDS.includes(kind) && !['stack', 'row', 'grid', 'divider', 'spacer'].includes(kind));

test('every catalog kind is dispatched by the native renderer', () => {
  assert.deepEqual(unhandled, [],
    `these kinds would silently fall into Actions(): ${unhandled.join(', ')}`);
});

test('the renderer dispatch never references a kind outside the catalog', () => {
  // 反向护栏:目录里改名/删掉某个 kind 后,渲染器里的旧名字必须一起清掉,
  // 否则会留下永远走不到的死分支。
  assert.deepEqual(unknownLiterals, [],
    `these literals are not in the catalog anymore: ${unknownLiterals.join(', ')}`);
});

test('the component fixture provides a sample for every catalog kind', () => {
  const fixturePath = path.join(__dirname, '../main/ets/platform_impl/ChatSmartUiComponentFixture.ets');
  const fixture = fs.readFileSync(fixturePath, 'utf8');
  const catalogLength = SMART_UI_COMPONENT_KINDS.length;
  // KIND_LABELS 按下标与目录一一对应,长度漂移就会把标签错配到别的卡片上
  const labelsBlock = fixture.slice(fixture.indexOf('const KIND_LABELS'), fixture.indexOf('];', fixture.indexOf('const KIND_LABELS')));
  const labels = (labelsBlock.match(/'[^']*'/g) || []).length;
  assert.equal(labels, catalogLength,
    `KIND_LABELS has ${labels} entries but the catalog has ${catalogLength}`);
  assert.ok(!fixture.includes('SMART_UI_COMPONENT_KINDS.filter'),
    'the gallery must iterate the catalog, not a hand-maintained subset');
});
