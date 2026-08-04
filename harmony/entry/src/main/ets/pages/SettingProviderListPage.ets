// SettingProviderListPage — Provider 列表管理页(G5-S3,对齐 Android SettingProviderPage.kt)
// 功能:
//   - Provider 卡:name + baseUrl + 模型计数 + enabled 开关 + chevron + 删除
//   - 添加 Provider(内联表单:name / baseUrl / apiKey / enabled)
//   - 编辑 Provider(点卡 → SettingProviderConfigPage)
//   - 删除 Provider(确认弹窗)
//   - 上移/下移重排
//   - 空态
//
// 占位持久化(本地草稿);后续接入 chat-domain(D-016/D-017)替换 load/save
//
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;
//   @Prop 避免保留字;ForEach/@Builder 内不声明 const/let
import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE,
} from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { ConfirmDialog } from '../components/ui/ConfirmDialog.ets';
import { ProviderCard } from '../components/ProviderCard.ets';

// Provider 行(本地草稿模型;接入 chat-domain 后替换为 ProviderSetting)
interface ProviderRow {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  enabled: boolean;
  modelCount: number;
}

function makeRow(
  id: string, name: string, baseUrl: string, apiKey: string,
  enabled: boolean, modelCount: number,
): ProviderRow {
  return {
    id: id, name: name, baseUrl: baseUrl, apiKey: apiKey,
    enabled: enabled, modelCount: modelCount,
  };
}

