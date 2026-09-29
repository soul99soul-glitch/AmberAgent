import { KeyboardAvoidMode } from '@kit.ArkUI';
import { PythonRuntime } from '@amber/chat-domain';
import type { PythonSnapshot, PythonExecuteRequest } from '@amber/chat-domain';
import { getPythonRuntime } from '../di/AppContainer.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { CenteredTitleBar, CardGroup } from '../components/PageTemplate.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

@Entry
@Component
struct PythonPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State source: string = 'import math\nprint("Hello, Python")\nprint(math.sqrt(144))';
  @State stdin: string = '';
  @State timeoutText: string = '15000';
  @State version: string = '';
  @State executing: boolean = false;
  @State cancelling: boolean = false;
  @State result: PythonSnapshot | null = null;
  @State error: string = '';
  @State loaded: boolean = false;
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('appBackgrounded') backgrounded: boolean = false;
  private runtime: PythonRuntime | null = null;
  private operation: EntryAbortController | null = null;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.load();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.operation?.abort();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.OFFSET);
  }

  private refreshColors(): void { this.colors = new SSHUIColors(); }

  private async load(): Promise<void> {
    try {
      const runtime: PythonRuntime = await getPythonRuntime();
      if (!this.alive) return;
      this.runtime = runtime;
      this.version = runtime.version();
      this.loaded = true;
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? error.message : String(error);
    }
  }

  private async execute(): Promise<void> {
    const runtime: PythonRuntime | null = this.runtime;
    if (runtime === null || this.executing || this.backgrounded) return;
    const timeoutMs: number = Number(this.timeoutText);
    if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 60000) {
      this.error = '超时须为 1–60000 毫秒的整数';
      return;
    }
    const controller: EntryAbortController = new EntryAbortController();
    this.operation = controller;
    this.executing = true;
    this.cancelling = false;
    this.error = '';
    this.result = null;
    const request: PythonExecuteRequest = {
      source: this.source, stdin: this.stdin, timeoutMs: timeoutMs,
      sourceToolName: null, sourceConversationId: null,
    };
    try {
      const result: PythonSnapshot = await runtime.execute(request, controller.signal);
      if (this.alive) this.result = result;
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? error.message : String(error);
    } finally {
      this.operation = null;
      if (this.alive) { this.executing = false; this.cancelling = false; }
    }
  }

  private cancel(): void {
    if (!this.executing || this.cancelling) return;
    this.cancelling = true;
    this.operation?.abort();
  }

  private statusText(): string {
    if (this.cancelling) return '正在取消，等待执行停止…';
    if (this.executing) return '正在本机执行…';
    if (this.result === null) return this.loaded ? '就绪' : '正在加载本机 Python…';
    if (this.result.status === 'COMPLETED') return '执行完成';
    if (this.result.status === 'CANCELLED') return '已取消';
    if (this.result.status === 'INTERRUPTED') return '执行已中断';
    if (this.result.status === 'TIMED_OUT') return '执行超时';
    return '执行失败';
  }

  @Builder
  private InputCard(): void {
    Column({ space: 8 }) {
      Text('Python 代码').fontSize(14).fontColor(this.colors.ink).width('100%')
      TextArea({ text: this.source, placeholder: '输入 Python 代码' })
        .height(220).fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.ink)
        .placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(!this.executing)
        .onChange((value: string): void => { this.source = value; })
      Text('标准输入 stdin（可选）').fontSize(14).fontColor(this.colors.ink).width('100%')
      TextArea({ text: this.stdin, placeholder: '代码通过 stdin 字符串或 input() 读取输入' })
        .height(100).fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.ink)
        .placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(!this.executing)
        .onChange((value: string): void => { this.stdin = value; })
      Row() {
        Text('超时（毫秒）').fontSize(14).fontColor(this.colors.secondary).layoutWeight(1)
        TextInput({ text: this.timeoutText }).type(InputType.Number).width(112).height(44)
          .fontSize(14).fontColor(this.colors.ink).backgroundColor(this.colors.inset).enabled(!this.executing)
          .onChange((value: string): void => { this.timeoutText = value; })
      }.width('100%')
      Row({ space: 8 }) {
        Text(this.statusText()).fontSize(12).fontColor(this.colors.secondary).layoutWeight(1)
        Button(this.executing ? this.cancelling ? '取消中' : '取消' : '运行')
          .type(ButtonType.Normal).height(44).constraintSize({ minWidth: 72 }).fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset)
          .enabled(this.executing ? !this.cancelling : this.loaded && !this.backgrounded)
          .onClick((): void => { if (this.executing) this.cancel(); else this.execute(); })
      }.width('100%')
      if (this.error.length > 0) {
        Text(this.error).fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
      }
    }.width('100%').padding(14)
  }

  @Builder
  private ResultCard(): void {
    Column({ space: 8 }) {
      if (this.result !== null) {
        Text(`${this.statusText()} · exitCode: ${this.result.exitCode === null ? '—' : this.result.exitCode}`)
          .fontSize(13).fontColor(this.colors.secondary).width('100%')
        Text('stdout').fontSize(12).fontColor(this.colors.secondary).width('100%')
        Text(this.result.stdout.length > 0 ? this.result.stdout : '（无输出）')
          .fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        if (this.result.stderr.length > 0) {
          Text('stderr').fontSize(12).fontColor(this.colors.secondary).width('100%')
          Text(this.result.stderr).fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.error).width('100%')
            .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        }
        if (this.result.errorCode !== null) {
          Text(this.result.errorCode).fontSize(12).fontColor(this.colors.error).width('100%')
            .wordBreak(WordBreak.BREAK_ALL)
        }
      }
    }.width('100%').padding(14)
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '本机 Python' })
      Scroll() {
        Column({ space: 12 }) {
          Text(this.version.length > 0 ? `CPython ${this.version}` : '内嵌 CPython')
            .fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
          Text('代码在本机运行。支持 JSON、数学等数据处理；文件可通过 Workspace 读取后传入 stdin，再保存输出。')
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
          CardGroup({ content: (): void => { this.InputCard(); } })
          if (this.result !== null) {
            CardGroup({ content: (): void => { this.ResultCard(); } })
          }
          Text('代码 / stdin / 输出上限为 256 / 64 / 128 KiB。没有文件、网络或包安装接口。取消与超时采用协作式停止，阻塞的 C 调用不能硬停止；这些限制不是系统级安全沙箱。')
            .fontSize(12).fontColor(this.colors.muted).width('100%')
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 16 + this.navBarHeight })
          .justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
