// SettingAppearancePage — 外观(iOS AppearanceSettingsView 对位,iOS 二级页骨架)
// 颜色模式(浅色/深色/跟随系统)+ 强调色五选一 + 主题包(C2:
//   内置三包/导入导出/AI 生成试穿,对齐 iOS AmberThemePack 体系)。
// 主题包导入兼容三格式(theme_pack_import.ets):跨平台 amber.theme.pack v1(iOS/Android 互通)、
//   Android 旧 token 包(schemaVersion=1,字体/布局 token 一并落 DisplaySetting)、鸿蒙原生;
//   导出统一发跨平台 v1(带鸿蒙扩展段,iOS/Android 可导入,鸿蒙无损回读)。
import {
  BG, INK, INK3, ACCENT, ACCENT_OPTIONS, setAccentColor, ERROR, TEXT_STYLES,
  SURFACE, LINE, LINE2, OVERLAY, MOTION_OVERLAY, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { CenteredTitleBar, PlainSection, PageSpacer, Seg, CardGroup, CardToggleRow } from '../components/PageTemplate.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { applyWindowTheme } from '../entryability/EntryAbility.ets';
import {
  ThemePack, themePackStore, applyThemePack, parseThemePackJson,
  isAmoledBlack, setAmoledBlack, canvasGradientColors, currentCanvasGradientAngle,
} from '../design/theme_packs.ets';
import { DesignCanvasLayer } from '../design/DesignCanvasLayer.ets';
import {
  ThemePackImportResult, ThemePackDisplayPatch, parseThemePackImport, themePackToPortableJson,
} from '../design/theme_pack_import.ets';
import { generateVisionText } from '../platform_impl/OcrSupport.ets';
import { resolveEffectiveChatChoice } from '../platform_impl/ChatModelSelectionSupport.ets';
import type { DisplaySetting } from '@amber/chat-domain';
import { loadDisplaySetting, saveDisplaySetting, makeUserMessage } from '@amber/chat-domain';
import { promptAction } from '@kit.ArkUI';
import { picker, fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';

const UI_COLOR_MODE_KEY: string = 'ui_color_mode';
const THEME_MODE_OPTIONS: string[] = ['浅色', '深色', '跟随系统'];

const THEME_GEN_PROMPT: string =
  '你是主题设计师。根据用户的描述,输出一个 JSON 主题包,且只输出 JSON 本体,不要输出解释。' +
  'JSON 结构:{"name":主题名(中文,不超过6字),"accent":"#RRGGBB","dot_grid":true或false,' +
  '"light":{"bg","surface","surface2","ink","ink2","muted","muted2","border","borderSoft"},' +
  '"dark":{同结构}},所有颜色为 #RRGGBB。' +
  '规则:bg 是页面底色;surface 是卡片色,必须比 bg 更浅;surface2 是内嵌面,介于 bg 与 surface 之间;' +
  'ink 是主文字色,与 bg 对比度至少 4.5;ink2 次文字;muted/muted2 渐弱的辅助文字;' +
  'border 强边框,borderSoft 弱边框;light 与 dark 必须是同一配色的明暗变体;' +
  'accent 在明暗两套底色上都要清晰可辨。用户描述:';

// 从模型返回中提取 JSON 本体(首个 { 到末个 })
const extractJsonObject = (text: string): string => {
  const start: number = text.indexOf('{');
  const end: number = text.lastIndexOf('}');
  if (start < 0 || end <= start) return '';
  return text.substring(start, end + 1);
};

@Entry
@Component
struct SettingAppearancePage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('systemDarkMode') systemDarkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State themeMode: string = 'system';
  @State accentColor: string = ACCENT;
  // R26:主题模式落盘失败可见回滚
  @State themeError: string = '';
  // 主题包(C2)
  @State packs: ThemePack[] = [];
  @State selectedPackId: string = '';
  // D0-11:AMOLED 纯黑(深色画布;hydrate 后取自 theme_packs 模块态)
  @State amoledOn: boolean = false;
  @State tryOnPack: ThemePack | null = null;
  @State aiPrompt: string = '';
  @State generating: boolean = false;
  @State genError: string = '';
  private preTryOnPack: ThemePack | null = null;
  private persistedThemeMode: string = 'system';
  private themeSeq: number = 0;

  aboutToAppear(): void {
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {      if (s.accentColor.length > 0) {
        this.accentColor = s.accentColor;
        setAccentColor(s.accentColor);
        AppStorage.setOrCreate<string>('accentColor', s.accentColor);
      }
    }).catch((): void => {});
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'dark' || raw === 'light' || raw === 'system') {
        this.themeMode = raw;
        this.persistedThemeMode = raw;
      }
    }).catch((): void => {});
    themePackStore.hydrate(getChatKvStore()).then((): void => {
      this.packs = themePackStore.all();
      this.selectedPackId = themePackStore.current().id;
      this.amoledOn = isAmoledBlack();
    }).catch((): void => {});
  }

  // D0-11:AMOLED 纯黑开关 — 落 KV + 重应用主题;darkMode 同值重设触发订阅者重建
  //   (与主题模式切换同一刷新机制,不引入新通道)
  private setAmoled(enabled: boolean): void {
    this.amoledOn = enabled;
    setAmoledBlack(getChatKvStore(), enabled, this.darkMode)
      .then((): void => {
        AppStorage.setOrCreate<boolean>('darkMode', this.darkMode);
      })
      .catch((): void => {});
  }

  private resolvedDark(): boolean {
    return this.themeMode === 'dark' || (this.themeMode === 'system' && this.systemDarkMode);
  }

  // R26:应用模式到主题 + 窗口 + AppStorage(单一入口,回滚复用)
  private applyThemeMode(mode: string): void {
    const resolvedDark: boolean = mode === 'dark' || (mode === 'system' && this.systemDarkMode);
    // 主题包感知:模式切换也走当前包(内含 applyTheme 全套)
    applyThemePack(themePackStore.current(), resolvedDark);
    applyWindowTheme(resolvedDark);
    AppStorage.setOrCreate<string>('uiColorMode', mode);
    AppStorage.setOrCreate<boolean>('darkMode', resolvedDark);
  }

  // R26:主题模式保存。乐观应用后 await 落盘;失败则可见回滚到最近成功值。
  //   竞态 guard:仅当本次仍是最新操作(seq 未被后操作超越)才回滚/报错,
  //   避免较早失败覆盖用户之后的选择。
  private async setTheme(mode: string): Promise<void> {
    const seq: number = ++this.themeSeq;
    this.themeMode = mode;
    this.themeError = '';
    this.applyThemeMode(mode);
    try {
      await getChatKvStore().put(UI_COLOR_MODE_KEY, mode);
      this.persistedThemeMode = mode;
    } catch (_e) {
      if (seq !== this.themeSeq) return;
      this.themeMode = this.persistedThemeMode;
      this.applyThemeMode(this.persistedThemeMode);
      this.themeError = '主题设置保存失败,已恢复原设置';
    }
  }

  private setAccent(color: string): void {
    this.accentColor = color;
    setAccentColor(color);
    AppStorage.setOrCreate<string>('accentColor', color);
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      const next: DisplaySetting = s;
      next.accentColor = color;
      saveDisplaySetting(getChatKvStore(), next);
    }).catch((): void => {});
  }

  // ── 主题包 ──

  private applyPack(pack: ThemePack): void {
    themePackStore.select(getChatKvStore(), pack).catch((): void => {});
    const dark: boolean = this.resolvedDark();
    applyThemePack(pack, dark);
    applyWindowTheme(dark);
    this.selectedPackId = pack.id;
    this.accentColor = pack.accent;
    AppStorage.setOrCreate<string>('accentColor', pack.accent);
    AppStorage.setOrCreate<boolean>('homeDotGrid', pack.dotGrid);
    AppStorage.setOrCreate<string>('themePackId', pack.id);
    // DisplaySetting.accentColor 与包 accent 同步(启动流两处 accent 解析一致)
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      const next: DisplaySetting = s;
      next.accentColor = pack.accent;
      saveDisplaySetting(getChatKvStore(), next);
    }).catch((): void => {});
  }

  private importPack(): void {
    const ctx: common.Context = getContext(this) as common.Context;
    const docPicker = new picker.DocumentViewPicker(ctx);
    docPicker.select({ maxSelectNumber: 1, fileSuffixFilters: ['json'] }).then((uris: string[]): void => {
      if (uris.length === 0) return;
      try {
        const file: fileIo.File = fileIo.openSync(uris[0], fileIo.OpenMode.READ_ONLY);
        const stat: fileIo.Stat = fileIo.statSync(file.fd);
        const buf: ArrayBuffer = new ArrayBuffer(stat.size);
        fileIo.readSync(file.fd, buf);
        fileIo.closeSync(file.fd);
        const text: string = new util.TextDecoder('utf-8').decode(new Uint8Array(buf));
        const result: ThemePackImportResult = parseThemePackImport(text);
        if (result.pack === null) {
          const first: string = result.issues.length > 0 ? result.issues[0] : '文件不是合法主题包';
          promptAction.showToast({ message: `导入失败:${first}`, duration: 2600 });
          return;
        }
        const pack: ThemePack = result.pack;
        // Android 旧 token 包的字体/布局偏好一并落 DisplaySetting(聊天页下次进入生效)
        const displayPatch: ThemePackDisplayPatch | null = result.display;
        if (displayPatch !== null) {
          const patch: ThemePackDisplayPatch = displayPatch;
          loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
            const next: DisplaySetting = s;
            if (patch.chatFontFamily !== undefined) next.chatFontFamily = patch.chatFontFamily;
            if (patch.fontSizeRatio !== undefined) next.fontSizeRatio = patch.fontSizeRatio;
            if (patch.showUserAvatar !== undefined) next.showUserAvatar = patch.showUserAvatar;
            if (patch.showAssistantBubble !== undefined) next.showAssistantBubble = patch.showAssistantBubble;
            if (patch.amberBaseFamily !== undefined) next.amberBaseFamily = patch.amberBaseFamily;
            saveDisplaySetting(getChatKvStore(), next).catch((): void => {});
          }).catch((): void => {});
        }
        themePackStore.addImported(getChatKvStore(), pack).then((): void => {
          this.packs = themePackStore.all();
          this.applyPack(pack);
          const note: string = result.warnings.length > 0 ? `,已修正:${result.warnings[0]}` : '';
          promptAction.showToast({ message: `已导入「${pack.displayName}」${note}`, duration: 2200 });
        }).catch((e: Error): void => {
          promptAction.showToast({ message: `导入失败:${e.message}`, duration: 2000 });
        });
      } catch (e) {
        promptAction.showToast({ message: `读取失败:${(e as Error).message}`, duration: 2000 });
      }
    }).catch((): void => {});
  }

  private exportCurrent(): void {
    const pack: ThemePack = themePackStore.current();
    // 统一导出跨平台 amber.theme.pack v1(iOS/Android 可直接导入;鸿蒙扩展段保无损回读)
    const json: string = themePackToPortableJson(pack);
    const fileName: string = `amber-theme-${pack.id}.json`;
    const ctx: common.Context = getContext(this) as common.Context;
    const docPicker = new picker.DocumentViewPicker(ctx);
    docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
      if (uris.length > 0) {
        try {
          const file: fileIo.File = fileIo.openSync(
            uris[0], fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
          fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(json));
          fileIo.closeSync(file.fd);
          promptAction.showToast({ message: `已导出 ${fileName}`, duration: 1800 });
        } catch (e) {
          promptAction.showToast({ message: `导出失败:${(e as Error).message}`, duration: 2000 });
        }
      }
    }).catch((): void => {});
  }

  private removeImported(pack: ThemePack): void {
    themePackStore.removeImported(getChatKvStore(), pack.id).then((): void => {
      this.packs = themePackStore.all();
      this.applyPack(themePackStore.current());
    }).catch((): void => {});
  }

  // ── AI 主题生成(描述 → LLM 产 JSON → 试穿 → 采用/还原;iOS AI 主题生成对齐) ──

  private generateTheme(): void {
    if (this.generating) return;
    const idea: string = this.aiPrompt.trim();
    if (idea.length === 0) {
      promptAction.showToast({ message: '先描述想要的主题感觉', duration: 1600 });
      return;
    }
    this.generating = true;
    this.genError = '';
    resolveEffectiveChatChoice(undefined).then((choice) => {
      if (choice === null) {
        this.generating = false;
        this.genError = '未配置聊天模型,无法生成主题';
        return;
      }
      generateVisionText(choice.provider, choice.model, [makeUserMessage(THEME_GEN_PROMPT + idea)])
        .then((text: string | null): void => {
          this.generating = false;
          if (text === null) {
            this.genError = '生成失败:模型无返回';
            return;
          }
          const json: string = extractJsonObject(text);
          const pack: ThemePack | null = json.length > 0 ? parseThemePackJson(json) : null;
          if (pack === null) {
            this.genError = '生成结果不是合法主题包,请重试';
            return;
          }
          this.preTryOnPack = themePackStore.current();
          this.tryOnPack = pack;
          this.applyTryOn(pack);
        }).catch((e: Error): void => {
          this.generating = false;
          this.genError = `生成失败:${e.message}`;
        });
    }).catch((e: Error): void => {
      this.generating = false;
      this.genError = `模型解析失败:${e.message}`;
    });
  }

  private applyTryOn(pack: ThemePack): void {
    const dark: boolean = this.resolvedDark();
    applyThemePack(pack, dark);
    applyWindowTheme(dark);
    AppStorage.setOrCreate<string>('accentColor', pack.accent);
    AppStorage.setOrCreate<boolean>('homeDotGrid', pack.dotGrid);
    AppStorage.setOrCreate<string>('themePackId', pack.id);
  }

  private adoptTryOn(): void {
    const pack: ThemePack | null = this.tryOnPack;
    if (pack === null) return;
    this.tryOnPack = null;
    this.preTryOnPack = null;
    themePackStore.addImported(getChatKvStore(), pack)
      .then((): Promise<void> => themePackStore.select(getChatKvStore(), pack))
      .then((): void => {
        this.packs = themePackStore.all();
        this.applyPack(pack);
      })
      .catch((e: Error): void => { this.genError = `保存失败:${e.message}`; });
  }

  private revertTryOn(): void {
    const prev: ThemePack | null = this.preTryOnPack;
    this.tryOnPack = null;
    this.preTryOnPack = null;
    if (prev !== null) {
      const dark: boolean = this.resolvedDark();
      applyThemePack(prev, dark);
      applyWindowTheme(dark);
      AppStorage.setOrCreate<string>('accentColor', prev.accent);
      AppStorage.setOrCreate<boolean>('homeDotGrid', prev.dotGrid);
      AppStorage.setOrCreate<string>('themePackId', prev.id);
      this.selectedPackId = prev.id;
      this.accentColor = prev.accent;
    }
  }

  private themeModeIndex(): number {
    if (this.themeMode === 'light') return 0;
    if (this.themeMode === 'dark') return 1;
    return 2;
  }

  @Builder
  AmoledCard(): void {
    CardToggleRow({
      label: 'AMOLED 纯黑',
      desc: '深色模式下页面背景使用纯黑,更省电',
      checked: this.amoledOn, isLast: true,
      onToggle: (): void => { this.setAmoled(!this.amoledOn); },
    })
  }

  // iOS themeCardChrome 对齐:画布样张(120 高)+ surface 页脚 + 选中描边(恒定 2pt 防跳动)
  @Builder
  ThemePackCard(pack: ThemePack): void {
    Column() {
      // 迷你样张:accent 圆点 + 品牌暗示条 + 骨架行 + accent 钮(全部取包内色板,不吃当前主题)
      Column() {
        Row() {
          Column().width(15).height(15).borderRadius(7.5)
            .backgroundColor(pack.accent)
          Column().width(58).height(6).borderRadius(3)
            .backgroundColor(pack.light.ink2)
            .margin({ left: 9 })
        }
        .width('100%').margin({ top: 12 })
        Column().width('72%').height(6).borderRadius(3)
          .backgroundColor(pack.light.muted)
          .margin({ top: 12 })
        Column().width('52%').height(6).borderRadius(3)
          .backgroundColor(pack.light.muted)
          .margin({ top: 8 })
        Column().width(46).height(16).borderRadius(8)
          .backgroundColor(pack.accent)
          .margin({ top: 12, bottom: 12 })
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
      .backgroundColor(pack.light.bg)

      Row() {
        Text(pack.displayName)
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .lineHeight(lineHeightForSize(13))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(pack.light.ink)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.selectedPackId === pack.id && this.tryOnPack === null) {
          Stack() {
            Column().width(18).height(18).borderRadius(9)
              .backgroundColor(pack.accent)
            Icon({ name: 'check', iconSize: 11, color: '#FFFFFF' })
          }
          .width(18).height(18)
        } else if (!pack.builtin) {
          Text('删除')
            .fontSize(11).fontColor(ERROR)
            .constraintSize({ minWidth: 32, minHeight: 32 })
            .textAlign(TextAlign.Center)
            .padding({ top: 8, bottom: 8 })
            .onClick((): void => { this.removeImported(pack); })
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 44 })
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .backgroundColor(pack.light.surface)
    }
    .width('100%')
    .borderRadius(18)
    .clip(true)
    .border({
      width: 2,
      color: this.selectedPackId === pack.id && this.tryOnPack === null
        ? this.accentColorStore : pack.light.borderSoft,
    })
    .onClick((): void => { this.applyPack(pack); })
  }

  @Builder
  private TryOnBar(pack: ThemePack): void {
    Row() {
      Text(`正在试穿「${pack.displayName}」`)
        .fontSize(13).fontColor(INK)
        .layoutWeight(1)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      Text('还原')
        .fontSize(13).fontColor(INK3)
        .padding({ left: 12, top: 8, bottom: 8 })
        .onClick((): void => { this.revertTryOn(); })
      Text('采用')
        .fontSize(13).fontColor(this.accentColorStore)
        .padding({ left: 12, top: 8, bottom: 8 })
        .onClick((): void => { this.adoptTryOn(); })
    }
    .width('92%')
    .padding({ left: 16, right: 16, top: 10, bottom: 10 })
    .backgroundColor(SURFACE)
    .borderRadius(12)
    .border({ width: 0.5, color: LINE })
    .shadow({ radius: 16, color: OVERLAY, offsetY: 4 })
    .margin({ bottom: 24 })
    .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 24 }))
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      // 画布纹理层(共享组件;压在渐变上、内容下)
      DesignCanvasLayer({ surface: 'shell' })
      Column() {
        CenteredTitleBar({ title: '外观' })
        Scroll() {
          Column() {
            PlainSection({ label: '外观模式' })
            Column() {
              Seg({
                options: THEME_MODE_OPTIONS,
                selected: this.themeModeIndex(),
                onSelect: (idx: number): void => {
                  this.setTheme(idx === 0 ? 'light' : idx === 1 ? 'dark' : 'system')
                    .catch((): void => {});
                },
              })
            }
            .width('100%')
            .padding({ left: 16, right: 16 })
            if (this.themeError.length > 0) {
              // R26:失败可见反馈(与设置页错误条同款式)
              Text(this.themeError)
                .fontSize(TEXT_STYLES.secondary.size)
                .fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(ERROR)
                .width('100%').maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .padding({ left: 16, right: 16 })
                .margin({ top: 8 })
            }

            // D0-11:AMOLED 纯黑(深色模式画布)
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.AmoledCard(); } })
              .margin({ left: 16, right: 16, top: 12 })

            // ── 主题包(内置 + 导入;iOS 主题库对齐:双列迷你样张卡) ──
            PlainSection({ label: '主题包' })
            Grid() {
              ForEach(this.packs, (pack: ThemePack): void => {
                GridItem() {
                  this.ThemePackCard(pack)
                }
              }, (pack: ThemePack): string => pack.id)
            }
            .columnsTemplate('1fr 1fr')
            .columnsGap(12)
            .rowsGap(12)
            .width('100%')
            .padding({ left: 16, right: 16 })
            Row() {
              Text('导入主题包')
                .fontSize(13).fontColor(this.accentColorStore)
                .padding({ left: 16, top: 6, bottom: 6 })
                .onClick((): void => { this.importPack(); })
              Text('导出当前主题')
                .fontSize(13).fontColor(INK3)
                .padding({ left: 20, top: 6, bottom: 6 })
                .onClick((): void => { this.exportCurrent(); })
            }
            .width('100%')
            .margin({ top: 10, bottom: 4 })

            // ── AI 主题生成(试穿 → 采用/还原) ──
            PlainSection({ label: 'AI 主题生成' })
            Column() {
              TextInput({ text: this.aiPrompt, placeholder: '例如:雨后竹林、深夜图书馆、奶油拿铁' })
                .height(44).fontSize(14).fontColor(INK)
                .backgroundColor(SURFACE).borderRadius(10)
                .padding({ left: 14, right: 14 })
                .onChange((v: string): void => { this.aiPrompt = v; })
              Row() {
                if (this.genError.length > 0) {
                  Text(this.genError)
                    .fontSize(12).fontColor(ERROR)
                    .layoutWeight(1)
                    .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                } else {
                  Blank().layoutWeight(1)
                }
                Text(this.generating ? '生成中…' : '生成并试穿')
                  .fontSize(14)
                  .fontColor(this.generating ? INK3 : this.accentColorStore)
                  .padding({ left: 12, top: 8, bottom: 8 })
                  .onClick((): void => { this.generateTheme(); })
              }
              .width('100%')
              .margin({ top: 10 })
            }
            .width('100%')
            .padding({ left: 16, right: 16 })

            PlainSection({ label: '强调色' })
            Row() {
              ForEach(ACCENT_OPTIONS, (color: string, idx: number): void => {
                Stack({ alignContent: Alignment.Center }) {
                  Column()
                    .width(this.accentColor === color ? 44 : 36)
                    .height(this.accentColor === color ? 44 : 36)
                    .borderRadius(this.accentColor === color ? 22 : 18)
                    .backgroundColor(this.accentColor === color ? BG : color)
                    .border({ width: this.accentColor === color ? 2 : 0, color: INK })
                  if (this.accentColor === color) {
                    Column()
                      .width(36).height(36).borderRadius(18)
                      .backgroundColor(color)
                      .border({ width: 3, color: BG })
                  }
                }
                .width(44).height(44)
                .margin({ right: idx < ACCENT_OPTIONS.length - 1 ? 12 : 0 })
                .onClick((): void => { this.setAccent(color); })
              }, (color: string, idx: number): string => `swatch-${idx}-${color}`)
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 10, bottom: 4 })
            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
      .width('100%').height('100%')
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      // 试穿条(iOS AmberThemeTryOnBar 语义:全局试穿 → 采用/还原)
      if (this.tryOnPack !== null) {
        this.TryOnBar(this.tryOnPack)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .linearGradient({
      angle: currentCanvasGradientAngle(),
      colors: canvasGradientColors(),
    })
  }
}
