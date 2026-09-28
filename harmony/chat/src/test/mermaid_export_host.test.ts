// 执行真实 host 导出函数；DOM fixture 只重现已测量的 CSS/native 几何，不模拟渲染。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createContext, runInContext } from 'node:vm';
import { imageExportPages } from '../main/ets/chat/image_export_pages.ts';

const html = readFileSync(new URL('../../../entry/src/main/resources/rawfile/mermaid_host.html', import.meta.url), 'utf8');
const hostScript = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(hostScript, 'real Mermaid host script exists');

function measuredGeometryHost(bodyHeight: number, quantizationSlack: number) {
  const style = { minHeight: '' };
  const viewport = {
    innerHeight: 0,
    scrollY: 0,
    scrollTo: (_x: number, top: number) => {
      const paddedBodyHeight = Math.max(bodyHeight, Number.parseFloat(style.minHeight) || 0);
      const maximum = Math.max(0, Math.floor(paddedBodyHeight) - viewport.innerHeight - quantizationSlack);
      viewport.scrollY = Math.max(0, Math.min(top, maximum));
    },
  };
  const context = createContext({
    window: viewport,
    document: {
      body: { style },
      getElementById: () => ({}),
    },
    mermaid: { initialize: () => {} },
    ResizeObserver: class { observe() {} },
  });
  runInContext(hostScript!, context);
  return { style, viewport, context };
}

test('real host prevents the measured CSS viewport tail clamp without changing content pages', () => {
  const bodyHeight = 2686.0224609375;
  const density = 3.5;
  // 真机 inner/clientHeight=239，未修复实际最大 scrollY=2446.857177734375。
  const slack = 2686 - 239 - 2446.857177734375;
  const host = measuredGeometryHost(bodyHeight, slack);
  const pages = imageExportPages(bodyHeight * density, 2856);
  assert.equal(pages.length, 4);
  assert.deepEqual(pages.at(-1), { top: 7052, height: 2350 });
  host.viewport.innerHeight = 239;
  host.viewport.scrollTo(0, 2448);
  assert.equal(host.viewport.scrollY, 2446.857177734375, 'fixture reproduces measured pre-fix clamp');
  let nextPixel = 0;
  for (const page of pages) {
    host.viewport.innerHeight = Math.ceil(page.height / density);
    const actual = runInContext(`window.__amberSetExportOffset(${page.top / density})`, host.context) as number;
    assert.equal(actual * density, page.top);
    assert.equal(actual * density, nextPixel, 'each actual capture starts at previous capture end');
    nextPixel = actual * density + page.height;
  }
  assert.equal(nextPixel, Math.ceil(bodyHeight * density), 'last page includes the complete measured SVG');
  runInContext('window.__amberEndExport()', host.context);
  assert.equal(host.style.minHeight, '');
  assert.equal(host.viewport.scrollY, 0);
});

test('ordinary single-page host export returns to its original document geometry', () => {
  const host = measuredGeometryHost(170.4, 0.2);
  host.viewport.innerHeight = 171;
  assert.equal(runInContext('window.__amberSetExportOffset(0)', host.context), 0);
  assert.equal(host.style.minHeight, '172px');
  runInContext('window.__amberEndExport()', host.context);
  assert.equal(host.style.minHeight, '');
  assert.equal(host.viewport.scrollY, 0);
});

test('real host render consumes the explicit app theme and preserves strict rendering readiness', async () => {
  // 库/DOM stub 仅核对生产 host 的传参和就绪协议，不模拟 Mermaid 图像结果。
  const themes: string[] = [];
  const inputs: string[] = [];
  const readiness: Array<{ revision: number; height: number; ok: boolean }> = [];
  const app = { innerHTML: '', getBoundingClientRect: () => ({ height: 120, width: 360 }) };
  const context = createContext({
    window: { scrollTo: () => {} },
    document: { getElementById: () => app },
    mermaid: {
      initialize: (config: { theme: string; startOnLoad: boolean; securityLevel: string }) => {
        assert.equal(config.startOnLoad, false);
        assert.equal(config.securityLevel, 'strict');
        themes.push(config.theme);
      },
      render: async (_id: string, source: string) => {
        inputs.push(source);
        return { svg: '<svg></svg>' };
      },
    },
    AmberMermaid: { postMessage: (raw: string) => readiness.push(JSON.parse(raw)) },
    requestAnimationFrame: (callback: () => void) => callback(),
    ResizeObserver: class { observe() {} },
  });
  runInContext(hostScript!, context);
  await runInContext('window.__amberRenderMermaid("graph TB; A-->B", 1, "dark")', context);
  await runInContext('window.__amberRenderMermaid("graph TB; A-->B", 2, "default")', context);
  assert.deepEqual(themes, ['dark', 'default']);
  assert.deepEqual(inputs, ['graph TB; A-->B', 'graph TB; A-->B']);
  assert.deepEqual(readiness, [
    { revision: 1, height: 136, ok: true }, { revision: 2, height: 136, ok: true },
  ]);
});
