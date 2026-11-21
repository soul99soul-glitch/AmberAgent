// AmberHubsSheet — 底栏「专题中心」原位面板
// 轻量本地 Topic Hubs：聚合关联对话 + 专属指令；近 14 天内 ≥4 条同主题未归类对话自动推荐一键收拢。

import type { ConversationSummary } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../../di/AppContainer.ets';
import {
  AmberHubRecord, SmartHubSuggestion, createAmberHub, deleteAmberHub,
  detectSmartHubCluster, loadAmberHubs, toggleConversationInHub, updateAmberHubInstruction,
} from '../../platform_impl/chat/AmberHubStore.ets';
import {
  ACCENT, ERROR, INK, INK2, INK3, INK4,
  LINE, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';

@Component
export struct AmberHubsSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop currentConversationId: string = '';

  onBackToChat: () => void = (): void => {};
  onOpenConversation: (conversationId: string) => void = (_conversationId: string): void => {};
  onStartWithInstruction: (hubId: string, instruction: string, title: string) => void =
    (_id: string, _inst: string, _title: string): void => {};
  onOpenMiniApps: () => void = (): void => {};

  @State private hubs: AmberHubRecord[] = [];
  @State private summaries: ConversationSummary[] = [];
  @State private suggestion: SmartHubSuggestion | null = null;
  @State private loading: boolean = false;
  @State private creating: boolean = false;
  @State private selectedHubId: string = '';

  @State private draftTitle: string = '';
  @State private draftInstruction: string = '';
  @State private editingInstruction: string = '';

  aboutToAppear(): void {
    this.reload();
  }

  private reload(): void {
    this.loading = true;
    Promise.all([loadAmberHubs(getChatKvStore()), getChatRepository().list()])
      .then((res): void => {
        this.hubs = res[0];
        this.summaries = res[1];
        this.suggestion = detectSmartHubCluster(this.summaries, this.hubs);
      })
      .catch((): void => {
        this.suggestion = null;
      })
      .finally((): void => {
        this.loading = false;
      });
  }

  private applyResult(nextHubs: AmberHubRecord[]): void {
    this.hubs = nextHubs;
    this.suggestion = detectSmartHubCluster(this.summaries, this.hubs);
  }

  private acceptSmartSuggestion(): void {
    const sug: SmartHubSuggestion | null = this.suggestion;
    if (sug === null) return;
    const ids: string[] = sug.matchedConversations.map((c: ConversationSummary): string => c.id);
    createAmberHub(getChatKvStore(), {
      title: sug.topicLabel,
      description: `由近 14 天内 ${ids.length} 条相似对话自动聚合`,
      customInstruction: `当前位于「${sug.topicLabel}」专题中心，请延续该主题的上下文和术语约定。`,
      conversationIds: ids,
    }).then((next: AmberHubRecord[]): void => {
      this.applyResult(next);
      if (next.length > 0) this.selectHub(next[0]);
    }).catch((): void => {});
  }

  private createManualHub(): void {
    const title: string = this.draftTitle.trim();
    if (title.length === 0) return;
    const initialIds: string[] = this.currentConversationId.length > 0 ? [this.currentConversationId] : [];
    createAmberHub(getChatKvStore(), {
      title,
      customInstruction: this.draftInstruction.trim(),
      conversationIds: initialIds,
    }).then((next: AmberHubRecord[]): void => {
      this.draftTitle = '';
      this.draftInstruction = '';
      this.creating = false;
      this.applyResult(next);
    }).catch((): void => {});
  }

  private selectHub(hub: AmberHubRecord): void {
    if (this.selectedHubId === hub.id) {
      this.selectedHubId = '';
      return;
    }
    this.selectedHubId = hub.id;
    this.editingInstruction = hub.customInstruction;
  }

  private selectedHub(): AmberHubRecord | null {
    return this.hubs.find((h: AmberHubRecord): boolean => h.id === this.selectedHubId) ?? null;
  }

  private conversationTitleById(id: string): string {
    const found: ConversationSummary | undefined = this.summaries.find(
      (c: ConversationSummary): boolean => c.id === id,
    );
    return found !== undefined && found.title.trim().length > 0 ? found.title : '历史对话';
  }

  build(): void {
    Column({ space: 10 }) {
      Row({ space: 8 }) {
        Text('专题中心')
          .fontSize(15)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
        Blank()
        Text(this.creating ? '取消' : '+ 新建专题')
          .fontSize(12)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.accentColorStore)
          .padding({ left: 10, right: 10, top: 4, bottom: 4 })
          .borderRadius(999)
          .backgroundColor(accentSoftBg(this.accentColorStore))
          .onClick((): void => {
            this.creating = !this.creating;
            this.selectedHubId = '';
          })
        Text('扩展小应用 ›')
          .fontSize(12)
          .fontColor(INK3)
          .onClick((): void => { this.onOpenMiniApps(); })
      }
      .width('100%')

      if (this.creating) {
        Column({ space: 8 }) {
          TextInput({ text: this.draftTitle, placeholder: '专题名称，例如：Harmony 重构' })
            .fontSize(13.5)
            .fontColor(INK)
            .backgroundColor(SURFACE2)
            .borderRadius(12)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onChange((v: string): void => { this.draftTitle = v; })
          TextArea({ text: this.draftInstruction, placeholder: '可选专属指令：该专题下希望 Amber 保持的视角与习惯' })
            .height(68)
            .fontSize(13)
            .fontColor(INK)
            .backgroundColor(SURFACE2)
            .borderRadius(12)
            .padding(10)
            .onChange((v: string): void => { this.draftInstruction = v; })
          Button('创建专题并关联当前对话')
            .width('100%')
            .height(36)
            .fontSize(13)
            .backgroundColor(this.accentColorStore)
            .fontColor('#FFFFFF')
            .enabled(this.draftTitle.trim().length > 0)
            .onClick((): void => { this.createManualHub(); })
        }
        .width('100%')
      }

      if (this.suggestion !== null && !this.creating) {
        Row({ space: 10 }) {
          Column({ space: 2 }) {
            Text(`发现高频话题「${this.suggestion.topicLabel}」`)
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor(INK)
            Text(`近 14 天已有 ${this.suggestion.matchedConversations.length} 条相关对话，可一键收拢为专注专题`)
              .fontSize(11.5)
              .fontColor(INK3)
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)

          Text('收拢')
            .fontSize(12)
            .fontWeight(FontWeight.Medium)
            .fontColor('#FFFFFF')
            .padding({ left: 10, right: 10, top: 6, bottom: 6 })
            .borderRadius(999)
            .backgroundColor(this.accentColorStore)
            .onClick((): void => { this.acceptSmartSuggestion(); })
        }
        .width('100%')
        .padding(12)
        .borderRadius(16)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .border({ width: 0.75, color: accentEdge(this.accentColorStore) })
      }

      if (this.loading) {
        Text('正在读取专题…').fontSize(12.5).fontColor(INK3).width('100%')
      } else if (this.hubs.length === 0 && !this.creating) {
        Text('把同类长期对话收拢在一起，附上专属指令，随时续聊。点击右上角「+ 新建专题」开始。')
          .fontSize(12.5)
          .fontColor(INK3)
          .width('100%')
          .padding(10)
          .borderRadius(14)
          .backgroundColor(SURFACE2)
      } else if (!this.creating) {
        Scroll() {
          Column({ space: 8 }) {
            ForEach(this.hubs, (hub: AmberHubRecord): void => {
              Column({ space: 6 }) {
                Row({ space: 8 }) {
                  Text(hub.title)
                    .fontSize(13.5)
                    .fontWeight(FontWeight.Medium)
                    .fontColor(INK)
                    .layoutWeight(1)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(`${hub.conversationIds.length} 篇`)
                    .fontSize(11.5)
                    .fontColor(INK3)
                  Text(this.selectedHubId === hub.id ? '收起' : '管理')
                    .fontSize(11.5)
                    .fontColor(this.accentColorStore)
                }
                .width('100%')
                .onClick((): void => { this.selectHub(hub); })

                if (hub.customInstruction.length > 0 && this.selectedHubId !== hub.id) {
                  Text(hub.customInstruction)
                    .fontSize(11.5)
                    .fontColor(INK3)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .width('100%')
                }

                if (this.selectedHubId === hub.id) {
                  Column({ space: 8 }) {
                    TextInput({ text: this.editingInstruction, placeholder: '该专题专属指令（留空则无）' })
                      .fontSize(12.5)
                      .fontColor(INK)
                      .backgroundColor(SURFACE)
                      .borderRadius(10)
                      .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                      .onChange((v: string): void => { this.editingInstruction = v; })

                    Row({ space: 8 }) {
                      Text('保存指令')
                        .fontSize(11.5)
                        .fontColor(this.accentColorStore)
                        .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                        .borderRadius(999)
                        .backgroundColor(SURFACE)
                        .onClick((): void => {
                          updateAmberHubInstruction(getChatKvStore(), hub.id, this.editingInstruction).then((next): void => {
                            this.applyResult(next);
                          }).catch((): void => {});
                        })
                      if (this.currentConversationId.length > 0) {
                        Text(hub.conversationIds.indexOf(this.currentConversationId) >= 0 ? '移出当前对话' : '加入当前对话')
                          .fontSize(11.5)
                          .fontColor(INK2)
                          .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                          .borderRadius(999)
                          .backgroundColor(SURFACE)
                          .onClick((): void => {
                            toggleConversationInHub(getChatKvStore(), hub.id, this.currentConversationId).then((next): void => {
                              this.applyResult(next);
                            }).catch((): void => {});
                          })
                      }
                      Text('以此发起新聊')
                        .fontSize(11.5)
                        .fontColor('#FFFFFF')
                        .padding({ left: 9, right: 9, top: 4, bottom: 4 })
                        .borderRadius(999)
                        .backgroundColor(this.accentColorStore)
                        .onClick((): void => {
                          this.onStartWithInstruction(hub.id, this.editingInstruction, hub.title);
                        })
                      Blank()
                      Text('删除')
                        .fontSize(11.5)
                        .fontColor(ERROR)
                        .onClick((): void => {
                          deleteAmberHub(getChatKvStore(), hub.id).then((next): void => {
                            this.selectedHubId = '';
                            this.applyResult(next);
                          }).catch((): void => {});
                        })
                    }
                    .width('100%')

                    if (hub.conversationIds.length > 0) {
                      ForEach(hub.conversationIds.slice(0, 6), (cid: string): void => {
                        Row() {
                          Text(this.conversationTitleById(cid))
                            .fontSize(12)
                            .fontColor(INK2)
                            .maxLines(1)
                            .textOverflow({ overflow: TextOverflow.Ellipsis })
                            .layoutWeight(1)
                          Text('打开 ›').fontSize(11).fontColor(this.accentColorStore)
                        }
                        .width('100%')
                        .padding({ left: 8, right: 8, top: 5, bottom: 5 })
                        .borderRadius(8)
                        .backgroundColor(SURFACE)
                        .onClick((): void => { this.onOpenConversation(cid); })
                      }, (cid: string): string => `${hub.id}-${cid}`)
                    }
                  }
                  .width('100%')
                  .padding({ top: 4 })
                }
              }
              .width('100%')
              .padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .borderRadius(16)
              .backgroundColor(SURFACE2)
            }, (hub: AmberHubRecord): string => `${hub.id}-${hub.updatedAt}-${hub.conversationIds.length}`)
          }
          .width('100%')
        }
        .layoutWeight(1)
        .scrollBar(BarState.Off)
      }
    }
    .width('100%')
    .height('100%')
    .padding({ left: 16, right: 16, top: 4, bottom: 10 })
  }
}
