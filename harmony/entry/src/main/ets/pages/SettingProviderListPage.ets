// SettingProviderListPage — Provider 列表管理页(v5 终端账本风格)
// 设计: Provider Management v5 — borderless fields, eyebrow rules, monospace labels,
//   hairline separators instead of card boxes.
//   - PageHeader: ← + "提供商" + 添加(描边按钮)
//   - Status strip: 📊 + N 提供商 | N 模型 | N 在线
//   - Filter row: ▸ + TextInput(筛选提供商_) + ✕
//   - Online group: eyebrow "在线 · 8" + provider rows(Monogram + name + dot + slug + auth badge + count)
//   - Offline group: eyebrow "未启用 · 1" + rows(opacity 0.62, no dot)
//
// ArkUI 规则: 无 as unknown / 无对象展开 / 无 any/unknown;
//   @Prop 避免保留字; ForEach/@Builder 内不声明 const/let(UI 树优先)
import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, SIGNAL, ERROR,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
} from '../design/tokens.ets';

// Provider 行(本地草稿模型;接入 chat-domain 后替换为 ProviderSetting)
interface ProviderItem {
  id: string;
  name: string;
  slug: string;
  authMethod: string;   // 'api_key' | 'token_plan'
  modelCount: number;
  online: boolean;
}

function makeProvider(
  id: string, name: string, slug: string, authMethod: string,
  modelCount: number, online: boolean,
): ProviderItem {
  return {
    id: id, name: name, slug: slug, authMethod: authMethod,
    modelCount: modelCount, online: online,
  };
}

// 占位种子数据(9 提供商: 8 在线 + 1 离线, 11 模型)
function seedProviders(): ProviderItem[] {
  return [
    makeProvider('p-minimax', 'MiniMax', 'minimax', 'api_key', 1, true),
    makeProvider('p-openai', 'OpenAI', 'openai', 'api_key', 3, true),
    makeProvider('p-codex', 'Codex', 'codex', 'api_key', 1, true),
    makeProvider('p-gemini', 'Gemini', 'google', 'api_key', 2, true),
    makeProvider('p-deepseek', 'DeepSeek', 'deepseek', 'api_key', 1, true),
    makeProvider('p-kimi', 'Kimi', 'kimi', 'api_key', 2, true),
    makeProvider('p-glm', 'GLM', 'glm', 'api_key', 1, true),
    makeProvider('p-mimo', 'MiMo', 'mimo', 'token_plan', 0, true),
    makeProvider('p-openrouter', 'OpenRouter', 'openrouter', 'api_key', 0, false),
  ];
}

@Entry
@Component
struct SettingProviderListPage {
  @State providers: ProviderItem[] = seedProviders();
  @State query: string = '';

  // —— 派生数据(私有方法, build 中调用) ——

  private initial(name: string): string {
    if (name.length === 0) return '?';
    return name.charAt(0).toUpperCase();
  }

  private authShort(method: string): string {
    if (method === 'token_plan') return 'PLAN';
    return 'API';
  }

  private matches(p: ProviderItem): boolean {
    const q: string = this.query.trim().toLowerCase();
    if (q.length === 0) return true;
    if (p.name.toLowerCase().indexOf(q) >= 0) return true;
    if (p.slug.toLowerCase().indexOf(q) >= 0) return true;
    return false;
  }

  private filteredOnline(): ProviderItem[] {
    return this.providers.filter((p: ProviderItem): boolean => p.online && this.matches(p));
  }

  private filteredOffline(): ProviderItem[] {
    return this.providers.filter((p: ProviderItem): boolean => !p.online && this.matches(p));
  }

  private onlineCount(): number {
    let n: number = 0;
    for (const p of this.providers) {
      if (p.online) n += 1;
    }
    return n;
  }

  private modelTotal(): number {
    let n: number = 0;
    for (const p of this.providers) {
      n += p.modelCount;
    }
    return n;
  }

