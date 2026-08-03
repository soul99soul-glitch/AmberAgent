// SettingModelPage — 模型管理页
// 维护可用模型列表:名称 / 上下文窗口 / 最大输出 tokens / 采样温度
// 占位持久化(本地草稿);后续接入 chat-domain 模型库后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';

interface ModelItem {
  id: string;
  name: string;
  contextWindow: number;
  maxOutputTokens: number;
  temperature: number;
}

function makeModel(id: string, name: string, contextWindow: number, maxOutputTokens: number, temperature: number): ModelItem {
  return {
    id: id, name: name, contextWindow: contextWindow,
    maxOutputTokens: maxOutputTokens, temperature: temperature,
  };
}

function placeholderLoadModels(): Promise<ModelItem[]> {
  const seed: ModelItem[] = [
    makeModel('m-1', 'gpt-4o', 128000, 16384, 0.7),
    makeModel('m-2', 'claude-3.5-sonnet', 200000, 8192, 0.6),
    makeModel('m-3', 'gemini-1.5-pro', 1000000, 8192, 0.9),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingModelPage {
  @State models: ModelItem[] = [];
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  private counter: number = 100;

  aboutToAppear(): void {
    placeholderLoadModels().then((list: ModelItem[]): void => {
      this.models = list;
      this.loaded = true;
    });
  }

  private nextId(): string {
    this.counter += 1;
    return `m-${this.counter}`;
  }

  private addModel(): void {
    const id: string = this.nextId();
    const fresh: ModelItem = makeModel(id, '', 8192, 4096, 0.7);
    this.models = [...this.models, fresh];
  }

  private removeModel(id: string): void {
    this.models = this.models.filter((m: ModelItem): boolean => m.id !== id);
  }

  private setName(id: string, name: string): void {
    this.models = this.models.map((m: ModelItem): ModelItem => {
      if (m.id !== id) return m;
      return makeModel(m.id, name, m.contextWindow, m.maxOutputTokens, m.temperature);
    });
  }

  private setContextWindow(id: string, v: string): void {
    const n: number = parseInt(v, 10);
    const value: number = isNaN(n) ? 0 : n;
    this.models = this.models.map((m: ModelItem): ModelItem => {
      if (m.id !== id) return m;
      return makeModel(m.id, m.name, value, m.maxOutputTokens, m.temperature);
    });
  }

  private setMaxOutput(id: string, v: string): void {
    const n: number = parseInt(v, 10);
    const value: number = isNaN(n) ? 0 : n;
    this.models = this.models.map((m: ModelItem): ModelItem => {
      if (m.id !== id) return m;
      return makeModel(m.id, m.name, m.contextWindow, value, m.temperature);
    });
  }

  private setTemperature(id: string, v: string): void {
    const n: number = parseFloat(v);
    const value: number = isNaN(n) ? 0 : n;
    this.models = this.models.map((m: ModelItem): ModelItem => {
      if (m.id !== id) return m;
      return makeModel(m.id, m.name, m.contextWindow, m.maxOutputTokens, value);
    });
  }

  private save(): void {
    // 占位保存:接入 chat-domain 后替换为 saveModels(getChatKvStore(), this.models)
    this.savedMsg = '已保存';
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.save();
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 完成
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('模型管理').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('模型列表')

            if (this.models.length === 0) {
              Text('暂无模型,点下方添加').fontSize(BODY).fontColor(INK3)
                .padding({ top: 16, bottom: 16 })
            }

            ForEach(this.models, (m: ModelItem): void => {
              Column() {
                Row() {
                  Text(m.name.length > 0 ? m.name : '未命名模型')
                    .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                    .layoutWeight(1)
                  Text('删除').fontSize(META).fontColor('#C0392B')
                    .padding({ left: 8, right: 4, top: 4, bottom: 4 })
                    .onClick((): void => { this.removeModel(m.id); })
                }
                .width('100%').margin({ bottom: 10 })

                FlatTextField({
                  value: m.name, label: '模型名称', placeholder: 'gpt-4o',
                  onChange: (v: string): void => { this.setName(m.id, v); },
                })
                FlatTextField({
                  value: `${m.contextWindow}`, label: '上下文窗口 (tokens)', placeholder: '8192',
                  onChange: (v: string): void => { this.setContextWindow(m.id, v); },
                }).margin({ top: 8 })
                FlatTextField({
                  value: `${m.maxOutputTokens}`, label: '最大输出 tokens', placeholder: '4096',
                  onChange: (v: string): void => { this.setMaxOutput(m.id, v); },
                }).margin({ top: 8 })
                FlatTextField({
                  value: `${m.temperature}`, label: '采样温度', placeholder: '0.7',
                  onChange: (v: string): void => { this.setTemperature(m.id, v); },
                }).margin({ top: 8 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding(12).backgroundColor(SURFACE).borderRadius(12)
              .border({ width: 1, color: LINE }).margin({ bottom: 12 })
            }, (m: ModelItem): string => m.id)

            Row() {
              Text('+ 添加模型').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => { this.addModel(); })
              Blank()
              if (this.savedMsg.length > 0) {
                Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
              }
            }
            .width('100%').margin({ top: 8, bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
