const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const root = path.resolve(__dirname, '../../../..');
const ts = require(require.resolve('typescript', { paths: [path.join(root, 'harmony/chat')] }));
const { NativeInlineRenderer } = require('../../../entry/src/main/ets/components/NativeMarkdownInline.ts');

// Execute the production component's real modifier and component methods. ArkUI's build DSL
// is excluded; no claims about compositor output follow from these unit tests.
function loadCandidate(sdkApiVersion = 24, capability = true) {
  let source = fs.readFileSync(path.join(__dirname, '../../../entry/src/main/ets/components/NativeMarkdownText.ets'), 'utf8');
  source = source.slice(0, source.indexOf('\n  build(): void')) + '\n}\n';
  source = source.replace(/@Component\s*/g, '').replace(/@Prop\s*/g, '').replace(/@State\s*/g, '')
    .replace(/@Watch\('[^']+'\)\s*/g, '').replace('export struct NativeMarkdownText', 'class NativeMarkdownText');
  source += '\nmodule.exports = { NativeMarkdownText, NativeMarkdownFadeModifier, supportsNativeRangeFade };';
  const code = ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS,
  } }).outputText;
  class DrawModifier { invalidations = 0; invalidate() { this.invalidations++; } }
  class Brush { setBlendMode(mode) { this.mode = mode; } setColor(color) { this.color = color; } }
  class StyledString {
    constructor(value, styles = []) { this.value = value; this.styles = styles; }
    get length() { return this.value.length; }
  }
  class MutableStyledString extends StyledString {
    styleWrites = 0;
    appendStyledString(other) { this.value += other.value; }
    replaceStyledString(start, count, other) {
      this.value = this.value.slice(0, start) + other.value + this.value.slice(start + count);
    }
    setStyle() { this.styleWrites++; }
  }
  class TextController {
    bindings = [];
    setStyledString(value) { this.bindings.push(value.value); }
    getLayoutManager() { throw new Error('layout manager must be explicitly supplied by test'); }
  }
  class ValueStyle { constructor(value) { this.value = value; } }
  const module = { exports: {} };
  const context = {
    module, exports: module.exports, DrawModifier, TextController, MutableStyledString, StyledString,
    TextStyle: ValueStyle, DecorationStyle: ValueStyle, GestureStyle: ValueStyle,
    FontWeight: { Normal: 0, Bold: 1 }, TextAlign: { Start: 0 }, FontStyle: { Normal: 0, Italic: 1 },
    StyledStringKey: { FONT: 0, DECORATION: 1, GESTURE: 2 }, TextDecorationType: { Underline: 0 },
    canIUse: () => capability,
    require: (name) => {
      if (name === '@kit.ArkUI') return { FrameCallback: class {}, LengthMetrics: { fp: (value) => value } };
      if (name === '@kit.ArkGraphics2D') return {
        drawing: { Brush, BlendMode: { DST_OUT: 8 } },
        text: { RectWidthStyle: { TIGHT: 0 }, RectHeightStyle: { MAX: 1 } },
      };
      if (name === '@kit.BasicServicesKit') return { deviceInfo: { sdkApiVersion } };
      if (name === './NativeMarkdownInline.ts') return { NativeInlineRenderer };
      if (name.endsWith('tokens.ets')) return { ACCENT: '#FA8200', BODY: 16, INK: '#303030', lineHeightForSize: (size) => size * 1.5 };
      if (name.endsWith('fonts.ets')) return { FONT_SANS: 'sans', FONT_SANS_BOLD: 'sans-bold', FONT_MONO: 'mono' };
      throw new Error(`Unexpected import: ${name}`);
    },
  };
  vm.runInNewContext(code, context);
  return module.exports;
}

function makeComponent(api = 24) {
  const { NativeMarkdownText } = loadCandidate(api);
  const component = new NativeMarkdownText();
  component.getUIContext = () => ({ postFrameCallback() {} });
  component.inlines = [{ type: 'text', text: '原有中文' }];
  component.aboutToAppear();
  component.onTextAppear();
  return component;
}

test('real component fade-only publication never calls setStyledString or setStyle', () => {
  const component = makeComponent();
  component.streaming = true;
  component.inlines = [{ type: 'text', text: '原有中文新增文字😀' }];
  component.onInlinesChanged();
  assert.equal(component.masking, true);
  assert.equal(component.controller.bindings.length, 2);
  component.renderer.frame(100);
  component.renderer.frame(350);
  component.renderer.frame(600);
  assert.equal(component.controller.bindings.length, 2, 'no text measurement submitted by fade frames');
  assert.equal(component.styledString.styleWrites, 0, 'alpha never mutates FONT or DECORATION');
  assert.equal(component.styledString.value, '原有中文新增文字😀');
  assert.equal(component.masking, false, 'offscreen is released after the last fade');
  assert.ok(component.fadeModifier.invalidations >= 4);
});

