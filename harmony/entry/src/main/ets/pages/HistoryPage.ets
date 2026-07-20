// SettingsPage — 杂志风设置页(AI 配置 + 热榜源开关 + 搜索源开关 + 搜索配置)
import { router } from '@kit.ArkUI';
import { getAppContainer } from '../di/AppContainer.ets';
import type { Storage } from '@amber/deepread-domain';
import { NEWSNOW_PRESETS } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowPreset } from '../platform_impl/NewsNowProvider.ets';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const INK4: string = '#B6B1A4';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';

interface SourceToggle {
  id: string;
  displayName: string;
  enabled: boolean;
}

// 搜索源定义(对照 Android SearchService)
interface SearchSourceDef {
  id: string;
  displayName: string;
  needKey: boolean;
}

const SEARCH_SOURCES: SearchSourceDef[] = [
  { id: 'newsnow', displayName: 'NewsNow 国内热榜', needKey: false },
  { id: 'duckduckgo', displayName: 'DuckDuckGo', needKey: false },
  { id: 'hackernews', displayName: 'Hacker News', needKey: false },
  { id: 'jina', displayName: 'Jina AI', needKey: false },
  { id: 'tavily', displayName: 'Tavily', needKey: true },
  { id: 'bocha', displayName: 'Bocha(需 key)', needKey: true },
  { id: 'brave', displayName: 'Brave(需 key)', needKey: true },
  { id: 'exa', displayName: 'Exa(需 key)', needKey: true },
  { id: 'searxng', displayName: 'SearXNG(自建)', needKey: false },
];

@Entry
@Component
struct HistoryPage {
  @State aiKey: string = '';
  @State aiBaseUrl: string = 'https://api.deepseek.com';
  @State tavilyKey: string = '';
  @State model: string = 'deepseek-chat';
  @State saved: string = '';
  @State hotlistToggles: SourceToggle[] = [];
  @State searchToggles: SourceToggle[] = [];
  @State bochaKey: string = '';
  @State braveKey: string = '';
  @State exaKey: string = '';
  @State searxUrl: string = '';
  private storage: Storage | null = null;

  async aboutToAppear(): Promise<void> {
    this.storage = getAppContainer().storage;
    this.aiKey = await this.storage.get<string>('ai_api_key', '');
    this.aiBaseUrl = await this.storage.get<string>('ai_base_url', 'https://api.deepseek.com');
    this.tavilyKey = await this.storage.get<string>('tavily_api_key', '');
    this.model = await this.storage.get<string>('ai_model', 'deepseek-chat');
    this.bochaKey = await this.storage.get<string>('bocha_api_key', '');
    this.braveKey = await this.storage.get<string>('brave_api_key', '');
    this.exaKey = await this.storage.get<string>('exa_api_key', '');
    this.searxUrl = await this.storage.get<string>('searxng_base_url', '');
    // 热榜源开关
    const hotlist: SourceToggle[] = [];
    for (let i = 0; i < NEWSNOW_PRESETS.length; i++) {
      const p: NewsNowPreset = NEWSNOW_PRESETS[i];
      const enabled: string = await this.storage.get<string>('source_' + p.id, 'true');
      hotlist.push({ id: p.id, displayName: p.displayName, enabled: enabled === 'true' });
    }
    this.hotlistToggles = hotlist;
    // 搜索源开关
    const search: SourceToggle[] = [];
    for (let i = 0; i < SEARCH_SOURCES.length; i++) {
      const s = SEARCH_SOURCES[i];
      const enabled: string = await this.storage.get<string>('search_' + s.id, 'true');
      search.push({ id: s.id, displayName: s.displayName, enabled: enabled === 'true' });
    }
    this.searchToggles = search;
  }

  async toggleHotlist(id: string): Promise<void> {
    const newToggles: SourceToggle[] = [];
    for (let i = 0; i < this.hotlistToggles.length; i++) {
      const t: SourceToggle = this.hotlistToggles[i];
      if (t.id === id) {
        const newEnabled: boolean = !t.enabled;
        newToggles.push({ id: t.id, displayName: t.displayName, enabled: newEnabled });
        await this.storage!.set<string>('source_' + id, newEnabled ? 'true' : 'false');
      } else {
        newToggles.push(t);
      }
    }
    this.hotlistToggles = newToggles;
  }

  async toggleSearch(id: string): Promise<void> {
    const newToggles: SourceToggle[] = [];
    for (let i = 0; i < this.searchToggles.length; i++) {
      const t: SourceToggle = this.searchToggles[i];
      if (t.id === id) {
        const newEnabled: boolean = !t.enabled;
        newToggles.push({ id: t.id, displayName: t.displayName, enabled: newEnabled });
        await this.storage!.set<string>('search_' + id, newEnabled ? 'true' : 'false');
      } else {
        newToggles.push(t);
      }
    }
    this.searchToggles = newToggles;
  }

