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
  ACCENT, BG, INK, INK2, INK3, INK4, SURFACE, LINE, LINE2, BODY2, META, TINY, LABEL,
  TEXT_STYLES, lineHeightForSize,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer, Chip, BtnGhost, PaperSeparator } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
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
      Text(`${value}`)
        .fontSize(META).fontWeight(600).lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(FONT_MONO_MEDIUM).fontColor(accent ? this.accentColorStore : INK)
      Text(` ${label}`)
        .fontSize(TINY).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(TINY))
        .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK3)
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
  providerRegistryRow(row: ProviderRow, isLast: boolean) {
    Column() {
      Row() {
        Text(row.mono)
          .fontSize(TINY).fontWeight(600).lineHeight(TEXT_STYLES.tinyTag.lineHeight)
          .fontFamily(FONT_MONO_MEDIUM)
          .fontColor(row.enabled ? INK2 : INK4)
          .width(40).height(40)
          .borderRadius(20)
          .backgroundColor(SURFACE)
          .border({ width: 1, color: LINE })
          .textAlign(TextAlign.Center)
        Column() {
          Row() {
            Text(row.name)
              .fontSize(15).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(row.enabled ? INK : INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (row.enabled) {
              Column().width(6).height(6).borderRadius(3)
                .backgroundColor(this.accentColorStore).margin({ left: 7 })
            }
          }
          .alignItems(VerticalAlign.Center)
          Text(row.slug)
            .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO).fontColor(INK3)
            .margin({ top: 3 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })

        Column() {
          Chip({ text: row.auth })
          Row() {
            Text(`${row.modelsCount}`)
              .fontSize(TINY).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(TINY))
              .fontFamily(FONT_MONO_MEDIUM).fontColor(row.modelsCount > 0 ? INK2 : INK4)
            Text(' 个模型')
              .fontSize(TINY).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(TINY))
              .fontFamily(TEXT_STYLES.secondary.family).fontColor(row.modelsCount > 0 ? INK2 : INK4)
          }
          .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.End)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .onClick((): void => {
      const params: Record<string, string> = {};
      params['providerId'] = row.id;
      router.pushUrl({ url: 'pages/ChatProviderDetailPage', params: params });
    })
    if (!isLast) {
      PaperSeparator()
    }
  }

  build() {
    Stack() {
    Column() {
      PageTitleBar({
        title: '提供商',
      })

      if (!this.loaded) {
        Column() {
          Text('加载中…')
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK3)
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
              BtnGhost({ label: '＋ 添加', onTap: (): void => { this.sheetShow = true; } })
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .padding({ left: 16, right: 16 })
            .margin({ top: 12, bottom: 2 })

            // 终端风筛选(▸ + mono 输入 + line2 底线)
            Column() {
              Row() {
                Text('▸').fontSize(META).lineHeight(TEXT_STYLES.meta.lineHeight).fontWeight(FontWeight.Medium)
                  .fontFamily(FONT_MONO_MEDIUM).fontColor(this.accentColorStore)
                TextInput({ text: this.filterQuery, placeholder: '筛选提供商' })
                  .fontSize(META).lineHeight(TEXT_STYLES.meta.lineHeight).fontWeight(FontWeight.Medium)
                  .fontColor(INK)
                  .fontFamily(FONT_MONO_MEDIUM)
                  .placeholderColor(INK4)
                  .placeholderFont({ size: 12 })
                  .backgroundColor(Color.Transparent)
                  .padding({ top: 0, bottom: 0, left: 7, right: 0 })
                  .constraintSize({ minHeight: 40 })
                  .layoutWeight(1)
                  .onChange((v: string): void => { this.filterQuery = v; })
                if (this.filterQuery.length > 0) {
                  Text('✕').fontSize(META).lineHeight(TEXT_STYLES.meta.lineHeight).fontWeight(FontWeight.Medium)
                    .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
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
            .padding({ left: 16, right: 16 })
            .margin({ top: 8, bottom: 2 })

            Row() {
              Text('//').fontSize(LABEL).fontWeight(600).lineHeight(TEXT_STYLES.eyebrow.lineHeight)
                .fontColor(this.accentColorStore).fontFamily(FONT_MONO_MEDIUM)
              Text(` 在线 · ${this.filteredRows().filter((r: ProviderRow): boolean => r.enabled).length}`)
                .fontSize(LABEL).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.eyebrow.lineHeight)
                .fontColor(INK2).fontFamily(FONT_MONO_MEDIUM)
              Blank().layoutWeight(1).height(1).backgroundColor(LINE).margin({ left: 10 })
            }
            .width('100%')
            .padding({ top: 18, bottom: 11, left: 16, right: 16 })
            .alignItems(VerticalAlign.Center)

            ForEach(this.filteredRows().filter((r: ProviderRow): boolean => r.enabled),
              (row: ProviderRow, idx: number): void => {
                this.providerRegistryRow(row,
                  idx === this.filteredRows().filter((r: ProviderRow): boolean => r.enabled).length - 1)
              }, (row: ProviderRow): string => `on-${row.id}`)

            ForEach(this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled),
              (row: ProviderRow, idx: number): void => {
                if (idx === 0) {
                  Row() {
                    Text('//').fontSize(LABEL).fontWeight(600).lineHeight(TEXT_STYLES.eyebrow.lineHeight)
                      .fontColor(this.accentColorStore).fontFamily(FONT_MONO_MEDIUM)
                    Text(` 未启用 · ${this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled).length}`)
                      .fontSize(LABEL).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.eyebrow.lineHeight)
                      .fontColor(INK2).fontFamily(FONT_MONO_MEDIUM)
                    Blank().layoutWeight(1).height(1).backgroundColor(LINE).margin({ left: 10 })
                  }
                  .width('100%')
                  .padding({ top: 22, bottom: 11, left: 16, right: 16 })
                  .alignItems(VerticalAlign.Center)
                }
                Column() {
                  this.providerRegistryRow(row,
                    idx === this.filteredRows().filter((r: ProviderRow): boolean => !r.enabled).length - 1)
                }
                .opacity(0.42)
              }, (row: ProviderRow): string => `off-${row.id}`)

            if (this.filteredRows().length === 0) {
              Text('没有匹配的提供商')
                .fontSize(TEXT_STYLES.body.size)
                .fontWeight(TEXT_STYLES.body.weight)
                .lineHeight(TEXT_STYLES.body.lineHeight)
                .fontFamily(TEXT_STYLES.body.family)
                .fontColor(INK3)
                .width('100%')
                .padding({ top: 28, bottom: 28 })
                .textAlign(TextAlign.Center)
            }

            Column().height(18)

            PageSpacer()
          }
          .width('100%')
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