  // —— 导航 ——

  private openDetail(p: ProviderItem): void {
    const params: Record<string, string> = {};
    params['providerId'] = p.id;
    params['slug'] = p.slug;
    router.pushUrl({ url: 'pages/SettingProviderDetailPageFull', params: params });
  }

  private openAdd(): void {
    const params: Record<string, string> = {};
    params['providerId'] = 'new';
    router.pushUrl({ url: 'pages/SettingProviderDetailPageFull', params: params });
  }

  // —— @Builder 原语 ——

  // Eyebrow: // ACCENT + 标签(mono UPPERCASE INK3) + · 计数(INK4) + 向右延伸的发丝线
  @Builder eyebrowLine(label: string, count: string): void {
    Row() {
      Text('//')
        .fontSize(10)
        .fontColor(ACCENT)
        .fontWeight(FontWeight.Bold)
        .fontFamily('monospace')
      Text(' ' + label.toUpperCase())
        .fontSize(10)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK3)
        .fontFamily('monospace')
      if (count.length > 0) {
        Text(' · ' + count)
          .fontSize(10)
          .fontColor(INK4)
          .fontFamily('monospace')
      }
      Column()
        .height(1)
        .backgroundColor(LINE)
        .layoutWeight(1)
        .margin({ left: 8 })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ top: 16, bottom: 9 })
  }

  // Monogram: 圆角方块 + 首字母(mono 9/700), on=false 时文字弱化
  @Builder monogramBox(initial: string, onState: boolean, boxSize: number): void {
    Stack() {
      Text(initial)
        .fontSize(9)
        .fontWeight(FontWeight.Bold)
        .fontColor(onState ? INK : INK4)
        .fontFamily('monospace')
    }
    .width(boxSize)
    .height(boxSize)
    .borderRadius(8)
    .backgroundColor(SURFACE2)
    .border({ width: 1, color: LINE })
  }

  // 状态条分隔竖线(1x11 LINE2)
  @Builder vsep(): void {
    Column()
      .width(1)
      .height(11)
      .backgroundColor(LINE2)
      .margin({ left: 10, right: 10 })
  }

  build(): void {
    Column() {
      // —— PageHeader ——
      Row() {
        Text('←')
          .fontSize(24)
          .fontColor(INK)
          .onClick((): void => { router.back(); })
        Text('提供商')
          .fontSize(18.5)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
          .margin({ left: 8 })
          .layoutWeight(1)
        Row() {
          Text('+')
            .fontSize(13)
            .fontColor(ACCENT)
            .fontWeight(FontWeight.Bold)
          Text('添加')
            .fontSize(11)
            .fontColor(ACCENT)
            .fontWeight(FontWeight.Bold)
            .fontFamily('monospace')
            .margin({ left: 5 })
        }
        .border({ width: 1, color: LINE2 })
        .borderRadius(3)
        .padding({ left: 11, right: 11, top: 5, bottom: 5 })
        .onClick((): void => { this.openAdd(); })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      // —— Body Scroll ——
      Scroll() {
        Column() {
          // 状态条
          Row() {
            Text('📊')
              .fontSize(13)
              .fontColor(ACCENT)
            Text(`${this.providers.length} 提供商`)
              .fontSize(11)
              .fontColor(INK3)
              .fontFamily('monospace')
              .margin({ left: 6 })
            this.vsep()
            Text(`${this.modelTotal()} 模型`)
              .fontSize(11)
              .fontColor(INK3)
              .fontFamily('monospace')
            this.vsep()
            Text(`${this.onlineCount()} 在线`)
              .fontSize(11)
              .fontColor(ACCENT)
              .fontFamily('monospace')
          }
          .width('100%')
          .padding({ top: 10, left: 4, right: 4, bottom: 12 })

          // 筛选行
          Row() {
            Text('▸')
              .fontSize(12)
              .fontColor(ACCENT)
            TextInput({ placeholder: '筛选提供商_' })
              .fontSize(12)
              .fontColor(INK)
              .fontFamily('monospace')
              .backgroundColor(BG)
              .borderRadius(0)
              .layoutWeight(1)
              .margin({ left: 8 })
              .onChange((v: string): void => { this.query = v; })
            if (this.query.length > 0) {
              Text('✕')
                .fontSize(12)
                .fontColor(INK3)
                .margin({ left: 8 })
                .onClick((): void => { this.query = ''; })
            }
          }
          .width('100%')
          .padding({ top: 8, bottom: 10 })
          .border({ width: { bottom: 1 }, color: { bottom: LINE } })

          // 在线分组
          this.eyebrowLine('在线', `${this.filteredOnline().length}`)
          ForEach(this.filteredOnline(), (p: ProviderItem): void => {
            Row() {
              this.monogramBox(this.initial(p.name), true, 32)
              Column() {
                Row() {
                  Text(p.name)
                    .fontSize(14.5)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(INK)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .layoutWeight(1)
                  Circle({ width: 5, height: 5 })
                    .fill(SIGNAL)
                    .margin({ left: 6 })
                }
                .width('100%')
                Text(p.slug)
                  .fontSize(10.5)
                  .fontColor(INK4)
                  .fontFamily('monospace')
                  .margin({ top: 3 })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
              .margin({ left: 12 })

              Column() {
                Text(this.authShort(p.authMethod))
                  .fontSize(10)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(INK3)
                  .fontFamily('monospace')
                  .backgroundColor(SURFACE2)
                  .border({ width: 1, color: LINE })
                  .borderRadius(3)
                  .padding({ left: 5, right: 5, top: 2, bottom: 2 })
                Text(`${p.modelCount} 个`)
                  .fontSize(11)
                  .fontColor(INK2)
                  .fontFamily('monospace')
                  .margin({ top: 6 })
              }
              .alignItems(HorizontalAlign.End)
            }
            .width('100%')
            .padding({ top: 11, right: 12, bottom: 11, left: 10 })
            .border({ width: { top: 1 }, color: { top: LINE } })
            .onClick((): void => { this.openDetail(p); })
          }, (p: ProviderItem): string => p.id)

          // 离线分组
          this.eyebrowLine('未启用', `${this.filteredOffline().length}`)
          ForEach(this.filteredOffline(), (p: ProviderItem): void => {
            Row() {
              this.monogramBox(this.initial(p.name), false, 32)
              Column() {
                Row() {
                  Text(p.name)
                    .fontSize(14.5)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(INK)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .layoutWeight(1)
                }
                .width('100%')
                Text(p.slug)
                  .fontSize(10.5)
                  .fontColor(INK4)
                  .fontFamily('monospace')
                  .margin({ top: 3 })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
              .margin({ left: 12 })

              Column() {
                Text(this.authShort(p.authMethod))
                  .fontSize(10)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(INK3)
                  .fontFamily('monospace')
                  .backgroundColor(SURFACE2)
                  .border({ width: 1, color: LINE })
                  .borderRadius(3)
                  .padding({ left: 5, right: 5, top: 2, bottom: 2 })
                Text(`${p.modelCount} 个`)
                  .fontSize(11)
                  .fontColor(INK2)
                  .fontFamily('monospace')
                  .margin({ top: 6 })
              }
              .alignItems(HorizontalAlign.End)
            }
            .width('100%')
            .opacity(0.62)
            .padding({ top: 11, right: 12, bottom: 11, left: 10 })
            .border({ width: { top: 1 }, color: { top: LINE } })
            .onClick((): void => { this.openDetail(p); })
          }, (p: ProviderItem): string => p.id)

          // 底部留白
          Column().height(28)
        }
        .width('100%')
        .padding({ left: 18, right: 18, bottom: 28 })
      }
      .layoutWeight(1)
      .scrollBar(BarState.Off)
      .align(Alignment.TopStart)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }
}
