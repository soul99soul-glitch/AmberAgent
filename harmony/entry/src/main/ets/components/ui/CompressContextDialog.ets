// CompressContextDialog — 上下文压缩确认弹窗(ai/CompressContextDialog.kt:175 行)
// Android 基准: AlertDialog + token 选择(500/1k/2k/4k)+ keepRecent 选择(0/16/32/64)
//   + additionalPrompt TextArea + loading 态(PigLoadingIndicator → 降级 DotLoading)
//   onConfirm 返回 Job;loading 时禁止 dismiss
// ArkUI: bindSheet;loading 态用 @State isLoading;confirm 异步 → Promise
import { INK, INK3, SURFACE, LINE, ACCENT, BODY, LABEL } from '../../design/tokens.ets';
import { RabbitLoading } from './RabbitLoading.ets';

const TOKEN_OPTIONS: number[] = [500, 1000, 2000, 4000];
const KEEP_RECENT_OPTIONS: number[] = [0, 16, 32, 64];

@Component
export struct CompressContextDialog {
  @Prop show: boolean = false;
  onConfirm: (additionalPrompt: string, targetTokens: number, keepRecentMessages: number) => void
    = (_p: string, _t: number, _k: number): void => {};
  onDismiss: () => void = (): void => {};
  @State additionalPrompt: string = '';
  @State selectedTokens: number = 2000;
  @State selectedKeepRecent: number = 32;
  @State isLoading: boolean = false;

  private confirm(): void {
    this.isLoading = true;
    try {
      this.onConfirm(this.additionalPrompt, this.selectedTokens, this.selectedKeepRecent);
    } finally {
      this.isLoading = false;
    }
    // 调用方完成压缩后应调用 onDismiss 关闭(对齐 Android Job 完成自动 dismiss)
  }

  build(): void {
    Column() {
      if (this.isLoading) {
        // loading 态
        Column() {
          RabbitLoading({ iconSize: 32 })
          Text('压缩中…').fontSize(BODY).fontColor(INK3).margin({ top: 12 })
        }
        .width('100%')
        .padding({ top: 32, bottom: 32 })
        .justifyContent(FlexAlign.Center)
      } else {
        // 标题
        Text('压缩上下文').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .width('100%').padding({ left: 20, top: 20, bottom: 8 })

        Text('压缩当前对话的上下文以节省 token 用量。')
          .fontSize(LABEL).fontColor(INK3)
          .width('100%').padding({ left: 20, bottom: 12 })

        // token 目标选择
        Text('目标 token 数').fontSize(LABEL).fontColor(INK3)
          .width('100%').padding({ left: 20, bottom: 6 })
        Row() {
          ForEach(TOKEN_OPTIONS, (opt: number, i: number): void => {
            Text(`${opt}`)
              .fontSize(LABEL)
              .fontColor(this.selectedTokens === opt ? '#FFFFFF' : INK3)
              .padding({ left: 10, right: 10, top: 4, bottom: 4 })
              .backgroundColor(this.selectedTokens === opt ? ACCENT : SURFACE2)
              .borderRadius(8)
              .margin({ right: 6 })
              .onClick((): void => { this.selectedTokens = opt; })
          }, (opt: number, i: number): string => `tok-${opt}`)
        }
        .width('100%').padding({ left: 20, bottom: 12 })

        // keepRecent 选择
        Text('保留最近消息数').fontSize(LABEL).fontColor(INK3)
          .width('100%').padding({ left: 20, bottom: 6 })
        Row() {
          ForEach(KEEP_RECENT_OPTIONS, (opt: number, i: number): void => {
            Text(`${opt}`)
              .fontSize(LABEL)
              .fontColor(this.selectedKeepRecent === opt ? '#FFFFFF' : INK3)
              .padding({ left: 10, right: 10, top: 4, bottom: 4 })
              .backgroundColor(this.selectedKeepRecent === opt ? ACCENT : SURFACE2)
              .borderRadius(8)
              .margin({ right: 6 })
              .onClick((): void => { this.selectedKeepRecent = opt; })
          }, (opt: number, i: number): string => `kr-${opt}`)
        }
        .width('100%').padding({ left: 20, bottom: 12 })

        // additionalPrompt
        Text('额外提示词(可选)').fontSize(LABEL).fontColor(INK3)
          .width('100%').padding({ left: 20, bottom: 6 })
        TextArea({ text: this.additionalPrompt, placeholder: '压缩时的额外指令…' })
          .fontSize(BODY).fontColor(INK)
          .backgroundColor(SURFACE).borderRadius(8)
          .border({ width: 1, color: LINE })
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .width('85%')
          .maxLines(4)
          .onChange((v: string): void => { this.additionalPrompt = v; })

        // 按钮
        Row() {
          Blank()
          Text('取消').fontSize(BODY).fontColor(INK3)
            .padding({ left: 16, right: 16, top: 8, bottom: 8 })
            .onClick((): void => { this.onDismiss(); })
          Text('确认压缩').fontSize(BODY).fontColor(ACCENT)
            .padding({ left: 16, right: 16, top: 8, bottom: 8 })
            .onClick((): void => { this.confirm(); })
        }
        .width('100%').margin({ top: 12 })
      }
    }
    .width('100%')
    .backgroundColor(SURFACE)
  }
}