// 占位种子数据
function placeholderLoad(): Promise<ProviderRow[]> {
  const seed: ProviderRow[] = [
    makeRow('p-1', 'OpenAI', 'https://api.openai.com/v1', 'sk-****', true, 3),
    makeRow('p-2', 'Anthropic', 'https://api.anthropic.com/v1', 'sk-ant-****', true, 2),
    makeRow('p-3', 'Google', 'https://generativelanguage.googleapis.com/v1', 'AIza****', false, 0),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingProviderListPage {
  @State rows: ProviderRow[] = [];
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  // 内联"添加"表单
  @State addOpen: boolean = false;
  @State addName: string = '';
  @State addBaseUrl: string = '';
  @State addApiKey: string = '';
  @State addEnabled: boolean = true;
  // 删除确认
  @State pendingDeleteId: string = '';
  private counter: number = 100;

  aboutToAppear(): void {
    placeholderLoad().then((list: ProviderRow[]): void => {
      this.rows = list;
      this.loaded = true;
    });
  }

  private nextId(): string {
    this.counter += 1;
    return `p-${this.counter}`;
  }

  // —— 添加 ——
  private openAdd(): void {
    this.addOpen = true;
    this.addName = '';
    this.addBaseUrl = '';
    this.addApiKey = '';
    this.addEnabled = true;
  }

  private closeAdd(): void {
    this.addOpen = false;
  }

  private confirmAdd(): void {
    const name: string = this.addName.trim();
    if (name.length === 0) return;
    const id: string = this.nextId();
    const fresh: ProviderRow = makeRow(
      id, name, this.addBaseUrl.trim(), this.addApiKey.trim(), this.addEnabled, 0,
    );
    this.rows = [fresh, ...this.rows];
    this.addOpen = false;
    this.flashSaved('已添加');
  }

  // —— 编辑(跳配置页) ——
  private openConfig(row: ProviderRow): void {
    const params: Record<string, string> = {};
    params['providerId'] = row.id;
    router.pushUrl({ url: 'pages/SettingProviderConfigPage', params: params });
  }

  // —— 删除 ——
  private askDelete(id: string): void {
    this.pendingDeleteId = id;
  }

  private cancelDelete(): void {
    this.pendingDeleteId = '';
  }

  private confirmDelete(): void {
    const id: string = this.pendingDeleteId;
    this.pendingDeleteId = '';
    this.rows = this.rows.filter((r: ProviderRow): boolean => r.id !== id);
    this.flashSaved('已删除');
  }

  // —— 启用/停用 ——
  private toggle(id: string, next: boolean): void {
    this.rows = this.rows.map((r: ProviderRow): ProviderRow => {
      if (r.id !== id) return r;
      return makeRow(r.id, r.name, r.baseUrl, r.apiKey, next, r.modelCount);
    });
  }

  // —— 重排 ——
  private moveUp(index: number): void {
    if (index <= 0) return;
    const arr: ProviderRow[] = this.rows.slice();
    const tmp: ProviderRow = arr[index - 1];
    arr[index - 1] = arr[index];
    arr[index] = tmp;
    this.rows = arr;
  }

  private moveDown(index: number): void {
    const arr: ProviderRow[] = this.rows.slice();
    if (index >= arr.length - 1) return;
    const tmp: ProviderRow = arr[index + 1];
    arr[index + 1] = arr[index];
    arr[index] = tmp;
    this.rows = arr;
  }

  // —— 保存(占位) ——
  private flashSaved(msg: string): void {
    this.savedMsg = msg;
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    // 占位保存:接入 chat-domain 后替换为 saveProviders(getChatKvStore(), this.rows)
    this.flashSaved('已保存');
    router.back();
  }

  @Builder addForm(): void {
    if (this.addOpen) {
      Column() {
        Text('新增 Provider').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .margin({ bottom: 8 })
        FlatTextField({
          value: this.addName, label: '名称', placeholder: 'OpenAI',
          onChange: (v: string): void => { this.addName = v; },
        })
        FlatTextField({
          value: this.addBaseUrl, label: 'Base URL', placeholder: 'https://api.openai.com/v1',
          mono: true,
          onChange: (v: string): void => { this.addBaseUrl = v; },
        }).margin({ top: 8 })
        FlatTextField({
          value: this.addApiKey, label: 'API Key', placeholder: 'sk-…',
          mono: true, obscure: true,
          onChange: (v: string): void => { this.addApiKey = v; },
        }).margin({ top: 8 })
        Row() {
          Text('启用').fontSize(BODY).fontColor(INK).layoutWeight(1)
          AmberSwitch({
            checked: this.addEnabled,
            switchSize: SwitchSize.Small,
            onCheckedChange: (v: boolean): void => { this.addEnabled = v; },
          })
        }.width('100%').margin({ top: 10 })
        Row() {
          Text('取消').fontSize(BODY).fontColor(INK3)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.closeAdd(); })
          Blank()
          Text('添加').fontSize(BODY).fontColor(ACCENT).fontWeight(FontWeight.Medium)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.confirmAdd(); })
        }.width('100%').margin({ top: 10 })
      }
      .width('100%').alignItems(HorizontalAlign.Start)
      .padding(12).backgroundColor(SURFACE).borderRadius(12)
      .border({ width: 1, color: ACCENT })
      .margin({ top: 8, bottom: 8 })
    }
  }

  build(): void {
    // 根 Stack:页面内容 + 删除确认浮层(ConfirmDialog 自带全屏遮罩,需叠加渲染)
    Stack() {
      Column() {
        // 标题栏:返回 + 标题 + 完成
        Row() {
          Text('←').fontSize(TITLE).fontColor(INK3)
            .onClick((): void => { router.back(); })
          Text('提供商').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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
              // 概览行
              Row() {
                Text(`共 ${this.rows.length} 个 Provider`).fontSize(META).fontColor(INK3)
                Blank()
                Text(`${this.rows.filter((r: ProviderRow): boolean => r.enabled).length} 启用`)
                  .fontSize(META).fontColor(ACCENT)
              }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 8 })

              if (this.rows.length === 0) {
                Column() {
                  Text('🗂').fontSize(28).fontColor(INK4)
                  Text('暂无 Provider,点下方添加').fontSize(BODY).fontColor(INK3)
                    .margin({ top: 8 })
                }
                .width('100%').alignItems(HorizontalAlign.Center)
                .padding({ top: 40, bottom: 40 })
              }

              ForEach(this.rows, (row: ProviderRow, idx: number): void => {
                Column() {
                ProviderCard({
                  providerName: row.name,
                  baseUrl: row.baseUrl,
                  modelCount: row.modelCount,
                  providerEnabled: row.enabled,
                  onCardClick: (): void => { this.openConfig(row); },
                  onToggle: (next: boolean): void => { this.toggle(row.id, next); },
                  onDelete: (): void => { this.askDelete(row.id); },
                })

                  // 重排按钮(轻量,放卡下方)
                  Row() {
                    Text('上移').fontSize(LABEL).fontColor(INK3)
                      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                      .onClick((): void => { this.moveUp(idx); })
                    Text('下移').fontSize(LABEL).fontColor(INK3)
                      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                      .onClick((): void => { this.moveDown(idx); })
                  }.width('100%').margin({ top: 4, bottom: 12 })
                }
                .width('100%')
                .padding({ left: 16, right: 16 })
              }, (row: ProviderRow): string => row.id)

              this.addForm()

              // 添加按钮
              Row() {
                Text(this.addOpen ? '收起' : '+ 添加 Provider')
                  .fontSize(BODY).fontColor(ACCENT)
                  .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                  .backgroundColor(SURFACE2).borderRadius(50)
                  .onClick((): void => { if (this.addOpen) this.closeAdd(); else this.openAdd(); })
                Blank()
                if (this.savedMsg.length > 0) {
                  Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
                }
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 4, bottom: 32 })
            }
            .width('100%')
          }
          .layoutWeight(1)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)

      // 删除确认浮层(ConfirmDialog show=true 时自带全屏遮罩 + 居中卡片)
      ConfirmDialog({
        show: this.pendingDeleteId.length > 0,
        title: '删除 Provider?',
        message: '该操作不可撤销,关联模型将一并移除。',
        confirmText: '删除',
        dismissText: '取消',
        onConfirm: (): void => { this.confirmDelete(); },
        onDismiss: (): void => { this.cancelDelete(); },
      })
    }
    .width('100%').height('100%')
  }
}
