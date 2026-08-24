// SettingExperimentalOfficeProPage — Office Pro 设置页
// 启用开关 + API Endpoint + API Key(掩码) + 自动保存开关
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface OfficeProSettings {
  enabled: boolean;
  endpoint: string;
  apiKey: string;
  autoSave: boolean;
}

function makeOfficePro(enabled: boolean, endpoint: string, apiKey: string, autoSave: boolean): OfficeProSettings {
  return { enabled: enabled, endpoint: endpoint, apiKey: apiKey, autoSave: autoSave };
}

function defaultOfficePro(): OfficeProSettings {
  return makeOfficePro(false, '', '', true);
}

function placeholderLoadOfficePro(): Promise<OfficeProSettings> {
  return Promise.resolve(defaultOfficePro());
}

@Entry
@Component
struct SettingExperimentalOfficeProPage {
  @State s: OfficeProSettings = defaultOfficePro();
  @State loaded: boolean = false;
  @State savedMsg: string = '';

  aboutToAppear(): void {
    placeholderLoadOfficePro().then((v: OfficeProSettings): void => {
      this.s = v;
      this.loaded = true;
    });
  }

  private setEnabled(v: boolean): void {
    const c: OfficeProSettings = this.s;
    this.s = makeOfficePro(v, c.endpoint, c.apiKey, c.autoSave);
  }

  private setEndpoint(v: string): void {
    const c: OfficeProSettings = this.s;
    this.s = makeOfficePro(c.enabled, v, c.apiKey, c.autoSave);
  }

  private setApiKey(v: string): void {
    const c: OfficeProSettings = this.s;
    this.s = makeOfficePro(c.enabled, c.endpoint, v, c.autoSave);
  }

  private setAutoSave(v: boolean): void {
    const c: OfficeProSettings = this.s;
    this.s = makeOfficePro(c.enabled, c.endpoint, c.apiKey, v);
  }

  private save(): void {
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
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('Office Pro').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('功能开关')

            this.toggleRow('启用 Office Pro', '开启文档/表格/幻灯片深度编辑能力', this.s.enabled,
              (): void => { this.setEnabled(!this.s.enabled); })

            this.sectionHeader('服务配置')

            Column() {
              FlatTextField({
                value: this.s.endpoint, label: 'API Endpoint', placeholder: 'https://api.officepro.example/v1',
                onChange: (v: string): void => { this.setEndpoint(v); },
              })

              FlatTextField({
                value: this.s.apiKey, label: 'API Key', placeholder: 'sk-xxxxxxxx',
                mono: true, obscure: true,
                supportingText: '密钥仅存储在本地,不会上传。',
                onChange: (v: string): void => { this.setApiKey(v); },
              }).margin({ top: 12 })
            }
            .width('100%').padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)

            this.sectionHeader('编辑选项')

            this.toggleRow('自动保存', '编辑文档时自动保存更改', this.s.autoSave,
              (): void => { this.setAutoSave(!this.s.autoSave); })

            Row() {
              Text('测试连接').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => {
                  this.savedMsg = '连接中…(占位)';
                  setTimeout((): void => { this.savedMsg = '连接成功(占位)'; }, 600);
                })
              Blank()
              if (this.savedMsg.length > 0) {
                Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
              }
            }
            .width('100%').margin({ top: 16, bottom: 32 })
          }
          .width('100%').padding({ left: 18, right: 18 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
