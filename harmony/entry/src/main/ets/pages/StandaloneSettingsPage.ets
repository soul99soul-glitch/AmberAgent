import { router } from '@kit.ArkUI';
import { BG, INK3 } from '../design/tokens.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote } from '../components/PageTemplate.ets';

@Entry
@Component
struct StandaloneSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;

  @Builder
  ReadingRows(): void {
    CardRow({ label: '阅读与发现', desc: '阅读模型、字体、字号和热榜来源',
      onRowClick: (): void => { router.pushUrl({ url: 'pages/SettingDeepReadPage' }); } })
    CardRow({ label: '文章模板', desc: '编辑、预览、导入或生成排版模板',
      onRowClick: (): void => { router.pushUrl({ url: 'pages/DeepReadTemplateSettingsPage' }); } })
  }

  @Builder
  ServiceRows(): void {
    CardRow({ label: '模型服务商', desc: '添加服务商、凭据和模型',
      onRowClick: (): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); } })
    CardRow({ label: '搜索服务', desc: '搜索引擎、结果数量和正文读取',
      onRowClick: (): void => { router.pushUrl({ url: 'pages/ChatSearchSettingsPage' }); } })
    CardRow({ label: '外观', desc: '浅色、深色与主题',
      onRowClick: (): void => { router.pushUrl({ url: 'pages/SettingAppearancePage' }); } })
  }

  build(): void {
    Column() {
    ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        CenteredTitleBar({ title: '深度阅读设置' })
        Scroll() {
          Column() {
            PlainSection({ label: '阅读' })
            CardGroup({ content: (): void => { this.ReadingRows(); } }).margin({ left: 16, right: 16 })
            PlainSection({ label: '服务与外观' })
            CardGroup({ content: (): void => { this.ServiceRows(); } }).margin({ left: 16, right: 16 })
            FooterNote({ text: '先添加模型服务商，再到“阅读与发现”选择阅读模型。文章、来源和设置保存在本应用中。' })
            Text('深度阅读').fontSize(12).fontColor(INK3).margin({ top: 24, bottom: 24 })
          }.width('100%').padding({ bottom: this.navBarHeight + 16 })
        }.layoutWeight(1).width('100%').align(Alignment.TopStart)
      }.width('100%').height('100%').backgroundColor(BG)
    }, (epoch: number): string => `${epoch}`)
    }.width('100%').height('100%').backgroundColor(BG)
  }
}
