// SettingAgentPermissionsPageFull — Agent 权限设置页(Full)
// 工具调用权限:每项工具 allow/ask/deny 三态(PillSelect) +
//   按类别分组(file / terminal / web / mcp / memory) +
//   全局默认策略选择器
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE,
} from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface ToolPermission {
  id: string;
  name: string;
  category: string;   // file / terminal / web / mcp / memory
  policyIndex: number; // 0=允许 1=询问 2=拒绝
}

const POLICY_OPTIONS: string[] = ['允许', '询问', '拒绝'];
const CATEGORY_LABELS: Record<string, string> = {
  'file': '文件',
  'terminal': '终端',
  'web': '网络',
  'mcp': 'MCP',
  'memory': '记忆',
};
const CATEGORY_ORDER: string[] = ['file', 'terminal', 'web', 'mcp', 'memory'];

function makePerm(id: string, name: string, category: string, policyIndex: number): ToolPermission {
  return { id: id, name: name, category: category, policyIndex: policyIndex };
}

function seedPermissions(): ToolPermission[] {
  return [
    makePerm('file-read', '读取文件', 'file', 0),
    makePerm('file-write', '写入文件', 'file', 1),
    makePerm('file-delete', '删除文件', 'file', 2),
    makePerm('term-exec', '执行命令', 'terminal', 1),
    makePerm('term-shell', '交互式 Shell', 'terminal', 2),
    makePerm('web-fetch', '抓取网页', 'web', 0),
    makePerm('web-search', '联网搜索', 'web', 0),
    makePerm('mcp-call', '调用 MCP 工具', 'mcp', 1),
    makePerm('mem-write', '写入记忆', 'memory', 0),
    makePerm('mem-read', '读取记忆', 'memory', 0),
  ];
}

function placeholderLoadPermissions(): Promise<ToolPermission[]> {
  return Promise.resolve(seedPermissions());
}

@Entry
@Component
struct SettingAgentPermissionsPageFull {
  @State permissions: ToolPermission[] = [];
  @State defaultPolicyIndex: number = 1;
  @State loaded: boolean = false;
  @State savedMsg: string = '';

  aboutToAppear(): void {
    placeholderLoadPermissions().then((list: ToolPermission[]): void => {
      this.permissions = list;
      this.loaded = true;
    });
  }

  private setPolicy(id: string, i: number): void {
    this.permissions = this.permissions.map((p: ToolPermission): ToolPermission => {
      if (p.id !== id) return p;
      return makePerm(p.id, p.name, p.category, i);
    });
  }

  private save(): void {
    this.savedMsg = '已保存';
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.save();
    router.back();
  }

  // 返回某类别下的工具(在 @Builder 内调用 → 必须为纯函数,无副作用)
  private permsInCategory(category: string): ToolPermission[] {
    return this.permissions.filter((p: ToolPermission): boolean => p.category === category);
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder permRow(p: ToolPermission): void {
    Row() {
      Column() {
        Text(p.name).fontSize(BODY).fontColor(INK)
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      PillSelect({
        options: POLICY_OPTIONS, selectedIndex: p.policyIndex,
        onSelect: (i: number): void => { this.setPolicy(p.id, i); },
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
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('Agent 权限').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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
            this.sectionHeader('全局默认策略')

            Row() {
              Column() {
                Text('默认策略').fontSize(BODY).fontColor(INK)
                Text('未单独配置的工具采用此策略').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              PillSelect({
                options: POLICY_OPTIONS, selectedIndex: this.defaultPolicyIndex,
                onSelect: (i: number): void => { this.defaultPolicyIndex = i; },
              })
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            // 按类别分组
            ForEach(CATEGORY_ORDER, (cat: string): void => {
              Column() {
                Text(CATEGORY_LABELS[cat]).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
                  .width('100%').padding({ left: 16, top: 16, bottom: 4 })
                ForEach(this.permsInCategory(cat), (p: ToolPermission): void => {
                  this.permRow(p)
                }, (p: ToolPermission): string => p.id)
              }
              .width('100%').alignItems(HorizontalAlign.Start)
            }, (cat: string): string => cat)

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
  }
}
