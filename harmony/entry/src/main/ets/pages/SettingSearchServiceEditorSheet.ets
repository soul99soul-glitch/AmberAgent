// SettingSearchServiceEditorSheet — 搜索服务编辑页
// 编辑单个搜索 provider: name / apiKey / endpoint / enabled / maxResults
// 占位持久化(本地草稿);后续接入 chat-domain 搜索服务后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface SearchServiceDraft {
  id: string;
  name: string;
  apiKey: string;
  endpoint: string;
  enabled: boolean;
  maxResults: number;
}

function makeDraft(id: string, name: string, apiKey: string, endpoint: string, enabled: boolean, maxResults: number): SearchServiceDraft {
  return { id: id, name: name, apiKey: apiKey, endpoint: endpoint, enabled: enabled, maxResults: maxResults };
}

function defaultDraft(): SearchServiceDraft {
  return makeDraft('', '', '', '', true, 5);
}

function placeholderLoadDraft(): Promise<SearchServiceDraft> {
  return Promise.resolve(defaultDraft());
}

@Entry
@Component
struct SettingSearchServiceEditorSheet {
  @State draft: SearchServiceDraft = defaultDraft();
  @State loaded: boolean = false;
  @State savedMsg: string = '';

  aboutToAppear(): void {
    placeholderLoadDraft().then((d: SearchServiceDraft): void => {
      this.draft = d;
      this.loaded = true;
    });
  }

  private setName(v: string): void {
    const c: SearchServiceDraft = this.draft;
    this.draft = makeDraft(c.id, v, c.apiKey, c.endpoint, c.enabled, c.maxResults);
  }

  private setApiKey(v: string): void {
    const c: SearchServiceDraft = this.draft;
    this.draft = makeDraft(c.id, c.name, v, c.endpoint, c.enabled, c.maxResults);
  }

  private setEndpoint(v: string): void {
    const c: SearchServiceDraft = this.draft;
    this.draft = makeDraft(c.id, c.name, c.apiKey, v, c.enabled, c.maxResults);
  }

  private setEnabled(v: boolean): void {
    const c: SearchServiceDraft = this.draft;
    this.draft = makeDraft(c.id, c.name, c.apiKey, c.endpoint, v, c.maxResults);
  }

  private setMaxResults(v: string): void {
    const n: number = parseInt(v, 10);
    const value: number = isNaN(n) ? 0 : n;
    const c: SearchServiceDraft = this.draft;
    this.draft = makeDraft(c.id, c.name, c.apiKey, c.endpoint, c.enabled, value);
  }

  private save(): void {
    // 占位保存:接入 chat-domain 后替换为 saveSearchService(getChatKvStore(), this.draft)
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

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: () => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      AmberSwitch({
        checked: checked,
        switchSize: SwitchSize.Small,
        onCheckedChange: (_c: boolean): void => { onToggle(); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('搜索服务').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('基本信息')

            Column() {
              FlatTextField({
                value: this.draft.name, label: '服务名称', placeholder: 'Exa Search',
                onChange: (v: string): void => { this.setName(v); },
              })

              FlatTextField({
                value: this.draft.endpoint, label: 'API Endpoint', placeholder: 'https://api.exa.ai/search',
                onChange: (v: string): void => { this.setEndpoint(v); },
              }).margin({ top: 12 })

              this.toggleRow('启用此服务', '启用后可在搜索源列表中选择', this.draft.enabled,
                (): void => { this.setEnabled(!this.draft.enabled); })
            }
            .width('100%').padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)

            this.sectionHeader('凭证')

            Column() {
              FlatTextField({
                value: this.draft.apiKey, label: 'API Key', placeholder: 'sk-xxxxxxxx',
                mono: true, obscure: true,
                supportingText: '密钥仅存储在本地,不会上传。',
                onChange: (v: string): void => { this.setApiKey(v); },
              })
            }
            .width('100%').padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)

            this.sectionHeader('检索参数')

            Column() {
              FlatTextField({
                value: `${this.draft.maxResults}`, label: '最大结果数', placeholder: '5',
                onChange: (v: string): void => { this.setMaxResults(v); },
              })
              Text('每次搜索返回的结果条数(1-20)').fontSize(LABEL).fontColor(INK3)
                .margin({ top: 6, left: 2 })
            }
            .width('100%').padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)

            Row() {
              Text('测试连接').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => {
                  this.savedMsg = '连接成功(占位)';
                  setTimeout((): void => { this.savedMsg = ''; }, 1500);
                })
              Blank()
              if (this.savedMsg.length > 0) {
                Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
              }
            }
            .width('100%').margin({ top: 12, bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
