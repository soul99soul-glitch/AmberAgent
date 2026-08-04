// SettingProviderDetailPageFull — Provider 详情页(Full)
// 编辑单个 Provider:name / baseUrl / apiKey(掩码) / 类型徽标(只读) /
//   连接测试(ProviderConnectionTester) / 模型列表(name + contextWindow) /
//   启用开关 / 删除(确认) / 保存
// 路由参数: providerId(从 router.getParams 读取)
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE,
} from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { ProviderConnectionTester } from '../components/ProviderConnectionTester.ets';

interface ProviderModel {
  id: string;
  name: string;
  contextWindow: number;
}

interface ProviderDetail {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  type: string;        // 只读徽标(openai / anthropic / gemini / ollama …)
  enabled: boolean;
  models: ProviderModel[];
}

interface ProviderParams {
  providerId?: string;
}

function makeModel(id: string, name: string, contextWindow: number): ProviderModel {
  return { id: id, name: name, contextWindow: contextWindow };
}

function makeDetail(
  id: string, name: string, baseUrl: string, apiKey: string, type: string,
  enabled: boolean, models: ProviderModel[],
): ProviderDetail {
  return {
    id: id, name: name, baseUrl: baseUrl, apiKey: apiKey, type: type,
    enabled: enabled, models: models,
  };
}

