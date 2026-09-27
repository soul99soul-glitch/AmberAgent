// ChatAgentExecutionPage — 运行与显示设置面(D-115)
// Android 锚点：工具循环上限、操作预览、自动重试生成、重试上限。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1986 风格):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardToggleRow + 卡内 Seg),逻辑零改动。
import { INK, INK3, LINE, BG, GUTTER, TEXT_STYLES } from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardToggleRow, Seg, PageSpacer,
} from '../components/PageTemplate.ets';
import {
  loadAgentRuntimeSnapshot, updateAgentRuntimeExecution,
} from '../platform_impl/AgentRuntimePrefs.ets';
import type {
  AgentOperationPreviewMode, AgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';

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

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  // 卡组内发丝分隔线(内缩至行文字起点,同 CardRow 分隔规则)
  @Builder
  CardHairline(): void {
    Row() {
      Row()
        .width('100%')
        .height(1)
        .backgroundColor(LINE)
    }
    .width('100%')
    .padding({ left: 14, right: 14 })
  }

  // 卡内选择块:标题/描述 + Seg(原有 selectPaper/selectTextPaper 装入卡片容器)
  @Builder
  SegRow(title: string, desc: string, labels: string[], selected: number,
    onSelect: (idx: number) => void): void {
    Column() {
      Text(title)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.body.lineHeight)
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK)
        .width('100%')
      Text(desc)
        .fontSize(12)
        .lineHeight(TEXT_STYLES.secondary.lineHeight)
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 2 })
        .width('100%')
      Seg({
        options: labels,
        selected: selected,
        onSelect: (idx: number): void => { onSelect(idx); },
      })
        .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  // 「运行」组:操作预览 + 工具循环上限
  @Builder
  RunGroup(): void {
    this.SegRow('操作预览', '控制输入框上方的 Agent 实时操作预览显示方式。',
      PREVIEW_MODE_LABELS, PREVIEW_MODE_OPTIONS.indexOf(this.operationPreviewMode),
      (idx: number): void => { this.setPreviewMode(PREVIEW_MODE_OPTIONS[idx]); })
    this.CardHairline()
    this.SegRow('工具循环上限', '单次回复最多调用多少轮工具。',
      this.numericLabels(TOOL_LOOP_STEP_OPTIONS, '步'),
      this.optionIndex(TOOL_LOOP_STEP_OPTIONS, coerceIn(this.maxToolLoopSteps, 16, 512)),
      (idx: number): void => { this.setToolLoopSteps(TOOL_LOOP_STEP_OPTIONS[idx]); })
  }

  // 「生成稳定性」组:自动重试生成(开关)+ 重试上限
  @Builder
  StabilityGroup(): void {
    CardToggleRow({
      label: '自动重试生成', desc: '网络或服务临时中断时自动重试。',
      checked: this.retryEnabled,
      onToggle: (): void => { this.setRetryEnabled(!this.retryEnabled); },
    })
    this.SegRow('重试上限', '单次模型调用最多重试次数。',
      this.numericLabels(RETRY_COUNT_OPTIONS, '次'),
      this.optionIndex(RETRY_COUNT_OPTIONS, coerceIn(this.retryMaxRetries, 1, 5)),
      (idx: number): void => { this.setRetryMaxRetries(RETRY_COUNT_OPTIONS[idx]); })
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '执行参数' })
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
              Text('运行时配置加载失败，以下内容未读取真实配置')
                .fontSize(12)
                .lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(FONT_SANS_MEDIUM)
                .fontColor(INK3)
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 4 })
            }
            PlainSection({ label: '运行' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.RunGroup(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '生成稳定性' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.StabilityGroup(); } })
              .margin({ left: GUTTER, right: GUTTER })
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
