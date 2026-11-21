// AgentEmbodimentSheet — 单层形象人设面板（对齐 iOS AgentEmbodimentHomeSheet，禁止嵌套第二张 Sheet）
// 顶部居中标题「形象人设」+ 胶囊「完成」（形象编辑态左侧显示「‹ 人设」）
// 居中大圆形形象徽章 + 底部叠加「更换形象 ›」胶囊 + 称呼/引语签名 + 带滑动指示器的 3-Tab 分段条
// 下方 3 Tab：人设 Soul（卡内原位编辑灵魂设定 + 快照回溯）| 记忆 Memory | 灵感 点子

import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
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
  ACCENT, ACCENT_INK, BG, BODY2, ERROR, HOME_GLASS_SHADOW, INK, INK2, INK3, INK4, LINE, META,
  SUCCESS_FG, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { AmberPersonaMedallion } from '../chat/AmberPersonaMedallion.ets';
import {
  APP_STORAGE_SOUL_AVATAR_PRESET, APP_STORAGE_SOUL_AVATAR_URI, APP_STORAGE_SOUL_AVATAR_SPRITE,
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

const AVATAR_PRESET_ROW_1: AvatarPresetOption[] = [
  { id: 'muse', label: '缪斯', iconName: 'spark' },
  { id: 'fox', label: '灵狐', iconName: 'ph_cat' },
  { id: 'crystal', label: '琥珀晶', iconName: 'ph_snowflake' },
  { id: 'star', label: '星芒', iconName: 'ph_star' },
];

const AVATAR_PRESET_ROW_2: AvatarPresetOption[] = [
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
  @StorageProp(APP_STORAGE_SOUL_AVATAR_SPRITE) avatarSpriteStore: boolean = false;

  @State private activeTab: EmbodimentSheetTab = 'soul';
  @State private editingAvatar: boolean = false;
  @State private editingSoul: boolean = false;
  @State private showAdvancedAvatarUrl: boolean = false;
  @State private pickingAvatar: boolean = false;
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
  @State private draftAvatarSprite: boolean = false;

  @State private tabIndicatorX: number = 0;
  @State private tabIndicatorW: number = 96;
  @State private tabIndicatorReady: boolean = false;
  private tabX: number[] = [0, 96, 192];
  private tabW: number[] = [96, 96, 96];
  private tabMeasured: boolean[] = [false, false, false];

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
    this.draftAvatarSprite = this.avatarSpriteStore;
    this.hydrateSoul(true);
    loadAgentSoulAvatarPrefs().then((prefs): void => {
      if (this.editingAvatar) return;
      this.draftPreset = prefs.preset;
      this.draftAvatarUri = prefs.customUri;
      this.draftAvatarSprite = prefs.spriteSheet;
    }).catch((): void => {});
    syncAgentSoulFromCloud();
    this.refreshMemoryPortrait();
  }

  onSoulStoreChanged(): void {
    if (!this.saving) {
      this.hydrateSoul(!this.dirty);
    }
  }

  private tabIndexOf(tab: EmbodimentSheetTab): number {
    if (tab === 'memory') return 1;
    if (tab === 'ideas') return 2;
    return 0;
  }

  private selectTab(tab: EmbodimentSheetTab): void {
    if (this.activeTab === tab) return;
    const idx: number = this.tabIndexOf(tab);
    const apply = (): void => {
      this.activeTab = tab;
      if (this.tabMeasured[idx]) {
        this.tabIndicatorX = this.tabX[idx];
        this.tabIndicatorW = this.tabW[idx];
        this.tabIndicatorReady = true;
      }
    };
    animateTo({ duration: this.reduceMotion ? 130 : 230, curve: Curve.FastOutSlowIn }, apply);
  }

  private recordTabArea(tab: EmbodimentSheetTab, area: Area): void {
    const idx: number = this.tabIndexOf(tab);
    const x: number = Number(area.position.x);
    const w: number = Number(area.width);
    if (!Number.isFinite(x) || !Number.isFinite(w) || w <= 0) return;
    this.tabX[idx] = x;
    this.tabW[idx] = w;
    this.tabMeasured[idx] = true;
    if (this.activeTab === tab) {
      if (!this.tabIndicatorReady) {
        this.tabIndicatorX = x;
        this.tabIndicatorW = w;
        this.tabIndicatorReady = true;
      } else if (Math.abs(this.tabIndicatorX - x) > 0.5 || Math.abs(this.tabIndicatorW - w) > 0.5) {
        this.tabIndicatorX = x;
        this.tabIndicatorW = w;
      }
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

  private resolvedSignatureText(): string {
    const trimmed: string = this.draftTagline.trim();
    if (trimmed.length > 0) {
      if ((trimmed.startsWith('“') && trimmed.endsWith('”')) || (trimmed.startsWith('"') && trimmed.endsWith('"'))) {
        return trimmed;
      }
      return `“${trimmed}”`;
    }
    return '“温和克制的全能效率搭档”';
  }

  private setAvatarEditing(next: boolean): void {
    if (this.editingAvatar === next) return;
    const apply = (): void => {
      this.editingAvatar = next;
      this.notice = '';
      if (next) {
        this.draftPreset = this.avatarPresetStore;
        this.draftAvatarUri = this.avatarUriStore;
        this.draftAvatarSprite = this.avatarSpriteStore;
        this.showAdvancedAvatarUrl = this.draftAvatarUri.startsWith('http://') || this.draftAvatarUri.startsWith('https://');
      }
    };
    if (this.reduceMotion) apply();
    else animateTo({ duration: 220, curve: Curve.FastOutSlowIn }, apply);
  }

  private setSoulEditing(next: boolean): void {
    if (this.editingSoul === next) return;
    const apply = (): void => {
      this.editingSoul = next;
      this.notice = '';
      if (!next) {
        this.dirty = false;
        this.hydrateSoul(true);
      }
    };
    if (this.reduceMotion) apply();
    else animateTo({ duration: 200, curve: Curve.FastOutSlowIn }, apply);
  }

  private setFeedback(msg: string, isError: boolean): void {
    this.notice = msg;
    this.noticeIsError = isError;
  }

  private pickAvatarFromGallery(): void {
    if (this.pickingAvatar) return;
    this.pickingAvatar = true;
    const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 1;
    const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    picker.select(options).then((result: photoAccessHelper.PhotoSelectResult): void => {
      if (result.photoUris.length === 0) return;
      const copiedUri: string = this.copyPhotoToSandbox(result.photoUris[0]);
      if (copiedUri.length > 0) {
        this.draftAvatarUri = copiedUri;
        this.draftAvatarSprite = false;
        this.setFeedback('已选中相册图片，点击「保存形象」生效', false);
      }
    }).catch((): void => {
      // 用户取消选择不视为错误
    }).finally((): void => {
      this.pickingAvatar = false;
    });
  }

  private copyPhotoToSandbox(uri: string): string {
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      const destPath: string = `${ctx.filesDir}/agent_avatar_${Date.now()}.jpg`;
      const src: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
      try {
        fileIo.copyFileSync(src.fd, destPath);
      } finally {
        fileIo.closeSync(src.fd);
      }
      return `file://${destPath}`;
    } catch (e) {
      const err: Error = e as Error;
      this.setFeedback(`读取图片失败：${err.message}`, true);
      return '';
    }
  }

  private saveAvatarSettings(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    saveAgentSoulAvatarPrefs(this.draftPreset, this.draftAvatarUri, this.draftAvatarSprite)
      .then((): void => {
        this.setAvatarEditing(false);
        this.setFeedback('形象外观已更新', false);
      })
      .catch((err: Error): void => {
        this.setFeedback(`保存失败：${err.message}`, true);
      })
      .finally((): void => {
        this.saving = false;
      });
  }

  private resetAvatarSettings(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    saveAgentSoulAvatarPrefs('muse', '', false)
      .then((): void => {
        this.draftPreset = 'muse';
        this.draftAvatarUri = '';
        this.draftAvatarSprite = false;
        this.setFeedback('已恢复默认灵狐形象', false);
      })
      .catch((err: Error): void => {
        this.setFeedback(`恢复失败：${err.message}`, true);
      })
      .finally((): void => {
        this.saving = false;
      });
  }

  private saveSoulSettings(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    updateAgentSoulFromUi(this.draftName, this.draftTagline, this.draftMarkdown)
      .then((res: AgentSoulMutationResult): void => {
        this.isDefault = res.state.isDefault;
        this.revision = res.state.revision;
        this.source = res.state.source;
        this.history = res.state.history;
        this.draftName = res.state.displayName;
        this.draftTagline = res.state.tagline;
        this.draftMarkdown = res.state.soulMarkdown;
        this.dirty = false;
        this.setSoulEditing(false);
        this.setFeedback('灵魂设定已保存', false);
      })
      .catch((err: Error): void => {
        this.setFeedback(`保存失败：${err.message}`, true);
      })
      .finally((): void => {
        this.saving = false;
      });
  }

  private resetSoulDefault(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    resetAgentSoulFromUi()
      .then((res: AgentSoulMutationResult): void => {
        this.isDefault = res.state.isDefault;
        this.revision = res.state.revision;
        this.source = res.state.source;
        this.history = res.state.history;
        this.draftName = res.state.displayName;
        this.draftTagline = res.state.tagline;
        this.draftMarkdown = res.state.soulMarkdown;
        this.dirty = false;
        this.setSoulEditing(false);
        this.setFeedback('已恢复默认 Amber 性格设定', false);
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
  private TopNavigationBar(): void {
    Stack({ alignContent: Alignment.Center }) {
      Text('形象人设')
        .fontSize(17)
        .fontWeight(FontWeight.Bold)
        .fontColor(INK)

      Row() {
        if (this.editingAvatar) {
          Row({ space: 3 }) {
            Icon({ name: 'chevron_left', iconSize: 12, color: INK2 })
            Text('人设')
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor(INK2)
          }
          .height(34)
          .padding({ left: 10, right: 12 })
          .borderRadius(999)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: LINE })
          .alignItems(VerticalAlign.Center)
          .onClick((): void => { this.setAvatarEditing(false); })
        }

        Blank()

        Text('完成')
          .fontSize(13.5)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.accentColorStore)
          .height(34)
          .padding({ left: 16, right: 16 })
          .borderRadius(999)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: accentEdge(this.accentColorStore) })
          .textAlign(TextAlign.Center)
          .onClick((): void => { this.onClose(); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .height(40)
  }

  @Builder
  private PersonaHeroHeader(): void {
    Column({ space: 10 }) {
      Stack({ alignContent: Alignment.Bottom }) {
        Column() {
          AmberPersonaMedallion({
            medallionSize: this.editingAvatar ? 92 : 104,
            displayName: this.previewName(),
            isDefault: this.previewIsDefault(),
            sending: false,
            customAvatarUri: this.editingAvatar ? this.draftAvatarUri : this.avatarUriStore,
            previewPreset: this.editingAvatar ? this.draftPreset : '',
            previewSpriteSheet: this.editingAvatar ? this.draftAvatarSprite : this.avatarSpriteStore,
            sheetOpen: true,
          })
        }
        .onClick((): void => { this.setAvatarEditing(true); })

        if (!this.editingAvatar) {
          Row({ space: 4 }) {
            Icon({ name: 'image', iconSize: 11, color: INK2 })
            Text('更换形象')
              .fontSize(11.5)
              .fontWeight(FontWeight.Medium)
              .fontColor(INK)
            Icon({ name: 'chevron_right', iconSize: 10, color: INK3 })
          }
          .height(26)
          .padding({ left: 10, right: 9 })
          .borderRadius(999)
          .backgroundColor(SURFACE)
          .border({ width: 0.75, color: LINE })
          .shadow({ radius: 8, color: HOME_GLASS_SHADOW, offsetY: 2 })
          .alignItems(VerticalAlign.Center)
          .translate({ y: 6 })
          .onClick((): void => { this.setAvatarEditing(true); })
        }
      }
      .padding({ bottom: this.editingAvatar ? 2 : 8 })

      Column({ space: 4 }) {
        Text(this.previewName())
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })

        Text(this.resolvedSignatureText())
          .fontSize(13)
          .fontColor(INK2)
          .textAlign(TextAlign.Center)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .padding({ left: 16, right: 16 })
      }
      .alignItems(HorizontalAlign.Center)
      .width('100%')

      if (this.notice.length > 0) {
        Text(this.notice)
          .fontSize(12)
          .fontColor(this.noticeIsError ? ERROR : SUCCESS_FG)
          .textAlign(TextAlign.Center)
          .width('100%')
      }

      if (!this.editingAvatar) {
        this.TabBar()
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Center)
    .padding({ top: 4, bottom: 2 })
  }

  @Builder
  private PresetGridCell(opt: AvatarPresetOption): void {
    Column({ space: 6 }) {
      Stack({ alignContent: Alignment.Center }) {
        Icon({
          name: opt.iconName,
          iconSize: 18,
          color: this.draftPreset === opt.id && this.draftAvatarUri.trim().length === 0 ? this.accentColorStore : INK2,
        })
      }
      .width(36)
      .height(36)
      .borderRadius(999)
      .backgroundColor(
        this.draftPreset === opt.id && this.draftAvatarUri.trim().length === 0
          ? accentSoftBg(this.accentColorStore)
          : SURFACE2,
      )

      Text(opt.label)
        .fontSize(12)
        .fontWeight(this.draftPreset === opt.id && this.draftAvatarUri.trim().length === 0 ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.draftPreset === opt.id && this.draftAvatarUri.trim().length === 0 ? this.accentColorStore : INK2)
        .maxLines(1)
    }
    .layoutWeight(1)
    .padding({ top: 10, bottom: 10 })
    .borderRadius(14)
    .backgroundColor(SURFACE)
    .border({
      width: this.draftPreset === opt.id && this.draftAvatarUri.trim().length === 0 ? 1.25 : 0.75,
      color: this.draftPreset === opt.id && this.draftAvatarUri.trim().length === 0
        ? accentEdge(this.accentColorStore)
        : LINE,
    })
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => {
      this.draftPreset = opt.id;
      this.draftAvatarUri = '';
      this.draftAvatarSprite = false;
    })
  }

  @Builder
  private AvatarEditorContent(): void {
    Column({ space: 14 }) {
      Column({ space: 10 }) {
        Text('内置形象预设')
          .fontSize(13.5)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
          .width('100%')

        Row({ space: 8 }) {
          ForEach(AVATAR_PRESET_ROW_1, (opt: AvatarPresetOption): void => {
            this.PresetGridCell(opt)
          }, (opt: AvatarPresetOption): string => opt.id)
        }
        .width('100%')

        Row({ space: 8 }) {
          ForEach(AVATAR_PRESET_ROW_2, (opt: AvatarPresetOption): void => {
            this.PresetGridCell(opt)
          }, (opt: AvatarPresetOption): string => opt.id)
        }
        .width('100%')
      }
      .width('100%')
      .padding(14)
      .borderRadius(18)
      .backgroundColor(SURFACE)
      .border({ width: 0.75, color: LINE })

      Column({ space: 10 }) {
        Text('自定义图片')
          .fontSize(13.5)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
          .width('100%')

        Row({ space: 10 }) {
          Row({ space: 6 }) {
            Icon({ name: 'image', iconSize: 16, color: this.accentColorStore })
            Text(this.pickingAvatar ? '打开相册中…' : '从相册选择图片')
              .fontSize(13.5)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.accentColorStore)
          }
          .layoutWeight(1)
          .height(42)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .borderRadius(12)
          .backgroundColor(accentSoftBg(this.accentColorStore))
          .border({ width: 0.75, color: accentEdge(this.accentColorStore) })
          .onClick((): void => { this.pickAvatarFromGallery(); })

          if (this.draftAvatarUri.trim().length > 0) {
            Text('清除图片')
              .fontSize(13)
              .fontColor(INK2)
              .height(42)
              .padding({ left: 14, right: 14 })
              .borderRadius(12)
              .backgroundColor(SURFACE2)
              .textAlign(TextAlign.Center)
              .onClick((): void => {
                this.draftAvatarUri = '';
                this.draftAvatarSprite = false;
              })
          }
        }
        .width('100%')

        if (this.draftAvatarUri.trim().length > 0) {
          Row() {
            Column({ space: 2 }) {
              Text('按 4×4 动画图集解析')
                .fontSize(13)
                .fontColor(INK)
              Text('四行情绪 × 四帧动态关键帧')
                .fontSize(11)
                .fontColor(INK3)
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)
            Toggle({ type: ToggleType.Switch, isOn: this.draftAvatarSprite })
              .selectedColor(this.accentColorStore)
              .onChange((enabled: boolean): void => { this.draftAvatarSprite = enabled; })
          }
          .width('100%')
          .padding({ top: 4 })
        }

        Divider().color(LINE).strokeWidth(0.5)

        Row() {
          Text('高级：使用图片链接')
            .fontSize(12.5)
            .fontColor(INK3)
            .layoutWeight(1)
          Icon({ name: 'chevron_down', iconSize: 13, color: INK3 })
            .rotate({ angle: this.showAdvancedAvatarUrl ? 180 : 0 })
        }
        .width('100%')
        .padding({ top: 2, bottom: 2 })
        .onClick((): void => {
          animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
            this.showAdvancedAvatarUrl = !this.showAdvancedAvatarUrl;
          });
        })

        if (this.showAdvancedAvatarUrl) {
          TextInput({ text: this.draftAvatarUri, placeholder: 'https:// 或本地图片路径' })
            .fontSize(13)
            .fontColor(INK)
            .backgroundColor(BG)
            .borderRadius(10)
            .border({ width: 0.75, color: LINE })
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onChange((v: string): void => {
              if (v !== this.draftAvatarUri) this.draftAvatarSprite = false;
              this.draftAvatarUri = v;
            })
        }
      }
      .width('100%')
      .padding(14)
      .borderRadius(18)
      .backgroundColor(SURFACE)
      .border({ width: 0.75, color: LINE })

      Row({ space: 10 }) {
        Row() {
          Text(this.saving ? '保存中…' : '保存形象')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .fontColor(ACCENT_INK)
        }
        .layoutWeight(1)
        .height(42)
        .borderRadius(14)
        .backgroundColor(this.accentColorStore)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .opacity(this.saving ? 0.6 : 1)
        .onClick((): void => { this.saveAvatarSettings(); })

        Row() {
          Text('恢复默认')
            .fontSize(13.5)
            .fontColor(INK2)
        }
        .height(42)
        .padding({ left: 16, right: 16 })
        .borderRadius(14)
        .backgroundColor(SURFACE)
        .border({ width: 0.75, color: LINE })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .opacity(this.saving ? 0.6 : 1)
        .onClick((): void => { this.resetAvatarSettings(); })
      }
      .width('100%')
    }
    .width('100%')
  }

  @Builder
  private TabBar(): void {
    Stack({ alignContent: Alignment.Start }) {
      if (this.tabIndicatorReady) {
        Column()
          .width(this.tabIndicatorW)
          .height(34)
          .borderRadius(999)
          .backgroundColor(SURFACE)
          .shadow({ radius: 6, color: HOME_GLASS_SHADOW, offsetY: 1 })
          .translate({ x: this.tabIndicatorX })
          .hitTestBehavior(HitTestMode.None)
      }

      Row({ space: 0 }) {
        this.TabChip('soul', '人设 Soul')
        this.TabChip('memory', '记忆 Memory')
        this.TabChip('ideas', '灵感 点子')
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .height(38)
    .padding(2)
    .borderRadius(999)
    .backgroundColor(SURFACE2)
  }

  @Builder
  private TabChip(tab: EmbodimentSheetTab, label: string): void {
    Text(label)
      .layoutWeight(1)
      .height(34)
      .textAlign(TextAlign.Center)
      .fontSize(13)
      .fontWeight(this.activeTab === tab ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(this.activeTab === tab ? INK : INK2)
      .borderRadius(999)
      .backgroundColor(this.tabIndicatorReady ? Color.Transparent : (this.activeTab === tab ? SURFACE : Color.Transparent))
      .onAreaChange((_old: Area, area: Area): void => {
        this.recordTabArea(tab, area);
      })
      .onClick((): void => { this.selectTab(tab); })
  }

  @Builder
  private SoulTabContent(): void {
    Column({ space: 12 }) {
      Column({ space: 10 }) {
        Row({ space: 8 }) {
          Text('灵魂设定')
            .fontSize(14.5)
            .fontWeight(FontWeight.Bold)
            .fontColor(INK)

          Text(this.previewIsDefault() ? '默认' : `v${this.revision}`)
            .fontSize(11)
            .fontColor(this.previewIsDefault() ? INK3 : this.accentColorStore)
            .padding({ left: 8, right: 8, top: 2, bottom: 2 })
            .borderRadius(999)
            .backgroundColor(this.previewIsDefault() ? SURFACE2 : accentSoftBg(this.accentColorStore))

          Blank()

          Row({ space: 4 }) {
            if (!this.editingSoul) {
              Icon({ name: 'edit', iconSize: 12.5, color: this.accentColorStore })
            }
            Text(this.editingSoul ? '取消' : '编辑')
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.editingSoul ? INK2 : this.accentColorStore)
          }
          .padding({ left: 8, right: 4, top: 4, bottom: 4 })
          .onClick((): void => { this.setSoulEditing(!this.editingSoul); })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        if (this.editingSoul) {
          Column({ space: 10 }) {
            Text(`称呼（最多 ${MAX_AGENT_SOUL_NAME_CHARS} 字）`).fontSize(12).fontColor(INK3).width('100%')
            TextInput({ text: this.draftName, placeholder: DEFAULT_AGENT_SOUL_NAME })
              .maxLength(MAX_AGENT_SOUL_NAME_CHARS)
              .fontSize(14)
              .fontColor(INK)
              .backgroundColor(BG)
              .borderRadius(12)
              .border({ width: 0.75, color: LINE })
              .padding({ left: 12, right: 12, top: 9, bottom: 9 })
              .onChange((v: string): void => { this.draftName = v; this.dirty = true; })

            Text(`一句话签名（最多 ${MAX_AGENT_SOUL_TAGLINE_CHARS} 字）`).fontSize(12).fontColor(INK3).width('100%')
            TextInput({ text: this.draftTagline, placeholder: '一句话描述你的角色定位' })
              .maxLength(MAX_AGENT_SOUL_TAGLINE_CHARS)
              .fontSize(14)
              .fontColor(INK)
              .backgroundColor(BG)
              .borderRadius(12)
              .border({ width: 0.75, color: LINE })
              .padding({ left: 12, right: 12, top: 9, bottom: 9 })
              .onChange((v: string): void => { this.draftTagline = v; this.dirty = true; })

            Text('Soul 性格设定（Markdown）').fontSize(12).fontColor(INK3).width('100%')
            TextArea({ text: this.draftMarkdown, placeholder: '写下性格、口吻、表达原则和记忆习惯…' })
              .height(116)
              .fontSize(13.5)
              .fontColor(INK)
              .backgroundColor(BG)
              .borderRadius(12)
              .border({ width: 0.75, color: LINE })
              .padding(10)
              .onChange((v: string): void => { this.draftMarkdown = v; this.dirty = true; })

            Row({ space: 10 }) {
              Row() {
                Text(this.saving ? '保存中…' : '保存设定')
                  .fontSize(13.5)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(ACCENT_INK)
              }
              .layoutWeight(1)
              .height(40)
              .borderRadius(12)
              .backgroundColor(this.accentColorStore)
              .justifyContent(FlexAlign.Center)
              .alignItems(VerticalAlign.Center)
              .opacity(this.saving ? 0.6 : 1)
              .onClick((): void => { this.saveSoulSettings(); })

              Row() {
                Text('恢复默认')
                  .fontSize(13)
                  .fontColor(INK2)
              }
              .height(40)
              .padding({ left: 14, right: 14 })
              .borderRadius(12)
              .backgroundColor(BG)
              .border({ width: 0.75, color: LINE })
              .justifyContent(FlexAlign.Center)
              .alignItems(VerticalAlign.Center)
              .opacity(this.saving ? 0.6 : 1)
              .onClick((): void => { this.resetSoulDefault(); })
            }
            .width('100%')
            .padding({ top: 2 })
          }
          .width('100%')
        } else {
          if (!this.loaded) {
            Text('正在读取形象设置…').fontSize(13).fontColor(INK3).width('100%')
          } else if (this.draftMarkdown.trim().length > 0) {
            Text(this.draftMarkdown.trim())
              .fontSize(13.5)
              .lineHeight(21)
              .fontColor(INK)
              .width('100%')
          } else {
            Text('还没有专门设定。可以在主对话直接说「你给自己写个人设」，或点右上角编辑。')
              .fontSize(13.5)
              .lineHeight(21)
              .fontColor(INK2)
              .width('100%')
          }
          Text(`同步来源：${this.source || 'default'} · 跨端双向同步`)
            .fontSize(META)
            .fontColor(INK4)
            .width('100%')
            .padding({ top: 2 })
        }
      }
      .width('100%')
      .padding(16)
      .borderRadius(18)
      .backgroundColor(SURFACE)
      .border({ width: 0.75, color: LINE })

      if (this.history.length > 0) {
        Column({ space: 8 }) {
          Text(`最近人设快照（${this.history.length}/8）`)
            .fontSize(13.5)
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
                .fontWeight(FontWeight.Medium)
                .fontColor(this.accentColorStore)
                .padding({ left: 10, right: 10, top: 5, bottom: 5 })
                .borderRadius(999)
                .backgroundColor(accentSoftBg(this.accentColorStore))
                .onClick((): void => { this.restoreSnapshot(entry); })
            }
            .width('100%')
            .padding({ left: 12, right: 12, top: 10, bottom: 10 })
            .borderRadius(12)
            .backgroundColor(BG)
          }, (entry: AgentSoulHistoryEntry): string => `${entry.id}-${entry.updatedAtMs}`)
        }
        .width('100%')
        .padding(16)
        .borderRadius(18)
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
          .padding(16)
          .borderRadius(18)
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
          .padding({ top: 11, bottom: 11 })
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
          .padding({ top: 11, bottom: 11 })
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
            .fontWeight(FontWeight.Medium)
            .fontColor(this.accentColorStore)
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 13, bottom: 13 })
        .borderRadius(18)
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
    Column({ space: 10 }) {
      this.TopNavigationBar()

      Scroll() {
        Column({ space: 14 }) {
          this.PersonaHeroHeader()

          if (this.editingAvatar) {
            this.AvatarEditorContent()
          } else if (this.activeTab === 'soul') {
            this.SoulTabContent()
          } else if (this.activeTab === 'memory') {
            this.MemoryTabContent()
          } else {
            this.IdeasTabContent()
          }
        }
        .width('100%')
        .padding({ bottom: 28 })
      }
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    .padding({ left: 18, right: 18, top: 10 })
    .backgroundColor(BG)
  }
}
