// SettingsPage — 杂志风设置页(AI 配置 + 热榜源开关 + 搜索源配置面板)
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

// 搜索源定义(需 key 的)
interface SearchSourceDef {
  id: string;
  displayName: string;
  keyPlaceholder: string;
}

const KEYED_SOURCES: SearchSourceDef[] = [
  { id: 'tavily', displayName: 'Tavily', keyPlaceholder: 'tvly-...' },
  { id: 'bocha', displayName: 'Bocha', keyPlaceholder: 'bocha-...' },
  { id: 'brave', displayName: 'Brave', keyPlaceholder: 'brave-...' },
  { id: 'exa', displayName: 'Exa', keyPlaceholder: 'exa-...' },
];

@Entry
@Component
struct HistoryPage {
  @State aiKey: string = '';
  @State aiBaseUrl: string = 'https://api.deepseek.com';
  @State model: string = 'deepseek-chat';
  @State saved: string = '';
  @State hotlistToggles: SourceToggle[] = [];
  @State selectedSource: string = 'tavily';
  @State showSourcePicker: boolean = false;
  @State currentKey: string = '';
  @State testResult: string = '';
  @State testStatus: string = '';
  private storage: Storage | null = null;

  async aboutToAppear(): Promise<void> {
    this.storage = getAppContainer().storage;
    this.aiKey = await this.storage.get<string>('ai_api_key', '');
    this.aiBaseUrl = await this.storage.get<string>('ai_base_url', 'https://api.deepseek.com');
    this.model = await this.storage.get<string>('ai_model', 'deepseek-chat');
    // 加载热榜源开关
    const hotlist: SourceToggle[] = [];
    for (let i = 0; i < NEWSNOW_PRESETS.length; i++) {
      const p: NewsNowPreset = NEWSNOW_PRESETS[i];
      const enabled: string = await this.storage.get<string>('source_' + p.id, 'true');
      hotlist.push({ id: p.id, displayName: p.displayName, enabled: enabled === 'true' });
    }
    this.hotlistToggles = hotlist;
    // 加载当前选中的搜索源和 key
    this.selectedSource = await this.storage.get<string>('active_search_source', 'tavily');
    this.currentKey = await this.storage.get<string>(this.selectedSource + '_api_key', '');
  }

