// AssistantListPage — 助手列表管理页(简化自 Android AssistantPage.kt 589 行)
// 功能:助手卡(emoji 头像/名称/描述/模型徽章)+ 搜索 + 排序 + 新建 +
//   点击 → AssistantDetailPage;长按 → 删除确认(级联删会话);剪贴板导入;空状态。
// 偏差登记:
//   - Assistant 模型无 lastUsed/createdAt 字段(P1):name 排序有效;
//     lastUsed 保持插入序;created 用逆插入序(新追加者居首)作启发式近似。
//   - 长按删除 = AlertDialog 确认 + 级联删该助手会话(同 ChatAssistantsPage 语义)。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { loadAssistants, saveAssistants, makeAssistant } from '@amber/chat-domain';
import type {
  Assistant, Avatar, Conversation, ConversationSummary,
} from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';
import { PillSelect } from '../components/ui/Select.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE, GUTTER, RADIUS,
} from '../design/tokens.ets';

// JSON 导入结构(字段均可缺省,同 AssistantImporter)
interface ImportedAssistant {
  name?: string;
  systemPrompt?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
}

const SORT_LABELS: string[] = ['按名称', '按最近使用', '按创建时间'];

@Entry
@Component
struct AssistantListPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State items: Assistant[] = [];
  @State loaded: boolean = false;
  @State query: string = '';
  @State sortIndex: number = 0;
  @State statusMsg: string = '';

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    loadAssistants(getChatKvStore()).then((list: Assistant[] | null): void => {
      this.items = list === null ? [] : list;
      this.loaded = true;
    });
  }

  // 过滤 + 排序(名称 localeCompare;lastUsed/created 偏差见文件头)
  private filtered(): Assistant[] {
    const q: string = this.query.trim().toLowerCase();
    const filtered: Assistant[] = q.length === 0
      ? this.items.slice()
      : this.items.filter((a: Assistant): boolean => a.name.toLowerCase().includes(q));
    if (this.sortIndex === 0) {
      return filtered.sort((a: Assistant, b: Assistant): number => a.name.localeCompare(b.name));
    }
    if (this.sortIndex === 2) {
      // created 近似:逆插入序(新追加者居首)
      return filtered.reverse();
    }
    // lastUsed 偏差:保持插入序
    return filtered;
  }

  private avatarText(a: Assistant): string {
    const av: Avatar = a.avatar;
    if (av.type === 'emoji' && av.content.length > 0) return av.content;
    return '🤖';
  }

  private modelBadge(a: Assistant): string {
    return a.chatModelId !== null && a.chatModelId.length > 0
      ? `模型 · ${a.chatModelId.substring(0, 8)}`
      : '默认模型';
  }

  private description(a: Assistant): string {
    const s: string = a.systemPrompt.trim();
    if (s.length === 0) return '未设置系统提示词';
    return s;
  }

  private gotoDetail(a: Assistant): void {
    const params: Record<string, string> = {};
    params['assistantId'] = a.id;
    router.pushUrl({ url: 'pages/AssistantDetailPage', params: params });
  }

  private async addAssistant(): Promise<void> {
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const created: Assistant = makeAssistant({ name: '新助手' });
    await saveAssistants(kv, [...base, created]);
    const params: Record<string, string> = {};
    params['assistantId'] = created.id;
    router.pushUrl({ url: 'pages/AssistantDetailPage', params: params });
  }

  // 长按删除:AlertDialog 确认 + 级联删该助手会话(同 ChatAssistantsPage 语义)
  private confirmDelete(a: Assistant): void {
    AlertDialog.show({
      title: '删除助手',
      message: `「${a.name.length > 0 ? a.name : '未命名助手'}」及其所有对话将被删除,且不可恢复。`,
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '删除',
        fontColor: 'ERROR',
        action: (): void => {
          loadAssistants(getChatKvStore()).then((loaded: Assistant[] | null): void => {
            const current: Assistant[] = loaded !== null && loaded.length > 0 ? loaded : this.items;
            const next: Assistant[] = current.filter((x: Assistant): boolean => x.id !== a.id);
            saveAssistants(getChatKvStore(), next).then((): Promise<void> => {
              const repo = getChatRepository();
              return repo.list(a.id).then((convs: ConversationSummary[]): Promise<void> => {
                let chain: Promise<void> = Promise.resolve();
                for (const c of convs) {
                  chain = chain.then((): Promise<void> =>
                    repo.getById(c.id).then((full: Conversation | null): Promise<void> => {
                      if (full === null) return Promise.resolve();
                      return repo.delete(c.id).then((): Promise<void> =>
                        cleanupDeletedConversation(full, repo, getChatKvStore()));
                    }));
                }
                return chain;
              });
            }).then((): void => this.refresh());
          });
        },
      },
    });
  }

  // ===== 剪贴板导入 =====

  private readClipboard(): string {
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      if (!board.hasDataSync()) return '';
      return board.getDataSync().getPrimaryText() ?? '';
    } catch (e) {
      return '';
    }
  }

  private async importFromClipboard(): Promise<void> {
    const text: string = this.readClipboard();
    if (text.trim().length === 0) { this.statusMsg = '剪贴板为空'; return; }
    try {
      const obj = JSON.parse(text) as ImportedAssistant;
      const name: string = obj.name !== undefined ? obj.name.trim() : '';
      if (name.length === 0) { this.statusMsg = 'name 不能为空'; return; }
      const kv = getChatKvStore();
      const list: Assistant[] | null = await loadAssistants(kv);
      const base: Assistant[] = list === null ? [] : list.slice();
      const created: Assistant = makeAssistant({
        name: name,
        systemPrompt: obj.systemPrompt !== undefined ? obj.systemPrompt : '',
        temperature: obj.temperature !== undefined ? obj.temperature : null,
        topP: obj.topP !== undefined ? obj.topP : null,
        maxTokens: obj.maxTokens !== undefined && obj.maxTokens > 0 ? obj.maxTokens : null,
      });
      await saveAssistants(kv, [...base, created]);
      this.statusMsg = '已导入助手';
      this.refresh();
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.statusMsg = `导入失败:${msg.substring(0, 80)}`;
    }
  }

  @Builder
  assistantCard(a: Assistant) {
    Column() {
      Row() {
        Text(this.avatarText(a))
          .fontSize(20).width(40).height(40).textAlign(TextAlign.Center)
          .backgroundColor(SURFACE2).borderRadius(20)
        Column() {
          Text(a.name.length > 0 ? a.name : '未命名助手')
            .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(this.description(a))
            .fontSize(META).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
        Text(this.modelBadge(a))
          .fontSize(LABEL).fontColor(INK2)
          .padding({ left: 6, right: 6, top: 2, bottom: 2 })
          .backgroundColor(SURFACE2).borderRadius(6)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .width('100%')
    }
    .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).borderRadius(RADIUS)
    .border({ width: 1, color: LINE }).margin({ bottom: 8 })
    .onClick((): void => this.gotoDetail(a))
    .gesture(
      LongPressGesture()
        .onAction((): void => { this.confirmDelete(a); })
    )
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 })
          .onClick((): void => { router.back(); })
        Text('助手列表').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('+ 新建').fontSize(BODY).fontColor(Color.White).maxLines(1)
          .backgroundColor(ACCENT).borderRadius(8)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .constraintSize({ minHeight: 40 })
          .onClick((): void => { this.addAssistant(); })
        Text('导入').fontSize(BODY).fontColor(ACCENT).maxLines(1)
          .margin({ left: 8 })
          .padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .constraintSize({ minHeight: 40 })
          .backgroundColor(ACCENT_LIGHT).borderRadius(8)
          .onClick((): void => { this.importFromClipboard(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      // 搜索栏
      Row() {
        TextInput({ text: this.query, placeholder: '搜索助手名称' })
          .fontSize(BODY).height(40).backgroundColor(SURFACE)
          .borderRadius(8).layoutWeight(1)
          .onChange((v: string): void => { this.query = v; })
      }
      .width('100%').padding({ left: GUTTER, right: GUTTER, top: 8, bottom: 8 })

      // 排序栏
      Row() {
        Text('排序').fontSize(META).fontColor(INK3).margin({ right: 12 })
        PillSelect({
          options: SORT_LABELS,
          selectedIndex: this.sortIndex,
          onSelect: (i: number): void => { this.sortIndex = i; },
        })
        Blank()
        if (this.statusMsg.length > 0) {
          Text(this.statusMsg).fontSize(LABEL).fontColor(INK3)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
      }
      .width('100%').padding({ left: GUTTER, right: GUTTER, bottom: 8 })

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else if (this.filtered().length === 0) {
        // 空状态
        Column() {
          Text(this.query.trim().length > 0 ? '无匹配助手' : '暂无助手')
            .fontSize(BODY).fontColor(INK3)
          Text(this.query.trim().length > 0 ? '尝试其他关键词' : '点击右上「+ 新建」创建第一个助手')
            .fontSize(META).fontColor(INK3).margin({ top: 8 })
        }
        .width('100%').padding({ top: 64, bottom: 64 }).alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            ForEach(this.filtered(), (a: Assistant): void => {
              this.assistantCard(a)
            }, (a: Assistant): string => a.id)
          }
          .width('100%').padding({ left: GUTTER, right: GUTTER, bottom: 16 })
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
