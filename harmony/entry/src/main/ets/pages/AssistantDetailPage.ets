// AssistantDetailPage — 助手详情 Tab 枢纽
// 顶部展示助手头像/名称;下方 5 tab(基础 | 提示词 | 请求 | 扩展 | 记忆),
// 点击 tab 经 router.pushUrl 携带 assistantId 跳转到对应子页。
import { router } from '@kit.ArkUI';
import { loadAssistants } from '@amber/chat-domain';
import type { Assistant, Avatar } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';

interface RouterParams {
  assistantId?: string;
}

interface TabItem {
  label: string;
  target: string;
}

@Entry
@Component
struct AssistantDetailPage {
  @State loaded: boolean = false;
  @State assistant: Assistant | null = null;
  @State activeTab: number = 0;
  private assistantId: string = '';
  private tabs: TabItem[] = [
    { label: '基础', target: 'pages/AssistantBasicPage' },
    { label: '提示词', target: 'pages/AssistantPromptPage' },
    { label: '请求', target: 'pages/AssistantRequestPage' },
    { label: '扩展', target: 'pages/AssistantExtensionsPage' },
    { label: '记忆', target: 'pages/AssistantMemoryPage' },
  ];

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined ? params.assistantId : '';
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const list: Assistant[] | null = await loadAssistants(getChatKvStore());
    const found: Assistant | null = list === null
      ? null
      : (list.find((a: Assistant): boolean => a.id === this.assistantId) ?? null);
    this.assistant = found;
    this.loaded = true;
  }

  // 头像文本(emoji 优先,否则占位)
  private avatarText(a: Assistant): string {
    const av: Avatar = a.avatar;
    if (av.type === 'emoji' && av.content.length > 0) return av.content;
    return '🤖';
  }

  private displayName(): string {
    return this.assistant !== null && this.assistant.name.length > 0 ? this.assistant.name : '未命名助手';
  }

  private displayAvatar(): string {
    return this.assistant !== null ? this.avatarText(this.assistant) : '🤖';
  }

  private displayIdSnippet(): string {
    return this.assistant !== null ? `ID · ${this.assistant.id.substring(0, 8)}` : '';
  }

  private promptPreview(): string {
    return this.assistant !== null && this.assistant.systemPrompt.trim().length > 0
      ? this.assistant.systemPrompt : '(未设置系统提示词)';
  }

  private promptHasContent(): boolean {
    return this.assistant !== null && this.assistant.systemPrompt.trim().length > 0;
  }

  private gotoTab(index: number): void {
    this.activeTab = index;
    const tab: TabItem = this.tabs[index];
    const params: Record<string, string> = {};
    params['assistantId'] = this.assistantId;
    router.pushUrl({ url: tab.target, params: params });
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('助手详情').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.assistant === null) {
        Text('助手不存在').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        // 顶部助手卡:头像 + 名称
        Row() {
          Text(this.displayAvatar())
            .fontSize(28).width(56).textAlign(TextAlign.Center)
            .backgroundColor(SURFACE2).borderRadius(28)
          Column() {
            Text(this.displayName())
              .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
            Text(this.displayIdSnippet())
              .fontSize(LABEL).fontColor(INK3).margin({ top: 4 })
          }
          .alignItems(HorizontalAlign.Start).margin({ left: 12 }).layoutWeight(1)
        }
        .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 16 })
        .backgroundColor(SURFACE)
        .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

        // Tab 栏
        Row() {
          ForEach(this.tabs, (t: TabItem, i: number): void => {
            Column() {
              Text(t.label)
                .fontSize(META)
                .fontColor(this.activeTab === i ? ACCENT : INK3)
              Text('')
                .width(24).height(2).borderRadius(1)
                .backgroundColor(this.activeTab === i ? ACCENT : 'transparent')
                .margin({ top: 6 })
            }
            .layoutWeight(1)
            .padding({ top: 10, bottom: 10 })
            .onClick((): void => { this.gotoTab(i); })
          }, (t: TabItem, i: number): string => `tab-${i}`)
        }
        .width('100%').backgroundColor(SURFACE)
        .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

        // 系统提示词预览
        Scroll() {
          Column() {
            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 系统提示词').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, top: 20, bottom: 6 })

            Text(this.promptPreview())
              .fontSize(BODY).fontColor(this.promptHasContent() ? INK2 : INK3)
              .width('100%').padding({ left: 16, right: 16 })
              .maxLines(6).textOverflow({ overflow: TextOverflow.Ellipsis })

            // 额外编辑入口
            Row() {
              Row() {
                Icon({ name: 'palette', iconSize: META, color: INK3 })
                Text(' 背景设置').fontSize(META).fontColor(INK3)
              }
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .backgroundColor(SURFACE2).borderRadius(8)
              .margin({ right: 8 })
              .onClick((): void => {
                const params: RouterParams = { assistantId: this.assistantId };
                router.pushUrl({ url: 'pages/AssistantBackgroundPicker', params: params });
              })
              Row() {
                Icon({ name: 'gear', iconSize: META, color: INK3 })
                Text(' 属性编辑').fontSize(META).fontColor(INK3)
              }
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .backgroundColor(SURFACE2).borderRadius(8)
              .onClick((): void => {
                const params: RouterParams = { assistantId: this.assistantId };
                router.pushUrl({ url: 'pages/AssistantPropertyEditor', params: params });
              })
            }
            .width('100%').padding({ left: 16, top: 16 })

            Column() {}.height(40)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
