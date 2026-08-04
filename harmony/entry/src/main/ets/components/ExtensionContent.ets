// ExtensionContent — 扩展内容渲染器(ai/ExtensionContent.kt:194 行)
// Android 基准: 4 种扩展类型(skills / modeInjections / lorebooks / quickMessages) → ListItem 列表 + Switch
// ArkUI MVP: 单卡片渲染(类型 + 名称 + 描述 + 内容预览),tap 展开/折叠
//
// 类型映射(对齐 Android 概念):
//   skill       → 技能: 名称 + 描述 + 内容预览
//   prompt      → 提示词注入: 名称 + 内容预览
//   mcp         → MCP 服务器: 名称 + 工具数
//   local_tool  → 本地工具: 名称 + 描述
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../design/tokens.ets';

@Component
export struct ExtensionContent {
  @Prop extensionType: string = '';
  @Prop name: string = '';
  @Prop description: string = '';
  @Prop content: string = '';
  @State expanded: boolean = false;

  private typeIcon(): string {
    if (this.extensionType === 'skill') return '✦';
    if (this.extensionType === 'prompt') return '¶';
    if (this.extensionType === 'mcp') return '⌘';
    if (this.extensionType === 'local_tool') return '⛏';
    return '•';
  }

  private typeLabel(): string {
    if (this.extensionType === 'skill') return '技能';
    if (this.extensionType === 'prompt') return '提示词';
    if (this.extensionType === 'mcp') return 'MCP';
    if (this.extensionType === 'local_tool') return '本地工具';
    return '扩展';
  }

  private hasPreview(): boolean {
    return this.content.length > 0;
  }

  private previewText(): string {
    if (this.content.length === 0) return '';
    if (this.expanded) return this.content;
    if (this.content.length <= 80) return this.content;
    return this.content.substring(0, 80) + '…';
  }

  // MCP: 内容字段存放工具数(由调用方传入 "3 个工具" 形式,或纯数字)
  private mcpToolCount(): string {
    if (this.content.length === 0) return '未知工具数';
    return this.content;
  }

  build(): void {
    Column() {
      // 卡片头: icon + 标题 + 描述 + 展开箭头
      Row() {
        Text(this.typeIcon())
          .fontSize(BODY).fontColor(ACCENT)
          .width(24).textAlign(TextAlign.Center)
        Column() {
          Text(this.name.length > 0 ? this.name : '未命名')
            .fontSize(BODY).fontColor(INK).fontWeight(FontWeight.Medium)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Row() {
            Text(this.typeLabel())
              .fontSize(LABEL).fontColor(INK3)
            if (this.description.length > 0) {
              Text(' · ').fontSize(LABEL).fontColor(INK4)
              Text(this.description)
                .fontSize(LABEL).fontColor(INK3)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
            } else {
              Blank()
            }
          }
          .width('100%').margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 8 })
        if (this.hasPreview()) {
          Text(this.expanded ? '▾' : '▸')
            .fontSize(LABEL).fontColor(INK3)
        }
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 10, bottom: 10 })
      .onClick((): void => {
        if (this.hasPreview()) this.expanded = !this.expanded;
      })

      // 内容预览(可展开)
      if (this.hasPreview()) {
        Column() {
          if (this.extensionType === 'mcp') {
            Row() {
              Text('工具数').fontSize(META).fontColor(INK3).layoutWeight(1)
              Text(this.mcpToolCount()).fontSize(META).fontColor(INK2)
            }
            .width('100%')
          } else {
            Text(this.previewText())
              .fontSize(META).fontColor(INK2)
              .width('100%')
              .maxLines(this.expanded ? 20 : 2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .width('100%')
        .padding({ left: 12, right: 12, bottom: 10 })
        .border({ width: { top: 0.5 }, color: { top: LINE } })
      }
    }
    .width('100%')
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(10)
  }
}
