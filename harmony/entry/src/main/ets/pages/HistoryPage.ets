// SettingsPage — 杂志风设置页(分组卡片 + 衬线标题 + 表单排版)
import { router } from '@kit.ArkUI';
import { getAppContainer } from '../di/AppContainer.ets';
import type { Storage } from '@amber/deepread-domain';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const INK4: string = '#B6B1A4';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';
const ACCENT_LIGHT: string = '#FFF5F0';

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
      // ── 顶栏:品牌(衬线) + 返回 ──
      Row() {
        Text('←').fontSize(18).fontColor(INK3)
          .onClick(() => { router.back(); })
        Text('设置')
          .fontSize(20).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif')
          .margin({ left: 12 })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
      .backgroundColor(BG)

      Scroll() {
        Column() {
          // ── AI 配置分组 ──
          Text('AI 配置').fontSize(15).fontWeight(FontWeight.Bold).fontColor(INK2)
            .margin({ top: 8, bottom: 8 }).width('100%')

          Column() {
            Text('API Base URL').fontSize(12).fontColor(INK3).margin({ bottom: 4 }).width('100%')
            TextInput({ text: this.aiBaseUrl })
              .height(44).fontSize(14).fontColor(INK)
              .backgroundColor(SURFACE2).borderRadius(10)
              .onChange((v: string) => { this.aiBaseUrl = v; })

            Text('API Key').fontSize(12).fontColor(INK3).margin({ top: 16, bottom: 4 }).width('100%')
            TextInput({ text: this.aiKey, placeholder: 'sk-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .onChange((v: string) => { this.aiKey = v; })

            Text('模型名称').fontSize(12).fontColor(INK3).margin({ top: 16, bottom: 4 }).width('100%')
            TextInput({ text: this.model, placeholder: 'deepseek-chat' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .onChange((v: string) => { this.model = v; })
          }
          .width('100%').padding(16).backgroundColor(SURFACE).borderRadius(12)
          .border({ width: 1, color: LINE })

          // ── 搜索配置分组 ──
          Text('搜索配置').fontSize(15).fontWeight(FontWeight.Bold).fontColor(INK2)
            .margin({ top: 24, bottom: 8 }).width('100%')

          Column() {
            Text('Tavily API Key(可选)').fontSize(12).fontColor(INK3).margin({ bottom: 4 }).width('100%')
            TextInput({ text: this.tavilyKey, placeholder: 'tvly-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .onChange((v: string) => { this.tavilyKey = v; })

            Text('不配 Tavily 也能用,NewsNow 国内热榜源始终可用。')
              .fontSize(11).fontColor(INK4).margin({ top: 10 }).width('100%')
          }
          .width('100%').padding(16).backgroundColor(SURFACE).borderRadius(12)
          .border({ width: 1, color: LINE })

          // ── 保存按钮:accent 大按钮 ──
          Column() {
            Text('保存').fontSize(15).fontWeight(FontWeight.Medium).fontColor('#FFFFFF')
          }
          .width('100%').height(48).borderRadius(12)
          .backgroundColor(ACCENT)
          .justifyContent(FlexAlign.Center)
          .margin({ top: 28 })
          .onClick(() => { this.save(); })

          if (this.saved.length > 0) {
            Text(this.saved).fontSize(13).fontColor(ACCENT).margin({ top: 10 })
          }

          // 底部留白
          Column() {}.height(40)
        }
        .width('88%')
        .alignItems(HorizontalAlign.Start)
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
