// SettingsPage — 设置(配 API key)
import { router } from '@kit.ArkUI';
import { getAppContainer } from '../di/AppContainer.ets';
import type { Storage } from '@amber/deepread-domain';

@Entry
@Component
struct HistoryPage {
  @State aiKey: string = '';
  @State aiBaseUrl: string = 'https://api.deepseek.com';
  @State tavilyKey: string = '';
  @State model: string = 'deepseek-chat';
  @State saved: string = '';
  private storage: Storage | null = null;

  async aboutToAppear(): Promise<void> {
    this.storage = getAppContainer().storage;
    this.aiKey = await this.storage.get<string>('ai_api_key', '');
    this.aiBaseUrl = await this.storage.get<string>('ai_base_url', 'https://api.deepseek.com');
    this.tavilyKey = await this.storage.get<string>('tavily_api_key', '');
    this.model = await this.storage.get<string>('ai_model', 'deepseek-chat');
  }

  async save(): Promise<void> {
    await this.storage!.set<string>('ai_api_key', this.aiKey);
    await this.storage!.set<string>('ai_base_url', this.aiBaseUrl);
    await this.storage!.set<string>('tavily_api_key', this.tavilyKey);
    await this.storage!.set<string>('ai_model', this.model);
    this.saved = '已保存 ✓';
  }

  build(): void {
    Column() {
      // 顶栏
      Row() {
        Text('返回').fontSize(14).fontColor('#1976D2').onClick(() => router.back())
        Text('设置').fontSize(18).fontWeight(FontWeight.Medium).margin({ left: 8 }).layoutWeight(1)
      }
      .width('100%').height(52).padding({ left: 16, right: 16 })

      Scroll() {
        Column() {
          // AI 配置
          Text('AI 配置').fontSize(15).fontWeight(FontWeight.Bold).margin({ top: 16, bottom: 8 }).width('100%')

          Text('API Base URL').fontSize(12).fontColor('#666').margin({ bottom: 4 }).width('100%')
          TextInput({ text: this.aiBaseUrl })
            .height(40).fontSize(13).backgroundColor('#F5F5F5').borderRadius(8)
            .onChange((v: string) => { this.aiBaseUrl = v; })
            .margin({ bottom: 12 })

          Text('API Key').fontSize(12).fontColor('#666').margin({ bottom: 4 }).width('100%')
          TextInput({ text: this.aiKey, placeholder: 'sk-...' })
            .height(40).fontSize(13).backgroundColor('#F5F5F5').borderRadius(8)
            .onChange((v: string) => { this.aiKey = v; })
            .margin({ bottom: 12 })

          Text('模型名称').fontSize(12).fontColor('#666').margin({ bottom: 4 }).width('100%')
          TextInput({ text: this.model, placeholder: 'deepseek-chat' })
            .height(40).fontSize(13).backgroundColor('#F5F5F5').borderRadius(8)
            .onChange((v: string) => { this.model = v; })
            .margin({ bottom: 16 })

          // Tavily 搜索
          Text('搜索配置').fontSize(15).fontWeight(FontWeight.Bold).margin({ bottom: 8 }).width('100%')

          Text('Tavily API Key(可选)').fontSize(12).fontColor('#666').margin({ bottom: 4 }).width('100%')
          TextInput({ text: this.tavilyKey, placeholder: 'tvly-...' })
            .height(40).fontSize(13).backgroundColor('#F5F5F5').borderRadius(8)
            .onChange((v: string) => { this.tavilyKey = v; })
            .margin({ bottom: 16 })

          Text('不配 Tavily 也能用,NewsNow 国内热榜源始终可用。')
            .fontSize(11).fontColor('#999').margin({ bottom: 16 })

          Button('保存')
            .width('100%').height(44).fontSize(15)
            .onClick(() => { this.save(); })

          if (this.saved.length > 0) {
            Text(this.saved).fontSize(12).fontColor('#43A047').margin({ top: 8 })
          }
        }
        .width('88%')
        .alignItems(HorizontalAlign.Start)
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%')
  }
}
