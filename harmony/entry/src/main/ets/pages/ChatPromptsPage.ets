// ChatPromptsPage — 提示词注入管理面(D-101)
// Android 锚点:
//   PromptPage.kt(全文 1176 行)— 双 tab(NavigationBar 模式注入/世界书);
//     列表卡(position/priority/已禁用 tag;entries 数 tag;desc 一行);
//     useEditState confirm:id 定位 → 替换,否则 append(:183-191,:578-586);
//     delete = list - item;拖拽 reorder = removeAt+add;
//     ModeInjectionEditSheet(:408-505 字段:name/enabled/priority/position/
//       AT_DEPTH→injectDepth/role/content);
//     LorebookEditSheet(:793-923:name/desc/enabled/entries CRUD);
//     RegexInjectionEditDialog(:949-1176:name/enabled/priority/position/
//       injectDepth/keywords chips+新增/useRegex/caseSensitive/constantActive
//       (+desc)/scanDepth/role/content;canSave = keywords 非空 || constantActive);
//     新增 = 默认构造(ModeInjection()/Lorebook()/RegexInjection())
//   values-zh/strings.xml:417-459(全部 label 逐字)
// 偏差登记:
//   - HorizontalPager+bottomNavigationBar → Seg 分段控件(2026-09-27 iOS 对齐)
//   - 长按拖拽排序 → 上移/下移 按钮(D-099 先例)
//   - SwipeToDismiss 删除 → 卡内'删除'按钮(语义等价)
//   - ModalBottomSheet/AlertDialog → 全屏 overlay(D-094 先例;条目编辑为二级 overlay)
//   - 导入/导出文件 picker + ExportDialog 未移植(外部目录 picker 未移植先例,P1 登记)
//   - int 输入:toIntOrNull()?.let 语义 = 草稿串 + 确认时解析失败回退最后有效值
//
// 2026-09-27 二级页 iOS 对齐:仅换视觉骨架(CenteredTitleBar + Seg 分段 +
// PlainSection + CardGroup r18+描边 + CardRow + StatusChip + FooterNote),
// 逻辑零改动;编辑 overlay 保持原 D-094 形态。
import { promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import {
  ACCENT, BG, INK, INK3, INK4, LINE, LINE2, OVERLAY, SURFACE, ERROR, GUTTER, TEXT_STYLES,
  lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, StatusChip, FooterNote, Seg, PageSpacer,
} from '../components/PageTemplate.ets';
import { makeLorebook, makeModeInjection, makeRegexInjection } from '@amber/chat-domain';
import {
  decodeLorebooks, decodeModeInjections, encodeLorebooks, encodeModeInjections,
} from '@amber/chat-domain';
import type {
  InjectionPosition, InjectionRole, Lorebook, PromptInjection, RegexInjection,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { loadLorebooks, loadModeInjections, updateLorebooks, updateModeInjections }
  from '../platform_impl/ExtensionPrefs.ets';

const POSITIONS: InjectionPosition[] = [
  'before_system_prompt', 'after_system_prompt', 'top_of_chat',
  'bottom_of_chat', 'at_depth',
];

// getPositionLabel(:510-516)/getRoleLabel(:537-541)zh 串逐字
const positionLabel = (p: InjectionPosition): string => {
  switch (p) {
    case 'before_system_prompt': return '系统提示词前';
    case 'after_system_prompt': return '系统提示词后';
    case 'top_of_chat': return '对话开头';
    case 'bottom_of_chat': return '最新消息前';
    case 'at_depth': return '在深度';
  }
};

const roleLabel = (r: InjectionRole): string =>
  r === 'user' ? '用户（包裹在系统标签中）' : '助手（预填充）';

// toIntOrNull:整串合法整数才解析
const parseIntOrNull = (v: string): number | null => {
  const t: string = v.trim();
  if (!/^[+-]?\d+$/.test(t)) return null;
  const n: number = parseInt(t, 10);
  return Number.isNaN(n) ? null : n;
};

// 导入文件形状(as 具名 interface 先例:ManagedFileStore.ets:55)
interface PromptsImportShape {
  modeInjections?: object[];
  lorebooks?: object[];
}

@Entry
@Component
struct ChatPromptsPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State loaded: boolean = false;
  @State tab: number = 0; // 0 = 模式注入;1 = 世界书
  @State modeInjections: PromptInjection[] = [];
  @State lorebooks: Lorebook[] = [];
  // ===== 模式注入编辑 overlay =====
  @State miVisible: boolean = false;
  @State miId: string = '';
  @State miName: string = '';
  @State miEnabled: boolean = true;
  @State miPriority: number = 0;
  @State miPriorityText: string = '0';
  @State miPosition: InjectionPosition = 'after_system_prompt';
  @State miInjectDepth: number = 4;
  @State miInjectDepthText: string = '4';
  @State miRole: InjectionRole = 'user';
  @State miContent: string = '';
  @State miPosMenuOpen: boolean = false;
  @State miRoleMenuOpen: boolean = false;
  // ===== 世界书编辑 overlay =====
  @State lbVisible: boolean = false;
  @State lbId: string = '';
  @State lbName: string = '';
  @State lbDesc: string = '';
  @State lbEnabled: boolean = true;
  @State lbEntries: RegexInjection[] = [];
  // ===== 条目编辑二级 overlay =====
  @State enVisible: boolean = false;
  @State enId: string = '';
  @State enName: string = '';
  @State enEnabled: boolean = true;
  @State enPriority: number = 0;
  @State enPriorityText: string = '0';
  @State enPosition: InjectionPosition = 'after_system_prompt';
  @State enInjectDepth: number = 4;
  @State enInjectDepthText: string = '4';
  @State enKeywords: string[] = [];
  @State enNewKeyword: string = '';
  @State enUseRegex: boolean = false;
  @State enCaseSensitive: boolean = false;
  @State enConstantActive: boolean = false;
  @State enScanDepth: number = 4;
  @State enScanDepthText: string = '4';
  @State enRole: InjectionRole = 'user';
  @State enContent: string = '';
  @State enPosMenuOpen: boolean = false;
  @State enRoleMenuOpen: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const kv = getChatKvStore();
    const mis: PromptInjection[] = await loadModeInjections(kv);
    const lbs: Lorebook[] = await loadLorebooks(kv);
    this.modeInjections = mis;
    this.lorebooks = lbs;
    this.loaded = true;
  }

  // ===== 模式注入列表操作 =====

  // 导出:DocumentSavePicker → amber-prompts.json(模式注入 + 世界书全量)
  private exportPrompts(): void {
    const content: string = '{"version":1,"modeInjections":'
      + encodeModeInjections(this.modeInjections) + ',"lorebooks":'
      + encodeLorebooks(this.lorebooks) + '}';
    const opts: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
    opts.newFileNames = ['amber-prompts.json'];
    const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker();
    docPicker.save(opts).then((uris: string[]): void => {
      if (uris.length === 0) return;
      const file = fileIo.openSync(uris[0], fileIo.OpenMode.WRITE_ONLY);
      fileIo.writeSync(file.fd, content);
      fileIo.closeSync(file);
      promptAction.showToast({ message: '已导出提示词', duration: 1400 });
    }).catch((): void => {
      promptAction.showToast({ message: '导出取消/失败', duration: 1400 });
    });
  }

  // 导入:DocumentSelectPicker → 追加合并,同名跳过(decode 失败即整体拒收)
  private importPrompts(): void {
    const opts: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
    opts.maxSelectNumber = 1;
    const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker();
    docPicker.select(opts).then(async (uris: string[]): Promise<void> => {
      if (uris.length === 0) return;
      try {
        const file = fileIo.openSync(uris[0], fileIo.OpenMode.READ_ONLY);
        const text: string = fileIo.readTextSync(uris[0]);
        fileIo.closeSync(file);
        const parsed = JSON.parse(text) as PromptsImportShape;
        const misRaw = parsed.modeInjections !== undefined
          ? decodeModeInjections(JSON.stringify(parsed.modeInjections)) : null;
        const lbsRaw = parsed.lorebooks !== undefined
          ? decodeLorebooks(JSON.stringify(parsed.lorebooks)) : null;
        if (misRaw === null && lbsRaw === null) {
          promptAction.showToast({ message: '文件格式不受支持', duration: 1600 });
          return;
        }
        let added: number = 0;
        let skipped: number = 0;
        const kv = getChatKvStore();
        if (misRaw !== null) {
          const names: string[] = this.modeInjections.map((m: PromptInjection): string => m.name);
          const add = misRaw.filter((m: PromptInjection): boolean => names.indexOf(m.name) < 0);
          skipped += misRaw.length - add.length;
          added += add.length;
          await updateModeInjections(kv, (): PromptInjection[] => [...this.modeInjections, ...add]);
        }
        if (lbsRaw !== null) {
          const names: string[] = this.lorebooks.map((l: Lorebook): string => l.name);
          const add = lbsRaw.filter((l: Lorebook): boolean => names.indexOf(l.name) < 0);
          skipped += lbsRaw.length - add.length;
          added += add.length;
          await updateLorebooks(kv, (): Lorebook[] => [...this.lorebooks, ...add]);
        }
        await this.refresh();
        promptAction.showToast({
          message: `导入 ${added} 条${skipped > 0 ? `,跳过同名 ${skipped} 条` : ''}`,
          duration: 1800,
        });
      } catch (e) {
        promptAction.showToast({ message: `导入失败:${String(e)}`, duration: 1800 });
      }
    }).catch((): void => {
      promptAction.showToast({ message: '导入取消', duration: 1200 });
    });
  }

  private async patchModeInjections(next: PromptInjection[]): Promise<void> {
    await updateModeInjections(getChatKvStore(), (): PromptInjection[] => next);
    await this.refresh();
  }

  // useEditState confirm(:183-191):id 定位 → 替换;否则 append
  private confirmModeInjection(): void {    const edited: PromptInjection = {
      id: this.miId,
      name: this.miName,
      enabled: this.miEnabled,
      priority: parseIntOrNull(this.miPriorityText) ?? this.miPriority,
      position: this.miPosition,
      content: this.miContent,
      injectDepth: parseIntOrNull(this.miInjectDepthText) ?? this.miInjectDepth,
      role: this.miRole,
    };
    const list: PromptInjection[] = this.modeInjections.slice();
    const index: number = list.findIndex((x: PromptInjection): boolean => x.id === edited.id);
    if (index >= 0) list[index] = edited;
    else list.push(edited);
    // 落盘成功后再关 overlay,失败诚实提示(修"先关后存"假成功)
    this.patchModeInjections(list).then((): void => { this.miVisible = false; })
      .catch((): void => { promptAction.showToast({ message: '保存失败', duration: 1500 }); });
  }

  private deleteModeInjection(item: PromptInjection): void {
    this.patchModeInjections(this.modeInjections.filter(
      (x: PromptInjection): boolean => x.id !== item.id));
  }

  // 拖拽 reorder(removeAt+add)→ 上移/下移 适配
  private moveModeInjection(from: number, to: number): void {
    if (from < 0 || from >= this.modeInjections.length) return;
    if (to < 0 || to >= this.modeInjections.length) return;
    const list: PromptInjection[] = this.modeInjections.slice();
    const item: PromptInjection = list.splice(from, 1)[0];
    list.splice(to, 0, item);
    this.patchModeInjections(list);
  }

  private openModeInjectionEditor(mi: PromptInjection | null): void {
    const v: PromptInjection = mi !== null ? mi : makeModeInjection();
    this.miId = v.id;
    this.miName = v.name;
    this.miEnabled = v.enabled;
    this.miPriority = v.priority;
    this.miPriorityText = String(v.priority);
    this.miPosition = v.position;
    this.miInjectDepth = v.injectDepth;
    this.miInjectDepthText = String(v.injectDepth);
    this.miRole = v.role;
    this.miContent = v.content;
    this.miPosMenuOpen = false;
    this.miRoleMenuOpen = false;
    this.miVisible = true;
  }

  // ===== 世界书列表操作 =====

  private async patchLorebooks(next: Lorebook[]): Promise<void> {
    await updateLorebooks(getChatKvStore(), (): Lorebook[] => next);
    await this.refresh();
  }

  private confirmLorebook(): void {
    const edited: Lorebook = {
      id: this.lbId,
      name: this.lbName,
      description: this.lbDesc,
      enabled: this.lbEnabled,
      entries: this.lbEntries,
    };
    const list: Lorebook[] = this.lorebooks.slice();
    const index: number = list.findIndex((x: Lorebook): boolean => x.id === edited.id);
    if (index >= 0) list[index] = edited;
    else list.push(edited);
    this.patchLorebooks(list).then((): void => { this.lbVisible = false; })
      .catch((): void => { promptAction.showToast({ message: '保存失败', duration: 1500 }); });
  }

  private deleteLorebook(item: Lorebook): void {
    this.patchLorebooks(this.lorebooks.filter(
      (x: Lorebook): boolean => x.id !== item.id));
  }

  private moveLorebook(from: number, to: number): void {
    if (from < 0 || from >= this.lorebooks.length) return;
    if (to < 0 || to >= this.lorebooks.length) return;
    const list: Lorebook[] = this.lorebooks.slice();
    const item: Lorebook = list.splice(from, 1)[0];
    list.splice(to, 0, item);
    this.patchLorebooks(list);
  }

  private openLorebookEditor(lb: Lorebook | null): void {
    const v: Lorebook = lb !== null ? lb : makeLorebook();
    this.lbId = v.id;
    this.lbName = v.name;
    this.lbDesc = v.description;
    this.lbEnabled = v.enabled;
    this.lbEntries = v.entries.slice();
    this.lbVisible = true;
  }

  // ===== 条目操作(作用于世界书草稿,确认世界书时才持久化) =====

  private openEntryEditor(e: RegexInjection | null): void {
    const v: RegexInjection = e !== null ? e : makeRegexInjection();
    this.enId = v.id;
    this.enName = v.name;
    this.enEnabled = v.enabled;
    this.enPriority = v.priority;
    this.enPriorityText = String(v.priority);
    this.enPosition = v.position;
    this.enInjectDepth = v.injectDepth;
    this.enInjectDepthText = String(v.injectDepth);
    this.enKeywords = v.keywords.slice();
    this.enNewKeyword = '';
    this.enUseRegex = v.useRegex;
    this.enCaseSensitive = v.caseSensitive;
    this.enConstantActive = v.constantActive;
    this.enScanDepth = v.scanDepth;
    this.enScanDepthText = String(v.scanDepth);
    this.enRole = v.role;
    this.enContent = v.content;
    this.enPosMenuOpen = false;
    this.enRoleMenuOpen = false;
    this.enVisible = true;
  }

  // entry useEditState confirm(:800-808):作用于 book.entries 草稿
  private confirmEntry(): void {
    const edited: RegexInjection = {
      id: this.enId,
      name: this.enName,
      enabled: this.enEnabled,
      priority: parseIntOrNull(this.enPriorityText) ?? this.enPriority,
      position: this.enPosition,
      content: this.enContent,
      injectDepth: parseIntOrNull(this.enInjectDepthText) ?? this.enInjectDepth,
      role: this.enRole,
      keywords: this.enKeywords,
      useRegex: this.enUseRegex,
      caseSensitive: this.enCaseSensitive,
      scanDepth: parseIntOrNull(this.enScanDepthText) ?? this.enScanDepth,
      constantActive: this.enConstantActive,
    };
    const list: RegexInjection[] = this.lbEntries.slice();
    const index: number = list.findIndex((x: RegexInjection): boolean => x.id === edited.id);
    if (index >= 0) list[index] = edited;
    else list.push(edited);
    this.lbEntries = list;
    this.enVisible = false;
  }

  private deleteEntry(item: RegexInjection): void {
    this.lbEntries = this.lbEntries.filter(
      (x: RegexInjection): boolean => x.id !== item.id);
  }

  private addKeyword(): void {
    const kw: string = this.enNewKeyword.trim();
    if (kw.length === 0) return; // isNotBlank 守卫(:1089)
    this.enKeywords = [...this.enKeywords, kw];
    this.enNewKeyword = '';
  }

  // ===== UI 构件 =====

  // 标签 chip:iOS 软底药丸(StatusChip 替换原 mono Chip;warn = 红浅底)
  @Builder
  tagText(text: string, warn: boolean) {
    StatusChip({ text: text, tone: warn ? 'error' : 'muted' }).margin({ right: 4 })
  }

  // 列表行动作:上移/下移/编辑/删除(disabled 灰;danger 红;44 热区)
  @Builder
  rowAction(label: string, enabled: boolean, danger: boolean, onTap: () => void) {
    Row() {
      Text(label)
        .fontSize(13).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(13))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(!enabled ? INK4 : (danger ? ERROR : this.accentColorStore))
    }
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minWidth: 44, minHeight: 40 })
    .onClick((): void => { if (enabled) onTap(); })
  }

  @Builder
  formField(label: string, value: string, onChange: (v: string) => void) {
    Column() {
      Text(label).fontSize(12).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(12)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3).margin({ bottom: 4 })
      TextInput({ text: value })
        .width('100%').fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13)).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
        .backgroundColor(BG).borderRadius(8)
        .onChange(onChange)
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
  }

  @Builder
  switchRow(label: string, desc: string, value: boolean, onChange: (v: boolean) => void) {
    Row() {
      Column() {
        Text(label).fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13)).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
        if (desc.length > 0) {
          Text(desc).fontSize(11).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(11)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3).margin({ top: 2 })
        }
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Stack({ alignContent: Alignment.Start }) {
        Column()
          .width(42).height(24).borderRadius(99)
          .backgroundColor(value ? this.accentColorStore : LINE2)
        Column()
          .width(20).height(20).borderRadius(10)
          .backgroundColor('#FFFFFF')
          .margin({ left: value ? 22 : 2 })
          .animation({ duration: 150, curve: Curve.EaseOut })
      }
      .clip(true)
      .onClick((): void => { onChange(!value); })
      .accessibilityText(value ? '已开启' : '已关闭')
    }
    .width('100%').margin({ bottom: 12 })
  }

  // Select 组件适配:当前值行 + 展开列表(D-099 先例)
  @Builder
  positionSelector(current: InjectionPosition, menuOpen: boolean,
    onToggleMenu: () => void, onSelect: (v: InjectionPosition) => void) {
    Column() {
      Text('注入位置').fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13)).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK).margin({ bottom: 4 })
      Row() {
        Text(positionLabel(current))
          .fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13))
          .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK).layoutWeight(1)
        Text(menuOpen ? '▲' : '▼').fontSize(11).fontWeight(FontWeight.Normal)
          .lineHeight(lineHeightForSize(11)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3)
      }
      .width('100%').padding(10).backgroundColor(BG).borderRadius(8)
      .onClick(onToggleMenu)

      if (menuOpen) {
        Column() {
          ForEach(POSITIONS, (p: InjectionPosition) => {
            Row() {
              Text(positionLabel(p))
                .fontSize(13).fontColor(p === current ? ACCENT : INK)
                .layoutWeight(1)
              if (p === current) {
                Text('✓').fontSize(12).fontColor(ACCENT)
              }
            }
            .width('100%').padding({ left: 10, right: 10, top: 8, bottom: 8 })
            .onClick((): void => { onSelect(p); })
          }, (p: InjectionPosition): string => p)
        }
        .width('100%').margin({ top: 4 })
        .backgroundColor(BG).borderRadius(8)
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
  }

  @Builder
  roleSelector(current: InjectionRole, menuOpen: boolean,
    onToggleMenu: () => void, onSelect: (v: InjectionRole) => void) {
    Column() {
      Text('注入角色').fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13)).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK).margin({ bottom: 4 })
      Row() {
        Text(roleLabel(current))
          .fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13))
          .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK).layoutWeight(1)
        Text(menuOpen ? '▲' : '▼').fontSize(11).fontWeight(FontWeight.Normal)
          .lineHeight(lineHeightForSize(11)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3)
      }
      .width('100%').padding(10).backgroundColor(BG).borderRadius(8)
      .onClick(onToggleMenu)

      if (menuOpen) {
        Column() {
          Row() {
            Text(roleLabel('user'))
              .fontSize(13).fontColor(current === 'user' ? ACCENT : INK).layoutWeight(1)
            if (current === 'user') {
              Text('✓').fontSize(12).fontColor(ACCENT)
            }
          }
          .width('100%').padding({ left: 10, right: 10, top: 8, bottom: 8 })
          .onClick((): void => { onSelect('user'); })
          Row() {
            Text(roleLabel('assistant'))
              .fontSize(13).fontColor(current === 'assistant' ? ACCENT : INK).layoutWeight(1)
            if (current === 'assistant') {
              Text('✓').fontSize(12).fontColor(ACCENT)
            }
          }
          .width('100%').padding({ left: 10, right: 10, top: 8, bottom: 8 })
          .onClick((): void => { onSelect('assistant'); })
        }
        .width('100%').margin({ top: 4 })
        .backgroundColor(BG).borderRadius(8)
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
  }

  // ===== 列表卡(iOS 行骨架:标题 + 软底标签;动作行右对齐 44 热区)=====

  @Builder
  modeInjectionRow(mi: PromptInjection, index: number) {
    Column() {
      Row() {
        Column() {
          Text(mi.name.length > 0 ? mi.name : '未命名')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Row() {
            this.tagText(positionLabel(mi.position), false)
            this.tagText(`优先级：${mi.priority}`, false)
            if (!mi.enabled) {
              this.tagText('已禁用', true)
            }
          }
          .margin({ top: 6 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 2 })
      .onClick((): void => { this.openModeInjectionEditor(mi); })

      Row() {
        this.rowAction('上移', index > 0, false,
          (): void => { this.moveModeInjection(index, index - 1); })
        this.rowAction('下移', index < this.modeInjections.length - 1, false,
          (): void => { this.moveModeInjection(index, index + 1); })
        this.rowAction('编辑', true, false,
          (): void => { this.openModeInjectionEditor(mi); })
        this.rowAction('删除', true, true,
          (): void => { this.deleteModeInjection(mi); })
      }
      .width('100%').justifyContent(FlexAlign.End)
      .padding({ left: 14, right: 8, bottom: 4 })

      if (index < this.modeInjections.length - 1) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  lorebookRow(lb: Lorebook, index: number) {
    Column() {
      Row() {
        Column() {
          Text(lb.name.length > 0 ? lb.name : '未命名Lorebook')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (lb.description.length > 0) {
            Text(lb.description)
              .fontSize(12).lineHeight(lineHeightForSize(12))
              .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).margin({ top: 2 })
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          Row() {
            this.tagText(`${lb.entries.length} 条目`, false)
            if (!lb.enabled) {
              this.tagText('已禁用', true)
            }
          }
          .margin({ top: 6 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
      }
      .width('100%').alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 2 })
      .onClick((): void => { this.openLorebookEditor(lb); })

      Row() {
        this.rowAction('上移', index > 0, false,
          (): void => { this.moveLorebook(index, index - 1); })
        this.rowAction('下移', index < this.lorebooks.length - 1, false,
          (): void => { this.moveLorebook(index, index + 1); })
        this.rowAction('编辑', true, false,
          (): void => { this.openLorebookEditor(lb); })
        this.rowAction('删除', true, true,
          (): void => { this.deleteLorebook(lb); })
      }
      .width('100%').justifyContent(FlexAlign.End)
      .padding({ left: 14, right: 8, bottom: 4 })

      if (index < this.lorebooks.length - 1) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%')
  }

  // 卡片内容聚合(@Builder:组件构造必须在 builder 上下文,铁律 4b)
  @Builder
  ModeInjectionList(): void {
    ForEach(this.modeInjections, (mi: PromptInjection, index: number): void => {
      this.modeInjectionRow(mi, index)
    }, (mi: PromptInjection): string => mi.id)
  }

  @Builder
  LorebookList(): void {
    ForEach(this.lorebooks, (lb: Lorebook, index: number): void => {
      this.lorebookRow(lb, index)
    }, (lb: Lorebook): string => lb.id)
  }

  @Builder
  ImportExportCard(): void {
    Column() {
      CardRow({
        label: '导入提示词',
        desc: '从 JSON 文件追加合并，同名条目跳过',
        onRowClick: (): void => { this.importPrompts(); },
      })
      CardRow({
        label: '导出提示词',
        desc: '导出全部模式注入与世界书为 amber-prompts.json',
        isLast: true,
        onRowClick: (): void => { this.exportPrompts(); },
      })
    }
    .width('100%')
  }

  @Builder
  EmptyHint(text: string): void {
    Column() {
      Text(text)
        .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
        .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
      Text('点击右上角 ＋ 添加')
        .fontSize(12).lineHeight(lineHeightForSize(12))
        .fontFamily(FONT_SANS_MEDIUM).fontColor(INK4)
        .margin({ top: 6 })
    }
    .width('100%').alignItems(HorizontalAlign.Center)
    .padding({ top: 64, bottom: 16 })
  }

  // ===== overlay:模式注入编辑(:408-505) =====

  @Builder
  modeInjectionEditor() {
    Column() {
      Blank().onClick((): void => { this.miVisible = false; })
      Column() {
        Text('编辑模式注入')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16)).fontFamily(TEXT_STYLES.body.family).fontColor(INK)
          .margin({ bottom: 12 })
        Scroll() {
          Column() {
            this.formField('名称', this.miName,
              (v: string): void => { this.miName = v; })
            this.switchRow('启用', '', this.miEnabled,
              (v: boolean): void => { this.miEnabled = v; })
            this.formField('优先级', this.miPriorityText,
              (v: string): void => { this.miPriorityText = v; })
            this.positionSelector(this.miPosition, this.miPosMenuOpen,
              (): void => { this.miPosMenuOpen = !this.miPosMenuOpen; },
              (v: InjectionPosition): void => {
                this.miPosition = v;
                this.miPosMenuOpen = false;
              })
            if (this.miPosition === 'at_depth') {
              this.formField('注入深度', this.miInjectDepthText,
                (v: string): void => { this.miInjectDepthText = v; })
            }
            this.roleSelector(this.miRole, this.miRoleMenuOpen,
              (): void => { this.miRoleMenuOpen = !this.miRoleMenuOpen; },
              (v: InjectionRole): void => {
                this.miRole = v;
                this.miRoleMenuOpen = false;
              })
            Column() {
              Text('注入内容').fontSize(12).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(12)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3).margin({ bottom: 4 })
              TextArea({ text: this.miContent })
                .width('100%').height(160).fontSize(13).fontColor(INK)
                .backgroundColor(BG).borderRadius(8)
                .onChange((v: string): void => { this.miContent = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
        Row() {
          Blank()
          Text('取消')
            .fontSize(14).fontColor(INK3)
            .constraintSize({ minHeight: 44 })
            .textAlign(TextAlign.Center)
            .padding({ left: 20, right: 20 })
            .onClick((): void => { this.miVisible = false; })
          Text('确定')
            .fontSize(14).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 })
            .textAlign(TextAlign.Center)
            .padding({ left: 20, right: 20 })
            .onClick((): void => { this.confirmModeInjection(); })
        }
        .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
      }
      .width('100%').height('90%').padding(16)
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
  }

  // ===== overlay:世界书编辑(:793-923) =====

  @Builder
  lorebookEditor() {
    Column() {
      Blank().onClick((): void => { this.lbVisible = false; })
      Column() {
        Text('编辑世界书')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16)).fontFamily(TEXT_STYLES.body.family).fontColor(INK)
          .margin({ bottom: 12 })
        Scroll() {
          Column() {
            this.formField('名称', this.lbName,
              (v: string): void => { this.lbName = v; })
            this.formField('描述', this.lbDesc,
              (v: string): void => { this.lbDesc = v; })
            this.switchRow('启用', '', this.lbEnabled,
              (v: boolean): void => { this.lbEnabled = v; })

            Row() {
              Text(`条目（${this.lbEntries.length}）`)
                .fontSize(13).fontColor(INK).layoutWeight(1)
              Text('＋ 添加条目')
                .fontSize(12).fontColor(ACCENT)
                .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                .onClick((): void => { this.openEntryEditor(null); })
            }
            .width('100%').margin({ bottom: 8 })

            ForEach(this.lbEntries, (e: RegexInjection) => {
              Column() {
                Row() {
                  Column() {
                    Text(e.name.length > 0 ? e.name : '未命名条目')
                      .fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13))
                      .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
                    if (e.keywords.length > 0) {
                      Text(`关键词：${e.keywords.join(', ')}`)
                        .fontSize(11).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(11)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3).margin({ top: 2 })
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    }
                    if (!e.enabled) {
                      Row() {
                        this.tagText('已禁用', true)
                      }
                      .margin({ top: 4 })
                    }
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 8 })
                  Text('编辑')
                    .fontSize(12).fontColor(ACCENT)
                    .padding({ left: 6, right: 6, top: 4, bottom: 4 })
                    .onClick((): void => { this.openEntryEditor(e); })
                  Text('删除')
                    .fontSize(12).fontColor(ERROR)
                    .padding({ left: 6, right: 6, top: 4, bottom: 4 })
                    .onClick((): void => { this.deleteEntry(e); })
                }
                .width('100%').padding(12)
              }
              .width('100%').backgroundColor(BG).borderRadius(10)
              .border({ width: 1, color: LINE }).margin({ bottom: 8 })
            }, (e: RegexInjection): string => e.id)
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
        Row() {
          Blank()
          Text('取消')
            .fontSize(14).fontColor(INK3)
            .constraintSize({ minHeight: 44 })
            .textAlign(TextAlign.Center)
            .padding({ left: 20, right: 20 })
            .onClick((): void => { this.lbVisible = false; })
          Text('确定')
            .fontSize(14).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 })
            .textAlign(TextAlign.Center)
            .padding({ left: 20, right: 20 })
            .onClick((): void => { this.confirmLorebook(); })
        }
        .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
      }
      .width('100%').height('95%').padding(16)
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
  }

  // ===== overlay:条目编辑(:949-1176;canSave = keywords 非空 || constantActive) =====

  @Builder
  entryEditor() {
    Column() {
      Blank().onClick((): void => { this.enVisible = false; })
      Column() {
        Text('编辑条目')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16)).fontFamily(TEXT_STYLES.body.family).fontColor(INK)
          .margin({ bottom: 12 })
        Scroll() {
          Column() {
            this.formField('名称', this.enName,
              (v: string): void => { this.enName = v; })
            this.switchRow('启用', '', this.enEnabled,
              (v: boolean): void => { this.enEnabled = v; })
            this.formField('优先级', this.enPriorityText,
              (v: string): void => { this.enPriorityText = v; })
            this.positionSelector(this.enPosition, this.enPosMenuOpen,
              (): void => { this.enPosMenuOpen = !this.enPosMenuOpen; },
              (v: InjectionPosition): void => {
                this.enPosition = v;
                this.enPosMenuOpen = false;
              })
            if (this.enPosition === 'at_depth') {
              this.formField('注入深度', this.enInjectDepthText,
                (v: string): void => { this.enInjectDepthText = v; })
            }

            Text('关键词').fontSize(13).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(13)).fontFamily(TEXT_STYLES.secondary.family).fontColor(INK)
            if (this.enKeywords.length > 0) {
              Flex({ wrap: FlexWrap.Wrap }) {
                ForEach(this.enKeywords, (kw: string) => {
                  Row() {
                    Text(kw).fontSize(11).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(11)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK)
                    Text('✕').fontSize(10).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(10)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3).margin({ left: 6 })
                  }
                  .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .backgroundColor(BG).borderRadius(6)
                  .border({ width: 1, color: LINE })
                  .margin({ right: 4, top: 4 })
                  .onClick((): void => {
                    this.enKeywords = this.enKeywords.filter(
                      (k: string): boolean => k !== kw);
                  })
                }, (kw: string): string => kw)
              }
              .width('100%').margin({ top: 4 })
            }
            Row() {
              TextInput({ text: this.enNewKeyword, placeholder: '新关键词' })
                .layoutWeight(1).fontSize(13).fontColor(INK)
                .backgroundColor(BG).borderRadius(8)
                .onChange((v: string): void => { this.enNewKeyword = v; })
              Text('添加')
                .fontSize(12).fontColor(ACCENT)
                .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                .onClick((): void => { this.addKeyword(); })
            }
            .width('100%').margin({ top: 8, bottom: 12 })

            this.switchRow('使用正则', '', this.enUseRegex,
              (v: boolean): void => { this.enUseRegex = v; })
            this.switchRow('大小写敏感', '', this.enCaseSensitive,
              (v: boolean): void => { this.enCaseSensitive = v; })
            this.switchRow('常驻激活', '无需匹配关键词即触发', this.enConstantActive,
              (v: boolean): void => { this.enConstantActive = v; })
            this.formField('扫描深度 (消息数)', this.enScanDepthText,
              (v: string): void => { this.enScanDepthText = v; })
            this.roleSelector(this.enRole, this.enRoleMenuOpen,
              (): void => { this.enRoleMenuOpen = !this.enRoleMenuOpen; },
              (v: InjectionRole): void => {
                this.enRole = v;
                this.enRoleMenuOpen = false;
              })
            Column() {
              Text('注入内容').fontSize(12).fontWeight(FontWeight.Normal).lineHeight(lineHeightForSize(12)).fontFamily(TEXT_STYLES.meta.family).fontColor(INK3).margin({ bottom: 4 })
              TextArea({ text: this.enContent })
                .width('100%').height(130).fontSize(13).fontColor(INK)
                .backgroundColor(BG).borderRadius(8)
                .onChange((v: string): void => { this.enContent = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
        Row() {
          Blank()
          Text('取消')
            .fontSize(14).fontColor(INK3)
            .constraintSize({ minHeight: 44 })
            .textAlign(TextAlign.Center)
            .padding({ left: 20, right: 20 })
            .onClick((): void => { this.enVisible = false; })
          Text('确定')
            .fontSize(14)
            .fontColor(this.enKeywords.length > 0 || this.enConstantActive ? ACCENT : LINE)
            .constraintSize({ minHeight: 44 })
            .textAlign(TextAlign.Center)
            .padding({ left: 20, right: 20 })
            .onClick((): void => {
              // canSave(:1161)= keywords 非空 || constantActive
              if (this.enKeywords.length > 0 || this.enConstantActive) this.confirmEntry();
            })
        }
        .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
      }
      .width('100%').height('92%').padding(16)
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
  }

  build(): void {
    Stack() {
      Column() {
        CenteredTitleBar({
          title: '提示词',
          actionIcon: 'plus',
          onAction: (): void => {
            // 添加动作跟随当前分段(原底部 floating toolbar 适配)
            if (this.tab === 0) this.openModeInjectionEditor(null);
            else this.openLorebookEditor(null);
          },
        })

        // 双 tab:底部二 tab 行 → Seg 分段控件(iOS 化,沿用组件)
        Row() {
          Seg({ options: ['模式注入', '世界书'], selected: this.tab,
            onSelect: (idx: number): void => { this.tab = idx; } })
        }
        .width('100%')
        .padding({ left: GUTTER, right: GUTTER, top: 4, bottom: 4 })

        if (!this.loaded) {
          Text('加载中…')
            .fontSize(TEXT_STYLES.body.size).fontWeight(TEXT_STYLES.body.weight)
            .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK3)
            .width('100%').textAlign(TextAlign.Center).padding({ top: 40 })
        } else {
          Scroll() {
            Column() {
              if (this.tab === 0) {
                PlainSection({ label: '模式注入' })
                if (this.modeInjections.length === 0) {
                  this.EmptyHint('暂无模式注入')
                } else {
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModeInjectionList(); } })
                    .margin({ left: GUTTER, right: GUTTER })
                  FooterNote({ text: '按优先级注入;点按行编辑,行尾按钮调整顺序或删除' })
                }
              } else {
                PlainSection({ label: '世界书' })
                if (this.lorebooks.length === 0) {
                  this.EmptyHint('暂无世界书')
                } else {
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.LorebookList(); } })
                    .margin({ left: GUTTER, right: GUTTER })
                  FooterNote({ text: '点按行编辑;条目在世界书编辑页内管理' })
                }
              }

              PlainSection({ label: '导入导出' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.ImportExportCard(); } })
                .margin({ left: GUTTER, right: GUTTER })
              FooterNote({ text: '导出文件可重新导入,追加合并、同名跳过' })
              PageSpacer()
            }
            .width('100%')
            .padding({ bottom: 20 })
          }
          .align(Alignment.TopStart)
          .width('100%').layoutWeight(1)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)

      if (this.miVisible) {
        this.modeInjectionEditor()
      }
      if (this.lbVisible) {
        this.lorebookEditor()
      }
      if (this.enVisible) {
        this.entryEditor()
      }
    }
    .width('100%').height('100%')
  }
}