test('API 12/13 and unavailable capability do not request API 14 range geometry', () => {
  for (const api of [12, 13]) {
    const component = makeComponent(api);
    component.streaming = true;
    component.inlines = [{ type: 'text', text: '原有中文即时追加' }];
    component.onInlinesChanged();
    assert.equal(component.masking, false);
    assert.equal(component.renderer.hasAnimation(), false);
    component.fadeModifier.drawFront({ size: { width: 100, height: 40 } });
    assert.equal(component.styledString.value, '原有中文即时追加');
  }
  assert.equal(loadCandidate(24, false).supportsNativeRangeFade(), false);
});

test('range mask uses cached pixel rectangles, invalidates on size/content changes, and preserves alpha', () => {
  const { NativeMarkdownFadeModifier } = loadCandidate();
  let queries = 0;
  const modifier = new NativeMarkdownFadeModifier({ getLayoutManager: () => ({
    getRectsForRange(range) {
      queries++;
      assert.deepEqual(JSON.parse(JSON.stringify(range)), { start: 4, end: 8 });
      return [{ rect: { left: 20, top: 10, right: 60, bottom: 30 } }];
    },
  }) }, true);
  const paints = [];
  let brush;
  const context = { size: { width: 100, height: 50 }, canvas: {
    attachBrush(value) { brush = value; },
    detachBrush() { brush = undefined; },
    drawRect(rect) { paints.push({ rect, alpha: brush.color.alpha, mode: brush.mode }); },
  } };
  modifier.setRanges([{ start: 4, length: 4, opacity: 0 }]);
  modifier.drawFront(context);
  modifier.setRanges([{ start: 4, length: 4, opacity: 0.5 }]);
  modifier.drawFront(context);
  assert.equal(queries, 1, 'opacity frames reuse layout-derived rectangles');
  assert.equal(paints[0].alpha, 255);
  assert.equal(paints[1].alpha, 128);
  assert.equal(paints[1].mode, 8, 'DST_OUT, independent of parent background color');
  assert.deepEqual(paints[1].rect, { left: 20, top: 10, right: 60, bottom: 30 });
  context.size.width = 120;
  modifier.drawFront(context);
  assert.equal(queries, 2);
  modifier.clearGeometry();
  modifier.drawFront(context);
  assert.equal(queries, 3, 'text/format change invalidates even if dimensions match');
  modifier.setRanges([]);
  modifier.drawFront(context);
  assert.equal(queries, 3, 'completed fade does not query layout or draw');
});

test('font, layout and content changes clear geometry while preserving the existing fade clock', () => {
  const component = makeComponent();
  component.streaming = true;
  component.inlines = [{ type: 'text', text: '原有中文尾段' }];
  component.onInlinesChanged();
  component.renderer.frame(100);
  component.renderer.frame(350);
  const opacity = component.renderer.activeFadeRanges()[0].opacity;
  component.fadeModifier.geometry = [{ start: 4, length: 2, rects: [] }];
  component.fontSize = 24;
  component.onPresentationChanged();
  assert.equal(component.fadeModifier.geometry.length, 0);
  assert.equal(component.renderer.activeFadeRanges()[0].opacity, opacity);
  component.fadeModifier.geometry = [{ start: 4, length: 2, rects: [] }];
  component.onLayoutChanged();
  assert.equal(component.fadeModifier.geometry.length, 0);
  component.renderer.frame(600);
  assert.equal(component.masking, false, 'presentation changes do not restart fades');
});

test('native styles and link gestures are still present on the single Text surface', () => {
  const component = makeComponent();
  let opened;
  let copied;
  component.onOpenLink = (url) => { opened = url; };
  component.onCopyLink = (url) => { copied = url; };
  const styled = component.makeStyledString([
    { kind: 'bold', text: '中文粗体', url: '' },
    { kind: 'link', text: '链接', url: 'https://example.com' },
    { kind: 'code', text: 'code', url: '' },
  ]);
  assert.equal(styled.value, '中文粗体链接code');
  assert.equal(styled.styles[0].styledValue.value.fontFamily, 'sans-bold');
  assert.equal(styled.styles[1].styledValue.value.fontColor, '#FA8200');
  const gesture = styled.styles.find((entry) => entry.styledKey === 2).styledValue.value;
  gesture.onClick(); gesture.onLongPress();
  assert.equal(opened, 'https://example.com');
  assert.equal(copied, 'https://example.com');
});


test('initial styled content binds on Text attachment on every supported device API', () => {
  for (const api of [12, 14, 15, 24]) {
    const { NativeMarkdownText } = loadCandidate(api);
    const component = new NativeMarkdownText();
    component.getUIContext = () => ({ postFrameCallback() {} });
    component.inlines = [{ type: 'text', text: '新段落'.repeat(40) }];
    component.streaming = true;
    component.aboutToAppear();
    assert.equal(component.controller.bindings.length, 0,
      `API ${api}: device API alone does not guarantee prebinding for this API12-targeted app`);
    component.onTextAppear();
    assert.equal(component.controller.bindings.length, 1, 'attachment binds once without a duplicate layout');
    assert.equal(component.controller.bindings[0], '新段落'.repeat(40));
  }
});
