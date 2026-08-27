// ChatProviderSettingsPage — Provider 设置(D-047:三变体列表 + 编辑)
// Android 基准:SettingProviderConfigPage.kt
//   OpenAIEndpointFields(:531-561) / GoogleAuthConsole(:436-487)
//   GoogleEndpointFields(:563-623) / ClaudeEndpointFields(:625-642)
// 持久化:saveProviders → Preferences KV(chat-domain D-016/D-017 线格式)
// 裁剪(登记 D-047):
//   - Harmony 仅支持 API Key;历史 OAuth/token-plan 配置明确提示迁移,
//     不把旧 token 当 API Key 展示或继续执行
//   - Responses API、Google Service Account 当前不支持;保存时迁移到已支持边界
//   - brand 编辑及 openai account 区(余额等)= P1
//   - 编辑中切换选中会丢弃未保存修改(MVP 草稿语义)
import {
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE2, LINE, LINE2, BODY2,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PageSpacer } from '../components/PageTemplate.ets';
import { router, promptAction } from '@kit.ArkUI';
import type { ProviderSetting } from '@amber/chat-domain';
import { setPendingProvider } from './ChatProviderDetailPage.ets';
import { loadProviders, saveProviders } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { DEFAULT_PROVIDERS, toProviderMonogram, providerSlug, providerAuthLabel } from '../di/ProviderDefaults.ets';
import { ProviderTemplatePickerSheet } from '../components/ProviderTemplatePickerSheet.ets';


interface ProviderRow {
  id: string;
  name: string;
  ptype: string;
  enabled: boolean;
  mono: string;
  slug: string;
  auth: string;
  modelsCount: number;
}