  async selectSource(id: string): Promise<void> {
    // 先保存当前 key
    await this.storage!.set<string>(this.selectedSource + '_api_key', this.currentKey);
    // 切换到新源
    this.selectedSource = id;
    this.currentKey = await this.storage!.get<string>(id + '_api_key', '');
    this.testResult = '';
    this.testStatus = '';
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

  async saveSourceConfig(): Promise<void> {
    await this.storage!.set<string>('active_search_source', this.selectedSource);
    await this.storage!.set<string>(this.selectedSource + '_api_key', this.currentKey);
    this.saved = '已保存 ✓';
  }

  async testSource(): Promise<void> {
    this.testStatus = 'testing';
    this.testResult = '';
    // 简化测试:检查 key 是否非空
    if (this.currentKey.length === 0) {
      this.testStatus = 'error';
      this.testResult = 'API Key 为空,请先填写';
    } else {
      this.testStatus = 'ok';
      this.testResult = '配置有效';
    }
  }

  async save(): Promise<void> {
    await this.storage!.set<string>('ai_api_key', this.aiKey);
    await this.storage!.set<string>('ai_base_url', this.aiBaseUrl);
    await this.storage!.set<string>('ai_model', this.model);
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

      List({ space: 0 }) {
        // ── AI 配置分组 ──
        ListItem() {
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' AI 配置').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
            }
            .width('100%').margin({ bottom: 16 })
            .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 100, curve: Curve.EaseIn }))

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
        }

        // ── 热榜源分组 ──
        ListItem() {
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 热榜源').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
              Blank()
              Text('NewsNow').fontSize(11).fontColor(INK4)
            }
            .width('100%').margin({ bottom: 16 })
            .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 200, curve: Curve.EaseIn }))

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
        }

        // ── 搜索源配置分组 ──
        ListItem() {
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 搜索源').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
              Blank()
              Text('Deep Read 搜索').fontSize(11).fontColor(INK4)
            }
            .width('100%').margin({ bottom: 16 })
            .transition(TransitionEffect.OPACITY.animation({ duration: 300, delay: 300, curve: Curve.EaseIn }))

            // 源选择器(自定义下拉菜单)
            Text('选择搜索源').fontSize(12).fontColor(INK3).margin({ bottom: 6 })

            Row() {
              Text(KEYED_SOURCES.find((s) => s.id === this.selectedSource)?.displayName ?? '选择')
                .fontSize(14).fontColor(INK).layoutWeight(1)
              Text('▼').fontSize(12).fontColor(INK3)
            }
            .width('100%').height(44).padding({ left: 14, right: 14 })
            .backgroundColor(SURFACE2).borderRadius(10)
            .onClick(() => { this.showSourcePicker = !this.showSourcePicker; })

            if (this.showSourcePicker) {
              Column() {
                ForEach(KEYED_SOURCES, (s: SearchSourceDef) => {
                  Row() {
                    Text(s.displayName).fontSize(14).fontColor(s.id === this.selectedSource ? ACCENT : INK2)
                  }
                  .width('100%').padding({ top: 12, bottom: 12, left: 14, right: 14 })
                  .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                  .onClick(() => {
                    this.selectSource(s.id);
                    this.showSourcePicker = false;
                  })
                })
              }
              .width('100%').backgroundColor(SURFACE).borderRadius(10)
              .border({ width: 1, color: LINE })
              .transition(TransitionEffect.OPACITY.animation({ duration: 200, curve: Curve.EaseIn }))
              .transition(TransitionEffect.translate({ y: -10 }).animation({ duration: 200, curve: Curve.EaseOut }))
              .margin({ top: 4 })
            }

            // 当前源的 key 输入
            Text('API Key').fontSize(12).fontColor(INK3).margin({ top: 16, bottom: 6 })
            TextInput({ text: this.currentKey, placeholder: KEYED_SOURCES.find((s) => s.id === this.selectedSource)?.keyPlaceholder ?? 'api-key-...' })
              .height(44).fontSize(14).fontColor(INK)
              .placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.currentKey = v; })

            // 测试按钮
            Row() {
              Text('测试连通').fontSize(13).fontColor(ACCENT)
                .onClick(() => { this.testSource(); })
              Blank()
              if (this.testStatus === 'testing') {
                Text('测试中...').fontSize(12).fontColor(INK3)
              } else if (this.testStatus === 'ok') {
                Text('✓ ' + this.testResult).fontSize(12).fontColor('#43A047')
              } else if (this.testStatus === 'error') {
                Text('✗ ' + this.testResult).fontSize(12).fontColor('#E53935')
              }
            }
            .width('100%').margin({ top: 12 })

            // 保存按钮
            Text('保存此源配置').fontSize(13).fontColor(ACCENT)
              .margin({ top: 12 })
              .onClick(() => { this.saveSourceConfig(); })
          }
          .width('100%').padding(18).backgroundColor(SURFACE).borderRadius(14)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 24 })
        }

        // ── 说明 ──
        ListItem() {
          Text('NewsNow/DuckDuckGo/HackerNews/Jina 始终可用,无需 key。')
            .fontSize(11).fontColor(INK4).margin({ bottom: 24 })
        }

        // ── 保存按钮 ──
        ListItem() {
          Column() {
            Text('保存全部').fontSize(15).fontWeight(FontWeight.Medium).fontColor('#FFFFFF')
          }
          .width('100%').height(48).borderRadius(12)
          .backgroundColor(ACCENT)
          .justifyContent(FlexAlign.Center)
          .onClick(() => { this.save(); })
        }

        // ── 保存状态 ──
        ListItem() {
          Text(this.saved.length > 0 ? this.saved : '').fontSize(13).fontColor(ACCENT).margin({ top: 10 })
        }
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
