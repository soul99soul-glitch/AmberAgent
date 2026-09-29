import {
  ACCENT_INK, BG, INK, INK3, LINE, SURFACE2, RAISED, SIGNAL, ERROR,
  TITLE, TEXT_STYLES, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import {
  PageTitleBar, Chip, Seg, ToggleRow, BtnFill, BtnGhost, PaperSeparator, IconSeat, PageSpacer,
} from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { McpToolPaperRow } from '../components/McpPaperRows.ets';
import { router } from '@kit.ArkUI';
import {
  parseMcpServersFromJson, cloneMcpServerConfig, makeMcpStreamableHttpServer,
  makeMcpSseServer,
} from '@amber/chat-domain';
import type { McpServerConfig, McpStatus, McpTool, McpCommonOptions } from '@amber/chat-domain';
import { getMcpManager, initMcpManager } from '../di/AppContainer.ets';
import { getMcpSettingsStore } from '../platform_impl/McpSettingsStore.ets';


// transportLabel(:367-370)/statusLabel(:372-378)逐字
const transportLabel = (config: McpServerConfig): string =>
  config.kind === 'sse' ? 'SSE' : 'Streamable HTTP';

const statusLabel = (status: McpStatus): string => {
  if (status.kind === 'idle') return 'Idle';
  if (status.kind === 'connecting') return 'Connecting';
  if (status.kind === 'connected') return 'Connected';
  if (status.kind === 'reconnecting') return 'Reconnecting';
  return 'Error';
};

// commonOptions 局部补丁(ArkTS 禁 Record 展开,改显式接口 + 逐字段构造)
interface CommonPatch {
  enable?: boolean;
  name?: string;
  headers?: Array<[string, string]>;
  tools?: McpTool[];
}

@Entry
@Component
struct ChatMcpSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State servers: McpServerConfig[] = [];
  @State statuses: Record<string, McpStatus> = {};
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State refreshing: boolean = false;
  // 编辑态:draft 非 null 即开 sheet;creating 区分新建/编辑
  @State draft: McpServerConfig | null = null;
  @State creating: boolean = false;
  @State draftTab: number = 0;
  // 输入和删除其他行时保留请求头输入框身份；ID 不写入服务器配置。
  @State draftHeaderIds: number[] = [];
  private nextDraftHeaderId: number = 0;
  // F23:空名称「保存」原本静默 no-op;现在以行内错误反馈(清空即消失)
  @State nameError: string = '';
  @State expandedTools: string[] = [];
  @State pressedServerId: string = '';
  // 导入态
  @State showImport: boolean = false;
  @State importText: string = '';
  @State importError: string = '';
  private unsubServers: () => void = (): void => {};
  private unsubStatuses: () => void = (): void => {};

  aboutToAppear(): void {
    initMcpManager().catch((): void => {});
    this.unsubServers = getMcpSettingsStore().subscribeMcpServers(
      (configs: McpServerConfig[]): void => {
        this.servers = [...configs];
      });
    this.unsubStatuses = getMcpManager().subscribeStatuses(
      (m: ReadonlyMap<string, McpStatus>): void => {
        const rec: Record<string, McpStatus> = {};
        m.forEach((v: McpStatus, k: string): void => {
          rec[k] = v;
        });
        this.statuses = rec;
        // loading(:156)— 任一 connecting/reconnecting
        let anyActive: boolean = false;
        m.forEach((v: McpStatus): void => {
          if (v.kind === 'connecting' || v.kind === 'reconnecting') anyActive = true;
        });
        this.refreshing = anyActive;
      });
  }

  aboutToDisappear(): void {
    this.unsubServers();
    this.unsubStatuses();
  }

  private statusOf(config: McpServerConfig): McpStatus {
    const s: McpStatus | undefined = this.statuses[config.id];
    return s ?? { kind: 'idle' };
  }

  // :180-186 — filter id 移除
  private deleteServer(config: McpServerConfig): void {
    getMcpSettingsStore().updateMcpServers(
      (old: McpServerConfig[]): McpServerConfig[] =>
        old.filter((c: McpServerConfig): boolean => c.id !== config.id));
  }

  // :104-110 新建 append / :111-122 编辑按 id 替换(name 非空才可保存 :453-455)
  private confirmDraft(): void {
    const draft: McpServerConfig | null = this.draft;
    if (draft === null) return;
    if (draft.commonOptions.name.trim().length === 0) {
      // F23:拒绝原因可见,不再静默;sheet 停留供用户修正
      this.nameError = '请填写服务器名称';
      return;
    }
    this.nameError = '';
    const common: McpCommonOptions = {
      enable: draft.commonOptions.enable,
      name: draft.commonOptions.name,
      headers: draft.commonOptions.headers.map((header: [string, string]): [string, string] =>
        [header[0].trim(), header[1].trim()]),
      tools: draft.commonOptions.tools,
    };
    const saved: McpServerConfig = cloneMcpServerConfig(draft, draft.id, common);
    if (this.creating) {
      getMcpSettingsStore().updateMcpServers(
        (old: McpServerConfig[]): McpServerConfig[] => [...old, saved]);
    } else {
      getMcpSettingsStore().updateMcpServers(
        (old: McpServerConfig[]): McpServerConfig[] =>
          old.map((c: McpServerConfig): McpServerConfig =>
            c.id === saved.id ? saved : c));
    }
    this.draft = null;
  }

  private openCreate(): void {
    // :140 — 新建默认 StreamableHTTPServer()
    this.draft = makeMcpStreamableHttpServer({});
    this.draftHeaderIds = [];
    this.creating = true;
    this.draftTab = 0;
    this.expandedTools = [];
    this.nameError = '';
  }

  private openEdit(config: McpServerConfig): void {
    this.draft = cloneMcpServerConfig(config);
    this.draftHeaderIds = config.commonOptions.headers.map((): number => this.nextDraftHeaderId++);
    this.creating = false;
    this.draftTab = 0;
    this.expandedTools = [];
    this.nameError = '';
  }

  // commonOptions 补丁(保 kind;copy 语义)
  private patchCommon(patch: CommonPatch): void {
    const draft: McpServerConfig | null = this.draft;
    if (draft === null) return;
    const c: McpCommonOptions = draft.commonOptions;
    const common: McpCommonOptions = {
      enable: patch.enable !== undefined ? patch.enable : c.enable,
      name: patch.name !== undefined ? patch.name : c.name,
      headers: patch.headers !== undefined ? patch.headers : c.headers,
      tools: patch.tools !== undefined ? patch.tools : c.tools,
    };
    this.draft = cloneMcpServerConfig(draft, draft.id, common);
  }

  private draftHeaderValue(id: number, column: number): string {
    const draft: McpServerConfig | null = this.draft;
    const index: number = this.draftHeaderIds.indexOf(id);
    return draft !== null && index >= 0 ? draft.commonOptions.headers[index][column] : '';
  }

  private patchHeader(id: number, column: number, value: string): void {
    const draft: McpServerConfig | null = this.draft;
    const index: number = this.draftHeaderIds.indexOf(id);
    if (draft === null || index < 0) return;
    const headers: Array<[string, string]> = [...draft.commonOptions.headers];
    headers[index] = column === 0 ? [value, headers[index][1]] : [headers[index][0], value];
    this.patchCommon({ headers: headers });
  }

  private removeHeader(id: number): void {
    const draft: McpServerConfig | null = this.draft;
    const index: number = this.draftHeaderIds.indexOf(id);
    if (draft === null || index < 0) return;
    const headers: Array<[string, string]> = draft.commonOptions.headers
      .filter((_header: [string, string], i: number): boolean => i !== index);
    this.draftHeaderIds = this.draftHeaderIds.filter((headerId: number): boolean => headerId !== id);
    this.patchCommon({ headers: headers });
  }

  // :573-595 — 传输类型切换(保 id/commonOptions/url)
  private switchTransport(index: number): void {
    const draft: McpServerConfig | null = this.draft;
    if (draft === null) return;
    const current: number = draft.kind === 'sse' ? 1 : 0;
    if (index === current) return;
    if (index === 0) {
      this.draft = makeMcpStreamableHttpServer({
        id: draft.id, commonOptions: draft.commonOptions, url: draft.url,
      });
    } else {
      this.draft = makeMcpSseServer({
        id: draft.id, commonOptions: draft.commonOptions, url: draft.url,
      });
    }
  }

  // 工具开关(:792-821)— 按 name 更新 draft tools
  private patchTool(toolName: string, field: string, value: boolean): void {
    const draft: McpServerConfig | null = this.draft;
    if (draft === null) return;
    const tools: McpTool[] = draft.commonOptions.tools.map((t: McpTool): McpTool => {
      if (t.name !== toolName) return t;
      if (field === 'enable') {
        return {
          enable: value, name: t.name, description: t.description,
          inputSchema: t.inputSchema, needsApproval: t.needsApproval,
        };
      }
      return {
        enable: t.enable, name: t.name, description: t.description,
        inputSchema: t.inputSchema, needsApproval: value,
      };
    });
    this.patchCommon({ tools: tools });
  }

  private toggleToolExpanded(name: string): void {
    const i: number = this.expandedTools.indexOf(name);
    if (i >= 0) {
      this.expandedTools.splice(i, 1);
    } else {
      this.expandedTools.push(name);
    }
    this.expandedTools = [...this.expandedTools];
  }

  // :986-996 — 导入解析
  private confirmImport(): void {
    try {
      const configs: McpServerConfig[] = parseMcpServersFromJson(this.importText.trim());
      if (configs.length === 0) {
        this.importError = '未找到有效的 MCP Server 配置';
        return;
      }
      // :218-219 — 按 name 去重
      const existingNames: string[] = this.servers.map(
        (c: McpServerConfig): string => c.commonOptions.name);
      const toAdd: McpServerConfig[] = configs.filter(
        (c: McpServerConfig): boolean => !existingNames.includes(c.commonOptions.name));
      getMcpSettingsStore().updateMcpServers(
        (old: McpServerConfig[]): McpServerConfig[] => [...old, ...toAdd]);
      this.showImport = false;
      this.importText = '';
      this.importError = '';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.importError = `JSON 解析失败：${msg}`;
    }
  }

  @Builder
  serverItem(config: McpServerConfig, isLast: boolean) {
    Column() {
      Row() {
        IconSeat({ iconName: 'plug' })
        Column() {
          Row() {
            Text(config.commonOptions.name)
              .fontSize(TEXT_STYLES.body.size)
              .fontWeight(600)
              .lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
            Circle({ width: 8, height: 8 })
              .fill(config.commonOptions.enable ? SIGNAL : ERROR)
              .margin({ left: 7 })
          }
          .width('100%')
          Row() {
            Chip({ text: transportLabel(config) })
            Row() {
              Chip({ text: statusLabel(this.statusOf(config)) })
            }
            .margin({ left: 6 })
          }
          .margin({ top: 5 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })
        Row() {
          Icon({ name: 'gear', iconSize: 18, color: INK3 })
        }
        .width(44).height(44)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .onClick((): void => { this.openEdit(config); })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressedServerId === config.id ? SURFACE2 : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressedServerId = config.id;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressedServerId = '';
      })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }

  @Builder
  formLabel(title: string, desc: string) {
    Column() {
      Text(title)
        .fontSize(13)
        .fontWeight(600)
        .lineHeight(lineHeightForSize(13))
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(INK)
      Text(desc)
        .fontSize(TEXT_STYLES.meta.size)
        .fontWeight(400)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(TEXT_STYLES.meta.family)
        .fontColor(INK3)
        .margin({ top: 3 })
    }
    .alignItems(HorizontalAlign.Start).width('100%')
    .padding({ top: 12, bottom: 6 })
  }

  @Builder
  basicTab(draft: McpServerConfig) {
    Scroll() {
      Column() {
        ToggleRow({
          label: '启用',
          desc: '是否启用此MCP服务器',
          checked: draft.commonOptions.enable,
          isLast: false,
          onToggle: (): void => { this.patchCommon({ enable: !draft.commonOptions.enable }); },
        })

        // 名称(:516-544)
        this.formLabel('名称', 'MCP服务器的显示名称')
        TextInput({ text: draft.commonOptions.name, placeholder: '例如：My MCP Server' })
          .fontSize(14).fontWeight(400).lineHeight(lineHeightForSize(14)).fontFamily(FONT_SANS_SEMIBOLD)
          .backgroundColor(RAISED)
          .border({
            width: this.nameError.length > 0 ? 1 : 0,
            color: this.nameError.length > 0 ? ERROR : LINE,
          })
          .onChange((v: string): void => {
            this.patchCommon({ name: v });
            if (this.nameError.length > 0 && v.trim().length > 0) this.nameError = '';
          })
        if (this.nameError.length > 0) {
          // F23:行内反馈(与导入错误同款式)
          Text(this.nameError)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(400)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(TEXT_STYLES.meta.family)
            .fontColor(ERROR).width('100%').maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 6 })
        }
        PaperSeparator()

        // 传输类型(:548-604)— 0=Streamable HTTP,1=SSE
        this.formLabel('传输类型', '选择MCP服务器的传输协议类型')
        Seg({
          options: ['Streamable HTTP', 'SSE'],
          selected: draft.kind === 'sse' ? 1 : 0,
          onSelect: (index: number): void => { this.switchTransport(index); },
        }).margin({ top: 12 })
        PaperSeparator()

        // URL(:608-646 — 按类型 desc/placeholder)
        this.formLabel('服务器地址',
          draft.kind === 'sse' ? 'SSE传输服务器的URL地址' : '流式HTTP服务器的URL地址')
        TextInput({
          text: draft.url,
          placeholder: draft.kind === 'sse'
            ? 'https://example.com/sse' : 'https://example.com/mcp',
        })
          .fontSize(14).fontWeight(400).lineHeight(lineHeightForSize(14)).fontFamily(FONT_MONO_MEDIUM)
          .backgroundColor(RAISED)
          .onChange((v: string): void => {
            const d: McpServerConfig | null = this.draft;
            if (d === null) return;
            if (d.kind === 'sse') {
              this.draft = makeMcpSseServer({
                id: d.id, commonOptions: d.commonOptions, url: v,
              });
            } else {
              this.draft = makeMcpStreamableHttpServer({
                id: d.id, commonOptions: d.commonOptions, url: v,
              });
            }
          })
        PaperSeparator()

        // 请求头(:650-769)
        this.formLabel('自定义请求头', '为MCP服务器请求添加自定义HTTP头')
        ForEach(this.draftHeaderIds, (headerId: number) => {
          Row() {
            Column() {
              TextInput({ text: this.draftHeaderValue(headerId, 0), placeholder: '例如：Authorization' })
                .id(`mcp-header-name-${headerId}`)
                .accessibilityText('请求头名称')
                .fontSize(13).fontWeight(400).lineHeight(lineHeightForSize(13)).fontFamily(FONT_MONO_MEDIUM)
                .backgroundColor(RAISED)
                .onChange((v: string): void => {
                  this.patchHeader(headerId, 0, v);
                })
              TextInput({ text: this.draftHeaderValue(headerId, 1), placeholder: '例如：Bearer token123' })
                .id(`mcp-header-value-${headerId}`)
                .accessibilityText('请求头值')
                .fontSize(13).fontWeight(400).lineHeight(lineHeightForSize(13)).fontFamily(FONT_MONO_MEDIUM)
                .backgroundColor(RAISED).margin({ top: 8 })
                .onChange((v: string): void => {
                  this.patchHeader(headerId, 1, v);
                })
            }
            .layoutWeight(1)
            Row() {
              Icon({ name: 'close', iconSize: 16, color: INK3 })
            }
            .width(32).height(44)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .margin({ left: 8 })
            .id(`mcp-header-remove-${headerId}`)
            .accessibilityText('删除请求头')
            .onClick((): void => { this.removeHeader(headerId); })
          }
          .width('100%').margin({ bottom: 8 })
        }, (headerId: number): string => String(headerId))
        Row() {
          BtnGhost({ label: '添加请求头', onTap: (): void => {
            const d: McpServerConfig | null = this.draft;
            if (d === null) return;
            // :746 — '' to ''
            const headers: Array<[string, string]> = [...d.commonOptions.headers, ['', '']];
            this.draftHeaderIds = [...this.draftHeaderIds, this.nextDraftHeaderId++];
            this.patchCommon({ headers: headers });
          } })
        }
        .width('100%')
      }
      .width('100%').padding({ bottom: 16 })
      .alignItems(HorizontalAlign.Start)
    }
        .align(Alignment.TopStart)
    .width('100%').layoutWeight(1)
  }

  @Builder
  toolsTab(draft: McpServerConfig) {
    Column() {
      // :784-787 — client null → 不可用文案
      if (getMcpManager().getClient(draft) === null) {
        Text('添加MCP Server时无法查看工具列表，请添加完后再来查看')
          .fontSize(TEXT_STYLES.secondary.size)
          .fontWeight(400)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK3)
          .margin({ top: 24 })
      }
      List() {
        ForEach(draft.commonOptions.tools, (tool: McpTool, index: number) => {
          ListItem() {
            McpToolPaperRow({
              toolName: tool.name,
              description: tool.description !== null ? tool.description.trim() : '',
              expanded: this.expandedTools.indexOf(tool.name) >= 0,
              enabledState: tool.enable,
              needsApproval: tool.needsApproval,
              params: tool.inputSchema !== null ? Object.keys(tool.inputSchema.properties) : [],
              requiredParams: tool.inputSchema !== null && tool.inputSchema.required !== null
                ? tool.inputSchema.required : [],
              isLast: index === draft.commonOptions.tools.length - 1,
              onEnableChange: (value: boolean): void => {
                this.patchTool(tool.name, 'enable', value);
              },
              onApprovalChange: (value: boolean): void => {
                this.patchTool(tool.name, 'needsApproval', value);
              },
              onExpand: (): void => { this.toggleToolExpanded(tool.name); },
            })
          }
        }, (tool: McpTool): string => tool.name)
      }
      .width('100%').layoutWeight(1)
    }
    .width('100%').layoutWeight(1)
  }

  // 滑动删除(:238-263 — end→start;@Builder 引用同 ChatListPage.swipeActions 先例)
  @Builder
  deleteAction(config: McpServerConfig) {
    Row() {
      Text('删除')
        .fontSize(13)
        .fontWeight(600)
        .lineHeight(lineHeightForSize(13))
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(ACCENT_INK)
        .padding({ left: 16, right: 16, top: 10, bottom: 10 })
    }
    .backgroundColor(ERROR)
    .onClick((): void => { this.deleteServer(config); })
  }

  @Builder
  editSheet() {
    Column() {
      // Tab 行(:398-424)
      Seg({
        options: ['基础设置', '工具'],
        selected: this.draftTab,
        onSelect: (index: number): void => { this.draftTab = index; },
      })
      if (this.draft !== null && this.draftTab === 0) {
        this.basicTab(this.draft)
      } else if (this.draft !== null) {
        this.toolsTab(this.draft)
      }
      // 保存(:447-460 — name 非空才可)
      Row() {
        BtnFill({ label: '保存', onTap: (): void => { this.confirmDraft(); } })
      }
      .width('100%').justifyContent(FlexAlign.End).padding({ top: 8 })
    }
      .width('100%').height('90%').padding(16)
      .backgroundColor(BG)
  }

  @Builder
  importSheet() {
    Column() {
      Text('导入MCP服务器')
        .fontSize(TITLE)
        .fontWeight(700)
        .lineHeight(TEXT_STYLES.screenTitle.lineHeight)
        .fontFamily(TEXT_STYLES.screenTitle.family)
        .fontColor(INK)
        .width('100%')
      Text('粘贴 MCP 服务器 JSON 配置，支持标准 mcpServers 格式')
        .fontSize(TEXT_STYLES.meta.size)
        .fontWeight(400)
        .lineHeight(TEXT_STYLES.meta.lineHeight)
        .fontFamily(TEXT_STYLES.meta.family)
        .fontColor(INK3)
        .width('100%').margin({ top: 6 })
      TextArea({ text: this.importText, placeholder: '{ "mcpServers": { ... } }' })
        .fontSize(13)
        .lineHeight(lineHeightForSize(13))
        .fontFamily(FONT_MONO_MEDIUM)
        .width('100%').layoutWeight(1)
        .backgroundColor(RAISED).margin({ top: 12 })
        .border({
          width: 1,
          color: this.importError.length > 0 ? ERROR : LINE,
        })
        .onChange((v: string): void => {
          this.importText = v;
          this.importError = '';
        })
      if (this.importError.length > 0) {
        Text(this.importError)
          .fontSize(TEXT_STYLES.meta.size)
          .fontWeight(400)
          .lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(TEXT_STYLES.meta.family)
          .fontColor(ERROR).width('100%').maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 6 })
      }
      Row() {
        BtnGhost({ label: '取消', onTap: (): void => { this.showImport = false; } })
        Row() {
          BtnFill({ label: '导入', onTap: (): void => { this.confirmImport(); } })
        }
        .margin({ left: 8 })
      }
      .width('100%').justifyContent(FlexAlign.End).margin({ top: 12 })
    }
      .width('100%').height('70%').padding(16)
      .backgroundColor(BG)
  }

  build() {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      // bindSheet 以宿主节点区分面板；独立宿主避免导入的 false 收起编辑面板。
      Column() {}
        .width(0).height(0)
        .hitTestBehavior(HitTestMode.None)
        .bindSheet(this.draft !== null, this.editSheet(), {
          height: SheetSize.LARGE,
          backgroundColor: BG,
          onDisappear: (): void => { this.draft = null; },
        })
      Column() {}
        .width(0).height(0)
        .hitTestBehavior(HitTestMode.None)
        .bindSheet(this.showImport, this.importSheet(), {
          height: SheetSize.LARGE,
          backgroundColor: BG,
          onDisappear: (): void => { this.showImport = false; },
        })
      // 顶栏(:126-147)— 返回 + MCP 设置 + 导入/添加(双 trailing → 保留 inline,对齐 PageTitleBar 样式)
      Row() {
        Row() {
          Icon({ name: 'back', iconSize: 24, color: INK })
        }
        .width(48).height(48)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .onClick((): void => { router.back(); })
        Text('MCP 设置')
          .fontSize(TITLE)
          .fontWeight(700)
          .lineHeight(TEXT_STYLES.screenTitle.lineHeight)
          .fontFamily(TEXT_STYLES.screenTitle.family)
          .fontColor(INK)
          .margin({ left: 8 })
          .layoutWeight(1)
        Text('导入')
          .fontSize(TEXT_STYLES.secondary.size)
          .fontWeight(500)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK3)
          .margin({ right: 16 })
          .onClick((): void => {
            this.importText = '';
            this.importError = '';
            this.showImport = true;
          })
        Row() {
          Icon({ name: 'plus', iconSize: 20, color: INK })
        }
        .width(44).height(44)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .id('mcp-add-server')
        .accessibilityText('添加 MCP 服务器')
        .onClick((): void => { this.openCreate(); })
      }
      .width('100%').padding({ left: 16, right: 16, top: this.statusBarHeight + 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      // 下拉刷新 syncAll(:157-165)
      Refresh({ refreshing: $$this.refreshing }) {
        List() {
          ForEach(this.servers, (config: McpServerConfig, index: number) => {
            ListItem() {
              this.serverItem(config, index === this.servers.length - 1)
            }
            .swipeAction({ end: this.deleteAction(config) })
          }, (config: McpServerConfig): string => config.id)
          ListItem() {
            PageSpacer()
          }
        }
        .width('100%').height('100%')
        .padding({ top: 10 })
      }
      .layoutWeight(1)
      .onRefreshing((): void => {
        getMcpManager().syncAll()
          .then((): void => { this.refreshing = false; })
          .catch((): void => { this.refreshing = false; });
      })

      // 空态(:192-209)
      if (this.servers.length === 0) {
        Column() {
          Text('未找到 MCP 服务器')
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(INK)
          Text('添加一个即可开始使用')
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(400)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(TEXT_STYLES.meta.family)
            .fontColor(INK3)
            .margin({ top: 8 })
        }
        .position({ x: 0, y: '40%' }).width('100%')
        .alignItems(HorizontalAlign.Center)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}
