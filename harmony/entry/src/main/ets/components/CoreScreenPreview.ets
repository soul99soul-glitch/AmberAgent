// CoreScreenPreview — DeepRead 热榜屏预览组件(G6-S5,对齐 Android CoreScreenPreview.kt)
// 用途:设置页内嵌的迷你"热榜屏"预览,实时反映主题/字号变更;点击打开完整 BoardPage
//
// 渲染内容:
//   - 标题栏:返回 + "热榜" + 刷新占位
//   - 热榜列表预览(3 项):排名 + 标题 + 热度,用 accent 色高亮排名/刷新
//   - accent 色块条(主题色可视化)
//
// Props:themeName(Graphite/Sage/Midnight) + fontSize + onClick
//
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;ForEach 前无 const/let;
//   @Prop 无保留字;@Builder 内无 const/let
import { BODY, META, LABEL } from '../design/tokens.ets';
import { colorsByName, ChatThemeColors } from '../design/ChatTheme.ets';

interface PreviewEntry {
  rank: number;
  title: string;
  heat: string;
}

@Component
export struct CoreScreenPreview {
  // @Watch:themeName 变更时同步 colors(@Prop 变化触发 onThemeChange,不在 build 内写 @State)
  @Prop @Watch('onThemeChange') themeName: string = 'Graphite';
  @Prop fontSize: number = BODY;
  onClick: () => void = (): void => {};

  // 预览用 3 条热点(静态,仅用于视觉预览)
  @State previewItems: PreviewEntry[] = [
    { rank: 1, title: '国产大模型集体降价,API 价格战再升级', heat: '2.4万' },
    { rank: 2, title: '鸿蒙 NEXT 5.0 发布,纯血原生应用破万', heat: '1.9万' },
    { rank: 3, title: 'AI 编程助手效率提升实测对比', heat: '1.5万' },
  ];

  // 当前主题色(@Watch 同步;初始 Graphite,aboutToAppear 兜底)
  @State colors: ChatThemeColors = colorsByName('Graphite');

  aboutToAppear(): void {
    this.colors = colorsByName(this.themeName);
  }

  // themeName Prop 变更 → 重算 colors(整对象重赋值触发重渲染)
  onThemeChange(): void {
    this.colors = colorsByName(this.themeName);
  }

  build(): void {
    Column() {
      // ── 标题栏:返回 + 热榜 + 刷新
      Row() {
        Text('←')
          .fontSize(this.fontSize + 4)
          .fontColor(this.colors.ink3)
        Text('热榜')
          .fontSize(this.fontSize + 4)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.colors.ink)
          .margin({ left: 10 })
          .layoutWeight(1)
        Text('↻')
          .fontSize(this.fontSize)
          .fontColor(this.colors.accent)
      }
      .width('100%')
      .height(48)
      .padding({ left: 16, right: 16 })
      .backgroundColor(this.colors.bg)

      // ── accent 色条(主题色可视化,1px hairline 下)
      Divider().color(this.colors.line).strokeWidth(0.5)
      Row()
        .width('100%')
        .height(3)
        .backgroundColor(this.colors.accent)

      // ── 热榜列表预览(3 项)
      Column() {
        ForEach(this.previewItems, (item: PreviewEntry): void => {
          Row() {
            Text(item.rank < 10 ? '0' + item.rank.toString() : item.rank.toString())
              .fontSize(META)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.colors.accent)
              .width(28)
            Text(item.title)
              .fontSize(this.fontSize)
              .fontColor(this.colors.ink)
              .layoutWeight(1)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(item.heat)
              .fontSize(LABEL)
              .fontColor(this.colors.ink4)
              .margin({ left: 8 })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 11, bottom: 11 })
          .backgroundColor(this.colors.surface)
          .border({ width: { bottom: 0.5 }, color: { bottom: this.colors.line } })
        }, (item: PreviewEntry): string => item.rank.toString())
      }
      .width('100%')
      .backgroundColor(this.colors.surface)
      .border({ width: 1, color: this.colors.line })
      .margin({ left: 12, right: 12, top: 12 })
      .borderRadius(12)
      .clip(true)

      // ── 底部留白 + 主题名标签
      Row() {
        Text('主题: ' + this.themeName)
          .fontSize(LABEL)
          .fontColor(this.colors.ink3)
          .margin({ left: 16, top: 10, bottom: 12 })
        Blank()
        Text('查看完整 →')
          .fontSize(LABEL)
          .fontColor(this.colors.accent)
          .margin({ right: 16, top: 10, bottom: 12 })
      }
      .width('100%')
    }
    .width('100%')
    .backgroundColor(this.colors.bg)
    .borderRadius(16)
    .clip(true)
    .onClick((): void => { this.onClick(); })
  }
}
