import { KeyboardAvoidMode } from '@kit.ArkUI';
import { getPluginSDK, normalizePluginInputs, PluginValidationError } from '@amber/chat-domain';
import type { AgentTool, InstalledPlugin, JsonObject, JsonValue, PluginDescriptor, PluginImportPreview,
  PluginIssue, PluginPackage, PluginReceipt, PluginSource, PluginStore, PluginTrustedKey } from '@amber/chat-domain';
import { getPluginStore, getPluginPrimitives, startPluginFromPage, exportPluginFromPage } from '../di/AppContainer.ets';
import { CenteredTitleBar, CardGroup } from '../components/PageTemplate.ets';
import { PluginJSONEditor } from '../components/PluginJSONEditor.ets';
import { pluginPublicKeyFingerprint } from '../platform_impl/PluginArchive.ets';
import { pluginCapabilityRows, pluginRuntimeLabel, pluginStatusLabel, pluginTrustLabel } from '../components/PluginPageProjection.ets';
import { BG, SURFACE, SURFACE2, INK, INK2, INK3, LINE, ERROR, ACCENT, GUTTER, MOTION_OVERLAY } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

class PluginPageColors {
  background: string = BG;
  surface: string = SURFACE;
  inset: string = SURFACE2;
  ink: string = INK;
  secondary: string = INK2;
  muted: string = INK3;
  line: string = LINE;
  error: string = ERROR;
}

type PluginManagementAction = 'enable' | 'disable' | 'remove' | 'rollback' | 'restore';

