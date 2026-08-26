// ChatAssistantLocalToolsPage — 助手本地工具开关面(D-107)
// Android 锚点:
//   AssistantLocalToolPage.kt(全文 280 行)— 13 个 LocalToolOption 行,
//     顺序逐字;title/desc zh 逐字;toggleLocalTool(:71-84):
//     enable → localTools + option;disable → WebMount 连带移除 WebMountEval,
//     否则 - option;WebMountEval 开关 enabled = WebMount 已开(:273)
//   values-zh/strings.xml:83-92,1294-1303
// 偏差登记:
//   - workspace/terminal/screen 三项 zh 缺失(values-zh 无该键)→ 按 Android
//     zh 回退行为取 values/strings.xml:998-1003 en 逐字
//   - detail tab(AssistantDetailPage tab 之一)→ 独立页,入口在助手行 '工具'
//   - 平台专属工具(terminal/screen/system_access/webview/icloud/webmount 系)
//     在 harmony 无实现,开关仅落 localTools 配置(Android 同为纯配置面;
//     工具实体缺失时目录自然不含)
import { ACCENT,BG,INK,INK3,LINE,SURFACE,GUTTER,RADIUS,BODY,BODY2,META } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { router } from '@kit.ArkUI';
import {
  loadAssistants, saveAssistants, patchAssistant,
} from '@amber/chat-domain';
import type { Assistant, LocalToolOption } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';


interface RouterParams {
  assistantId?: string;
}

interface LocalToolRow {
  option: LocalToolOption;
  title: string;
  desc: string;
}

// 行序 = AssistantLocalToolPage.kt item 声明序(:95-277)
const LOCAL_TOOL_ROWS: LocalToolRow[] = [
  {
    option: 'javascript_engine', title: 'JavaScript 引擎',
    desc: '使用 QuickJS 引擎执行 JavaScript 代码，进行计算和脚本处理',
  },
  {
    option: 'time_info', title: '时间信息',
    desc: '从设备获取当前日期、星期、时间和时区信息',
  },
  {
    option: 'clipboard', title: '剪贴板',
    desc: '读取或写入设备剪贴板中的纯文本',
  },
  {
    option: 'tts', title: '文字转语音',
    desc: '让AI使用设备TTS引擎为您朗读文本',
  },
  {
    option: 'ask_user', title: '询问用户',
    desc: '允许 AI 向你提问以获得更多信息',
  },
  {
    option: 'workspace_files', title: 'Workspace file tools',
    desc: 'List, read, write, edit, search, and move files in the user-authorized workspace.',
  },
  {
    option: 'terminal', title: 'Terminal tools',
    desc: 'Run commands and persistent terminal sessions in the AmberAgent runtime workspace.',
  },
  {
    option: 'screen_automation', title: 'Screen automation tools',
    desc: 'Use approved low-level screen actions and VLM task orchestration.',
  },
  {
    option: 'system_access', title: '系统权限工具',
    desc: '允许 Agent 使用经授权的短信、通讯录、日历、媒体、定位、通知和应用工具。',
  },
  {
    option: 'webview', title: 'WebView 预览工具',
    desc: '允许 Agent 在实时操作预览中打开网页 URL。',
  },
  {
    option: 'icloud_drive', title: 'iCloud Drive 工具',
    desc: '允许 Agent 使用实验性的 iCloud WebMount 读取配置好的 Obsidian 或 iCloud Drive 文件。',
  },
  {
    option: 'webmount', title: 'WebMount 浏览器工具',
    desc: '允许 Agent 操控隐藏 WebView:打开网址、提取 DOM、点击、输入、滚动、截图。' +
      '默认关闭。任意 JS 执行 (wm_eval) 由下方独立开关单独控制。',
  },
  {
    option: 'webmount_eval', title: 'WebMount:任意 JS (wm_eval)',
    desc: '允许 Agent 调用 wm_eval —— 在 WebMount 会话页面 origin 下执行任意 JavaScript ' +
      '(可读取 cookie / sessionStorage / localStorage,以及同源带凭据请求)。' +
      '每次调用仍需要人工逐次确认,不可自动批准 —— 但开启此项会把工具放入 catalog。' +
      '需要 WebMount 开关也打开。默认关闭。',
  },
];

