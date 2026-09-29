// CompressContextDialog — 上下文压缩确认弹窗(G4-S3 升级)
// Android 基准: CompressContextDialog.kt(175 行)
// 升级: 真模态(遮罩层) + SURFACE2 导入修复 + 警告文本 + 全宽 TextArea
//   + loading 态保留标题/取消按钮(只替换 body)
// 降级(跳过): SingleChoiceSegmentedButtonRow(用 pill 替代) / Promise 异步(用同步 onConfirm)
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_INK, ERROR, BODY, LABEL, OVERLAY } from '../../design/tokens.ets';
import { RabbitLoading } from './RabbitLoading.ets';

const TOKEN_OPTIONS: number[] = [500, 1000, 2000, 4000];
const KEEP_RECENT_OPTIONS: number[] = [0, 16, 32, 64];

@Component
export struct CompressContextDialog {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
    this.onConfirm(this.additionalPrompt, this.selectedTokens, this.selectedKeepRecent);
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Stack() {
        Column() {
          // 标题(loading 时也保留)
          Text('压缩上下文')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .width('100%')
            .padding({ top: 20, bottom: 8 })

          if (this.isLoading) {
            // loading body(只替换中间内容,保留标题和取消按钮)
            Column() {
              RabbitLoading({ iconSize: 32 })
              Text('压缩中…')
                .fontSize(BODY)
                .fontColor(INK3)
                .margin({ top: 12 })
            }
            .width('100%')
            .padding({ top: 24, bottom: 24 })
            .justifyContent(FlexAlign.Center)
          } else {
            // 描述
            Text('压缩当前对话的上下文以节省 token 用量。')
              .fontSize(LABEL)
              .fontColor(INK3)
              .width('100%')
              .margin({ bottom: 12 })

            // token 目标选择
            Text('目标 token 数')
              .fontSize(LABEL)
              .fontColor(INK3)
              .width('100%')
              .margin({ bottom: 6 })
            Row() {
              ForEach(TOKEN_OPTIONS, (opt: number, i: number): void => {
                Text(`${opt}`)
                  .fontSize(LABEL)
                  .fontColor(this.selectedTokens === opt ? ACCENT_INK : INK3)
                  .padding({ left: 10, right: 10, top: 4, bottom: 4 })
                  .backgroundColor(this.selectedTokens === opt ? ACCENT : SURFACE2)
                  .borderRadius(8)
                  .margin({ right: 6 })
                  .onClick((): void => { this.selectedTokens = opt; })
              }, (opt: number, i: number): string => `tok-${opt}`)
            }
            .width('100%')
            .margin({ bottom: 12 })

            // keepRecent 选择
            Text('保留最近消息数')
              .fontSize(LABEL)
              .fontColor(INK3)
              .width('100%')
              .margin({ bottom: 6 })
            Row() {
              ForEach(KEEP_RECENT_OPTIONS, (opt: number, i: number): void => {
                Text(`${opt}`)
                  .fontSize(LABEL)
                  .fontColor(this.selectedKeepRecent === opt ? ACCENT_INK : INK3)
                  .padding({ left: 10, right: 10, top: 4, bottom: 4 })
                  .backgroundColor(this.selectedKeepRecent === opt ? ACCENT : SURFACE2)
                  .borderRadius(8)
                  .margin({ right: 6 })
                  .onClick((): void => { this.selectedKeepRecent = opt; })
              }, (opt: number, i: number): string => `kr-${opt}`)
            }
            .width('100%')
            .margin({ bottom: 12 })

            // additionalPrompt
            Text('额外提示词(可选)')
              .fontSize(LABEL)
              .fontColor(INK3)
              .width('100%')
              .margin({ bottom: 6 })
            TextArea({ text: this.additionalPrompt, placeholder: '压缩时的额外指令…' })
              .fontSize(BODY)
              .fontColor(INK)
              .backgroundColor(SURFACE2)
              .borderRadius(8)
              .border({ width: 1, color: LINE })
              .padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .width('100%')
              .maxLines(4)
              .onChange((v: string): void => { this.additionalPrompt = v; })

            // 警告
            Text('⚠ 压缩后不可撤销,历史消息将被摘要替代。')
              .fontSize(LABEL)
              .fontColor(ERROR)
              .width('100%')
              .margin({ top: 8, bottom: 8 })
          }

          // 按钮行(loading 时显示取消,非 loading 显示取消+确认)
          Row() {
            Blank()
            if (this.isLoading) {
              Text('取消')
                .fontSize(BODY)
                .fontColor(INK3)
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .onClick((): void => { this.isLoading = false; this.onDismiss(); })
            } else {
              Text('取消')
                .fontSize(BODY)
                .fontColor(INK3)
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .onClick((): void => { this.onDismiss(); })
              Text('确认压缩')
                .fontSize(BODY)
                .fontColor(ACCENT)
                .fontWeight(FontWeight.Medium)
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .onClick((): void => { this.confirm(); })
            }
          }
          .width('100%')
          .margin({ top: 4 })
        }
        .width('80%')
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .padding({ left: 20, right: 20, bottom: 12 })
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Center)
    }
  }
}
