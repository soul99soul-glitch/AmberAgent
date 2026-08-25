// AssistantExtensionsPage — 扩展配置入口(助手详情子页)
// 列出 4 个扩展类别(Skills / MCP / LocalTools / Prompts),每行为
// icon + 标题 + chevron,点击携带 assistantId 跳转到对应既有页面。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, GUTTER, RADIUS } from '../design/tokens.ets';

interface RouterParams {
  assistantId?: string;
}

interface CategoryRow {
  icon: string;
  title: string;
  desc: string;
  target: string;
}

@Entry
@Component
struct AssistantExtensionsPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State assistantId: string = '';
  private categories: CategoryRow[] = [
    { icon: '✦', title: 'Skills', desc: '已启用的技能集合', target: 'pages/ChatAssistantExtensionsPage' },
    { icon: '◐', title: 'MCP', desc: '模型上下文协议服务器', target: 'pages/ChatAssistantMcpPage' },
    { icon: '▣', title: 'LocalTools', desc: '本地工具开关与范围', target: 'pages/ChatAssistantLocalToolsPage' },
    { icon: '❝', title: 'Prompts', desc: '提示词注入与世界书', target: 'pages/ChatPromptsPage' },
  ];

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined ? params.assistantId : '';
  }

  private goto(target: string): void {
    const params: Record<string, string> = {};
    params['assistantId'] = this.assistantId;
    router.pushUrl({ url: target, params: params });
  }

  @Builder rowIcon(icon: string): void {
    Text(icon)
      .fontSize(BODY).fontColor(ACCENT)
      .width(32).height(32).textAlign(TextAlign.Center)
      .backgroundColor(SURFACE2).borderRadius(8)
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 })
          .onClick((): void => { router.back(); })
        Text('扩展').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      // 说明条
      Row() {
        Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
        Text(' 为此助手配置可用的扩展能力').fontSize(META).fontColor(INK3)
      }
      .width('100%').padding({ left: 16, top: 16, bottom: 8 })

      // 类别列表
      List() {
        ForEach(this.categories, (c: CategoryRow, i: number): void => {
          ListItem() {
            Row() {
              this.rowIcon(c.icon)
              Column() {
                Text(c.title).fontSize(BODY).fontColor(INK)
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(c.desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
              Text('›').fontSize(18).fontColor(INK3)
            }
            .width('100%').padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
            .constraintSize({ minHeight: 64 })
            .backgroundColor(SURFACE)
            .onClick((): void => { this.goto(c.target); })
          }
        }, (c: CategoryRow, i: number): string => `cat-${i}`)
      }
      .width('100%').layoutWeight(1)
      .divider({ strokeWidth: 0.5, color: LINE })
      .padding({ left: 0, right: 0 })

      Column() {}.height(16)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
