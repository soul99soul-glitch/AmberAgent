// ExportDialog — 通用导出弹窗(ui/Export.kt:99 行)
// Android 基准: AlertDialog 两选项(导出到文件 / 导出并分享)+ 取消
// ArkUI: bindSheet 含两个选项行 + 回调(exportToFile/exportAndShare 由调用方实现)
import { INK2, INK3, SURFACE, LINE, BODY, LABEL } from '../../design/tokens.ets';
import { Icon } from './Icon.ets';

@Component
export struct ExportDialog {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop show: boolean = false;
  @Prop title: string = '导出';
  onExportToFile: () => void = (): void => {};
  onExportAndShare: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  build(): void {
    // bindSheet 形式:调用方绑到触发表面
    // 本组件只提供 sheet builder;show 状态由调用方控制
    Column() {
      Text(this.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ left: 20, top: 20, bottom: 12 })

      Row() {
        Icon({ name: 'doc', iconSize: BODY, color: INK2 }).margin({ right: 10 })
        Column() {
          Text('导出到文件').fontSize(BODY).fontColor(INK2)
          Text('保存到本地存储').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 12, bottom: 12 })
      .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
      .onClick((): void => { this.onExportToFile(); this.onDismiss(); })

      Row() {
        Icon({ name: 'share', iconSize: BODY, color: INK2 }).margin({ right: 10 })
        Column() {
          Text('导出并分享').fontSize(BODY).fontColor(INK2)
          Text('导出后打开分享面板').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 12, bottom: 12 })
      .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
      .onClick((): void => { this.onExportAndShare(); this.onDismiss(); })

      Text('取消').fontSize(BODY).fontColor(INK3)
        .width('100%')
        .padding({ left: 20, top: 14, bottom: 14 })
        .onClick((): void => { this.onDismiss(); })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .backgroundColor(SURFACE)
  }
}