@Entry
@Component
struct ChatAssistantLocalToolsPage {
  @State loaded: boolean = false;
  @State assistant: Assistant | null = null;
  private assistantId: string = '';

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined
      ? params.assistantId : '';
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const assistants: Assistant[] | null = await loadAssistants(getChatKvStore());
    this.assistant = assistants === null
      ? null
      : (assistants.find((a: Assistant): boolean => a.id === this.assistantId) ?? null);
    this.loaded = true;
  }

  private hasTool(option: LocalToolOption): boolean {
    return this.assistant !== null && this.assistant.localTools.indexOf(option) >= 0;
  }

  // toggleLocalTool(:71-84 逐字):enable → + option(Set 去重);
  //   disable → webmount 连带 - webmount_eval(Phase 2 M2.0.2:重开 WebMount
  //   不静默带回 wm_eval),否则 - option
  private async toggleLocalTool(option: LocalToolOption, enabled: boolean): Promise<void> {
    if (this.assistant === null) return;
    const cur: Assistant = this.assistant;
    let next: LocalToolOption[];
    if (enabled) {
      next = cur.localTools.indexOf(option) >= 0
        ? cur.localTools.slice() : [...cur.localTools, option];
    } else if (option === 'webmount') {
      next = cur.localTools.filter(
        (x: LocalToolOption): boolean => x !== 'webmount' && x !== 'webmount_eval');
    } else {
      next = cur.localTools.filter((x: LocalToolOption): boolean => x !== option);
    }
    await this.saveAssistant(patchAssistant(cur, { localTools: next }));
  }

  private async saveAssistant(next: Assistant): Promise<void> {
    const kv = getChatKvStore();
    const assistants: Assistant[] | null = await loadAssistants(kv);
    const list: Assistant[] = assistants === null ? [] : assistants.slice();
    const index: number = list.findIndex((a: Assistant): boolean => a.id === next.id);
    if (index >= 0) list[index] = next;
    else list.push(next);
    await saveAssistants(kv, list);
    this.assistant = next;
  }

  build() {
    Column() {
      PageTitleBar({ title: '本地工具' })

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY2).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else if (this.assistant === null) {
        Column() {
          Text('助手不存在').fontSize(BODY2).fontColor(INK3)
        }.width('100%').padding({ top: 64, bottom: 64 }).alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            ForEach(LOCAL_TOOL_ROWS, (row: LocalToolRow, i: number) => {
              Row() {
                Column() {
                  Text(row.title)
                    .fontSize(BODY).fontColor(INK)
                    .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(row.desc)
                    .fontSize(META).fontColor(INK3).margin({ top: 3 })
                    .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1)
                .margin({ right: 12 })
                Toggle({
                  type: ToggleType.Switch,
                  isOn: this.hasTool(row.option),
                })
                  .selectedColor(ACCENT)
                  // :273 — wm_eval 开关 enabled = WebMount 已开
                  .enabled(row.option !== 'webmount_eval' || this.hasTool('webmount'))
                  .onChange((v: boolean): void => {
                    this.toggleLocalTool(row.option, v);
                  })
              }
              .width('100%')
              .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
              .border({
                width: { bottom: i < LOCAL_TOOL_ROWS.length - 1 ? 0.5 : 0 },
                color: LINE,
              })
            }, (row: LocalToolRow): string => row.option)
          }
          .width('100%')
          .backgroundColor(SURFACE)
          .borderRadius(RADIUS)
          .border({ width: 1, color: LINE })
          .margin({ left: GUTTER, right: GUTTER, bottom: 24 })
        }
        .align(Alignment.Start)
        .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