function placeholderLoadProvider(id: string): Promise<ProviderDetail> {
  const seed: ProviderDetail = makeDetail(
    id.length > 0 ? id : 'p-1',
    'OpenAI',
    'https://api.openai.com/v1',
    'sk-••••••••••••••••',
    'openai',
    true,
    [
      makeModel('m-1', 'gpt-4o', 128000),
      makeModel('m-2', 'gpt-4o-mini', 128000),
      makeModel('m-3', 'o1-mini', 200000),
    ],
  );
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingProviderDetailPageFull {
  @State detail: ProviderDetail = makeDetail('', '', '', '', '', true, []);
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  @State showDeleteConfirm: boolean = false;
  @State testMessage: string = '';

  aboutToAppear(): void {
    const rawParams: object = router.getParams() as object;
    let providerId: string = '';
    if (rawParams !== null && rawParams !== undefined) {
      const params: ProviderParams = rawParams as ProviderParams;
      providerId = params.providerId ?? '';
    }
    placeholderLoadProvider(providerId).then((p: ProviderDetail): void => {
      this.detail = p;
      this.loaded = true;
    });
  }

  private setName(v: string): void {
    const d: ProviderDetail = this.detail;
    this.detail = makeDetail(d.id, v, d.baseUrl, d.apiKey, d.type, d.enabled, d.models);
  }

  private setBaseUrl(v: string): void {
    const d: ProviderDetail = this.detail;
    this.detail = makeDetail(d.id, d.name, v, d.apiKey, d.type, d.enabled, d.models);
  }

  private setApiKey(v: string): void {
    const d: ProviderDetail = this.detail;
    this.detail = makeDetail(d.id, d.name, d.baseUrl, v, d.type, d.enabled, d.models);
  }

  private setEnabled(v: boolean): void {
    const d: ProviderDetail = this.detail;
    this.detail = makeDetail(d.id, d.name, d.baseUrl, d.apiKey, d.type, v, d.models);
  }

  private save(): void {
    this.savedMsg = '已保存';
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.save();
    router.back();
  }

  private confirmDelete(): void {
    this.showDeleteConfirm = true;
  }

  private executeDelete(): void {
    this.showDeleteConfirm = false;
    router.back();
  }

  private onTestResult(success: boolean, message: string): void {
    this.testMessage = message;
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: (v: boolean) => void): void {
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
        Text('Provider 详情').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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
            this.sectionHeader('基本')

            this.toggleRow('启用', '停用后该 Provider 不参与模型调度', this.detail.enabled,
              (v: boolean): void => { this.setEnabled(v); })

            Column() {
              // 类型徽标(只读)
              Row() {
                Text('类型').fontSize(META).fontColor(INK3).margin({ bottom: 8 })
              }.width('100%')
              Row() {
                Text(this.detail.type)
                  .fontSize(META).fontColor(ACCENT)
                  .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                  .backgroundColor(ACCENT_LIGHT).borderRadius(50)
                  .border({ width: 1, color: ACCENT })
              }.width('100%').margin({ bottom: 12 })

              // 名称(直接 TextInput,与 Android 一致)
              TextInput({ text: this.detail.name, placeholder: 'OpenAI' })
                .fontSize(BODY).fontColor(INK)
                .backgroundColor(SURFACE).borderRadius(12)
                .border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                .width('100%')
                .onChange((v: string): void => { this.setName(v); })

              // Base URL
              FlatTextField({
                value: this.detail.baseUrl, label: 'Base URL',
                placeholder: 'https://api.openai.com/v1', mono: true,
                onChange: (v: string): void => { this.setBaseUrl(v); },
              }).margin({ top: 12 })

              // API Key(掩码 + 等宽)
              FlatTextField({
                value: this.detail.apiKey, label: 'API Key', placeholder: 'sk-…',
                mono: true, obscure: true,
                onChange: (v: string): void => { this.setApiKey(v); },
              }).margin({ top: 12 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('连接测试')

            Column() {
              ProviderConnectionTester({
                baseUrl: this.detail.baseUrl,
                apiKey: this.detail.apiKey,
                onResult: (success: boolean, message: string): void => {
                  this.onTestResult(success, message);
                },
              })
              if (this.testMessage.length > 0) {
                Text(this.testMessage).fontSize(LABEL).fontColor(INK3).margin({ top: 8 })
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('模型列表')

            Column() {
              if (this.detail.models.length === 0) {
                Text('暂无模型').fontSize(META).fontColor(INK3)
                  .padding({ top: 4, bottom: 8 })
              } else {
                ForEach(this.detail.models, (m: ProviderModel): void => {
                  Row() {
                    Text(m.name).fontSize(BODY).fontColor(INK).layoutWeight(1)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(`${m.contextWindow}`).fontSize(META).fontColor(INK3)
                      .margin({ left: 8 })
                    Text(' ctx').fontSize(LABEL).fontColor(INK4)
                  }
                  .width('100%').padding({ top: 10, bottom: 10 })
                  .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                }, (m: ProviderModel): string => m.id)
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(12).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 12 })

            this.sectionHeader('危险操作')

            Row() {
              Text('删除该 Provider').fontSize(BODY).fontColor('#C0392B').layoutWeight(1)
              Text('删除').fontSize(BODY).fontColor('#FFFFFF')
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .backgroundColor('#C0392B').borderRadius(50)
                .onClick((): void => { this.confirmDelete(); })
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            Row() {
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
    .bindSheet(this.showDeleteConfirm, this.DeleteSheet(), {
      height: SheetSize.MEDIUM,
      onDisappear: (): void => { this.showDeleteConfirm = false; },
    })
  }

  @Builder DeleteSheet(): void {
    Column() {
      Text('删除 Provider').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ left: 20, top: 20, bottom: 8 })
      Text('确定要删除该 Provider 及其全部模型配置吗?此操作不可撤销。')
        .fontSize(META).fontColor(INK3)
        .width('100%').padding({ left: 20, right: 20, bottom: 16 })
      Row() {
        Blank()
        Text('取消').fontSize(BODY).fontColor(INK3)
          .padding({ left: 16, right: 16, top: 8, bottom: 8 })
          .onClick((): void => { this.showDeleteConfirm = false; })
        Text('删除').fontSize(BODY).fontColor('#C0392B')
          .padding({ left: 16, right: 16, top: 8, bottom: 8 })
          .onClick((): void => { this.executeDelete(); })
      }
      .width('100%')
    }
    .width('100%')
  }
}
