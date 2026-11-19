// AgentEmbodimentSheet — 单层具身面板（禁止嵌套第二张 Sheet）
// 顶部：72vp 形象圆章 + 名称/签名/状态 + 原地展开编辑（名称/签名/Soul/头像预设与自定义/最近 8 条历史快照回溯）
// 下方 3 Tab：人设 Soul | 记忆 Memory（三维记忆画像 + 分桶过滤 + 直达全部记忆/主题）| 灵感 点子（基于记忆生成起手句）

import type {
  AgentSoulHistoryEntry, AgentSoulMutationResult, AgentSoulState,
  MemoryPortrait, MemoryPortraitBucketFilter, MemoryPortraitDimension,
  MemoryPortraitItem, MemoryRecord,
} from '@amber/chat-domain';
import {
  DEFAULT_AGENT_SOUL_NAME, MAX_AGENT_SOUL_NAME_CHARS, MAX_AGENT_SOUL_TAGLINE_CHARS,
  buildMemoryPortrait, computeIsAgentSoulDefault, normalizeAgentSoulDisplayName,
} from '@amber/chat-domain';
import {
  ACCENT, BG, BODY, BODY2, ERROR, INK, INK2, INK3, INK4, LINE, META, RADIUS,
  SUCCESS_FG, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { AmberPersonaMedallion } from '../chat/AmberPersonaMedallion.ets';
import {
  APP_STORAGE_SOUL_AVATAR_PRESET, APP_STORAGE_SOUL_AVATAR_URI,
  APP_STORAGE_SOUL_REVISION,
  loadAgentSoulAvatarPrefs, loadAgentSoulState, resetAgentSoulFromUi,
  restoreAgentSoulSnapshotFromUi, saveAgentSoulAvatarPrefs,
  syncAgentSoulFromCloud, updateAgentSoulFromUi,
} from '../../platform_impl/AgentSoulSync.ets';
import { getMemoryLibraryOps } from '../../platform_impl/MemoryStore.ets';
import { Icon } from '../ui/Icon.ets';

export type EmbodimentSheetTab = 'soul' | 'memory' | 'ideas';

interface AvatarPresetOption {
  id: string;
  label: string;
  iconName: string;
}

interface BucketChipOption {
  key: MemoryPortraitBucketFilter;
  label: string;
}

const AVATAR_PRESETS: AvatarPresetOption[] = [
  { id: 'muse', label: '缪斯', iconName: 'ph_maskHappy' },
  { id: 'star', label: '星芒', iconName: 'ph_star' },
  { id: 'rocket', label: '探索', iconName: 'ph_rocket' },
  { id: 'yinyang', label: '平衡', iconName: 'ph_yinYang' },
  { id: 'alien', label: '星际', iconName: 'ph_alien' },
  { id: 'ghost', label: '灵影', iconName: 'ph_ghost' },
];

const BUCKET_CHIPS: BucketChipOption[] = [
  { key: 'all', label: '全部' },
  { key: 'core', label: '核心' },
  { key: 'short_term', label: '短期' },
  { key: 'long_term', label: '长期' },
];

const formatShortTime = (ms: number): string => {
  if (ms <= 0) return '未知时间';
  const d: Date = new Date(ms);
  const mm: string = String(d.getMonth() + 1).padStart(2, '0');
  const dd: string = String(d.getDate()).padStart(2, '0');
  const hh: string = String(d.getHours()).padStart(2, '0');
  const mi: string = String(d.getMinutes()).padStart(2, '0');
  return `${mm}-${dd} ${hh}:${mi}`;
};

@Component
export struct AgentEmbodimentSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp(APP_STORAGE_SOUL_REVISION) @Watch('onSoulStoreChanged') soulRevisionTick: number = 0;
  @StorageProp(APP_STORAGE_SOUL_AVATAR_PRESET) avatarPresetStore: string = 'muse';
  @StorageProp(APP_STORAGE_SOUL_AVATAR_URI) avatarUriStore: string = '';

  @State private activeTab: EmbodimentSheetTab = 'soul';
  @State private editing: boolean = false;
  @State private loaded: boolean = false;
  @State private saving: boolean = false;
  @State private dirty: boolean = false;
  @State private notice: string = '';
  @State private noticeIsError: boolean = false;

  @State private isDefault: boolean = true;
  @State private revision: number = 0;
  @State private source: string = 'default';
  @State private history: AgentSoulHistoryEntry[] = [];
  @State private draftName: string = DEFAULT_AGENT_SOUL_NAME;
  @State private draftTagline: string = '';
  @State private draftMarkdown: string = '';
  @State private draftPreset: string = 'muse';
  @State private draftAvatarUri: string = '';

  @State private memoryLoading: boolean = false;
  @State private memoryRecords: MemoryRecord[] = [];
  @State private memoryBucket: MemoryPortraitBucketFilter = 'all';
  @State private portrait: MemoryPortrait = buildMemoryPortrait([]);

  onClose: () => void = (): void => {};
  onPickStarter: (prompt: string) => void = (_prompt: string): void => {};
  onOpenMemoryRecords: () => void = (): void => {};
  onOpenMemoryTopics: () => void = (): void => {};

  aboutToAppear(): void {
    this.draftPreset = this.avatarPresetStore;
    this.draftAvatarUri = this.avatarUriStore;
    this.hydrateSoul(true);
    loadAgentSoulAvatarPrefs().then((prefs): void => {
      if (this.editing) return;
      this.draftPreset = prefs.preset;
      this.draftAvatarUri = prefs.customUri;
    }).catch((): void => {});
    syncAgentSoulFromCloud();
    this.refreshMemoryPortrait();
  }

  onSoulStoreChanged(): void {
    if (!this.saving) {
      this.hydrateSoul(!this.dirty);
    }
  }

  private hydrateSoul(overwriteDrafts: boolean): void {
    loadAgentSoulState(true).then((state: AgentSoulState): void => {
      this.isDefault = state.isDefault;
      this.revision = state.revision;
      this.source = state.source;
      this.history = state.history;
      if (overwriteDrafts && !this.dirty) {
        this.draftName = state.displayName;
        this.draftTagline = state.tagline;
        this.draftMarkdown = state.soulMarkdown;
        this.dirty = false;
      }
      this.loaded = true;
    }).catch((): void => {
      this.loaded = true;
    });
  }

  private refreshMemoryPortrait(): void {
    this.memoryLoading = true;
    getMemoryLibraryOps().getAllRecords()
      .then((records: MemoryRecord[]): void => {
        this.memoryRecords = records;
        this.portrait = buildMemoryPortrait(records, { bucket: this.memoryBucket });
      })
      .catch((): void => {
        this.portrait = buildMemoryPortrait([]);
      })
      .finally((): void => {
        this.memoryLoading = false;
      });
  }

  private selectBucket(bucket: MemoryPortraitBucketFilter): void {
    this.memoryBucket = bucket;
    this.portrait = buildMemoryPortrait(this.memoryRecords, { bucket });
  }

  private previewName(): string {
    return normalizeAgentSoulDisplayName(this.draftName);
  }

  private previewIsDefault(): boolean {
    return computeIsAgentSoulDefault(this.draftName, this.draftTagline, this.draftMarkdown);
  }

  private toggleEditor(): void {
    const apply = (): void => {
      this.editing = !this.editing;
      if (!this.editing) {
        this.dirty = false;
        this.hydrateSoul(true);
        this.draftPreset = this.avatarPresetStore;
        this.draftAvatarUri = this.avatarUriStore;
      }
    };
    if (this.reduceMotion) apply();
    else animateTo({ duration: 200, curve: Curve.EaseOut }, apply);
  }

  private setFeedback(msg: string, isError: boolean): void {
    this.notice = msg;
    this.noticeIsError = isError;
  }

  private savePersona(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    Promise.all([
      updateAgentSoulFromUi(this.draftName, this.draftTagline, this.draftMarkdown),
      saveAgentSoulAvatarPrefs(this.draftPreset, this.draftAvatarUri),
    ])
      .then((results): void => {
        const res: AgentSoulMutationResult = results[0];
        this.isDefault = res.state.isDefault;
        this.revision = res.state.revision;
        this.source = res.state.source;
        this.history = res.state.history;
        this.draftName = res.state.displayName;
        this.draftTagline = res.state.tagline;
        this.draftMarkdown = res.state.soulMarkdown;
        this.dirty = false;
        this.editing = false;
        this.setFeedback('形象与头像设置已保存', false);
      })
      .catch((err: Error): void => {
        this.setFeedback(`保存失败：${err.message}`, true);
      })
      .finally((): void => {
        this.saving = false;
      });
  }

  private resetDefault(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    Promise.all([
      resetAgentSoulFromUi(),
      saveAgentSoulAvatarPrefs('muse', ''),
    ])
      .then((results): void => {
        const res: AgentSoulMutationResult = results[0];
        this.isDefault = res.state.isDefault;
        this.revision = res.state.revision;
        this.source = res.state.source;
        this.history = res.state.history;
        this.draftName = res.state.displayName;
        this.draftTagline = res.state.tagline;
        this.draftMarkdown = res.state.soulMarkdown;
        this.draftPreset = 'muse';
        this.draftAvatarUri = '';
        this.dirty = false;
        this.setFeedback('已恢复默认 Amber 形象', false);
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
        this.isDefault = res.state.isDefault;
        this.revision = res.state.revision;
        this.source = res.state.source;
        this.history = res.state.history;
        this.draftName = res.state.displayName;
        this.draftTagline = res.state.tagline;
        this.draftMarkdown = res.state.soulMarkdown;
        this.dirty = false;
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
  private HeroCard(): void {
    Column({ space: 12 }) {
      Row({ space: 14 }) {
        AmberPersonaMedallion({
          medallionSize: 64,
          displayName: this.previewName(),
          isDefault: this.previewIsDefault(),
          sending: false,
          customAvatarUri: this.editing ? this.draftAvatarUri : this.avatarUriStore,
          previewPreset: this.editing ? this.draftPreset : '',
        })
        Column({ space: 4 }) {
          Row({ space: 8 }) {
            Text(this.previewName())
              .fontSize(19)
              .fontWeight(FontWeight.Bold)
              .fontColor(INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
            Text(this.previewIsDefault() ? '默认' : `v${this.revision}`)
              .fontSize(11)
              .fontColor(this.previewIsDefault() ? INK3 : this.accentColorStore)
              .padding({ left: 8, right: 8, top: 3, bottom: 3 })
              .borderRadius(999)
              .backgroundColor(this.previewIsDefault() ? SURFACE2 : accentSoftBg(this.accentColorStore))
          }
          .width('100%')

          Text(this.draftTagline.trim().length > 0 ? this.draftTagline.trim() : '你的私人 Agent 伙伴')
            .fontSize(13)
            .fontColor(INK3)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Text(this.editing ? '收起编辑' : '编辑形象')
          .fontSize(12.5)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.accentColorStore)
          .padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .borderRadius(999)
          .backgroundColor(accentSoftBg(this.accentColorStore))
          .onClick((): void => { this.toggleEditor(); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      if (this.notice.length > 0) {
        Text(this.notice)
          .fontSize(12)
          .fontColor(this.noticeIsError ? ERROR : SUCCESS_FG)
          .width('100%')
      }

      if (this.editing) {
        Column({ space: 10 }) {
          Text('头像样式').fontSize(12).fontColor(INK3).width('100%')
          Scroll() {
            Row({ space: 8 }) {
              ForEach(AVATAR_PRESETS, (opt: AvatarPresetOption): void => {
                Row({ space: 5 }) {
                  Icon({
                    name: opt.iconName,
                    iconSize: 15,
                    color: this.draftPreset === opt.id ? this.accentColorStore : INK2,
                  })
                  Text(opt.label)
                    .fontSize(12.5)
                    .fontColor(this.draftPreset === opt.id ? this.accentColorStore : INK2)
                }
                .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                .borderRadius(999)
                .backgroundColor(this.draftPreset === opt.id ? accentSoftBg(this.accentColorStore) : SURFACE2)
                .border({
                  width: 1,
                  color: this.draftPreset === opt.id ? accentEdge(this.accentColorStore) : 'transparent',
                })
                .onClick((): void => {
                  this.draftPreset = opt.id;
                })
              }, (opt: AvatarPresetOption): string => opt.id)
            }
          }
          .scrollable(ScrollDirection.Horizontal)
          .scrollBar(BarState.Off)
          .width('100%')

          Text('自定义头像 URI（可选，留空用上方几何预设）').fontSize(12).fontColor(INK3).width('100%')
          TextInput({ text: this.draftAvatarUri, placeholder: 'file:// 或 https:// 图片地址' })
            .fontSize(13)
            .fontColor(INK)
            .backgroundColor(SURFACE2)
            .borderRadius(10)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onChange((v: string): void => { this.draftAvatarUri = v; })

          Text(`称呼（最多 ${MAX_AGENT_SOUL_NAME_CHARS} 字）`).fontSize(12).fontColor(INK3).width('100%')
          TextInput({ text: this.draftName, placeholder: DEFAULT_AGENT_SOUL_NAME })
            .maxLength(MAX_AGENT_SOUL_NAME_CHARS)
            .fontSize(14)
            .fontColor(INK)
            .backgroundColor(SURFACE2)
            .borderRadius(10)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onChange((v: string): void => { this.draftName = v; this.dirty = true; })

          Text(`一句话签名（最多 ${MAX_AGENT_SOUL_TAGLINE_CHARS} 字）`).fontSize(12).fontColor(INK3).width('100%')
          TextInput({ text: this.draftTagline, placeholder: '一句话描述你的角色定位' })
            .maxLength(MAX_AGENT_SOUL_TAGLINE_CHARS)
            .fontSize(14)
            .fontColor(INK)
            .backgroundColor(SURFACE2)
            .borderRadius(10)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onChange((v: string): void => { this.draftTagline = v; this.dirty = true; })

          Text('Soul 性格设定（Markdown）').fontSize(12).fontColor(INK3).width('100%')
          TextArea({ text: this.draftMarkdown, placeholder: '写下性格、口吻、表达原则和记忆习惯…' })
            .height(110)
            .fontSize(13.5)
            .fontColor(INK)
            .backgroundColor(SURFACE2)
            .borderRadius(12)
            .padding(10)
            .onChange((v: string): void => { this.draftMarkdown = v; this.dirty = true; })

          Row({ space: 10 }) {
            Button(this.saving ? '保存中…' : '保存修改')
              .layoutWeight(1)
              .height(38)
              .fontSize(13.5)
              .fontWeight(FontWeight.Medium)
              .backgroundColor(this.accentColorStore)
              .fontColor('#FFFFFF')
              .enabled(!this.saving)
              .onClick((): void => { this.savePersona(); })

            Button('恢复默认')
              .height(38)
              .fontSize(13)
              .backgroundColor(SURFACE2)
              .fontColor(INK2)
              .enabled(!this.saving)
              .onClick((): void => { this.resetDefault(); })
          }
          .width('100%')
        }
        .width('100%')
        .padding({ top: 8 })
      }
    }
    .width('100%')
    .padding(16)
    .borderRadius(20)
    .backgroundColor(SURFACE)
    .border({ width: 0.75, color: LINE })
  }

  @Builder
  private TabBar(): void {
    Row({ space: 6 }) {
      this.TabChip('soul', '人设 Soul')
      this.TabChip('memory', '记忆 Memory')
      this.TabChip('ideas', '灵感 点子')
    }
    .width('100%')
    .padding(4)
    .borderRadius(14)
    .backgroundColor(SURFACE2)
  }

  @Builder
  private TabChip(tab: EmbodimentSheetTab, label: string): void {
    Text(label)
      .layoutWeight(1)
      .textAlign(TextAlign.Center)
      .fontSize(13)
      .fontWeight(this.activeTab === tab ? FontWeight.Bold : FontWeight.Medium)
      .fontColor(this.activeTab === tab ? INK : INK3)
      .padding({ top: 8, bottom: 8 })
      .borderRadius(10)
      .backgroundColor(this.activeTab === tab ? SURFACE : 'transparent')
      .onClick((): void => { this.activeTab = tab; })
  }

  @Builder
  private SoulTabContent(): void {
    Column({ space: 12 }) {
      Column({ space: 6 }) {
        Text('当前灵魂设定')
          .fontSize(13)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
          .width('100%')
        if (!this.loaded) {
          Text('正在读取形象设置…').fontSize(13).fontColor(INK3).width('100%')
        } else if (this.draftMarkdown.trim().length > 0) {
          Text(this.draftMarkdown.trim())
            .fontSize(13.5)
            .lineHeight(21)
            .fontColor(INK2)
            .width('100%')
        } else {
          Text('当前使用默认 Amber 性格设定；点击右上角「编辑形象」或直接在聊天里让它自我塑造。')
            .fontSize(13)
            .lineHeight(20)
            .fontColor(INK3)
            .width('100%')
        }
        Text(`同步来源：${this.source || 'default'} · 跨端 C-SOUL-V1 双向同步`)
          .fontSize(META)
          .fontColor(INK4)
          .width('100%')
          .padding({ top: 4 })
      }
      .width('100%')
      .padding(14)
      .borderRadius(16)
      .backgroundColor(SURFACE)
      .border({ width: 0.75, color: LINE })

      if (this.history.length > 0) {
        Column({ space: 8 }) {
          Text(`最近人设快照（${this.history.length}/8）`)
            .fontSize(13)
            .fontWeight(FontWeight.Bold)
            .fontColor(INK)
            .width('100%')
          ForEach(this.history, (entry: AgentSoulHistoryEntry): void => {
            Row({ space: 10 }) {
              Column({ space: 2 }) {
                Text(entry.displayName)
                  .fontSize(13.5)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(INK)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(`${formatShortTime(entry.updatedAtMs)} · ${entry.tagline.length > 0 ? entry.tagline : '无签名'}`)
                  .fontSize(11.5)
                  .fontColor(INK3)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)

              Text('切回')
                .fontSize(12)
                .fontColor(this.accentColorStore)
                .padding({ left: 10, right: 10, top: 5, bottom: 5 })
                .borderRadius(999)
                .backgroundColor(accentSoftBg(this.accentColorStore))
                .onClick((): void => { this.restoreSnapshot(entry); })
            }
            .width('100%')
            .padding(10)
            .borderRadius(12)
            .backgroundColor(SURFACE2)
          }, (entry: AgentSoulHistoryEntry): string => `${entry.id}-${entry.updatedAtMs}`)
        }
        .width('100%')
        .padding(14)
        .borderRadius(16)
        .backgroundColor(SURFACE)
        .border({ width: 0.75, color: LINE })
      }
    }
    .width('100%')
  }

  @Builder
  private MemoryTabContent(): void {
    Column({ space: 12 }) {
      Row({ space: 6 }) {
        ForEach(BUCKET_CHIPS, (chip: BucketChipOption): void => {
          Text(chip.label)
            .fontSize(12)
            .fontWeight(this.memoryBucket === chip.key ? FontWeight.Bold : FontWeight.Normal)
            .fontColor(this.memoryBucket === chip.key ? this.accentColorStore : INK3)
            .padding({ left: 10, right: 10, top: 5, bottom: 5 })
            .borderRadius(999)
            .backgroundColor(this.memoryBucket === chip.key ? accentSoftBg(this.accentColorStore) : SURFACE)
            .border({
              width: 0.75,
              color: this.memoryBucket === chip.key ? accentEdge(this.accentColorStore) : LINE,
            })
            .onClick((): void => { this.selectBucket(chip.key); })
        }, (chip: BucketChipOption): string => chip.key)
        Blank()
        Text(`活跃 ${this.portrait.filteredCount} 条${this.portrait.dedupedCount > 0 ? ` · 去重 ${this.portrait.dedupedCount}` : ''}`)
          .fontSize(11)
          .fontColor(INK4)
      }
      .width('100%')

      if (this.memoryLoading) {
        Text('正在整理三维记忆画像…').fontSize(13).fontColor(INK3).padding(12)
      } else {
        ForEach(this.portrait.dimensions, (dim: MemoryPortraitDimension): void => {
          Column({ space: 8 }) {
            Row() {
              Text(dim.title)
                .fontSize(13.5)
                .fontWeight(FontWeight.Bold)
                .fontColor(INK)
              Text(` · ${dim.subtitle}`)
                .fontSize(11.5)
                .fontColor(INK3)
                .layoutWeight(1)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(`${dim.items.length}`)
                .fontSize(11.5)
                .fontColor(this.accentColorStore)
            }
            .width('100%')

            if (dim.items.length === 0) {
              Text('暂无该维度记忆；随着对话自然沉淀。')
                .fontSize(12)
                .fontColor(INK4)
                .width('100%')
            } else {
              ForEach(dim.items, (item: MemoryPortraitItem): void => {
                Row({ space: 6 }) {
                  Text(item.pinned ? '◆' : '•')
                    .fontSize(11)
                    .fontColor(item.pinned ? this.accentColorStore : INK3)
                  Text(item.content)
                    .fontSize(13)
                    .lineHeight(19)
                    .fontColor(INK2)
                    .maxLines(2)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .layoutWeight(1)
                }
                .width('100%')
                .alignItems(VerticalAlign.Top)
              }, (item: MemoryPortraitItem): string => `${dim.key}-${item.id}-${item.updatedAt}`)
            }
          }
          .width('100%')
          .padding(14)
          .borderRadius(16)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: LINE })
        }, (dim: MemoryPortraitDimension): string => `${dim.key}-${this.memoryBucket}-${dim.items.length}`)
      }

      Row({ space: 10 }) {
        Text('全部记忆库 ›')
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.accentColorStore)
          .padding({ top: 10, bottom: 10 })
          .borderRadius(14)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: LINE })
          .onClick((): void => {
            this.onClose();
            this.onOpenMemoryRecords();
          })

        Text('主题索引 ›')
          .layoutWeight(1)
          .textAlign(TextAlign.Center)
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.accentColorStore)
          .padding({ top: 10, bottom: 10 })
          .borderRadius(14)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: LINE })
          .onClick((): void => {
            this.onClose();
            this.onOpenMemoryTopics();
          })
      }
      .width('100%')
    }
    .width('100%')
  }

  @Builder
  private IdeasTabContent(): void {
    Column({ space: 10 }) {
      Text('根据你的记忆与近期线索，挑一句直接开启对话：')
        .fontSize(12.5)
        .fontColor(INK3)
        .width('100%')

      ForEach(this.portrait.starterPrompts, (prompt: string, index: number): void => {
        Row({ space: 10 }) {
          Icon({ name: 'spark', iconSize: 16, color: this.accentColorStore })
          Text(prompt)
            .fontSize(BODY2)
            .lineHeight(20)
            .fontColor(INK)
            .layoutWeight(1)
          Text('填入')
            .fontSize(12)
            .fontColor(this.accentColorStore)
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
        .borderRadius(16)
        .backgroundColor(SURFACE)
        .border({ width: 0.75, color: LINE })
        .onClick((): void => {
          this.onPickStarter(prompt);
          this.onClose();
        })
      }, (prompt: string, index: number): string => `${index}-${prompt}`)
    }
    .width('100%')
  }

  build(): void {
    Column({ space: 12 }) {
      Row() {
        Text('具身与记忆')
          .fontSize(17)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
        Blank()
        Text('完成')
          .fontSize(14)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.accentColorStore)
          .padding({ left: 10, right: 4, top: 4, bottom: 4 })
          .onClick((): void => { this.onClose(); })
      }
      .width('100%')

      Scroll() {
        Column({ space: 12 }) {
          this.HeroCard()
          this.TabBar()
          if (this.activeTab === 'soul') {
            this.SoulTabContent()
          } else if (this.activeTab === 'memory') {
            this.MemoryTabContent()
          } else {
            this.IdeasTabContent()
          }
        }
        .width('100%')
        .padding({ bottom: 24 })
      }
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .padding({ left: 16, right: 16, top: 14 })
    .backgroundColor(BG)
  }
}
