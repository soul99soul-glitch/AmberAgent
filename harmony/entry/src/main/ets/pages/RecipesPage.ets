import { KeyboardAvoidMode } from '@kit.ArkUI';
import type { AgentTool, JsonObject, InstalledRecipe, RecipeDescriptor, RecipeImportPreview,
  RecipeInputType, RecipeStore } from '@amber/chat-domain';
import { getRecipeStore, getRecipePrimitives, startRecipeFromPage } from '../di/AppContainer.ets';
import { CenteredTitleBar, CardGroup } from '../components/PageTemplate.ets';
import { BG, SURFACE, SURFACE2, INK, INK2, INK3, LINE, ERROR, ACCENT, GUTTER, MOTION_OVERLAY } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

class RecipeColors {
  background: string = BG;
  surface: string = SURFACE;
  inset: string = SURFACE2;
  ink: string = INK;
  secondary: string = INK2;
  muted: string = INK3;
  line: string = LINE;
  error: string = ERROR;
}

class RecipeInputField {
  name: string;
  type: RecipeInputType;
  text: string;
  checked: boolean;

  constructor(name: string, type: RecipeInputType, text: string = '', checked: boolean = false) {
    this.name = name; this.type = type; this.text = text; this.checked = checked;
  }
}

@Entry
@Component
struct RecipesPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State installed: InstalledRecipe[] = [];
  @State primitives: AgentTool[] = [];
  @State workspacePath: string = '';
  @State preview: RecipeImportPreview | null = null;
  @State detail: RecipeDescriptor | null = null;
  @State detailEnabled: boolean = false;
  @State inputFields: RecipeInputField[] = [];
  @State loaded: boolean = false;
  @State busy: string = '';
  @State error: string = '';
  @State notice: string = '';
  @State colors: RecipeColors = new RecipeColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('appBackgrounded') backgrounded: boolean = false;
  private store: RecipeStore | null = null;
  private alive: boolean = false;
  private navigationVersion: number = 0;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.enableKeyboardAvoidance();
    this.load();
  }

  onPageShow(): void { this.enableKeyboardAvoidance(); }

  onPageHide(): void {
    // 回到本页也不恢复离开前提交的导航权限。
    this.navigationVersion++;
    this.restoreKeyboardAvoidance();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.onPageHide();
  }

  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }

  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  private refreshColors(): void { this.colors = new RecipeColors(); }

  private message(error: Error): string {
    if (error.message === 'stale_candidate') return 'Workspace 清单已更改，请重新预览后安装';
    if (error.message === 'stale_base') return '已安装版本已更改，请刷新后重新打开详情或安装预览';
    return error.message;
  }

  private async load(): Promise<void> {
    if (this.busy.length > 0) return;
    this.busy = '正在加载…';
    this.error = '';
    try {
      const store: RecipeStore = await getRecipeStore();
      const installed: InstalledRecipe[] = await store.listInstalled();
      const primitives: AgentTool[] = await getRecipePrimitives();
      if (!this.alive) return;
      this.store = store;
      this.installed = installed;
      this.primitives = primitives;
      if (this.preview === null && this.detail !== null) {
        const detail: RecipeDescriptor = this.detail;
        const selected: InstalledRecipe | undefined = installed.find((item: InstalledRecipe): boolean =>
          item.descriptor.manifest.name === detail.manifest.name && item.descriptor.hash === detail.hash);
        if (selected === undefined) {
          this.detail = null; this.inputFields = [];
          this.notice = '所选版本已更改或删除，请重新打开详情';
        } else this.detailEnabled = selected.enabled;
      }
      this.loaded = true;
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? this.message(error) : String(error);
    } finally { if (this.alive) this.busy = ''; }
  }

  private missingTools(descriptor: RecipeDescriptor): string[] {
    const available: string[] = this.primitives.map((tool: AgentTool): string => tool.name);
    const missing: string[] = [];
    for (const step of descriptor.manifest.steps) {
      if (available.indexOf(step.tool) < 0 && missing.indexOf(step.tool) < 0) missing.push(step.tool);
    }
    return missing;
  }

  private selectRecipe(recipe: InstalledRecipe): void {
    if (this.busy.length > 0) return;
    this.preview = null;
    this.detail = recipe.descriptor;
    this.detailEnabled = recipe.enabled;
    this.inputFields = Object.keys(recipe.descriptor.manifest.inputs).map((name: string): RecipeInputField =>
      new RecipeInputField(name, recipe.descriptor.manifest.inputs[name]));
    this.error = ''; this.notice = '';
  }

  private closeDetail(): void {
    if (this.busy.length > 0) return;
    this.preview = null; this.detail = null;
    // 输入在下次选择时重建，退出过渡保留当前编辑内容。
    this.error = ''; this.notice = '';
  }

  private pathChanged(path: string): void {
    this.workspacePath = path;
    if (this.preview !== null) { this.preview = null; this.detail = null; }
    this.error = ''; this.notice = '';
  }

  private async prepareImport(): Promise<void> {
    const store: RecipeStore | null = this.store;
    const path: string = this.workspacePath.trim();
    if (store === null || this.busy.length > 0 || this.backgrounded) return;
    if (path.length === 0) { this.error = '请输入 Workspace 中 recipe.json 的路径'; return; }
    this.busy = '正在预览…';
    this.error = ''; this.notice = '';
    try {
      const primitives: AgentTool[] = await getRecipePrimitives();
      const preview: RecipeImportPreview = await store.prepareImport(path, primitives);
      if (!this.alive) return;
      this.primitives = primitives;
      this.preview = preview;
      this.detail = preview.candidate;
      this.inputFields = [];
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? this.message(error) : String(error);
    } finally { if (this.alive) this.busy = ''; }
  }

  private async applyImport(): Promise<void> {
    const store: RecipeStore | null = this.store;
    const preview: RecipeImportPreview | null = this.preview;
    if (store === null || preview === null || this.busy.length > 0 || this.backgrounded) return;
    this.busy = '正在安装…'; this.error = ''; this.notice = '';
    try {
      const primitives: AgentTool[] = await getRecipePrimitives();
      const descriptor: RecipeDescriptor = await store.applyImport(preview, primitives);
      const installed: InstalledRecipe[] = await store.listInstalled();
      if (!this.alive) return;
      this.primitives = primitives; this.installed = installed; this.preview = null;
      const recipe: InstalledRecipe | undefined = installed.find((item: InstalledRecipe): boolean =>
        item.descriptor.manifest.name === descriptor.manifest.name && item.descriptor.hash === descriptor.hash);
      this.detail = null;
      if (recipe !== undefined) {
        this.detail = recipe.descriptor; this.detailEnabled = recipe.enabled;
        this.inputFields = Object.keys(recipe.descriptor.manifest.inputs).map((name: string): RecipeInputField =>
          new RecipeInputField(name, recipe.descriptor.manifest.inputs[name]));
      }
      this.notice = '安装成功，下一轮对话工具目录会使用该版本';
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? this.message(error) : String(error);
    } finally { if (this.alive) this.busy = ''; }
  }

  private async setEnabled(): Promise<void> {
    const store: RecipeStore | null = this.store;
    const detail: RecipeDescriptor | null = this.detail;
    const enabled: boolean = !this.detailEnabled;
    if (store === null || detail === null || this.preview !== null || this.busy.length > 0 || this.backgrounded) return;
    this.busy = enabled ? '正在启用…' : '正在停用…'; this.error = ''; this.notice = '';
    try {
      await store.setEnabled(detail.manifest.name, detail.hash, enabled);
      const installed: InstalledRecipe[] = await store.listInstalled();
      if (!this.alive) return;
      this.installed = installed; this.detailEnabled = enabled;
      this.notice = enabled ? '已启用，下一轮对话可用' : '已停用，下一轮对话不再提供';
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? this.message(error) : String(error);
    } finally { if (this.alive) this.busy = ''; }
  }

  private confirmRemove(): void {
    const detail: RecipeDescriptor | null = this.detail;
    if (detail === null || this.preview !== null || this.busy.length > 0 || this.backgrounded) return;
    AlertDialog.show({
      title: '删除 Recipe', message: `删除「${detail.manifest.name}」${detail.manifest.version}？`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '删除', fontColor: this.colors.error,
        action: (): void => { this.removeRecipe(detail); } },
    });
  }

  private async removeRecipe(detail: RecipeDescriptor): Promise<void> {
    const store: RecipeStore | null = this.store;
    if (store === null || this.busy.length > 0 || this.backgrounded) return;
    this.busy = '正在删除…'; this.error = ''; this.notice = '';
    try {
      await store.remove(detail.manifest.name, detail.hash);
      const installed: InstalledRecipe[] = await store.listInstalled();
      if (!this.alive) return;
      this.installed = installed; this.detail = null; this.inputFields = [];
      this.notice = '已删除，下一轮对话不再提供';
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? this.message(error) : String(error);
    } finally { if (this.alive) this.busy = ''; }
  }

  private changeInput(name: string, text: string, checked: boolean): void {
    this.inputFields = this.inputFields.map((field: RecipeInputField): RecipeInputField =>
      field.name === name ? new RecipeInputField(field.name, field.type, text, checked) : field);
    this.error = '';
  }

  private inputText(name: string): string {
    return this.inputFields.find((field: RecipeInputField): boolean => field.name === name)?.text ?? '';
  }

  private inputChecked(name: string): boolean {
    return this.inputFields.find((field: RecipeInputField): boolean => field.name === name)?.checked ?? false;
  }

  private inputValues(): JsonObject {
    const inputs: JsonObject = {};
    for (const field of this.inputFields) {
      if (field.type === 'boolean') inputs[field.name] = field.checked;
      else if (field.type === 'number') {
        if (field.text.trim().length === 0 || !Number.isFinite(Number(field.text))) {
          throw new Error(`「${field.name}」须填写有限数值`);
        }
        inputs[field.name] = Number(field.text);
      } else inputs[field.name] = field.text;
    }
    return inputs;
  }

  private async runRecipe(): Promise<void> {
    const detail: RecipeDescriptor | null = this.detail;
    if (detail === null || this.preview !== null || !this.detailEnabled || this.busy.length > 0 || this.backgrounded) return;
    const navigationVersion: number = this.navigationVersion;
    this.busy = '正在提交到对话…'; this.error = ''; this.notice = '';
    try {
      const inputs: JsonObject = this.inputValues();
      await startRecipeFromPage(detail.manifest.name, detail.hash, inputs,
        (): boolean => this.alive && navigationVersion === this.navigationVersion);
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? this.message(error) : String(error);
    } finally { if (this.alive) this.busy = ''; }
  }

  @Builder
  private InstallCard(): void {
    Column({ space: 8 }) {
      Text('从 Workspace 安装').fontSize(15).fontColor(this.colors.ink).width('100%')
      Text('填写 recipe.json 的 Workspace 相对路径，先核对完整预览，再确认安装或更新。')
        .fontSize(12).fontColor(this.colors.secondary).width('100%')
      TextInput({ text: this.workspacePath, placeholder: '例如 recipes/format_json/recipe.json' })
        .height(52).fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.ink)
        .placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.pathChanged(value); })
      Button('预览安装').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
        .fontColor(this.accentColor).backgroundColor(this.colors.inset)
        .enabled(this.loaded && this.busy.length === 0 && !this.backgrounded)
        .onClick((): void => { this.prepareImport(); })
    }.width('100%').padding(14)
  }

  @Builder
  private InstalledCard(): void {
    Column() {
      if (this.installed.length === 0) {
        Text(this.loaded ? '暂无已安装 Recipe' : this.error.length > 0 ? '读取失败，请刷新重试' : '正在读取已安装 Recipe…')
          .fontSize(14).fontColor(this.colors.secondary).width('100%').padding(14)
      } else {
        ForEach(this.installed, (recipe: InstalledRecipe): void => {
          Column({ space: 5 }) {
            Row({ space: 8 }) {
              Text(recipe.descriptor.manifest.name).fontSize(15).fontColor(this.colors.ink)
                .layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(recipe.enabled ? '已启用' : '已停用').fontSize(12).fontColor(this.colors.secondary)
            }.width('100%')
            Text(`${recipe.descriptor.manifest.version} · ${recipe.descriptor.manifest.steps.length} 个步骤`)
              .fontSize(12).fontColor(this.colors.secondary).width('100%')
            Text(recipe.descriptor.manifest.description).fontSize(12).fontColor(this.colors.secondary)
              .width('100%').maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (this.missingTools(recipe.descriptor).length > 0) {
              Text(`当前缺失工具：${this.missingTools(recipe.descriptor).join('、')}`)
                .fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
            }
          }.width('100%').constraintSize({ minHeight: 52 }).padding(14)
            .border({ width: { bottom: 0.5 }, color: this.colors.line })
            .enabled(this.busy.length === 0)
            .onClick((): void => { this.selectRecipe(recipe); })
        }, (recipe: InstalledRecipe): string => `${recipe.descriptor.manifest.name}:${recipe.descriptor.hash}:${recipe.enabled}`)
      }
    }.width('100%')
  }

  @Builder
  private ManifestCard(detail: RecipeDescriptor | null): void {
    Column({ space: 8 }) {
      if (detail !== null) {
        Row({ space: 8 }) {
          Text(detail.manifest.name).fontSize(16).fontColor(this.colors.ink).layoutWeight(1)
            .wordBreak(WordBreak.BREAK_ALL)
          Button('关闭').type(ButtonType.Normal).height(44).constraintSize({ minWidth: 56 }).fontSize(13)
            .fontColor(this.colors.secondary).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
            .onClick((): void => { this.closeDetail(); })
        }.width('100%')
        Text(`版本 ${detail.manifest.version}`).fontSize(12).fontColor(this.colors.secondary).width('100%')
          .wordBreak(WordBreak.BREAK_ALL)
        Text(detail.manifest.description).fontSize(13).fontColor(this.colors.ink).width('100%')
          .wordBreak(WordBreak.BREAK_ALL)
        Text('内容 SHA-256').fontSize(12).fontColor(this.colors.secondary).width('100%')
        Text(detail.hash).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        if (this.missingTools(detail).length > 0) {
          Text(`默认助手本地目录缺失工具：${this.missingTools(detail).join('、')}`)
            .fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
        }
        Text('完整清单（输入类型、步骤参数与输出绑定）').fontSize(12).fontColor(this.colors.secondary).width('100%')
        Text(detail.canonicalJSON).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
      }
    }.width('100%').padding(14)
  }

  @Builder
  private PreviewCard(preview: RecipeImportPreview | null): void {
    Column({ space: 8 }) {
      if (preview !== null) {
        Text(preview.baseHash === null ? '安装预览 · 新 Recipe' : '安装预览 · 更新已安装 Recipe')
          .fontSize(14).fontColor(this.colors.ink).width('100%')
        Text(`Workspace：${preview.workspacePath}`).fontSize(12).fontColor(this.colors.secondary).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        if (preview.baseHash !== null) {
          Text(`已安装基线 SHA-256：${preview.baseHash}`).fontSize(12).fontFamily(FONT_MONO)
            .fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
            .copyOption(CopyOptions.LocalDevice)
        }
        Text(`涉及工具：${preview.envelope.tools.join('、')}`).fontSize(12).fontColor(this.colors.ink)
          .width('100%').wordBreak(WordBreak.BREAK_ALL)
        Text(`风险：${preview.envelope.risk === 'high' ? '高' : preview.envelope.risk === 'sensitive' ? '敏感' : '常规'} · ${
          preview.envelope.mutates ? '会修改数据' : '无声明的数据修改'} · ${
          preview.envelope.needsApproval ? '步骤包含审批' : '仍按当前步骤权限执行'}`)
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        Text('确认只安装清单；运行时每个步骤仍使用当前助手的工具与审批。候选文件或已安装版本变化会拒绝此次安装。')
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
      }
    }.width('100%').padding(14)
  }

  @Builder
  private RunCard(detail: RecipeDescriptor | null, preview: RecipeImportPreview | null): void {
    Column({ space: 8 }) {
      if (detail !== null && preview === null) {
        Row({ space: 8 }) {
          Text(this.detailEnabled ? '已启用' : '已停用').fontSize(13).fontColor(this.colors.secondary).layoutWeight(1)
          Button(this.detailEnabled ? '停用' : '启用').type(ButtonType.Normal).height(44).fontSize(13)
            .constraintSize({ minWidth: 64 }).fontColor(this.accentColor).backgroundColor(this.colors.inset)
            .enabled(this.busy.length === 0 && !this.backgrounded)
            .onClick((): void => { this.setEnabled(); })
          Button('删除').type(ButtonType.Normal).height(44).fontSize(13).constraintSize({ minWidth: 64 })
            .fontColor(this.colors.error).backgroundColor(this.colors.inset)
            .enabled(this.busy.length === 0 && !this.backgrounded)
            .onClick((): void => { this.confirmRemove(); })
        }.width('100%')
        Text('运行输入').fontSize(15).fontColor(this.colors.ink).width('100%')
        if (this.inputFields.length === 0) {
          Text('此 Recipe 无需输入').fontSize(12).fontColor(this.colors.secondary).width('100%')
        }
        ForEach(this.inputFields, (field: RecipeInputField): void => {
          Column({ space: 5 }) {
            Text(`${field.name} · ${field.type}`).fontSize(13).fontColor(this.colors.ink).width('100%')
            if (field.type === 'boolean') {
              Row() {
                Text(this.inputChecked(field.name) ? 'true' : 'false').fontSize(13).fontFamily(FONT_MONO)
                  .fontColor(this.colors.secondary).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: this.inputChecked(field.name) }).selectedColor(this.accentColor)
                  .enabled(this.busy.length === 0)
                  .onChange((value: boolean): void => { this.changeInput(field.name, this.inputText(field.name), value); })
              }.width('100%').height(44)
            } else if (field.type === 'number') {
              TextInput({ text: this.inputText(field.name), placeholder: '有限数值，例如 -1.5' }).height(52).fontSize(13)
                .fontColor(this.colors.ink).placeholderColor(this.colors.muted).backgroundColor(this.colors.inset)
                .enabled(this.busy.length === 0)
                .onChange((value: string): void => { this.changeInput(field.name, value, this.inputChecked(field.name)); })
            } else {
              TextArea({ text: this.inputText(field.name), placeholder: '字符串' }).height(88).fontSize(13).fontColor(this.colors.ink)
                .placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
                .onChange((value: string): void => { this.changeInput(field.name, value, this.inputChecked(field.name)); })
            }
          }.width('100%')
        }, (field: RecipeInputField): string => `${detail === null ? '' : detail.hash}:${field.name}`)
        Text('使用当前默认助手的本地工具配置，提交固定名称、内容 hash 与输入到对话。逐步审批、进度和最终结果都在对话中查看。')
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        Button('在对话中运行').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset)
          .enabled(this.detailEnabled && this.missingTools(detail).length === 0 &&
            this.busy.length === 0 && !this.backgrounded)
          .onClick((): void => { this.runRecipe(); })
      }
    }.width('100%').padding(14)
  }

  // Builder 参数保留当前详情，关闭时 nullable 状态清空不会影响退出帧。
  @Builder
  private DetailPanel(detail: RecipeDescriptor, preview: RecipeImportPreview | null): void {
    Column({ space: 12 }) {
      if (preview !== null) {
        CardGroup({ content: (): void => { this.PreviewCard(preview); } })
      }
      CardGroup({ content: (): void => { this.ManifestCard(detail); } })
      if (preview !== null) {
        Button(preview.baseHash === null ? '确认安装' : '确认更新').type(ButtonType.Normal).height(44)
          .width('100%').fontSize(14).fontColor(this.accentColor).backgroundColor(this.colors.surface)
          .enabled(this.busy.length === 0 && !this.backgrounded)
          .onClick((): void => { this.applyImport(); })
      } else {
        CardGroup({ content: (): void => { this.RunCard(detail, preview); } })
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: 'Recipes', actionText: '刷新', onAction: (): void => { this.load(); } })
      Scroll() {
        Column({ space: 12 }) {
          Text('Recipe 用声明式步骤串联当前默认助手的本地工具。安装不会执行步骤，运行沿用对话中的权限和审批。')
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
          CardGroup({ content: (): void => { this.InstallCard(); } })
          if (this.busy.length > 0) {
            Text(this.busy).fontSize(12).fontColor(this.colors.secondary).width('100%')
          }
          if (this.error.length > 0) {
            Text(this.error).fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }
          if (this.notice.length > 0) {
            Text(this.notice).fontSize(12).fontColor(this.colors.secondary).width('100%')
          }
          if (this.detail !== null) {
            this.DetailPanel(this.detail, this.preview)
          } else {
            Column({ space: 12 }) {
              Text('已安装').fontSize(14).fontColor(this.colors.secondary).width('100%')
              CardGroup({ content: (): void => { this.InstalledCard(); } })
            }.width('100%').alignItems(HorizontalAlign.Start)
              .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
          }
        }.width('100%').padding({ left: GUTTER, right: GUTTER, top: 12, bottom: 16 + this.navBarHeight })
          .justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
