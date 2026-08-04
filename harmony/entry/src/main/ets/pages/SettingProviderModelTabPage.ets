// SettingProviderModelTabPage — Provider 模型 Tab 页
// 两个 tab:模型(列表 + 选择 + 增删) / 高级(占位开关)
// 占位持久化;接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, ERROR } from '../design/tokens.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface ModelRow {
  id: string;
  label: string;
}

function makeRow(id: string, label: string): ModelRow {
  return { id: id, label: label };
}

function placeholderLoadRows(): Promise<ModelRow[]> {
  const seed: ModelRow[] = [
    makeRow('gpt-4o', 'gpt-4o'),
    makeRow('gpt-4o-mini', 'gpt-4o-mini'),
    makeRow('o1-mini', 'o1-mini'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingProviderModelTabPage {
  @State rows: ModelRow[] = [];
  @State selectedId: string = '';
  @State activeTab: number = 0; // 0 = 模型, 1 = 高级
  @State newModel: string = '';
  @State stream: boolean = true;
  @State caching: boolean = false;
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadRows().then((list: ModelRow[]): void => {
      this.rows = list;
      if (list.length > 0) this.selectedId = list[0].id;
      this.loaded = true;
    });
  }

  private selectTab(i: number): void {
    this.activeTab = i;
  }

  private selectModel(id: string): void {
    this.selectedId = id;
  }

  private addModel(): void {
    const id: string = this.newModel.trim();
    if (id.length === 0) return;
    if (this.rows.findIndex((r: ModelRow): boolean => r.id === id) >= 0) {
      this.newModel = '';
      return;
    }
    this.rows = [...this.rows, makeRow(id, id)];
    this.selectedId = id;
    this.newModel = '';
  }

  private removeModel(id: string): void {
    const next: ModelRow[] = this.rows.filter((r: ModelRow): boolean => r.id !== id);
    this.rows = next;
    if (this.selectedId === id) {
      this.selectedId = next.length > 0 ? next[0].id : '';
    }
  }

  private done(): void {
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder tabSegment(label: string, index: number): void {
    Text(label)
      .fontSize(BODY).fontColor(this.activeTab === index ? ACCENT : INK3)
      .fontWeight(this.activeTab === index ? FontWeight.Medium : FontWeight.Normal)
      .layoutWeight(1)
      .textAlign(TextAlign.Center)
      .padding({ top: 10, bottom: 10 })
      .border({ width: { bottom: this.activeTab === index ? 2 : 0 }, color: { bottom: ACCENT } })
      .onClick((): void => { this.selectTab(index); })
  }

  @Builder advancedToggle(label: string, desc: string, checked: boolean, onToggle: (v: boolean) => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      AmberSwitch({
        checked: checked, switchSize: SwitchSize.Small,
        onCheckedChange: (v: boolean): void => { onToggle(v); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 完成
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('Provider 模型').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      // Tab 栏
      Row() {
        this.tabSegment('模型', 0)
        this.tabSegment('高级', 1)
      }
      .width('100%').backgroundColor(SURFACE)
      .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.activeTab === 0) {
        // 模型 tab
        Scroll() {
          Column() {
            this.sectionHeader('可用模型')

            if (this.rows.length === 0) {
              Text('暂无模型,在下方输入 modelId 添加').fontSize(META).fontColor(INK3)
                .padding({ top: 8, bottom: 12 })
            }

            ForEach(this.rows, (r: ModelRow): void => {
              Row() {
                Text(r.label).fontSize(BODY).fontColor(INK).layoutWeight(1)
                if (r.id === this.selectedId) {
                  Text('✓').fontSize(BODY).fontColor(ACCENT).margin({ right: 8 })
                }
                Text('移除').fontSize(META).fontColor('ERROR')
                  .padding({ left: 8, right: 4, top: 4, bottom: 4 })
                  .onClick((): void => { this.removeModel(r.id); })
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })
              .backgroundColor(r.id === this.selectedId ? SURFACE2 : SURFACE)
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              .onClick((): void => { this.selectModel(r.id); })
            }, (r: ModelRow): string => r.id)

            Row() {
              TextInput({ text: this.newModel, placeholder: 'modelId' })
                .fontSize(BODY).fontColor(INK)
                .backgroundColor(SURFACE2).borderRadius(8)
                .layoutWeight(1)
                .onChange((v: string): void => { this.newModel = v; })
              Text('添加').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 12, right: 12, top: 10, bottom: 10 })
                .onClick((): void => { this.addModel(); })
            }
            .width('100%').margin({ top: 12, bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      } else {
        // 高级 tab
        Scroll() {
          Column() {
            this.sectionHeader('推理选项')

            this.advancedToggle('流式输出', '逐 token 返回,首字延迟更低', this.stream,
              (v: boolean): void => { this.stream = v; })

            this.advancedToggle('Prompt 缓存', '复用前缀以降低成本(Provider 需支持)', this.caching,
              (v: boolean): void => { this.caching = v; })

            Column() {}.height(24)
            Text('模型级参数(温度 / topP 等)请在「模型设置」表单编辑')
              .fontSize(LABEL).fontColor(INK3).padding({ left: 16, right: 16 })
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
