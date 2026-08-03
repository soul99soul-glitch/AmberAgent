// SettingAgentExtensionsPageFull — Agent 扩展管理页(G5-S4a)
// 四类扩展:Skills / Prompts / LocalTools / MCP,各显示计数 + 启停 + 优先级上下移
// 导航:点击卡片跳转对应设置页
// 持久化:占位本地草稿(后续接入 AgentExtensionStore 后替换)
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface ExtensionCategory {
  id: string;
  title: string;
  desc: string;
  count: number;
  enabled: boolean;
  targetPage: string;
}

function makeCategory(
  id: string, title: string, desc: string, count: number,
  enabled: boolean, targetPage: string,
): ExtensionCategory {
  return {
    id: id, title: title, desc: desc, count: count,
    enabled: enabled, targetPage: targetPage,
  };
}

@Entry
@Component
struct SettingAgentExtensionsPageFull {
  @State loaded: boolean = false;
  @State categories: ExtensionCategory[] = [];

  aboutToAppear(): void {
    this.categories = [
      makeCategory('skills', '技能 (Skills)', '可调用的技能包', 8, true, 'pages/SkillsPage'),
      makeCategory('prompts', '提示词 (Prompts)', '预设系统提示模板', 12, true, 'pages/ChatPromptsPage'),
      makeCategory('localtools', '本地工具 (LocalTools)', '设备端工具实现', 5, true, 'pages/ChatAssistantLocalToolsPage'),
      makeCategory('mcp', 'MCP 服务器', 'Model Context Protocol 服务', 3, false, 'pages/SettingMcpPageFull'),
    ];
    this.loaded = true;
  }

  private toggleEnabled(id: string): void {
    this.categories = this.categories.map((c: ExtensionCategory): ExtensionCategory => {
      if (c.id !== id) return c;
      return makeCategory(c.id, c.title, c.desc, c.count, !c.enabled, c.targetPage);
    });
  }

  private moveUp(idx: number): void {
    if (idx <= 0) return;
    const arr: ExtensionCategory[] = this.categories.slice();
    const tmp: ExtensionCategory = arr[idx - 1];
    arr[idx - 1] = arr[idx];
    arr[idx] = tmp;
    this.categories = arr;
  }

  private moveDown(idx: number): void {
    const arr: ExtensionCategory[] = this.categories.slice();
    if (idx >= arr.length - 1) return;
    const tmp: ExtensionCategory = arr[idx + 1];
    arr[idx + 1] = arr[idx];
    arr[idx] = tmp;
    this.categories = arr;
  }

  private navigate(target: string): void {
    if (target.length === 0) return;
    router.pushUrl({ url: target });
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('扩展管理').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('扩展类别')

            if (this.categories.length === 0) {
              Column() {
                Text('暂无扩展类别').fontSize(BODY).fontColor(INK3)
                Text('请先在对应设置页配置扩展').fontSize(LABEL).fontColor(INK4).margin({ top: 4 })
              }
              .width('100%').alignItems(HorizontalAlign.Center)
              .padding({ top: 32, bottom: 32 })
            }

            ForEach(this.categories, (c: ExtensionCategory, idx: number): void => {
              Column() {
                Row() {
                  Column() {
                    Text(c.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                    Text(c.desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)
                  AmberSwitch({
                    checked: c.enabled,
                    switchSize: SwitchSize.Small,
                    onCheckedChange: (_v: boolean): void => { this.toggleEnabled(c.id); },
                  })
                }
                .width('100%')

                Row() {
                  Text(`${c.count} 项`).fontSize(META).fontColor(ACCENT)
                  Blank()
                  Text('▲').fontSize(META).fontColor(idx > 0 ? INK2 : INK4)
                    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
                    .onClick((): void => { this.moveUp(idx); })
                  Text('▼').fontSize(META).fontColor(idx < this.categories.length - 1 ? INK2 : INK4)
                    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
                    .onClick((): void => { this.moveDown(idx); })
                  Text('管理 ›').fontSize(META).fontColor(ACCENT)
                    .padding({ left: 10, right: 4, top: 6, bottom: 6 })
                    .onClick((): void => { this.navigate(c.targetPage); })
                }
                .width('100%').margin({ top: 8 })
                .border({ width: { top: 0.5 }, color: { top: LINE } })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding(12).backgroundColor(SURFACE).borderRadius(12)
              .border({ width: 1, color: LINE }).margin({ left: 16, right: 16, bottom: 10 })
            }, (c: ExtensionCategory): string => c.id)

            this.sectionHeader('说明')
            Column() {
              Text('优先级影响 Agent 在多扩展可用时的调度顺序(上→下)。')
                .fontSize(META).fontColor(INK3).lineHeight(18)
              Text('关闭某类扩展后,Agent 将不再调用该类别下的任何扩展。')
                .fontSize(META).fontColor(INK3).lineHeight(18).margin({ top: 6 })
            }
            .width('100%').padding({ left: 16, right: 16, top: 10, bottom: 10 })
            .backgroundColor(SURFACE2).borderRadius(10)
            .margin({ left: 16, right: 16 })

            Column() {}.height(32)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