  async save(): Promise<void> {
    await this.storage!.set<string>('ai_api_key', this.aiKey);
    await this.storage!.set<string>('ai_base_url', this.aiBaseUrl);
    await this.storage!.set<string>('tavily_api_key', this.tavilyKey);
    await this.storage!.set<string>('ai_model', this.model);
    await this.storage!.set<string>('bocha_api_key', this.bochaKey);
    await this.storage!.set<string>('brave_api_key', this.braveKey);
    await this.storage!.set<string>('exa_api_key', this.exaKey);
    await this.storage!.set<string>('searxng_base_url', this.searxUrl);
    this.saved = '已保存 ✓';
  }

  build(): void {
    Column() {
      // ── 顶栏:返回 + 衬线标题 ──
      Row() {
        Text('←').fontSize(18).fontColor(INK3)
          .onClick(() => { router.back(); })
        Text('设置')
          .fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif')
          .margin({ left: 12 })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
      .backgroundColor(BG)

      Scroll() {
        Column() {
          // ── AI 配置分组 ──
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' AI 配置').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
            }
            .width('100%').margin({ bottom: 16 })

            Text('API Base URL').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.aiBaseUrl })
              .height(44).fontSize(14).fontColor(INK)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.aiBaseUrl = v; })
              .margin({ bottom: 20 })

            Text('API Key').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.aiKey, placeholder: 'sk-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.aiKey = v; })
              .margin({ bottom: 20 })

            Text('模型名称').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.model, placeholder: 'deepseek-chat' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.model = v; })
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 24 })

          // ── 热榜源分组 ──
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 热榜源').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
              Blank()
              Text('NewsNow').fontSize(11).fontColor(INK4)
            }
            .width('100%').margin({ bottom: 16 })

            ForEach(this.hotlistToggles, (t: SourceToggle) => {
              Row() {
                Text(t.displayName).fontSize(14).fontColor(INK2).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: t.enabled })
                  .selectedColor(ACCENT)
                  .onChange(() => { this.toggleHotlist(t.id); })
              }
              .width('100%').padding({ top: 12, bottom: 12 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            })
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 24 })

          // ── 搜索源分组 ──
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 搜索源').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
              Blank()
              Text('Deep Read 搜索').fontSize(11).fontColor(INK4)
            }
            .width('100%').margin({ bottom: 16 })

            ForEach(this.searchToggles, (t: SourceToggle) => {
              Row() {
                Text(t.displayName).fontSize(14).fontColor(INK2).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: t.enabled })
                  .selectedColor(ACCENT)
                  .onChange(() => { this.toggleSearch(t.id); })
              }
              .width('100%').padding({ top: 12, bottom: 12 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            })
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 24 })

          // ── 搜索配置分组 ──
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 搜索配置').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
            }
            .width('100%').margin({ bottom: 16 })

            Text('Tavily API Key(可选)').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.tavilyKey, placeholder: 'tvly-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.tavilyKey = v; })
              .margin({ bottom: 20 })

            Text('Bocha API Key(可选)').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.bochaKey, placeholder: 'bocha-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.bochaKey = v; })
              .margin({ bottom: 20 })

            Text('Brave API Key(可选)').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.braveKey, placeholder: 'brave-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.braveKey = v; })
              .margin({ bottom: 20 })

            Text('Exa API Key(可选)').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.exaKey, placeholder: 'exa-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.exaKey = v; })
              .margin({ bottom: 20 })

            Text('SearXNG Base URL(自建)').fontSize(12).fontColor(INK3).margin({ bottom: 6 })
            TextInput({ text: this.searxUrl, placeholder: 'https://searx.example.com' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.searxUrl = v; })

            Text('不配 Tavily 也能用,NewsNow/DuckDuckGo/HackerNews/Jina 始终可用。')
              .fontSize(11).fontColor(INK4).margin({ top: 12 })
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 28 })

          // ── 保存按钮 ──
          Column() {
            Text('保存').fontSize(15).fontWeight(FontWeight.Medium).fontColor('#FFFFFF')
          }
          .width('100%').height(48).borderRadius(12)
          .backgroundColor(ACCENT)
          .justifyContent(FlexAlign.Center)
          .onClick(() => { this.save(); })

          if (this.saved.length > 0) {
            Text(this.saved).fontSize(13).fontColor(ACCENT).margin({ top: 10 })
          }

          Column() {}.height(40)
        }
        .width('90%')
        .alignItems(HorizontalAlign.Start)
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