@Entry
@Component
struct ChatProviderSettingsPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State loaded: boolean = false;
  @State filterQuery: string = '';
  @State sheetShow: boolean = false;
  @State rows: ProviderRow[] = [];
  private all: ProviderSetting[] = [];

  aboutToAppear(): void {
    this.load();
  }

  // 子页(模型参数页)保存返回后重读,避免陈旧 models 合并覆盖新参数
  onPageShow(): void {
    this.load();
    // 进入页面不弹键盘、不自动滚到编辑区:延迟清掉首帧抢到的焦点
    setTimeout((): void => {
      try {
        this.getUIContext().getFocusController().clearFocus();
      } catch (_e) {
      }
    }, 250);
  }

  // 加载序号:aboutToAppear + onPageShow 双触发时,迟到的旧加载不得覆盖新数据/选中态
  private loadSeq: number = 0;

  private load(): void {
    const seq: number = ++this.loadSeq;
    loadProviders(getChatKvStore()).then((list: ProviderSetting[] | null): void => {
      if (seq !== this.loadSeq) return;
      if (list === null) {
        // 首装播种:await 落库完成才放行交互,避免与详情新增写入竞态互相覆盖
        this.all = DEFAULT_PROVIDERS;
        this.rebuildRows();
        saveProviders(getChatKvStore(), this.all).then((): void => {
          if (seq === this.loadSeq) this.loaded = true;
        }).catch((): void => {
          if (seq === this.loadSeq) this.loaded = true;
        });
        return;
      }
      this.all = list;
      this.rebuildRows();
      this.loaded = true;
    }).catch((): void => {
      // 读取失败也要退出加载态(否则页面永远 loading)
      if (seq === this.loadSeq) this.loaded = true;
    });
  }

  private rebuildRows(): void {
    this.rows = this.all.map((p: ProviderSetting): ProviderRow => ({
      id: p.id, name: p.name, ptype: p.type, enabled: p.enabled,
      mono: toProviderMonogram(p.name),
      slug: providerSlug(p),
      auth: providerAuthLabel(p),
      modelsCount: p.models.length,
    }));
  }

  private filteredRows(): ProviderRow[] {
    const q: string = this.filterQuery.trim().toLowerCase();
    if (q.length === 0) return this.rows;
    return this.rows.filter((r: ProviderRow): boolean =>
      r.name.toLowerCase().indexOf(q) >= 0 || r.slug.toLowerCase().indexOf(q) >= 0);
  }

  private totalModelCount(): number {
    let n: number = 0;
    for (const p of this.all) n += p.models.length;
    return n;
  }

  private onlineCount(): number {
    let n: number = 0;
    for (const p of this.all) {
      if (p.enabled) n++;
    }
    return n;
  }

  @Builder
  registryStat(value: number, label: string, accent: boolean) {
    Row() {
      Text(`${value}`).fontSize(12).fontWeight(FontWeight.Bold)
        .fontFamily('JetBrainsMono')
        .fontColor(accent ? this.accentColorStore : INK)
      Text(` ${label}`).fontSize(11.5).fontColor(INK3)
    }
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  registrySep() {
    Column()
      .width(1).height(11)
      .backgroundColor(LINE2)
      .margin({ left: 13, right: 13 })
  }

  @Builder
  providerRegistryRow(row: ProviderRow) {
    Column() {
      Row() {
        Text(row.mono)
          .fontSize(10.5).fontWeight(FontWeight.Bold)
          .fontFamily('JetBrainsMono')
          .fontColor(row.enabled ? INK2 : INK4)
          .width(32).height(32)
          .borderRadius(9)
          .backgroundColor(SURFACE2)
          .border({ width: 1, color: LINE })
          .textAlign(TextAlign.Center)
        Column() {
          Row() {
            Text(row.name)
              .fontSize(15).fontWeight(FontWeight.Medium)
              .fontColor(row.enabled ? INK : INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (row.enabled) {
              Column().width(6).height(6).borderRadius(3)
                .backgroundColor(this.accentColorStore).margin({ left: 7 })
            }
          }
          .alignItems(VerticalAlign.Center)
          Text(row.slug)
            .fontSize(11).fontFamily('JetBrainsMono').fontColor(INK4)
            .margin({ top: 3 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })

        Column() {
          Text(row.auth)
            .fontSize(10).fontWeight(FontWeight.Medium)
            .fontFamily('JetBrainsMono')
            .fontColor(row.auth === '—' ? INK4 : INK3)
            .padding({ left: 6, right: 6, top: 2, bottom: 2 })
            .backgroundColor(SURFACE2)
            .borderRadius(3)
            .border({ width: 1, color: LINE })
          Text(`${row.modelsCount} 个模型`)
            .fontSize(11)
            .fontColor(row.modelsCount > 0 ? INK2 : INK4)
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.End)
      }
      .width('100%')
      .padding({ left: 4, right: 4, top: 11, bottom: 11 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .onClick((): void => {
      const params: Record<string, string> = {};
      params['providerId'] = row.id;
      router.pushUrl({ url: 'pages/ChatProviderDetailPage', params: params });
    })
  }

  build() {
    Stack() {
    Column() {
      PageTitleBar({
        title: '提供商',
      })

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY2).fontColor(INK3)
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(HorizontalAlign.Center)
        .padding({ top: 48 })
      } else {
        Scroll() {
          Column() {
            // ── Provider 注册表(amber-zhenji / Android SettingProviderPage 1:1)──
            Row() {
              Row({ space: 1 }) {
                Column().width(3).height(5).borderRadius(1).backgroundColor(this.accentColorStore)
                Column().width(3).height(9).borderRadius(1).backgroundColor(this.accentColorStore)
                Column().width(3).height(13).borderRadius(1).backgroundColor(this.accentColorStore)
              }
              .alignItems(VerticalAlign.Bottom)
              .margin({ right: 9 })
              this.registryStat(this.all.length, '提供商', false)
              this.registrySep()
              this.registryStat(this.totalModelCount(), '模型', false)
              this.registrySep()
              this.registryStat(this.onlineCount(), '在线', true)
              Blank()
              // + 添加(ghost,accent 描边)
              Row() {
                Text('+').fontSize(13).fontColor(this.accentColorStore).margin({ right: 6 })
                Text('添加').fontSize(11).fontWeight(FontWeight.Bold)
                  .fontFamily('JetBrainsMono').fontColor(this.accentColorStore)
              }
              .height(32)
              .padding({ left: 11, right: 11 })
              .borderRadius(3)
              .border({ width: 1, color: this.accentColorStore })
              .alignItems(VerticalAlign.Center)
              .onClick((): void => { this.sheetShow = true; })
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .margin({ top: 12, bottom: 2 })

            // 终端风筛选(▸ + mono 输入 + line2 底线)
            Column() {
              Row() {
                Text('▸').fontSize(12).lineHeight(16).fontWeight(FontWeight.Medium).fontColor(this.accentColorStore)
                TextInput({ text: this.filterQuery, placeholder: '筛选提供商' })
                  .fontSize(12).fontColor(INK)
                  .fontFamily('JetBrainsMono')
                  .placeholderColor(INK4)
                  .placeholderFont({ size: 12 })
                  .backgroundColor(Color.Transparent)
                  .padding({ top: 0, bottom: 0, left: 7, right: 0 })
                  .constraintSize({ minHeight: 40 })
                  .layoutWeight(1)
                  .onChange((v: string): void => { this.filterQuery = v; })
                if (this.filterQuery.length > 0) {
                  Text('✕').fontSize(12).fontColor(INK3)
                    .width(24).height(24).textAlign(TextAlign.Center)
                    .onClick((): void => { this.filterQuery = ''; })
                }
              }
              .width('100%')
              .alignItems(VerticalAlign.Center)
              .constraintSize({ minHeight: 48 })
              Column().width('100%').height(1).backgroundColor(LINE2)
            }
            .width('100%')
            .margin({ top: 8, bottom: 2 })

            Row() {
              Text('//').fontSize(11).fontWeight(FontWeight.Medium)
                .fontColor(this.accentColorStore).fontFamily('JetBrainsMono')
              Text(` 在线`).fontSize(11).fontWeight(FontWeight.Medium)
                .fontColor(INK3).fontFamily('JetBrainsMono').letterSpacing(1.3)
              Text(`· ${this.filteredRows().filter((r: ProviderRow): boolean => r.enabled).length}`)
                .fontSize(11).fontColor(INK4).margin({ left: 6 })
              Blank()
              Column().width(1).height(1)
            }
            .width('100%')
            .padding({ top: 18, bottom: 11, left: 4, right: 4 })
            .alignItems(VerticalAlign.Center)

            ForEach(this.filteredRows().filter((r: ProviderRow): boolean => r.enabled),
              (row: ProviderRow): void => {
                this.providerRegistryRow(row)
              }, (row: ProviderRow): string => `on-${row.id}`)

            ForEach(this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled),
              (row: ProviderRow, idx: number): void => {
                if (idx === 0) {
                  Row() {
                    Text('//').fontSize(11).fontWeight(FontWeight.Medium)
                      .fontColor(this.accentColorStore).fontFamily('JetBrainsMono')
                    Text(` 未启用`).fontSize(11).fontWeight(FontWeight.Medium)
                      .fontColor(INK3).fontFamily('JetBrainsMono').letterSpacing(1.3)
                    Text(`· ${this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled).length}`)
                      .fontSize(11).fontColor(INK4).margin({ left: 6 })
                    Blank()
                  }
                  .width('100%')
                  .padding({ top: 22, bottom: 11, left: 4, right: 4 })
                  .alignItems(VerticalAlign.Center)
                }
                Column() {
                  this.providerRegistryRow(row)
                }
                .opacity(0.62)
              }, (row: ProviderRow): string => `off-${row.id}`)

            if (this.filteredRows().length === 0) {
              Text('没有匹配的提供商')
                .fontSize(13).fontColor(INK3)
                .width('100%')
                .padding({ top: 28, bottom: 28 })
                .textAlign(TextAlign.Center)
            }

            Column().height(18)

            PageSpacer()
          }
          .width('100%')
          .padding({ left: 18, right: 18 })
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    ProviderTemplatePickerSheet({
            show: this.sheetShow,
            existingIds: JSON.stringify(this.all.map((p: ProviderSetting): string => p.id)),
            onPickPreset: (p: ProviderSetting): void => {
              this.sheetShow = false;
              setPendingProvider(p);
              const params: Record<string, string> = {};
              params['isNew'] = '1';
              router.pushUrl({ url: 'pages/ChatProviderDetailPage', params: params });
            },
            onOauthPick: (): void => {
              this.sheetShow = false;
              promptAction.showToast({ message: '鸿蒙版暂不支持 OAuth 登录，请使用 API Key 预置', duration: 2200 });
            },
            onDismiss: (): void => { this.sheetShow = false; },
          })
    }
  }
}
