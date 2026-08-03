// QRCode — 二维码(ui/QRCode.kt:46 行)
// Android 基准: zxing QRCodeWriter → BitMatrix → Bitmap → Image
// ArkUI: 无原生 QR 生成 API;zxing 无 TS 移植
//   MVP 降级:显示 URL 文本 + 二维码占位(诚实,不伪生成)
//   后续:接 @ohos.multimedia.image 自绘 或 zxing-ts(需验证可用性)
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BODY, META, LABEL } from '../../design/tokens.ets';

@Component
export struct QRCode {
  @Prop value: string = '';
  @Prop qrSize: number = 200;
  build(): void {
    Column() {
      // 占位:二维码区域(后续接 zxing-ts 或平台 API)
      Column() {
        Text('▦').fontSize(this.qrSize * 0.4).fontColor(INK3)
        Text('二维码').fontSize(LABEL).fontColor(INK3).margin({ top: 4 })
      }
      .width(this.qrSize).height(this.qrSize)
      .backgroundColor(SURFACE2)
      .borderRadius(8)
      .border({ width: 1, color: LINE })
      .justifyContent(FlexAlign.Center)

      Text(this.value)
        .fontSize(LABEL).fontColor(INK3)
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 8 })
        .textAlign(TextAlign.Center)
    }
    .alignItems(HorizontalAlign.Center)
  }
}
