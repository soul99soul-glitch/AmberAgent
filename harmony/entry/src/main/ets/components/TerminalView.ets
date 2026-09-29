import { webview } from '@kit.ArkWeb';
import { BG, INK, SURFACE, SURFACE2, ACCENT } from '../design/tokens.ets';

interface TerminalPalette {
  background: string;
  foreground: string;
  cursor: string;
  cursorAccent: string;
  selectionBackground: string;
  toolbarBackground: string;
  accent: string;
}

// Only trusted local JS is called; remote output is serialized as byte numbers.
export class TerminalViewController {
  private controller: webview.WebviewController | null = null;
  private ready: boolean = false;
  private writes: Promise<void> = Promise.resolve();
  onError: (message: string) => void = (_message: string): void => {};

  bind(controller: webview.WebviewController): void {
    this.controller = controller;
    this.ready = false;
    this.writes = Promise.resolve();
  }

  unbind(): void {
    this.ready = false;
    this.controller = null;
  }

  markReady(): void { this.ready = true; }

  async call(script: string): Promise<void> {
    const controller: webview.WebviewController | null = this.controller;
    if (controller === null) throw new Error('终端视图尚未加载');
    await controller.runJavaScript(script);
  }

  write(bytes: Uint8Array): Promise<void> {
    const controller: webview.WebviewController | null = this.controller;
    if (!this.ready || controller === null) return Promise.reject(new Error('终端视图尚未就绪'));
    const values: number[] = [];
    for (let index: number = 0; index < bytes.length; index++) values.push(bytes[index]);
    const next: Promise<void> = this.writes.then(async (): Promise<void> => {
      await controller.runJavaScript(`window.AmberTerminal.write(${JSON.stringify(values)})`);
    });
    this.writes = next;
    return next;
  }

  reset(): Promise<void> {
    const controller: webview.WebviewController | null = this.controller;
    if (controller === null) return Promise.reject(new Error('终端视图尚未加载'));
    const next: Promise<void> = this.writes.catch((): void => {}).then(async (): Promise<void> => {
      await controller.runJavaScript('window.AmberTerminal.reset()');
    });
    this.writes = next;
    return next;
  }

  setInputEnabled(enabled: boolean): void {
    if (!this.ready) return;
    this.call(`window.AmberTerminal.setInputEnabled(${enabled ? 'true' : 'false'})`)
      .catch((error: Error): void => { this.onError(error.message); });
  }

  fit(): void {
    if (!this.ready) return;
    this.call('window.AmberTerminal.fit()')
      .catch((error: Error): void => { this.onError(error.message); });
  }
}

class TerminalBridge {
  onReady: (columns: number, rows: number) => void = (_columns: number, _rows: number): void => {};
  onInput: (bytes: Uint8Array) => void = (_bytes: Uint8Array): void => {};
  onResize: (columns: number, rows: number) => void = (_columns: number, _rows: number): void => {};

  ready(columns: number, rows: number): void {
    if (this.validSize(columns, rows)) this.onReady(columns, rows);
  }

  resize(columns: number, rows: number): void {
    if (this.validSize(columns, rows)) this.onResize(columns, rows);
  }

  input(values: number[]): void {
    if (values.length === 0 || values.length > 65536) return;
    for (const value of values) {
      if (!Number.isInteger(value) || value < 0 || value > 255) return;
    }
    this.onInput(new Uint8Array(values));
  }

  private validSize(columns: number, rows: number): boolean {
    return Number.isInteger(columns) && Number.isInteger(rows) &&
      columns >= 1 && rows >= 1 && columns <= 4096 && rows <= 4096;
  }
}

@Component
export struct TerminalView {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  viewController: TerminalViewController = new TerminalViewController();
  onReady: (columns: number, rows: number) => void = (_columns: number, _rows: number): void => {};
  onInput: (bytes: Uint8Array) => void = (_bytes: Uint8Array): void => {};
  onResize: (columns: number, rows: number) => void = (_columns: number, _rows: number): void => {};
  @StorageProp('darkMode') @Watch('applyPalette') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('applyPalette') themePackId: string = '';
  @StorageProp('accentColor') @Watch('applyPalette') accentColor: string = ACCENT;
  private webController: webview.WebviewController = new webview.WebviewController();
  private bridge: TerminalBridge = new TerminalBridge();
  private alive: boolean = false;
  private attached: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.viewController.bind(this.webController);
    this.bridge.onReady = (columns: number, rows: number): void => {
      if (!this.alive) return;
      this.viewController.markReady();
      this.onReady(columns, rows);
    };
    this.bridge.onInput = (bytes: Uint8Array): void => { if (this.alive) this.onInput(bytes); };
    this.bridge.onResize = (columns: number, rows: number): void => {
      if (this.alive) this.onResize(columns, rows);
    };
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.viewController.unbind();
    if (this.attached) {
      this.webController.deleteJavaScriptRegister('AmberTerminalBridge');
    }
    this.attached = false;
  }

  private palette(): TerminalPalette {
    return {
      background: SURFACE, foreground: INK, cursor: INK, cursorAccent: SURFACE,
      selectionBackground: this.darkMode ? '#665B7C99' : '#66527A99',
      toolbarBackground: SURFACE2, accent: this.accentColor,
    };
  }

  private applyPalette(): void {
    if (!this.attached) return;
    this.viewController.call(`window.AmberTerminal.setTheme(${JSON.stringify(this.palette())})`)
      .catch((error: Error): void => { this.viewController.onError(error.message); });
  }

  build(): void {
    Web({ src: $rawfile('terminal/host.html'), controller: this.webController })
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(BG)
      .javaScriptAccess(true)
      .domStorageAccess(false)
      .verticalScrollBarAccess(false)
      .horizontalScrollBarAccess(false)
      .javaScriptProxy({
        object: this.bridge, name: 'AmberTerminalBridge', methodList: ['ready', 'input', 'resize'],
        controller: this.webController,
      })
      .onPageEnd((): void => {
        this.attached = true;
        this.viewController.call(`window.AmberTerminal.boot(${JSON.stringify(this.palette())})`)
          .catch((error: Error): void => { this.viewController.onError(error.message); });
      })
      .onAreaChange((): void => { this.viewController.fit(); })
  }
}
