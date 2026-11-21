// AgentEmbodimentSheet — 单层形象人设面板（对齐 iOS AgentEmbodimentHomeSheet + AmberPersonaStudio，禁止嵌套第二张 Sheet）
// 顶部居中标题「形象人设」+ 胶囊「完成」（形象编辑态左侧显示「‹ 人设」）
// 居中大圆形形象徽章 + 底部叠加「更换形象 ›」胶囊 + 称呼/引语签名 + 带滑动指示器的 3-Tab 分段条
// 形象编辑态支持：内置预设、相册静态选图、AI 生成单张、AI 生成 4×4 四态动画精灵图（带四态实时动态预览）

import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import type {
  AgentSoulHistoryEntry, AgentSoulMutationResult, AgentSoulState, AmberPersonaMood,
  MemoryPortrait, MemoryPortraitBucketFilter, MemoryPortraitDimension,
  MemoryPortraitItem, MemoryRecord, SpriteSheetLayoutSpec,
} from '@amber/chat-domain';
import {
  DEFAULT_AGENT_SOUL_NAME, MAX_AGENT_SOUL_NAME_CHARS, MAX_AGENT_SOUL_TAGLINE_CHARS,
  buildMemoryPortrait, computeIsAgentSoulDefault, detectPersonaSpriteSheetLayout,
  normalizeAgentSoulDisplayName,
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
import { generatePersonaImage, imageGenToolAvailable, type PersonaImageGenResult } from '../../platform_impl/ImageGenTool.ets';
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

interface MoodPreviewOption {
  mood: AmberPersonaMood;
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

const MOOD_PREVIEW_OPTIONS: MoodPreviewOption[] = [
  { mood: 'idle', label: '待机' },
  { mood: 'running', label: '思考' },
  { mood: 'needs_input', label: '等待' },
  { mood: 'blocked', label: '异常' },
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

// 对齐 iOS AmberPersonaSpriteSheetPrompt：单次请求生成 4×4 四态动画精灵图
const buildPersonaSpriteSheetPrompt = (stylePrompt: string): string =>
  `Character sprite sheet for the personal AI companion: ${stylePrompt}. ` +
  'One single image containing a strict 4x4 grid: 4 equal columns and 4 equal rows, 16 equally sized cells, ' +
  'generous even padding inside every cell, plain flat background, no text, no labels, no grid lines, no borders. ' +
  'The same character appears in all 16 cells with identical face, hairstyle, outfit, palette, head-to-body ratio, ' +
  'lighting, camera distance and framing; each cell is a front-facing centered bust that stays circular-crop friendly. ' +
  'Row order top to bottom, each row is one 4-frame loop: ' +
  'Row 1 idle: calm looking ahead, half blink, eyes closed breathing, eyes open with a small gaze drift. ' +
  'Row 2 running: focused leaning forward, glancing left while reviewing, glancing right while reviewing, a small determined nod. ' +
  'Row 3 needs input: head raised looking at the viewer, slight head tilt, a light wave, expectant attentive gaze. ' +
  'Row 4 blocked: a brief startled freeze, sighing softly, hand on chin thinking, cheering up with head lifted.';

const buildSinglePortraitPrompt = (stylePrompt: string): string =>
  `Personal AI companion avatar portrait: ${stylePrompt}. ` +
  'Centered circular-crop friendly bust composition, expressive eyes, warm editorial illustration quality, clean plain background, no text.';

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
  @State private showStylePrompt: boolean = false;
  @State private avatarPreviewMood: AmberPersonaMood = 'idle';
  @State private pickingAvatar: boolean = false;
  @State private generatingAvatar: boolean = false;
  @State private generatingHint: string = '';
  @State private imageGenConfigured: boolean = false;
  @State private stylePrompt: string = '';
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
  private mounted: boolean = false;
  private generationToken: number = 0;

  @State private memoryLoading: boolean = false;
  @State private memoryRecords: MemoryRecord[] = [];
  @State private memoryBucket: MemoryPortraitBucketFilter = 'all';
  @State private portrait: MemoryPortrait = buildMemoryPortrait([]);

  onClose: () => void = (): void => {};
  onPickStarter: (prompt: string) => void = (_prompt: string): void => {};
  onOpenMemoryRecords: () => void = (): void => {};
  onOpenMemoryTopics: () => void = (): void => {};

  aboutToAppear(): void {
    this.mounted = true;
    this.draftPreset = this.avatarPresetStore;
    this.draftAvatarUri = this.avatarUriStore;
    this.draftAvatarSprite = this.avatarSpriteStore;
    this.hydrateSoul(true);
    loadAgentSoulAvatarPrefs().then((prefs): void => {
      if (!this.mounted || this.editingAvatar) return;
      this.draftPreset = prefs.preset;
      this.draftAvatarUri = prefs.customUri;
      this.draftAvatarSprite = prefs.spriteSheet;
    }).catch((): void => {});
    imageGenToolAvailable().then((available: boolean): void => {
      if (this.mounted) this.imageGenConfigured = available;
    }).catch((): void => {});
    syncAgentSoulFromCloud();
    this.refreshMemoryPortrait();
  }

  aboutToDisappear(): void {
    this.mounted = false;
    this.generationToken++;
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
      if (!this.mounted) return;
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
      if (this.mounted) this.loaded = true;
    });
  }

  private refreshMemoryPortrait(): void {
    this.memoryLoading = true;
    getMemoryLibraryOps().getAllRecords()
      .then((records: MemoryRecord[]): void => {
        if (!this.mounted) return;
        this.memoryRecords = records;
        this.portrait = buildMemoryPortrait(records, { bucket: this.memoryBucket });
      })
      .catch((): void => {
        if (this.mounted) this.portrait = buildMemoryPortrait([]);
      })
      .finally((): void => {
        if (this.mounted) this.memoryLoading = false;
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

  private resolvedGenerationStylePrompt(): string {
    const custom: string = this.stylePrompt.trim();
    if (custom.length > 0) return custom;
    const parts: string[] = [];
    const name: string = this.previewName();
    if (name.length > 0) parts.push(name);
    const tagline: string = this.draftTagline.trim();
    if (tagline.length > 0) parts.push(tagline);
    if (parts.length > 0) return `${parts.join('，')}，温暖精致的插画风人物形象`;
    return '温暖精致的插画风私人助手形象，柔和光线，干净浅色背景';
  }

  private soulOriginLabel(): string {
    if (this.source === 'cloud') return '已与云端保持同步';
    if (this.previewIsDefault()) return '当前使用默认人设';
    return '已保存为当前形象设定';
  }

  private setAvatarEditing(next: boolean): void {
    if (this.editingAvatar === next) return;
    const apply = (): void => {
      this.editingAvatar = next;
      this.notice = '';
      this.avatarPreviewMood = 'idle';
      if (next) {
        this.draftPreset = this.avatarPresetStore;
        this.draftAvatarUri = this.avatarUriStore;
        this.draftAvatarSprite = this.avatarSpriteStore;
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
    if (this.pickingAvatar || this.generatingAvatar) return;
    this.pickingAvatar = true;
    const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 1;
    const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    picker.select(options).then((result: photoAccessHelper.PhotoSelectResult): void => {
      if (!this.mounted || result.photoUris.length === 0) return;
      const copiedUri: string = this.copyPhotoToSandbox(result.photoUris[0]);
      if (copiedUri.length > 0) {
        // 普通相册照片始终按单张静态头像处理，绝不盲切 4×4 网格
        this.draftAvatarUri = copiedUri;
        this.draftAvatarSprite = false;
        this.setFeedback('已选中静态照片，可直接保存，或点「生成 4×4 动图」基于它生成动画形象', false);
      }
    }).catch((): void => {
      // 用户取消选择不视为错误
    }).finally((): void => {
      if (this.mounted) this.pickingAvatar = false;
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

  private async readImageDimensions(filePath: string): Promise<SpriteSheetLayoutSpec> {
    const src: image.ImageSource = image.createImageSource(filePath);
    try {
      const info: image.ImageInfo = await src.getImageInfo();
      return detectPersonaSpriteSheetLayout(info.size.width, info.size.height);
    } finally {
      try { await src.release(); } catch (_e) {}
    }
  }

  private runAiAvatarGeneration(asSpriteSheet: boolean): void {
    if (this.generatingAvatar) return;
    this.generatingAvatar = true;
    this.generatingHint = asSpriteSheet ? '正在生成 4×4 四态动画形象…' : '正在生成静态形象…';
    this.setFeedback('', false);
    const token: number = ++this.generationToken;
    const style: string = this.resolvedGenerationStylePrompt();
    const prompt: string = asSpriteSheet
      ? buildPersonaSpriteSheetPrompt(style)
      : buildSinglePortraitPrompt(style);
    // 若用户已选静态参考图（非已有精灵图），自动带入作为图生图参考保持角色一致
    const refUri: string | undefined = (!this.draftAvatarSprite && this.draftAvatarUri.trim().length > 0)
      ? this.draftAvatarUri.trim()
      : undefined;

    generatePersonaImage(prompt, refUri)
      .then(async (res: PersonaImageGenResult): Promise<void> => {
        if (!this.mounted || token !== this.generationToken) return;
        if (asSpriteSheet) {
          const spec: SpriteSheetLayoutSpec = await this.readImageDimensions(res.filePath);
          if (!this.mounted || token !== this.generationToken) return;
          if (spec.kind !== 'quad4x4') {
            this.setFeedback('生成图未满足 4×4 网格比例，原有形象已保留，可重试。', true);
            return;
          }
          this.draftAvatarUri = res.fileUri;
          this.draftAvatarSprite = true;
          this.avatarPreviewMood = 'idle';
          this.setFeedback('4×4 动画形象已生成，可在上方切换情绪预览并保存', false);
        } else {
          this.draftAvatarUri = res.fileUri;
          this.draftAvatarSprite = false;
          this.setFeedback('静态形象已生成，点击「保存形象」生效', false);
        }
      })
      .catch((err: Error): void => {
        if (!this.mounted || token !== this.generationToken) return;
        this.setFeedback(err.message, true);
      })
      .finally((): void => {
        if (this.mounted && token === this.generationToken) {
          this.generatingAvatar = false;
          this.generatingHint = '';
        }
      });
  }

  private cancelAiGeneration(): void {
    this.generationToken++;
    this.generatingAvatar = false;
    this.generatingHint = '';
    this.setFeedback('已取消本次形象生成', false);
  }

  private saveAvatarSettings(): void {
    if (this.saving || this.generatingAvatar) return;
    this.saving = true;
    this.setFeedback('', false);
    saveAgentSoulAvatarPrefs(this.draftPreset, this.draftAvatarUri, this.draftAvatarSprite)
      .then((): void => {
        if (!this.mounted) return;
        this.setAvatarEditing(false);
        this.setFeedback('形象外观已更新', false);
      })
      .catch((err: Error): void => {
        if (this.mounted) this.setFeedback(`保存失败：${err.message}`, true);
      })
      .finally((): void => {
        if (this.mounted) this.saving = false;
      });
  }

  private resetAvatarSettings(): void {
    if (this.saving || this.generatingAvatar) return;
    this.saving = true;
    this.setFeedback('', false);
    saveAgentSoulAvatarPrefs('muse', '', false)
      .then((): void => {
        if (!this.mounted) return;
        this.draftPreset = 'muse';
        this.draftAvatarUri = '';
        this.draftAvatarSprite = false;
        this.setFeedback('已恢复默认灵狐形象', false);
      })
      .catch((err: Error): void => {
        if (this.mounted) this.setFeedback(`恢复失败：${err.message}`, true);
      })
      .finally((): void => {
        if (this.mounted) this.saving = false;
      });
  }

  private saveSoulSettings(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    updateAgentSoulFromUi(this.draftName, this.draftTagline, this.draftMarkdown)
      .then((res: AgentSoulMutationResult): void => {
        if (!this.mounted) return;
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
        if (this.mounted) this.setFeedback(`保存失败：${err.message}`, true);
      })
      .finally((): void => {
        if (this.mounted) this.saving = false;
      });
  }

  private resetSoulDefault(): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    resetAgentSoulFromUi()
      .then((res: AgentSoulMutationResult): void => {
        if (!this.mounted) return;
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
        if (this.mounted) this.setFeedback(`恢复失败：${err.message}`, true);
      })
      .finally((): void => {
        if (this.mounted) this.saving = false;
      });
  }

  private restoreSnapshot(entry: AgentSoulHistoryEntry): void {
    if (this.saving) return;
    this.saving = true;
    this.setFeedback('', false);
    restoreAgentSoulSnapshotFromUi(entry.id)
      .then((res: AgentSoulMutationResult): void => {
        if (!this.mounted) return;
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
        if (this.mounted) this.setFeedback(`切回失败：${err.message}`, true);
      })
      .finally((): void => {
        if (this.mounted) this.saving = false;
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
            mood: this.editingAvatar ? this.avatarPreviewMood : 'idle',
            customAvatarUri: this.editingAvatar ? this.draftAvatarUri : this.avatarUriStore,
            previewPreset: this.editingAvatar ? this.draftPreset : '',
            previewSpriteSheet: this.editingAvatar ? this.draftAvatarSprite : this.avatarSpriteStore,
            sheetOpen: false,
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

      // 形象编辑态且为动图或内置动画形象时，允许切换 4 种情绪实时预览动画效果
      if (this.editingAvatar && (this.draftAvatarSprite || this.draftAvatarUri.trim().length === 0)) {
        Row({ space: 6 }) {
          ForEach(MOOD_PREVIEW_OPTIONS, (opt: MoodPreviewOption): void => {
            Text(opt.label)
              .fontSize(11.5)
              .fontWeight(this.avatarPreviewMood === opt.mood ? FontWeight.Medium : FontWeight.Normal)
              .fontColor(this.avatarPreviewMood === opt.mood ? this.accentColorStore : INK3)
              .padding({ left: 10, right: 10, top: 4, bottom: 4 })
              .borderRadius(999)
              .backgroundColor(this.avatarPreviewMood === opt.mood ? accentSoftBg(this.accentColorStore) : SURFACE)
              .border({
                width: 0.75,
                color: this.avatarPreviewMood === opt.mood ? accentEdge(this.accentColorStore) : LINE,
              })
              .onClick((): void => { this.avatarPreviewMood = opt.mood; })
          }, (opt: MoodPreviewOption): string => opt.mood)
        }
      }

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
      // 1. 相册静态照片 + AI 生成（对齐 iOS AmberPersonaStudioContent）
      Column({ space: 12 }) {
        Text('自定义与 AI 生成')
          .fontSize(13.5)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
          .width('100%')

        Row({ space: 10 }) {
          Row({ space: 6 }) {
            Icon({ name: 'image', iconSize: 16, color: ACCENT_INK })
            Text(this.pickingAvatar ? '打开相册中…' : '相册选图')
              .fontSize(13.5)
              .fontWeight(FontWeight.Medium)
              .fontColor(ACCENT_INK)
          }
          .layoutWeight(1)
          .height(42)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .borderRadius(12)
          .backgroundColor(this.accentColorStore)
          .opacity(this.generatingAvatar ? 0.55 : 1)
          .onClick((): void => { this.pickAvatarFromGallery(); })

          if (this.draftAvatarUri.trim().length > 0) {
            Row() {
              Text('清除图片')
                .fontSize(13)
                .fontColor(INK2)
            }
            .height(42)
            .padding({ left: 14, right: 14 })
            .borderRadius(12)
            .backgroundColor(BG)
            .border({ width: 0.75, color: LINE })
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => {
              this.draftAvatarUri = '';
              this.draftAvatarSprite = false;
            })
          }
        }
        .width('100%')

        if (this.generatingAvatar) {
          Row({ space: 10 }) {
            LoadingProgress()
              .width(18)
              .height(18)
              .color(this.accentColorStore)
            Column({ space: 2 }) {
              Text(this.generatingHint.length > 0 ? this.generatingHint : '正在生成形象…')
                .fontSize(12.5)
                .fontWeight(FontWeight.Medium)
                .fontColor(INK)
              Text('生成完成后会自动在上方预览，确认后点击保存即可')
                .fontSize(11)
                .fontColor(INK3)
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)

            Text('取消')
              .fontSize(12)
              .fontWeight(FontWeight.Medium)
              .fontColor(ERROR)
              .padding({ left: 10, right: 10, top: 5, bottom: 5 })
              .borderRadius(999)
              .backgroundColor(BG)
              .onClick((): void => { this.cancelAiGeneration(); })
          }
          .width('100%')
          .padding(12)
          .borderRadius(12)
          .backgroundColor(BG)
          .border({ width: 0.75, color: LINE })
          .alignItems(VerticalAlign.Center)
        } else {
          Row({ space: 10 }) {
            Row({ space: 5 }) {
              Icon({ name: 'spark', iconSize: 14, color: this.accentColorStore })
              Text('生成多帧动图')
                .fontSize(13)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.accentColorStore)
            }
            .layoutWeight(1)
            .height(38)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .borderRadius(11)
            .backgroundColor(accentSoftBg(this.accentColorStore))
            .border({ width: 0.75, color: accentEdge(this.accentColorStore) })
            .onClick((): void => { this.runAiAvatarGeneration(true); })

            Row({ space: 5 }) {
              Icon({ name: 'spark', iconSize: 14, color: INK2 })
              Text('AI 生成单张')
                .fontSize(13)
                .fontWeight(FontWeight.Medium)
                .fontColor(INK2)
            }
            .layoutWeight(1)
            .height(38)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .borderRadius(11)
            .backgroundColor(BG)
            .border({ width: 0.75, color: LINE })
            .onClick((): void => { this.runAiAvatarGeneration(false); })
          }
          .width('100%')
        }

        Row({ space: 8 }) {
          Text(this.imageGenConfigured
            ? (this.draftAvatarUri.trim().length > 0 && !this.draftAvatarSprite
              ? '点「生成多帧动图」会基于当前照片自动生成四态动画'
              : 'AI 生图会结合下方描述词生成形象')
            : '未配置生图模型时，可直接从相册选静态照片')
            .fontSize(11.5)
            .fontColor(INK3)
            .layoutWeight(1)

          Row({ space: 3 }) {
            Text(this.showStylePrompt ? '收起描述' : '生成描述词')
              .fontSize(11.5)
              .fontWeight(FontWeight.Medium)
              .fontColor(INK2)
            Icon({ name: 'chevron_down', iconSize: 11, color: INK3 })
              .rotate({ angle: this.showStylePrompt ? 180 : 0 })
          }
          .onClick((): void => {
            animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
              this.showStylePrompt = !this.showStylePrompt;
            });
          })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)

        if (this.showStylePrompt) {
          TextArea({ text: this.stylePrompt, placeholder: '描述期望的外貌、画风或服饰（留空按当前人设自动生成）' })
            .height(72)
            .fontSize(13)
            .fontColor(INK)
            .backgroundColor(BG)
            .borderRadius(10)
            .border({ width: 0.75, color: LINE })
            .padding(10)
            .onChange((v: string): void => { this.stylePrompt = v; })
        }
      }
      .width('100%')
      .padding(14)
      .borderRadius(18)
      .backgroundColor(SURFACE)
      .border({ width: 0.75, color: LINE })

      // 2. 内置预设网格
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
        .opacity(this.saving || this.generatingAvatar ? 0.55 : 1)
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
        .opacity(this.saving || this.generatingAvatar ? 0.55 : 1)
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

            Text('性格与表达设定').fontSize(12).fontColor(INK3).width('100%')
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
          Text(this.soulOriginLabel())
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
