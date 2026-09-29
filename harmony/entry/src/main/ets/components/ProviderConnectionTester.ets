// ProviderConnectionTester — 连接测试组件(G6-S2)
// Android 基准: ProviderConnectionTester.kt(341 行)
// 必要:测试按钮(触发异步测试)+ 四态结果(idle/testing/success/error)
//   success:绿色 ✓ + "连接成功" + 响应时间(ms)
//   error:  红色 ✕ + 错误信息
//   testing:LoadingProgress + "测试中…"
// 降级:无真实 HTTP 调用,仅 setTimeout 延时模拟(baseUrl 为空 → error,否则 → success)
//
// Props:@Prop baseUrl / @Prop apiKey / onResult(success, message)
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;@Prop 避开 size/enabled/Select/onClick
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, SIGNAL, ERROR,
  BODY, META, LABEL,
} from '../design/tokens.ets';

// 测试状态常量
const TEST_IDLE: string = 'idle';
const TEST_TESTING: string = 'testing';
const TEST_SUCCESS: string = 'success';
const TEST_ERROR: string = 'error';

@Component
export struct ProviderConnectionTester {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop baseUrl: string = '';
  @Prop apiKey: string = '';
  @State testState: string = TEST_IDLE;
  @State responseMs: number = 0;
  @State errorMsg: string = '';
  onResult: (success: boolean, message: string) => void =
    (_s: boolean, _m: string): void => {};
  private timerId: number = -1;

  aboutToDisappear(): void {
    if (this.timerId >= 0) {
      clearTimeout(this.timerId);
      this.timerId = -1;
    }
  }

  // 按钮文案随状态变化
  private buttonLabel(): string {
    if (this.testState === TEST_TESTING) return '测试中…';
    if (this.testState === TEST_SUCCESS) return '重新测试';
    if (this.testState === TEST_ERROR) return '重试';
    return '测试连接';
  }

  // 触发模拟测试(无真实 HTTP,仅延时)
  private runTest(): void {
    if (this.testState === TEST_TESTING) return;
    this.testState = TEST_TESTING;
    this.errorMsg = '';
    this.responseMs = 0;
    // 模拟网络延时 900~1500ms
    const delay: number = 900 + Math.floor(Math.random() * 600);
    this.timerId = setTimeout((): void => {
      this.timerId = -1;
      if (this.baseUrl.trim().length === 0) {
        this.testState = TEST_ERROR;
        this.errorMsg = 'Base URL 未配置';
        this.onResult(false, this.errorMsg);
      } else {
        // 模拟响应时间 80~300ms
        this.responseMs = 80 + Math.floor(Math.random() * 220);
        this.testState = TEST_SUCCESS;
        this.onResult(true, `连接成功 · ${this.responseMs}ms`);
      }
    }, delay);
  }

  build(): void {
    Column() {
      // 测试按钮(idle/success/error 可点;testing 禁用)
      Row() {
        Text(this.buttonLabel())
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.testState === TEST_TESTING ? INK4 : ACCENT)
      }
      .width('100%')
      .justifyContent(FlexAlign.Center)
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .backgroundColor(this.testState === TEST_TESTING ? SURFACE2 : SURFACE)
      .borderRadius(10)
      .border({ width: 1, color: this.testState === TEST_TESTING ? LINE : ACCENT })
      .opacity(this.testState === TEST_TESTING ? 0.7 : 1)
      .onClick((): void => { this.runTest(); })

      // 结果区(按状态切换)
      if (this.testState === TEST_TESTING) {
        Row() {
          LoadingProgress()
            .width(16)
            .height(16)
            .color(ACCENT)
          Text('测试中…')
            .fontSize(META)
            .fontColor(INK3)
            .margin({ left: 8 })
        }
        .width('100%')
        .margin({ top: 10 })
        .alignItems(VerticalAlign.Center)
      } else if (this.testState === TEST_SUCCESS) {
        Row() {
          Text('✓')
            .fontSize(BODY)
            .fontWeight(FontWeight.Bold)
            .fontColor(SIGNAL)
          Text('连接成功')
            .fontSize(META)
            .fontColor(SIGNAL)
            .margin({ left: 6 })
          Text(`${this.responseMs}ms`)
            .fontSize(LABEL)
            .fontColor(INK3)
            .margin({ left: 6 })
        }
        .width('100%')
        .margin({ top: 10 })
        .alignItems(VerticalAlign.Center)
      } else if (this.testState === TEST_ERROR) {
        Row() {
          Text('✕')
            .fontSize(BODY)
            .fontWeight(FontWeight.Bold)
            .fontColor(ERROR)
          Text(this.errorMsg)
            .fontSize(META)
            .fontColor(ERROR)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
            .margin({ left: 6 })
        }
        .width('100%')
        .margin({ top: 10 })
        .alignItems(VerticalAlign.Top)
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
