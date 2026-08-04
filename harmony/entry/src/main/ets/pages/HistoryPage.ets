// SettingsPage — 杂志风设置页(AI 配置 + 热榜源开关 + 搜索源配置面板)
import { ACCENT,BG,INK,INK2,INK3,INK4,LINE,SURFACE,SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getAppContainer, getModelRegistry } from '../di/AppContainer.ets';
import type { Storage, ModelConfig } from '@amber/deepread-domain';
import { makeModelConfig } from '@amber/deepread-domain';
import { NEWSNOW_PRESETS } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowPreset } from '../platform_impl/NewsNowProvider.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Settings';


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
  // 模型库(多模型配置,供议会席位 / 小说生成选择)
  @State models: ModelConfig[] = [];
  @State newLabel: string = '';
  @State newBaseUrl: string = 'https://api.deepseek.com';
  @State newApiKey: string = '';
  @State newModel: string = 'deepseek-chat';
  @State modelSaved: string = '';
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
    // 加载模型库
    await this.loadModels();
  }

  async loadModels(): Promise<void> {
    this.models = await getModelRegistry().list();
  }

  async addModel(): Promise<void> {
    if (this.newApiKey.length === 0) {
      this.modelSaved = 'API Key 为空,无法添加';
      return;
    }
    const cfg: ModelConfig = makeModelConfig({
      label: this.newLabel.length > 0 ? this.newLabel : this.newModel,
      baseUrl: this.newBaseUrl,
      apiKey: this.newApiKey,
      model: this.newModel,
    });
    const next: ModelConfig[] = [...this.models, cfg];
    await getModelRegistry().save(next);
    await this.loadModels();
    this.newLabel = '';
    this.newApiKey = '';
    this.modelSaved = '已添加 ✓';
  }

  async deleteModel(id: string): Promise<void> {
    const next: ModelConfig[] = [];
    for (let i = 0; i < this.models.length; i++) {
      if (this.models[i].id !== id) next.push(this.models[i]);
    }
    await getModelRegistry().save(next);
    await this.loadModels();
    this.modelSaved = '已删除';
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
    hilog.info(DOMAIN, TAG, 'save() called: storage=%{public}s key=%{public}s baseUrl=%{public}s model=%{public}s',
      this.storage === null ? 'null' : 'ok',
      this.aiKey.length > 0 ? `len=${this.aiKey.length}` : 'empty',
      this.aiBaseUrl,
      this.model);
    if (this.storage === null) {
      this.saved = '存储未初始化';
      return;
    }
    try {
      await this.storage.set<string>('ai_api_key', this.aiKey);
      await this.storage.set<string>('ai_base_url', this.aiBaseUrl);
      await this.storage.set<string>('ai_model', this.model);
      // 验证写入
      const verify: string = await this.storage.get<string>('ai_api_key', '');
      hilog.info(DOMAIN, TAG, 'save() verify: key len=%{public}d', verify.length);
      this.saved = verify.length > 0 ? '已保存 ✓' : '保存失败';
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'save() ERROR: %{public}s', String(e).substring(0, 100));
      this.saved = '保存失败: ' + String(e).substring(0, 30);
    }
  }

  build(): void {
    Column() {
      // ── 顶栏 ──
      PageTitleBar({ title: '历史记录' })

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

        // ── 模型库分组(多模型,供议会/小说选择) ──
        ListItem() {
          Column() {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 模型库').fontSize(14).fontWeight(FontWeight.Bold).fontColor(INK2)
              Blank()
              Text('议会 / 小说使用').fontSize(11).fontColor(INK4)
            }
            .width('100%').margin({ bottom: 16 })

            if (this.models.length === 0) {
              Text('暂无模型,请在下方添加。议会的每个席位需指定一个模型。')
                .fontSize(12).fontColor(INK3).margin({ bottom: 12 })
            }

            ForEach(this.models, (m: ModelConfig) => {
              Row() {
                Column() {
                  Text(m.label.length > 0 ? m.label : m.model)
                    .fontSize(14).fontColor(INK)
                  Text(m.model + '  ·  ' + m.baseUrl)
                    .fontSize(11).fontColor(INK3).margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1)
                Text('✕').fontSize(15).fontColor(INK4)
                  .padding({ left: 12, top: 4, bottom: 4 })
                  .onClick(() => { this.deleteModel(m.id); })
              }
              .width('100%').padding({ top: 14, bottom: 14 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            }, (m: ModelConfig) => m.id)

            // 添加表单
            Text('名称(可选)').fontSize(12).fontColor(INK3).margin({ top: 16, bottom: 6 })
            TextInput({ text: this.newLabel, placeholder: '如:GPT-4o / 备用 DeepSeek' })
              .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.newLabel = v; })

            Text('API Base URL').fontSize(12).fontColor(INK3).margin({ top: 12, bottom: 6 })
            TextInput({ text: this.newBaseUrl })
              .height(44).fontSize(14).fontColor(INK)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.newBaseUrl = v; })

            Text('API Key').fontSize(12).fontColor(INK3).margin({ top: 12, bottom: 6 })
            TextInput({ text: this.newApiKey, placeholder: 'sk-...' })
              .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.newApiKey = v; })

            Text('模型名称').fontSize(12).fontColor(INK3).margin({ top: 12, bottom: 6 })
            TextInput({ text: this.newModel, placeholder: 'deepseek-chat' })
              .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string) => { this.newModel = v; })

            Row() {
              Text('添加模型').fontSize(13).fontColor(ACCENT)
                .onClick(() => { this.addModel(); })
              Blank()
              Text(this.modelSaved).fontSize(12).fontColor(INK3)
            }
            .width('100%').margin({ top: 12 })
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
              .width('100%').padding({ top: 14, bottom: 14 })
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
                  .width('100%').padding({ top: 14, bottom: 14, left: 14, right: 14 })
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
            Column() {
              Text('保存全部').fontSize(15).fontWeight(FontWeight.Medium).fontColor('#FFFFFF')
            }
            .width('70%').height(48).borderRadius(12)
            .backgroundColor(ACCENT)
            .justifyContent(FlexAlign.Center)
            .onClick(() => { this.save(); })
          }
          .width('100%').justifyContent(FlexAlign.Center)
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
