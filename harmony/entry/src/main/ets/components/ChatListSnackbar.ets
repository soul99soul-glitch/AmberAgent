// ChatListSnackbar — 删除撤销条(G5-S2)
// Android 基准: HistoryPage.kt:124-153 SnackbarHost;'对话已删除'/'撤销' 逐字
//   (strings.xml:308/:320);SnackbarDuration.Short ≈ 4s
//   超时/被顶替 → purge;ActionPerformed → restoreConversation(insert)
// 现有: ChatListPage.ets:261-276 内联 Row;本组件抽离为可复用 struct
// 语义: show → 渲染底部 snackbar;4s 自动 onDismiss;撤销 → onUndo
import { ACCENT, INK } from '../design/tokens.ets';

// 底部深色背景 + 文案 + 撤销按钮(ACCENT)
const SNACK_BG: string = '#32302B';
const SNACK_DURATION_MS: number = 4000;

@Component
export struct ChatListSnackbar {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop show: boolean = false;
  @Prop message: string = '对话已删除';
  onUndo: () => void = (): void => {};
  onDismiss: () => void = (): void => {};
  private timerId: number = -1;

  // show 变 true → 启动 4s 倒计时;变 false → 清理计时器
  // (旧条顶替 = 父组件先 false 再 true,触发倒计时重置)
  aboutToDisappear(): void {
    this.clearTimer();
  }

  private clearTimer(): void {
    if (this.timerId >= 0) {
      clearTimeout(this.timerId);
      this.timerId = -1;
    }
  }

  private startTimer(): void {
    this.clearTimer();
    this.timerId = setTimeout((): void => {
      this.timerId = -1;
      this.onDismiss();
    }, SNACK_DURATION_MS);
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Row() {
        Text(this.message)
          .fontSize(13)
          .fontColor(Color.White)
        Blank()
        Text('撤销')
          .fontSize(13)
          .fontColor(ACCENT)
          .padding({ left: 16, right: 4, top: 6, bottom: 6 })
          .onClick((): void => {
            this.clearTimer();
            this.onUndo();
          })
          .accessibilityText('撤销删除')
      }
      .width('92%')
      .padding({ left: 16, right: 16, top: 10, bottom: 10 })
      .backgroundColor(SNACK_BG)
      .borderRadius(8)
      .margin({ bottom: 24 })
      .onAppear((): void => { this.startTimer(); })
      .onDisAppear((): void => { this.clearTimer(); })
    }
  }
}