@Entry
@Component
struct PluginsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State installed: InstalledPlugin[] = [];
  @State primitives: AgentTool[] = [];
  @State trustedKeys: PluginTrustedKey[] = [];
  @State workspacePath: string = '';
  @State sourceKind: string = 'directory';
  @State enableImport: boolean = false;
  @State preview: PluginImportPreview | null = null;
  @State detail: InstalledPlugin | null = null;
  @State selectedToolId: string = '';
  @State inputsText: string = '{}';
  @State expectedText: string = 'null';
  @State expectedProvided: boolean = false;
  @State exportPath: string = '';
  @State keyBase64: string = '';
  @State keyLabel: string = '';
  @State showToolChoices: boolean = false;
  @State showManifest: boolean = false;
  @State showFiles: boolean = false;
  @State showSource: boolean = false;
  @State sourceText: string = '';
  @State showDiagnostics: boolean = false;
  @State showKeys: boolean = false;
  @State showSDK: boolean = false;
  @State sdkJSON: string = '';
  @State receiptJSON: string = '';
  @State loaded: boolean = false;
  @State busy: string = '';
  @State error: string = '';
  @State inputIssue: string = '';
  @State expectedIssue: string = '';
  @State notice: string = '';
  @State colors: PluginPageColors = new PluginPageColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('appBackgrounded') backgrounded: boolean = false;
  private store: PluginStore | null = null;
  private alive: boolean = false;
  private navigationVersion: number = 0;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.enableKeyboardAvoidance();
    this.load();
  }

  onPageShow(): void {
    this.enableKeyboardAvoidance();
    if (this.loaded) this.load();
  }

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

  private refreshColors(): void { this.colors = new PluginPageColors(); }
  private available(): boolean { return this.loaded && this.busy.length === 0 && !this.backgrounded; }

  private errorMessage(error: Error): string {
    if (error.message === 'stale_candidate') return '候选包已更改，请重新预览后测试或导入';
    if (error.message === 'stale_base') return '已安装版本已更改，请刷新后重新打开';
    if (error.message === 'no_previous') return '没有可回退的上一版本';
    if (error.message === 'plugin_manual_tool_unavailable') return '当前助手无法使用此插件工具，请检查助手的工具配置';
    return error.message;
  }

  private failure(error: Error | string): void {
    if (this.alive) this.error = error instanceof Error ? this.errorMessage(error) : error;
  }

  private async load(): Promise<void> {
    if (this.busy.length > 0) return;
    this.busy = '正在读取插件中心…'; this.error = '';
    try {
      const primitives: AgentTool[] = await getPluginPrimitives();
      const store: PluginStore = await getPluginStore();
      const installed: InstalledPlugin[] = await store.listInstalled(primitives);
      const keys: PluginTrustedKey[] = await store.listTrustedKeys();
      if (!this.alive) return;
      this.store = store; this.primitives = primitives; this.installed = installed; this.trustedKeys = keys;
      if (this.detail !== null) {
        const detail: InstalledPlugin = this.detail;
        const updated: InstalledPlugin | undefined = installed.find((item: InstalledPlugin): boolean =>
          item.id === detail.id && item.currentHash === detail.currentHash);
        if (updated === undefined) {
          this.closeSelection(); this.notice = '所选版本已更改或删除，请重新打开详情';
        } else this.detail = updated;
      }
      this.sdkJSON = JSON.stringify(getPluginSDK(), null, 2); this.loaded = true;
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
    finally { if (this.alive) this.busy = ''; }
  }

  private selectedPackage(preview: PluginImportPreview | null = this.preview,
    detail: InstalledPlugin | null = this.detail): PluginPackage | null {
    return preview?.candidate ?? detail?.package ?? null;
  }
  private selectedTool(): PluginDescriptor | null {
    const candidate: PluginPackage | null = this.selectedPackage();
    return candidate?.tools.find((tool: PluginDescriptor): boolean => tool.toolId === this.selectedToolId) ?? null;
  }

  private missingTools(tool: PluginDescriptor): string[] {
    const names: string[] = this.primitives.map((primitive: AgentTool): string => primitive.name);
    return tool.primitiveTools.filter((name: string): boolean => names.indexOf(name) < 0);
  }

  private selectTool(tool: PluginDescriptor): void {
    this.selectedToolId = tool.toolId; this.inputsText = '{}'; this.expectedProvided = false;
    this.expectedText = 'null'; this.showToolChoices = false; this.inputIssue = ''; this.expectedIssue = ''; this.error = '';
    this.showSource = false;
    if (tool.implementation.kind === 'javascript' || tool.implementation.kind === 'command') this.sourceText = tool.implementation.source;
    else if (tool.implementation.kind === 'recipe') this.sourceText = JSON.stringify(tool.implementation.manifest, null, 2);
    else this.sourceText = JSON.stringify(tool.implementation.remote, null, 2);
  }

  private selectPlugin(plugin: InstalledPlugin): void {
    if (!this.available()) return;
    this.preview = null; this.detail = plugin; this.receiptJSON = ''; this.error = ''; this.notice = '';
    this.showManifest = false; this.showFiles = false; this.showDiagnostics = false;
    this.selectedToolId = ''; this.inputsText = '{}'; this.inputIssue = ''; this.expectedIssue = '';
    if (plugin.package !== null && plugin.package.tools.length > 0) this.selectTool(plugin.package.tools[0]);
  }

  private closeSelection(): void {
    this.preview = null; this.detail = null;
    // 工具选择和输入在下次打开时初始化，退出期间保留面板内容。
  }

  private sourceChanged(path: string, kind: string): void {
    this.workspacePath = path; this.sourceKind = kind;
    if (this.preview !== null) this.closeSelection();
    this.error = ''; this.notice = '';
  }

  private async prepareImport(): Promise<void> {
    const store: PluginStore | null = this.store;
    if (store === null || !this.available()) return;
    const path: string = this.workspacePath.trim();
    if (path.length === 0) { this.error = '请输入 Workspace 中的插件目录或归档文件路径'; return; }
    const source: PluginSource = { kind: this.sourceKind === 'archive' ? 'archive' : 'directory', workspacePath: path };
    const enable: boolean = this.enableImport;
    this.busy = '正在验证并生成固定预览…'; this.error = ''; this.notice = '';
    try {
      const primitives: AgentTool[] = await getPluginPrimitives();
      const preview: PluginImportPreview = await store.prepareImport(source, primitives, enable);
      if (!this.alive) return;
      this.primitives = primitives; this.preview = preview; this.detail = null; this.receiptJSON = '';
      this.showManifest = false; this.showFiles = false; this.selectedToolId = '';
      if (preview.candidate.tools.length > 0) this.selectTool(preview.candidate.tools[0]);
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
    finally { if (this.alive) this.busy = ''; }
  }

  private sourceInput(preview: PluginImportPreview): JsonObject {
    const input: JsonObject = { expected_candidate_hash: preview.candidate.hash };
    input[preview.source.kind === 'archive' ? 'archive_path' : 'workspace_directory'] = preview.source.workspacePath;
    return input;
  }

  private inputValues(tool: PluginDescriptor): JsonObject {
    let parsed: JsonValue;
    try { parsed = JSON.parse(this.inputsText) as JsonValue; }
    catch (error) { this.inputIssue = '输入必须是有效 JSON object'; throw new Error(this.inputIssue); }
    try { this.inputIssue = ''; return normalizePluginInputs(tool, parsed); }
    catch (error) {
      if (error instanceof PluginValidationError) this.inputIssue = error.issues.map((issue: PluginIssue): string => `${issue.path}：${issue.message}`).join('\n');
      throw error as Error;
    }
  }

  private expectedValue(): JsonValue {
    try { this.expectedIssue = ''; return JSON.parse(this.expectedText) as JsonValue; }
    catch (error) { this.expectedIssue = '预期结果必须是有效 JSON；null 是一个明确的预期值'; throw new Error(this.expectedIssue); }
  }

  private async launch(kind: string): Promise<void> {
    const tool: PluginDescriptor | null = this.selectedTool();
    const preview: PluginImportPreview | null = this.preview;
    const detail: InstalledPlugin | null = this.detail;
    if (!this.available()) return;
    const navigationVersion: number = this.navigationVersion;
    const shouldNavigate: () => boolean = (): boolean => this.alive && navigationVersion === this.navigationVersion;
    this.busy = '正在保存到对话…'; this.error = ''; this.notice = '';
    try {
      if (kind === 'import' && preview !== null) {
        const input: JsonObject = this.sourceInput(preview); input['enable'] = preview.enable;
        input['expected_base_hash'] = preview.baseHash;
        await startPluginFromPage('plugin_import', input, `导入 ${preview.candidate.manifest.name}`, shouldNavigate);
      } else if (kind === 'test' && preview !== null && tool !== null) {
        const input: JsonObject = this.sourceInput(preview);
        input['tool'] = tool.name; input['inputs'] = this.inputValues(tool);
        if (this.expectedProvided) input['expected_result'] = this.expectedValue();
        await startPluginFromPage('plugin_test', input, `测试 ${preview.candidate.manifest.name} / ${tool.name}`, shouldNavigate);
      } else if (kind === 'run' && detail !== null && detail.package !== null && detail.enabled && tool !== null) {
        await startPluginFromPage(tool.toolId, this.inputValues(tool), `${detail.package.manifest.name} / ${tool.name}`,
          shouldNavigate, detail.package.hash);
      }
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
    finally { if (this.alive) this.busy = ''; }
  }

  private confirmManagement(action: PluginManagementAction): void {
    const detail: InstalledPlugin | null = this.detail;
    if (detail === null || detail.currentHash === null || !this.available()) return;
    const verb: string = action === 'enable' ? '启用' : action === 'disable' ? '停用' : action === 'remove' ? '删除' : action === 'rollback' ? '回退上一版' : '解除隔离';
    AlertDialog.show({ title: `${verb}插件`, message: `${detail.package?.manifest.name ?? detail.id}\n版本 ${detail.package?.manifest.version ?? '内容无效'}\nSHA-256 ${detail.currentHash}`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: verb, fontColor: action === 'remove' ? this.colors.error : this.accentColor,
        action: (): void => { this.manage(action, detail); } } });
  }

  private async manage(action: PluginManagementAction, detail: InstalledPlugin): Promise<void> {
    const store: PluginStore | null = this.store;
    if (store === null || detail.currentHash === null || !this.available()) return;
    const hash: string = detail.currentHash;
    this.busy = '正在更新插件状态…'; this.error = ''; this.notice = '';
    try {
      let receipt: PluginReceipt | null = null;
      if (action === 'enable' || action === 'disable') await store.setEnabled(detail.id, hash, action === 'enable');
      else if (action === 'remove') await store.remove(detail.id, hash);
      else if (action === 'rollback') receipt = await store.rollback(detail.id, hash);
      else await store.restore(detail.id, hash);
      const installed: InstalledPlugin[] = await store.listInstalled(this.primitives);
      if (!this.alive) return;
      this.installed = installed;
      this.detail = installed.find((plugin: InstalledPlugin): boolean => plugin.id === detail.id) ?? null;
      this.receiptJSON = receipt === null ? '' : JSON.stringify(receipt, null, 2);
      this.selectedToolId = '';
      if (this.detail?.package !== null && this.detail?.package !== undefined && this.detail.package.tools.length > 0) {
        this.selectTool(this.detail.package.tools[0]);
      }
      this.notice = '已完成，下一轮对话使用当前插件状态';
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
    finally { if (this.alive) this.busy = ''; }
  }

  private async exportPlugin(): Promise<void> {
    const detail: InstalledPlugin | null = this.detail;
    const path: string = this.exportPath.trim();
    if (detail?.package === null || detail === null || !this.available()) return;
    if (path.length === 0) { this.error = '请输入导出归档的 Workspace 相对路径'; return; }
    this.busy = '正在导出归档…'; this.error = ''; this.notice = '';
    try {
      await exportPluginFromPage(detail.id, detail.package.hash, path);
      if (this.alive) this.notice = `已导出到 Workspace：${path}`;
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
    finally { if (this.alive) this.busy = ''; }
  }

  private confirmAddKey(): void {
    if (!this.available()) return;
    const publicKey: string = this.keyBase64.trim();
    const label: string = this.keyLabel.trim();
    try {
      const fingerprint: string = pluginPublicKeyFingerprint(publicKey);
      AlertDialog.show({ title: '信任发布者公钥', message: `请与发布者核对完整 SHA-256：\n${fingerprint}\n${label}`,
        primaryButton: { value: '取消', action: (): void => {} }, secondaryButton: { value: '核对一致，信任',
          action: (): void => { this.addKey(publicKey, label); } } });
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
  }

  private async addKey(publicKey: string, label: string): Promise<void> {
    const store: PluginStore | null = this.store;
    if (store === null || !this.available()) return;
    this.busy = '正在添加可信发布者…'; this.error = ''; this.notice = '';
    try {
      const key: PluginTrustedKey = await store.addTrustedKey(publicKey, label);
      const keys: PluginTrustedKey[] = await store.listTrustedKeys();
      const installed: InstalledPlugin[] = await store.listInstalled(this.primitives);
      if (!this.alive) return;
      this.trustedKeys = keys; this.installed = installed; this.keyBase64 = ''; this.keyLabel = '';
      if (this.detail !== null) this.detail = installed.find((plugin: InstalledPlugin): boolean => plugin.id === this.detail!.id && plugin.currentHash === this.detail!.currentHash) ?? null;
      this.notice = `已信任发布者 SHA-256：${key.fingerprint}`;
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
    finally { if (this.alive) this.busy = ''; }
  }

  private confirmRemoveKey(key: PluginTrustedKey): void {
    if (!this.available()) return;
    AlertDialog.show({ title: '移除可信发布者', message: `${key.label}\nSHA-256 ${key.fingerprint}`,
      primaryButton: { value: '取消', action: (): void => {} }, secondaryButton: { value: '移除', fontColor: this.colors.error,
        action: (): void => { this.removeKey(key.fingerprint); } } });
  }

  private async removeKey(fingerprint: string): Promise<void> {
    const store: PluginStore | null = this.store;
    if (store === null || !this.available()) return;
    this.busy = '正在移除可信发布者…'; this.error = ''; this.notice = '';
    try {
      await store.removeTrustedKey(fingerprint);
      const keys: PluginTrustedKey[] = await store.listTrustedKeys();
      const installed: InstalledPlugin[] = await store.listInstalled(this.primitives);
      if (!this.alive) return;
      this.trustedKeys = keys; this.installed = installed; this.notice = '已移除发布者信任';
      if (this.detail !== null) this.detail = installed.find((plugin: InstalledPlugin): boolean => plugin.id === this.detail!.id && plugin.currentHash === this.detail!.currentHash) ?? null;
    } catch (error) { this.failure(error instanceof Error ? error : String(error)); }
    finally { if (this.alive) this.busy = ''; }
  }

  @Builder
  private SourceCard(): void {
    Column({ space: 8 }) {
      Text('从 Workspace 验证与导入').fontSize(15).fontColor(this.colors.ink).width('100%')
      Row({ space: 8 }) {
        Button('插件目录').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(13)
          .fontColor(this.sourceKind === 'directory' ? this.accentColor : this.colors.secondary).backgroundColor(this.colors.inset)
          .enabled(this.busy.length === 0).onClick((): void => { this.sourceChanged(this.workspacePath, 'directory'); })
        Button('归档文件').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(13)
          .fontColor(this.sourceKind === 'archive' ? this.accentColor : this.colors.secondary).backgroundColor(this.colors.inset)
          .enabled(this.busy.length === 0).onClick((): void => { this.sourceChanged(this.workspacePath, 'archive'); })
      }.width('100%')
      TextInput({ text: this.workspacePath, placeholder: this.sourceKind === 'archive' ? 'plugins/example.amberplugin.json' : 'plugins/example' })
        .height(52).fontSize(13).fontFamily(FONT_MONO).fontColor(this.colors.ink).placeholderColor(this.colors.muted)
        .backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.sourceChanged(value, this.sourceKind); })
      Row() {
        Text('批准导入后启用').fontSize(13).fontColor(this.colors.ink).layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.enableImport }).selectedColor(this.accentColor).enabled(this.busy.length === 0)
          .onChange((value: boolean): void => { this.enableImport = value; if (this.preview !== null) this.closeSelection(); })
      }.width('100%').height(52)
      Text('预览固定文件、内容 hash、权限差异和启用意图。测试与导入进入真实对话，逐次审批和结果回执均在对话中查看。')
        .fontSize(12).fontColor(this.colors.secondary).width('100%')
      Button('验证并预览').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
        .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available())
        .onClick((): void => { this.prepareImport(); })
    }.width('100%').padding(14)
  }

  @Builder
  private InstalledCard(): void {
    Column() {
      if (this.installed.length === 0) {
        Text(this.loaded ? '暂无已安装插件' : this.error.length > 0 ? '读取失败，请刷新重试' : '正在读取已安装插件…')
          .fontSize(14).fontColor(this.colors.secondary).width('100%').padding(14)
      } else {
        ForEach(this.installed, (plugin: InstalledPlugin): void => {
          Column({ space: 6 }) {
            Row({ space: 8 }) {
              Text(plugin.package?.manifest.name ?? plugin.id).fontSize(15).fontColor(this.colors.ink).layoutWeight(1)
                .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(pluginStatusLabel(plugin)).fontSize(12).fontColor(this.colors.secondary)
            }.width('100%')
            Text(plugin.package === null ? plugin.id : `${plugin.package.manifest.version} · ${plugin.package.tools.length} 个工具`)
              .fontSize(12).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
            Text(pluginTrustLabel(plugin.trust)).fontSize(12).fontColor(this.colors.secondary).width('100%')
            if (plugin.errorMessage !== null) {
              Text(plugin.errorMessage).fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
            }
          }.width('100%').constraintSize({ minHeight: 52 }).padding(14)
            .border({ width: { bottom: 0.5 }, color: this.colors.line }).enabled(this.available())
            .onClick((): void => { this.selectPlugin(plugin); })
        }, (plugin: InstalledPlugin): string => `${plugin.id}:${plugin.currentHash}:${plugin.enabled}:${plugin.health?.quarantinedAt}:${plugin.errorCode}:${plugin.trust.publisherTrusted}`)
      }
    }.width('100%')
  }

  @Builder
  private PreviewCard(preview: PluginImportPreview | null): void {
    Column({ space: 8 }) {
      if (preview !== null) {
        Text(preview.baseHash === null ? '新插件导入预览' : '插件更新预览').fontSize(15).fontColor(this.colors.ink).width('100%')
        Text(`来源：${preview.source.workspacePath}`).fontSize(12).fontColor(this.colors.secondary).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        Text(`批准后${preview.enable ? '启用' : '保持停用'} · ${preview.permissionExpanded ? '权限扩大，须核对差异' : '无新增权限'}`)
          .fontSize(13).fontColor(this.colors.ink).width('100%')
        if (preview.baseHash !== null) {
          Text(`已安装基线 SHA-256：${preview.baseHash}`).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary)
            .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        }
        ForEach(preview.permissionDiff, (difference: string): void => {
          Text(`新增或变化：${difference}`).fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
        }, (difference: string): string => difference)
        Text('测试执行此候选内容，按当前助手权限审批，不影响已安装版本的健康状态。验证成功只代表包结构有效。')
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        Button('进入对话审批导入').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available())
          .onClick((): void => { this.launch('import'); })
      }
    }.width('100%').padding(14)
  }

  @Builder
  private PackageCard(preview: PluginImportPreview | null, detail: InstalledPlugin | null): void {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text(this.selectedPackage(preview, detail)?.manifest.name ?? detail?.id ?? '').fontSize(16).fontColor(this.colors.ink)
          .layoutWeight(1).wordBreak(WordBreak.BREAK_ALL)
        Button('关闭').type(ButtonType.Normal).height(44).fontSize(13).fontColor(this.colors.secondary)
          .backgroundColor(this.colors.inset).enabled(this.busy.length === 0).onClick((): void => { this.closeSelection(); })
      }.width('100%')
      if (this.selectedPackage(preview, detail) !== null) {
        Text(`版本 ${this.selectedPackage(preview, detail)!.manifest.version} · ${this.selectedPackage(preview, detail)!.manifest.id}`)
          .fontSize(12).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
        Text(this.selectedPackage(preview, detail)!.manifest.description).fontSize(13).fontColor(this.colors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL)
        Text('包内容 SHA-256').fontSize(12).fontColor(this.colors.secondary).width('100%')
        Text(this.selectedPackage(preview, detail)!.hash).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        Text(pluginTrustLabel(preview?.trust ?? detail!.trust)).fontSize(13).fontColor(this.colors.ink).width('100%')
        if ((preview?.trust ?? detail!.trust).fingerprint !== null) {
          Text(`发布者完整 SHA-256：${(preview?.trust ?? detail!.trust).fingerprint}`)
            .fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
            .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        }
        ForEach(pluginCapabilityRows(this.selectedPackage(preview, detail)!.manifest.capabilities), (capability: string): void => {
          Text(capability).fontSize(12).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
        }, (capability: string): string => capability)
        Text(this.selectedPackage(preview, detail)!.manifest.backgroundAllowed ? '清单申请后台资格；仍受宿主实际后台工具与运行环境限制' : '清单未申请后台运行')
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        Column({ space: 8 }) {
          Button(this.showManifest ? '收起完整清单' : '完整清单 / Schema').type(ButtonType.Normal).height(44).width('100%')
            .fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.inset)
            .onClick((): void => {
              animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                this.showManifest = !this.showManifest;
              });
            })
          if (this.showManifest) {
            Text(JSON.stringify(this.selectedPackage(preview, detail)!.manifest, null, 2)).fontSize(12).fontFamily(FONT_MONO)
              .fontColor(this.colors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          }
        }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
        Column({ space: 8 }) {
          Button(this.showFiles ? '收起文件摘要' : '查看所有文件 SHA-256').type(ButtonType.Normal).height(44).width('100%')
            .fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.inset)
            .onClick((): void => {
              animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                this.showFiles = !this.showFiles;
              });
            })
          if (this.showFiles) {
            Text(JSON.stringify(this.selectedPackage(preview, detail)!.fileHashes, null, 2)).fontSize(12).fontFamily(FONT_MONO)
              .fontColor(this.colors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          }
        }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
      } else if (detail !== null) {
        Text(`无效包：${detail.errorCode ?? ''}\n${detail.errorMessage ?? ''}`).fontSize(13)
          .fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        Text('无有效包内容，无法运行或导出。重新预览 Workspace 中修正的包后可发起更新。')
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        if (detail.currentHash !== null) {
          Text(`当前指针 SHA-256：${detail.currentHash}`).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink)
            .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        } else {
          Text('当前指针已损坏，无法确认版本 hash，不能进行版本管理。').fontSize(12).fontColor(this.colors.error).width('100%')
        }
      }
    }.width('100%').padding(14)
  }

  @Builder
  private ToolCard(preview: PluginImportPreview | null, detail: InstalledPlugin | null, tool: PluginDescriptor | null): void {
    Column({ space: 8 }) {
      if (this.selectedPackage(preview, detail) !== null && tool !== null) {
        Column({ space: 8 }) {
          Button(`工具：${tool!.name} · 更换`).type(ButtonType.Normal).constraintSize({ minHeight: 52 })
            .width('100%').fontSize(14).fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
            .onClick((): void => {
              animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                this.showToolChoices = !this.showToolChoices;
              });
            })
          if (this.showToolChoices) {
            ForEach(this.selectedPackage(preview, detail)!.tools, (tool: PluginDescriptor): void => {
              Button(`${tool.name} · ${pluginRuntimeLabel(tool)}`).type(ButtonType.Normal).constraintSize({ minHeight: 44 })
                .width('100%').fontSize(13).fontColor(this.colors.ink).backgroundColor(this.colors.inset)
                .onClick((): void => { this.selectTool(tool); })
            }, (tool: PluginDescriptor): string => tool.toolId)
          }
        }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
        Text(tool!.description).fontSize(13).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
        Text(`${pluginRuntimeLabel(tool!)} · ${tool!.timeoutMs} ms · 输出上限 ${tool!.maxOutputChars} 字符`)
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        Text(`注册名：${tool!.toolId}\n宿主工具：${tool!.primitiveTools.join('、') || '无'}`)
          .fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
          .copyOption(CopyOptions.LocalDevice)
        Text(`风险 ${tool!.envelope.risk} · ${tool!.envelope.mutates ? '会修改数据' : '无声明的数据修改'} · ${tool!.envelope.needsApproval ? '包含审批' : '仍按当前工具权限执行'}`)
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
        Column({ space: 8 }) {
          Button(this.showSource ? '收起固定实现' : '查看固定源码 / 实现').type(ButtonType.Normal).height(44).width('100%')
            .fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.inset)
            .onClick((): void => {
              animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                this.showSource = !this.showSource;
              });
            })
          if (this.showSource) {
            Text(this.sourceText).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
              .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          }
        }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
        if (this.missingTools(tool!).length > 0) {
          Text(`当前助手缺失工具：${this.missingTools(tool!).join('、')}`).fontSize(12)
            .fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
        }
        if (tool!.implementation.kind === 'javascript') {
          Text('JSVM 没有浏览器 DOM 或直接网络、文件 API。取消会关闭宿主调用并放弃结果；无限循环可能仍占用 CPU，旧 VM 结束前无法再启动 JS 插件。')
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        } else if (tool!.implementation.kind === 'command') {
          Text('使用本机 embedded_python；不支持 sys、os、pip。取消须等待实际执行结束，C 阻塞调用不能保证立即停止。')
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        }
        PluginJSONEditor({ text: this.inputsText, label: '输入 JSON（按 Schema 填必需、可选及嵌套字段）',
          schemaJSON: JSON.stringify(tool!.inputSchema, null, 2), issue: this.inputIssue,
          editable: this.busy.length === 0, foreground: this.colors.ink, secondary: this.colors.secondary,
          muted: this.colors.muted, inset: this.colors.inset, accent: this.accentColor, errorColor: this.colors.error,
          onTextChange: (value: string): void => { this.inputsText = value; this.inputIssue = ''; } })
        Text(`输出：${tool!.output}\n${JSON.stringify(tool!.outputSchema, null, 2)}`)
          .fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
          .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        if (preview !== null) {
          Column({ space: 8 }) {
            Row() {
              Text('指定 expected_result').fontSize(13).fontColor(this.colors.ink).layoutWeight(1)
              Toggle({ type: ToggleType.Switch, isOn: this.expectedProvided }).selectedColor(this.accentColor)
                .enabled(this.busy.length === 0).onChange((value: boolean): void => {
                  animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                    this.expectedProvided = value;
                  });
                })
            }.width('100%').height(52)
            if (this.expectedProvided) {
              PluginJSONEditor({ text: this.expectedText, label: '预期结果 JSON（包括明确的 null）', issue: this.expectedIssue,
                editable: this.busy.length === 0, foreground: this.colors.ink, secondary: this.colors.secondary,
                muted: this.colors.muted, inset: this.colors.inset, accent: this.accentColor, errorColor: this.colors.error,
                onTextChange: (value: string): void => { this.expectedText = value; this.expectedIssue = ''; } })
            }
          }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
          Button('进入对话真实测试').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
            .fontColor(this.accentColor).backgroundColor(this.colors.inset)
            .enabled(this.available() && this.missingTools(tool!).length === 0)
            .onClick((): void => { this.launch('test'); })
        } else {
          Button('在对话中运行').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
            .fontColor(this.accentColor).backgroundColor(this.colors.inset)
            .enabled(this.available() && detail?.enabled === true && this.missingTools(tool!).length === 0)
            .onClick((): void => { this.launch('run'); })
        }
        Text('使用当前默认助手的真实工具和模型配置。保存固定版本及输入后进入聊天；执行、审批、日志与最终结果均在同一父工具中查看。')
          .fontSize(12).fontColor(this.colors.secondary).width('100%')
      }
    }.width('100%').padding(14)
  }

  @Builder
  private ManagementCard(detail: InstalledPlugin | null): void {
    Column({ space: 8 }) {
      if (detail !== null && detail.package !== null) {
        Text(`${pluginStatusLabel(detail)} · 配置${detail.configuredEnabled ? '启用' : '停用'}`)
          .fontSize(14).fontColor(this.colors.ink).width('100%')
        Row({ space: 8 }) {
          Button(detail.configuredEnabled ? '停用' : '启用').type(ButtonType.Normal).height(44).layoutWeight(1)
            .fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available())
            .onClick((): void => { this.confirmManagement(detail!.configuredEnabled ? 'disable' : 'enable'); })
          Button('删除').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(13)
            .fontColor(this.colors.error).backgroundColor(this.colors.inset).enabled(this.available())
            .onClick((): void => { this.confirmManagement('remove'); })
        }.width('100%')
        Button('回退上一版本').type(ButtonType.Normal).height(44).width('100%').fontSize(13)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available())
          .onClick((): void => { this.confirmManagement('rollback'); })
        if (detail.health?.quarantinedAt !== null && detail.health?.quarantinedAt !== undefined) {
          Text(`隔离原因：${detail.health.quarantineReason ?? ''}`).fontSize(12).fontColor(this.colors.error).width('100%')
            .wordBreak(WordBreak.BREAK_ALL)
          Button('解除当前版本隔离').type(ButtonType.Normal).height(44).width('100%').fontSize(13)
            .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available())
            .onClick((): void => { this.confirmManagement('restore'); })
        }
        TextInput({ text: this.exportPath, placeholder: '归档导出路径，例如 plugins/example.amberplugin.json' })
          .height(52).fontSize(13).fontColor(this.colors.ink).placeholderColor(this.colors.muted)
          .backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
          .onChange((value: string): void => { this.exportPath = value; })
        Button('导出到 Workspace').type(ButtonType.Normal).height(44).width('100%').fontSize(13)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available())
          .onClick((): void => { this.exportPlugin(); })
        Column({ space: 8 }) {
          Button(this.showDiagnostics ? '收起诊断' : '查看当前版本诊断').type(ButtonType.Normal).height(44).width('100%')
            .fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.inset)
            .onClick((): void => {
              animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                this.showDiagnostics = !this.showDiagnostics;
              });
            })
          if (this.showDiagnostics) {
            Text(JSON.stringify(detail.health, null, 2)).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink)
              .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          }
        }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
        if (this.receiptJSON.length > 0) {
          Text(`真实操作回执\n${this.receiptJSON}`).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink)
            .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        }
      } else if (detail !== null && detail.currentHash !== null) {
        Text('损坏包仍可按当前指针 hash 删除或回退上一版本。').fontSize(12).fontColor(this.colors.secondary).width('100%')
        Row({ space: 8 }) {
          Button('回退上一版').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(13)
            .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available())
            .onClick((): void => { this.confirmManagement('rollback'); })
          Button('删除损坏包').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(13)
            .fontColor(this.colors.error).backgroundColor(this.colors.inset).enabled(this.available())
            .onClick((): void => { this.confirmManagement('remove'); })
        }.width('100%')
      }
    }.width('100%').padding(14)
  }

  @Builder
  private TrustCard(): void {
    Column({ space: 8 }) {
      Text('可信发布者').fontSize(15).fontColor(this.colors.ink).width('100%')
      Text('添加公钥前，向发布者核对完整 SHA-256 指纹。签名有效与发布者已信任是不同状态；归档短 keyId 不能代替完整指纹。')
        .fontSize(12).fontColor(this.colors.secondary).width('100%')
      ForEach(this.trustedKeys, (key: PluginTrustedKey): void => {
        Column({ space: 6 }) {
          Row({ space: 8 }) {
            Text(key.label || '未命名发布者').fontSize(14).fontColor(this.colors.ink).layoutWeight(1).wordBreak(WordBreak.BREAK_ALL)
            Button('移除').type(ButtonType.Normal).height(44).fontSize(13).fontColor(this.colors.error)
              .backgroundColor(this.colors.inset).enabled(this.available()).onClick((): void => { this.confirmRemoveKey(key); })
          }.width('100%')
          Text(key.fingerprint).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
            .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        }.width('100%').padding({ bottom: 8 })
      }, (key: PluginTrustedKey): string => key.fingerprint)
      if (this.trustedKeys.length === 0) {
        Text('尚未添加可信发布者').fontSize(12).fontColor(this.colors.secondary).width('100%')
      }
      TextInput({ text: this.keyLabel, placeholder: '发布者备注' }).height(52).fontSize(13).fontColor(this.colors.ink)
        .placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.keyLabel = value; })
      TextArea({ text: this.keyBase64, placeholder: 'Ed25519 公钥 Base64' }).height(88).fontSize(13).fontFamily(FONT_MONO)
        .fontColor(this.colors.ink).placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.keyBase64 = value; })
      Button('添加可信发布者公钥').type(ButtonType.Normal).height(44).width('100%').fontSize(13)
        .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(this.available()).onClick((): void => { this.confirmAddKey(); })
    }.width('100%').padding(14)
  }

  // Builder 参数保留当前包和工具，清空页面选择时仍可完成退出过渡。
  @Builder
  private SelectionPanel(preview: PluginImportPreview | null, detail: InstalledPlugin | null,
    tool: PluginDescriptor | null): void {
    Column({ space: 12 }) {
      if (preview !== null) {
        CardGroup({ content: (): void => { this.PreviewCard(preview); } })
      }
      CardGroup({ content: (): void => { this.PackageCard(preview, detail); } })
      if (this.selectedPackage(preview, detail) !== null) {
        CardGroup({ content: (): void => { this.ToolCard(preview, detail, tool); } })
      }
      if (preview === null) {
        CardGroup({ content: (): void => { this.ManagementCard(detail); } })
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '插件中心', actionText: '刷新', onAction: (): void => { this.load(); } })
      Scroll() {
        Column({ space: 12 }) {
          Text('插件把包内 Recipe、JSVM、本机 Python 或固定远程端点注册成工具，能力仍与当前默认助手的真实工具及审批相交。')
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
          Column() {
            Row({ space: 8 }) {
              Button(this.showKeys ? '收起发布者' : '可信发布者').type(ButtonType.Normal).height(44).layoutWeight(1)
                .fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.surface)
                .onClick((): void => {
                  animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                    this.showKeys = !this.showKeys;
                  });
                })
              Button(this.showSDK ? '收起 SDK' : '插件 SDK').type(ButtonType.Normal).height(44).layoutWeight(1)
                .fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.surface)
                .onClick((): void => {
                  animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                    this.showSDK = !this.showSDK;
                  });
                })
            }.width('100%')
            Column() {
              if (this.showKeys) { CardGroup({ content: (): void => { this.TrustCard(); } }) }
            }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
              .margin({ top: this.showKeys ? 12 : 0 })
            Column() {
              if (this.showSDK) {
                Text(this.sdkJSON).fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.ink).width('100%')
                  .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
              }
            }.width('100%').alignItems(HorizontalAlign.Start).clip(true)
              .margin({ top: this.showSDK ? 12 : 0 })
          }.width('100%').alignItems(HorizontalAlign.Start)
          CardGroup({ content: (): void => { this.SourceCard(); } })
          if (this.busy.length > 0) { Text(this.busy).fontSize(12).fontColor(this.colors.secondary).width('100%') }
          if (this.error.length > 0) {
            Text(this.error).fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          }
          if (this.notice.length > 0) {
            Text(this.notice).fontSize(12).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          }
          if (this.preview !== null || this.detail !== null) {
            this.SelectionPanel(this.preview, this.detail, this.selectedTool())
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
