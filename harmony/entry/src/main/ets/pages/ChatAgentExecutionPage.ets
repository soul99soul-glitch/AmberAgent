// ChatAgentExecutionPage — 运行与显示设置面(D-115)
// Android 锚点：工具循环上限、操作预览、自动重试生成、重试上限。
import { BG, INK, INK3, TEXT_STYLES } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, ToggleRow, Seg, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import {
  loadAgentRuntimeSnapshot, updateAgentRuntimeExecution,
} from '../platform_impl/AgentRuntimePrefs.ets';
import type {
  AgentOperationPreviewMode, AgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { FONT_MONO } from '../design/fonts.ets';

const TOOL_LOOP_STEP_OPTIONS: number[] = [64, 128, 256, 384, 512];
const RETRY_COUNT_OPTIONS: number[] = [1, 2, 3, 5];
const PREVIEW_MODE_OPTIONS: AgentOperationPreviewMode[] = ['always', 'auto', 'hidden'];
const PREVIEW_MODE_LABELS: string[] = ['常驻', '自动', '隐藏'];

const coerceIn = (v: number, lo: number, hi: number): number =>
  Math.min(Math.max(v, lo), hi);

@Entry
@Component
struct ChatAgentExecutionPage {
  @State loaded: boolean = false;
  @State loadError: boolean = false;
  @State maxToolLoopSteps: number = 256;
  @State retryEnabled: boolean = true;
  @State retryMaxRetries: number = 5;
  @State operationPreviewMode: AgentOperationPreviewMode = 'always';

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    loadAgentRuntimeSnapshot(getChatKvStore()).then((snap: AgentRuntimeSnapshot): void => {
      this.maxToolLoopSteps = snap.maxToolLoopSteps;
      this.retryEnabled = snap.generationRetry.enabled;
      this.retryMaxRetries = snap.generationRetry.maxRetries;
      this.operationPreviewMode = snap.operationPreviewMode;
      this.loadError = false;
      this.loaded = true;
    }).catch((): void => {
      this.loadError = true;
      this.loaded = true;
    });
  }

  private setToolLoopSteps(steps: number): void {
    updateAgentRuntimeExecution(getChatKvStore(), { maxToolLoopSteps: steps })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.maxToolLoopSteps = snap.maxToolLoopSteps;
      });
  }

  private setPreviewMode(mode: AgentOperationPreviewMode): void {
    updateAgentRuntimeExecution(getChatKvStore(), { operationPreviewMode: mode })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.operationPreviewMode = snap.operationPreviewMode;
      });
  }

  private setRetryEnabled(checked: boolean): void {
    updateAgentRuntimeExecution(getChatKvStore(), { retryEnabled: checked })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.retryEnabled = snap.generationRetry.enabled;
      });
  }

  private setRetryMaxRetries(maxRetries: number): void {
    updateAgentRuntimeExecution(getChatKvStore(), { retryMaxRetries: maxRetries })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.retryMaxRetries = snap.generationRetry.maxRetries;
      });
  }

  private numericLabels(options: number[], suffix: string): string[] {
    return options.map((opt: number): string => `${opt} ${suffix}`);
  }

  private optionIndex(options: number[], selected: number): number {
    const idx: number = options.indexOf(selected);
    return idx >= 0 ? idx : 0;
  }

  @Builder
  selectPaper(title: string, desc: string, options: number[], selected: number,
    valueSuffix: string, onSelect: (v: number) => void, isLast: boolean): void {
    Column() {
      Text(title)
        .fontSize(TEXT_STYLES.body.size)
        .fontWeight(600)
        .lineHeight(TEXT_STYLES.body.lineHeight)
        .fontFamily(TEXT_STYLES.body.family)
        .fontColor(INK)
        .width('100%')
      Text(desc)
        .fontSize(TEXT_STYLES.secondary.size)
        .fontWeight(TEXT_STYLES.secondary.weight)
        .lineHeight(TEXT_STYLES.secondary.lineHeight)
        .fontFamily(TEXT_STYLES.secondary.family)
        .fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 4 })
        .width('100%')
      Column() {
        Seg({
          options: this.numericLabels(options, valueSuffix),
          selected: this.optionIndex(options, selected),
          onSelect: (idx: number): void => { onSelect(options[idx]); },
        })
      }
      .margin({ top: 12 })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
  }

  @Builder
  selectTextPaper(title: string, desc: string, options: AgentOperationPreviewMode[], labels: string[],
    selected: AgentOperationPreviewMode, onSelect: (v: AgentOperationPreviewMode) => void, isLast: boolean): void {
    Column() {
      Text(title)
        .fontSize(TEXT_STYLES.body.size)
        .fontWeight(600)
        .lineHeight(TEXT_STYLES.body.lineHeight)
        .fontFamily(TEXT_STYLES.body.family)
        .fontColor(INK)
        .width('100%')
      Text(desc)
        .fontSize(TEXT_STYLES.secondary.size)
        .fontWeight(TEXT_STYLES.secondary.weight)
        .lineHeight(TEXT_STYLES.secondary.lineHeight)
        .fontFamily(TEXT_STYLES.secondary.family)
        .fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 4 })
        .width('100%')
      Column() {
        Seg({
          options: labels,
          selected: options.indexOf(selected),
          onSelect: (idx: number): void => { onSelect(options[idx]); },
        })
      }
      .margin({ top: 12 })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '执行参数' })
      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK3)
          .margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            if (this.loadError) {
              Text('// 运行时配置加载失败，以下内容未读取真实配置')
                .fontSize(TEXT_STYLES.meta.size)
                .fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(FONT_MONO)
                .fontColor(INK3)
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 4 })
            }
            SectionHeader({ label: '运行' })
            this.selectTextPaper('操作预览', '控制输入框上方的 Agent 实时操作预览显示方式。',
              PREVIEW_MODE_OPTIONS, PREVIEW_MODE_LABELS, this.operationPreviewMode,
              (v: AgentOperationPreviewMode): void => { this.setPreviewMode(v); }, false)
            this.selectPaper('工具循环上限', '单次回复最多调用多少轮工具。',
              TOOL_LOOP_STEP_OPTIONS, coerceIn(this.maxToolLoopSteps, 16, 512), '步',
              (v: number): void => { this.setToolLoopSteps(v); }, true)

            SectionHeader({ label: '生成稳定性' })
            ToggleRow({
              label: '自动重试生成', desc: '网络或服务临时中断时自动重试。',
              checked: this.retryEnabled,
              onToggle: (): void => { this.setRetryEnabled(!this.retryEnabled); },
            })
            this.selectPaper('重试上限', '单次模型调用最多重试次数。',
              RETRY_COUNT_OPTIONS, coerceIn(this.retryMaxRetries, 1, 5), '次',
              (v: number): void => { this.setRetryMaxRetries(v); }, true)
            PageSpacer()
          }
          .width('100%')
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
