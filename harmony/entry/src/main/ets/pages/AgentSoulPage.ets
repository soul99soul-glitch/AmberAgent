// AgentSoulPage — 形象人设面板(跨端统一 C-SOUL-V1 · Arquiel Design 分层降噪二级页)
//
// 功能边界:
//   - 查看与编辑:角色称呼(displayName)、一句话签名(tagline)、灵魂设定正文(soulMarkdown)
//   - 历史人设切回(最多 8 条快照,切换前自动归档当前自定义形象人设)
//   - 一键恢复默认 Amber
//   - 进入页面自动触发一次云端同步拉取(GET /v2/soul),保存/切回/恢复默认自动异步推送(PUT /v2/soul)

import { KeyboardAvoidMode } from '@kit.ArkUI';
import type { AgentSoulHistoryEntry, AgentSoulMutationResult, AgentSoulState } from '@amber/chat-domain';
import {
  DEFAULT_AGENT_SOUL_NAME, MAX_AGENT_SOUL_NAME_CHARS, MAX_AGENT_SOUL_TAGLINE_CHARS,
  computeIsAgentSoulDefault, normalizeAgentSoulDisplayName, normalizeAgentSoulMarkdown,
  normalizeAgentSoulTagline,
} from '@amber/chat-domain';
import {
  ACCENT, BG, ERROR, GUTTER, INK, INK2, INK3, INK4, LINE, META, SURFACE2,
  TEXT_STYLES, accentSoftBg, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS, FONT_SANS_BOLD, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import {
  BtnFill, BtnGhost, CardGroup, CenteredTitleBar, FooterNote, PageSpacer, PlainSection, StatusChip,
} from '../components/PageTemplate.ets';
import {
  loadAgentSoulState, resetAgentSoulFromUi, restoreAgentSoulSnapshotFromUi,
  syncAgentSoulFromCloud, updateAgentSoulFromUi,
} from '../platform_impl/AgentSoulSync.ets';

const formatSourceLabel = (source: string): string => {
  if (source.includes('chat')) return '对话自塑';
  if (source.includes('ui')) return '手动设置';
  if (source === 'default') return '默认';
  return '多端同步';
};

const pad2 = (n: number): string => (n < 10 ? `0${n}` : `${n}`);

const formatShortTime = (ms: number): string => {
  if (ms <= 0) return '';
  const d: Date = new Date(ms);
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
};

const historyPreviewLine = (entry: AgentSoulHistoryEntry): string => {
  if (entry.tagline.trim().length > 0) return entry.tagline.trim();
  const compact: string = entry.soulMarkdown.replace(/\s+/g, ' ').trim();
  if (compact.length === 0) return '自定义形象人设';
  return compact.length <= 48 ? compact : `${compact.substring(0, 47)}…`;
};

@Entry
@Component
struct AgentSoulPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('agentSoulRevision') @Watch('onSoulStoreChanged') soulRevisionTick: number = 0;

  @State loaded: boolean = false;
  @State isDefault: boolean = true;
  @State revision: number = 0;
  @State source: string = 'default';
  @State history: AgentSoulHistoryEntry[] = [];

  @State draftName: string = DEFAULT_AGENT_SOUL_NAME;
  @State draftTagline: string = '';
  @State draftMarkdown: string = '';
  @State dirty: boolean = false;

  @State saving: boolean = false;
  @State notice: string = '';
  @State noticeIsError: boolean = false;

  private previousKeyboardMode: KeyboardAvoidMode | null = null;

  aboutToAppear(): void {
    this.hydrateFromStore(true);
    syncAgentSoulFromCloud();
  }

  onPageShow(): void {
    if (this.previousKeyboardMode === null) {
      this.previousKeyboardMode = this.getUIContext().getKeyboardAvoidMode();
    }
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    if (!this.dirty) {
      this.hydrateFromStore(false);
    }
    syncAgentSoulFromCloud();
  }

  onPageHide(): void {
    if (this.previousKeyboardMode !== null) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardMode);
      this.previousKeyboardMode = null;
    }
  }

  aboutToDisappear(): void {
    this.onPageHide();
  }

  private onSoulStoreChanged(): void {
    if (!this.saving) {
      this.hydrateFromStore(!this.dirty);
    }
  }

  private applyState(state: AgentSoulState, overwriteDrafts: boolean): void {
    this.isDefault = state.isDefault;
    this.revision = state.revision;
    this.source = state.source;
    this.history = state.history;
    if (overwriteDrafts) {
      this.draftName = state.displayName;
      this.draftTagline = state.tagline;
      this.draftMarkdown = state.soulMarkdown;
      this.dirty = false;
    }
    this.loaded = true;
  }

  private hydrateFromStore(overwriteDrafts: boolean): void {
    loadAgentSoulState(true).then((state: AgentSoulState): void => {
      this.applyState(state, overwriteDrafts);
    }).catch((): void => {
      this.loaded = true;
    });
  }

  private previewName(): string {
    return normalizeAgentSoulDisplayName(this.draftName);
  }

  private monogramChar(): string {
    const chars: string[] = Array.from(this.previewName());
    return chars.length > 0 ? chars[0].toUpperCase() : 'A';
  }

  private draftIsDefault(): boolean {
    return computeIsAgentSoulDefault(
      normalizeAgentSoulDisplayName(this.draftName),
      normalizeAgentSoulTagline(this.draftTagline),
      normalizeAgentSoulMarkdown(this.draftMarkdown),
    );
  }

  private setFeedback(text: string, isError: boolean): void {
    this.notice = text;
    this.noticeIsError = isError;
  }

  private savePersona(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    updateAgentSoulFromUi(this.draftName, this.draftTagline, this.draftMarkdown)
      .then((res: AgentSoulMutationResult): void => {
        this.applyState(res.state, true);
        this.setFeedback(res.changed ? '形象人设已保存' : '当前内容未发生变化', false);
      })
      .catch((err: Error): void => {
        this.setFeedback(`保存失败：${err.message}`, true);
      })
      .finally((): void => {
        this.saving = false;
      });
  }

  private resetDefaultPersona(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    resetAgentSoulFromUi()
      .then((res: AgentSoulMutationResult): void => {
        this.applyState(res.state, true);
        this.setFeedback('已恢复默认 Amber 形象人设', false);
      })
      .catch((err: Error): void => {
        this.setFeedback(`恢复失败：${err.message}`, true);
      })
      .finally((): void => {
        this.saving = false;
      });
  }

  private restoreSnapshot(entry: AgentSoulHistoryEntry): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    restoreAgentSoulSnapshotFromUi(entry.id)
      .then((res: AgentSoulMutationResult): void => {
        this.applyState(res.state, true);
        this.setFeedback(`已切回「${res.state.displayName}」`, false);
      })
      .catch((err: Error): void => {
        this.setFeedback(`切回失败：${err.message}`, true);
      })
      .finally((): void => {
        this.saving = false;
      });
  }

  @Builder
  private IdentitySummaryCard(): void {
    Row() {
      Row() {
        Text(this.monogramChar())
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontFamily(FONT_SANS_BOLD)
          .fontColor(this.accentColorStore)
      }
      .width(48)
      .height(48)
      .borderRadius(14)
      .backgroundColor(accentSoftBg(this.accentColorStore))
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .margin({ right: 14 })

      Column() {
        Row({ space: 8 }) {
          Text(this.previewName())
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .lineHeight(lineHeightForSize(17))
            .fontFamily(FONT_SANS_BOLD)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .flexShrink(1)
          StatusChip({
            text: this.isDefault
              ? '默认 Amber'
              : (this.revision > 0 ? `自定义 · v${this.revision}` : '自定义'),
            tone: this.isDefault ? 'muted' : 'accent',
          })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        Text(
          this.draftTagline.trim().length > 0
            ? this.draftTagline.trim()
            : (this.isDefault ? '诚实可靠的默认智能助手' : `最近更新来源：${formatSourceLabel(this.source)}`),
        )
          .fontSize(13)
          .lineHeight(lineHeightForSize(13))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK3)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
  }

  @Builder
  private EditorFormCard(): void {
    Column() {
      // 字段 1: 角色称呼
      Column() {
        Text('角色称呼')
          .fontSize(12)
          .fontWeight(600)
          .lineHeight(lineHeightForSize(12))
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(INK2)
        TextInput({ text: this.draftName, placeholder: DEFAULT_AGENT_SOUL_NAME })
          .id('agent-soul-name-input')
          .maxLength(MAX_AGENT_SOUL_NAME_CHARS)
          .fontSize(15)
          .fontFamily(FONT_SANS)
          .fontColor(INK)
          .placeholderColor(INK4)
          .backgroundColor(SURFACE2)
          .borderRadius(10)
          .constraintSize({ minHeight: 44 })
          .padding({ left: 12, right: 12 })
          .margin({ top: 6 })
          .enabled(!this.saving)
          .onChange((val: string): void => {
            if (val !== this.draftName) {
              this.draftName = val;
              this.dirty = true;
            }
          })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 16, right: 16, top: 14, bottom: 12 })

      Row() {
        Row().width('100%').height(1).backgroundColor(LINE)
      }
      .width('100%')
      .padding({ left: 16, right: 16 })

      // 字段 2: 一句话签名
      Column() {
        Text('一句话签名')
          .fontSize(12)
          .fontWeight(600)
          .lineHeight(lineHeightForSize(12))
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(INK2)
        TextInput({ text: this.draftTagline, placeholder: '简短概括角色身份或气质（选填）' })
          .id('agent-soul-tagline-input')
          .maxLength(MAX_AGENT_SOUL_TAGLINE_CHARS)
          .fontSize(15)
          .fontFamily(FONT_SANS)
          .fontColor(INK)
          .placeholderColor(INK4)
          .backgroundColor(SURFACE2)
          .borderRadius(10)
          .constraintSize({ minHeight: 44 })
          .padding({ left: 12, right: 12 })
          .margin({ top: 6 })
          .enabled(!this.saving)
          .onChange((val: string): void => {
            if (val !== this.draftTagline) {
              this.draftTagline = val;
              this.dirty = true;
            }
          })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })

      Row() {
        Row().width('100%').height(1).backgroundColor(LINE)
      }
      .width('100%')
      .padding({ left: 16, right: 16 })

      // 字段 3: 灵魂设定正文
      Column() {
        Text('灵魂设定正文')
          .fontSize(12)
          .fontWeight(600)
          .lineHeight(lineHeightForSize(12))
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(INK2)
        TextArea({
          text: this.draftMarkdown,
          placeholder: '描述角色的身份背景、性格特征、说话口吻或称呼偏好…',
        })
          .id('agent-soul-markdown-input')
          .width('100%')
          .height(168)
          .fontSize(14)
          .lineHeight(20)
          .fontFamily(FONT_SANS)
          .fontColor(INK)
          .placeholderColor(INK4)
          .backgroundColor(SURFACE2)
          .borderRadius(10)
          .padding(12)
          .margin({ top: 6 })
          .enabled(!this.saving)
          .onChange((val: string): void => {
            if (val !== this.draftMarkdown) {
              this.draftMarkdown = val;
              this.dirty = true;
            }
          })

        Row({ space: 10 }) {
          Row() {
            BtnFill({
              label: this.saving ? '保存中…' : '保存形象人设',
              onTap: (): void => { this.savePersona(); },
            })
          }
          .layoutWeight(1)

          if (!this.isDefault || !this.draftIsDefault()) {
            BtnGhost({
              label: '恢复默认 Amber',
              onTap: (): void => { this.resetDefaultPersona(); },
            })
          }
        }
        .width('100%')
        .margin({ top: 14 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 16, right: 16, top: 12, bottom: 16 })
    }
    .width('100%')
  }

  @Builder
  private HistoryRow(entry: AgentSoulHistoryEntry, idx: number): void {
    Column() {
      Row() {
        Column() {
          Row({ space: 6 }) {
            Text(entry.displayName)
              .fontSize(15)
              .fontWeight(FontWeight.Medium)
              .lineHeight(lineHeightForSize(15))
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .flexShrink(1)
            Text(
              formatShortTime(entry.updatedAtMs).length > 0
                ? `${formatSourceLabel(entry.source)} · ${formatShortTime(entry.updatedAtMs)}`
                : formatSourceLabel(entry.source),
            )
              .fontSize(11)
              .fontFamily(FONT_MONO)
              .fontColor(INK4)
              .maxLines(1)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)

          Text(historyPreviewLine(entry))
            .fontSize(12)
            .lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 3 })
            .width('100%')
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Row() {
          Text('切回')
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(this.accentColorStore)
        }
        .constraintSize({ minWidth: 52, minHeight: 44 })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .borderRadius(8)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .margin({ left: 12 })
        .accessibilityText(`切回历史人设 ${entry.displayName}`)
        .onClick((): void => { this.restoreSnapshot(entry); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .padding({ left: 16, right: 14, top: 10, bottom: 10 })

      if (idx < this.history.length - 1) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 16, right: 16 })
      }
    }
    .width('100%')
  }

  @Builder
  private HistoryListCard(): void {
    Column() {
      ForEach(this.history, (entry: AgentSoulHistoryEntry, idx: number): void => {
        this.HistoryRow(entry, idx);
      }, (entry: AgentSoulHistoryEntry): string => entry.id)
    }
    .width('100%')
  }

  build(): void {
    Stack() {
      ForEach([this.themeEpoch], (_e: number): void => {
        Column() {
          CenteredTitleBar({
            title: '形象人设',
            subtitle: '多端同步的角色称呼、签名与灵魂设定',
          })
          Scroll() {
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.IdentitySummaryCard(); } })
                .margin({ left: GUTTER, right: GUTTER, top: 6 })

              if (this.notice.length > 0) {
                Text(this.notice)
                  .fontSize(META)
                  .fontFamily(FONT_SANS_MEDIUM)
                  .fontColor(this.noticeIsError ? ERROR : this.accentColorStore)
                  .width('100%')
                  .padding({ left: GUTTER + 4, right: GUTTER + 4, top: 8 })
              }

              PlainSection({ label: '角色设定' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.EditorFormCard(); } })
                .margin({ left: GUTTER, right: GUTTER })
              FooterNote({
                text: '在对话中直接要求更换人设、性格、说话口吻或称呼时，助手也会自动持久更新形象人设并同步至已配对设备；底层诚实执行与安全边界保持不变。',
              })

              if (this.history.length > 0) {
                PlainSection({ label: '历史人设' })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.HistoryListCard(); } })
                  .margin({ left: GUTTER, right: GUTTER })
                FooterNote({ text: '最多保留最近 8 条自定义历史人设，可随时一键切回。' })
              }

              PageSpacer()
            }
            .width('100%')
            .padding({ bottom: this.navBarHeight })
          }
          .layoutWeight(1)
          .scrollBar(BarState.Off)
          .align(Alignment.Top)
        }
        .width('100%')
        .height('100%')
        .backgroundColor(BG)
      }, (e: number): string => `theme-${e}`)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }
}
