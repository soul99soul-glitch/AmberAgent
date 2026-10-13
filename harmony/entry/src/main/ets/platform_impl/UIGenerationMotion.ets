import { webview } from '@kit.ArkWeb';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

// Both MiniApp surfaces share ownership; a WebView reload must not replay an entrance.
const consumed: Set<string> = new Set<string>();
const owners: Map<string, UIGenerationMotion> = new Map<string, UIGenerationMotion>();

const readAsset = (name: string): string => {
  const context: Context = AppStorage.get<Context>('abilityContext') as Context;
  return util.TextDecoder.create('utf-8').decodeToString(
    context.resourceManager.getRawFileContentSync(`visual/${name}`));
};

const installScript = (): string => {
  const css: string = JSON.stringify(readAsset('ui-generation.css'));
  const runtime: string = readAsset('ui-generation.js').replace(/export function /g, 'function ');
  const adapter: string = readAsset('amber-ui-generation.js');
  return `(() => {
    if (window.AmberUIGeneration) return;
    const amberGenerationCSS = ${css};
    const animationWindow = window;
    function AmberCreateGenerationRuntime(document, window) {
      const getComputedStyle = window.getComputedStyle.bind(window);
      const SVGSVGElement = window.SVGSVGElement;
      const scrollX = window.scrollX, scrollY = window.scrollY;
      const innerWidth = window.innerWidth, innerHeight = window.innerHeight;
      const matchMedia = window.matchMedia.bind(window);
      // The iframe forbids script scheduling; only the trusted parent runs the clock.
      const performance = animationWindow.performance;
      const requestAnimationFrame = animationWindow.requestAnimationFrame.bind(animationWindow);
      const cancelAnimationFrame = animationWindow.cancelAnimationFrame.bind(animationWindow);
      const setTimeout = animationWindow.setTimeout.bind(animationWindow);
      const clearTimeout = animationWindow.clearTimeout.bind(animationWindow);
      ${runtime}
      return { generate };
    }
    ${adapter}
  })();`;
};

export class UIGenerationMotion {
  private controller: webview.WebviewController;
  private key: string = '';
  private epoch: number = 0;
  private timer: number = -1;
  private polls: number = 0;

  constructor(controller: webview.WebviewController) {
    this.controller = controller;
  }

  play(key: string, eligible: boolean, reduceMotion: boolean, iframe: boolean = false): void {
    if (!eligible || key.length === 0) return;
    if (this.key === key) return;
    this.cancel();
    if (consumed.has(key) || owners.has(key)) return;
    this.key = key;
    owners.set(key, this);
    const epoch: number = ++this.epoch;
    this.polls = 0;
    try {
      const dark: boolean = AppStorage.get<boolean>('darkMode') ?? false;
      this.controller.runJavaScript(installScript() +
        `window.AmberUIGeneration.play(${iframe}, ${reduceMotion}, ${dark});`)
        .then((status: string): void => { this.acceptStatus(status, epoch); })
        .catch((error: Error): void => { this.failed(error, epoch); });
    } catch (error) {
      this.failed(error as Error, epoch);
    }
  }

  cancel(consume: boolean = false): void {
    this.epoch++;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    if (this.key.length === 0) return;
    if (consume) consumed.add(this.key);
    if (owners.get(this.key) === this) owners.delete(this.key);
    this.key = '';
    // Cancellation also runs while a page is being detached.
    this.controller.runJavaScript('window.AmberUIGeneration?.cancel();').catch((): void => {});
  }

  private acceptStatus(status: string, epoch: number): void {
    if (epoch !== this.epoch || this.key.length === 0) return;
    if (status === 'started' || status === 'skipped' || status === '"started"' || status === '"skipped"') {
      consumed.add(this.key);
      owners.delete(this.key);
      return;
    }
    if (++this.polls >= 45) {
      this.cancel(true);
      return;
    }
    this.timer = setTimeout((): void => {
      this.timer = -1;
      this.controller.runJavaScript('window.AmberUIGeneration.status();')
        .then((next: string): void => { this.acceptStatus(next, epoch); })
        .catch((error: Error): void => { this.failed(error, epoch); });
    }, 100);
  }

  private failed(error: Error, epoch: number): void {
    if (epoch !== this.epoch) return;
    hilog.warn(0x0001, 'UI.Generation', 'Reveal unavailable: %{public}s', error.message);
    this.cancel(true);
  }
}
