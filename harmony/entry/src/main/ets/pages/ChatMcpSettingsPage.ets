// ChatMcpSettingsPage — MCP 设置面(D-120)
// Android 锚点:
//   SettingMcpPage.kt(全文 1004 行):
//     主页(:99-225)— 顶栏 导入/添加;列表 status 订阅;下拉刷新 syncAll
//       (loading = 任一 Connecting/Reconnecting :156);空态双行(:192-209);
//       导入去重按 name(:218-219)
//     McpServerItem(:227-347)— '{}' 图标块;名称+enable 圆点(green6/red6);
//       transport/status 双 pill;编辑 gear;滑动删除(end→start,:262-263)
//     transportLabel(:367-370)/statusLabel(:372-378)— 英文标签逐字
//     编辑 Modal(:380-464)— 两 Tab 基础设置/工具;name 非空才可保存(:453-455)
//     基础设置(:466-771)— enable 开关/名称/传输类型 segmented
//       (Streamable HTTP=0,SSE=1,切换保 id/commonOptions/url :573-595)/
//       URL(按类型 desc+placeholder :613-644)/请求头编辑器(name/value trim
//       写入 :678/:702,删除,添加 '' to '' :746)
//     工具 Tab(:773-935)— client null → 不可用文案(:784-787);卡片:名称+
//       需要审批/启用开关+展开(描述+参数 tag,required 高亮 :913-931)
//     导入 Modal(:937-1003)— 粘贴 JSON → parseMcpServersFromJson;空 →
//       '未找到有效的 MCP Server 配置';异常 → 'JSON 解析失败：<msg>'
//   values-zh/strings.xml(全部文案逐字)
// 偏差登记:视觉按 harmony 设置面既有惯例(BG/SURFACE/INK/LINE/ACCENT);
//   green6/red6 → #34A853/#EA4335 近似;placement 同 '运行' 先例(顶栏入口,
//   harmony 无设置索引页);滑动删除无撤销(Android 本页即无撤销,区别于 D-096)

import { ACCENT,ACCENT_INK,BG,INK,INK3,LINE,SURFACE,TITLE } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router } from '@kit.ArkUI';
import {
  parseMcpServersFromJson, cloneMcpServerConfig, makeMcpStreamableHttpServer,
  makeMcpSseServer,
} from '@amber/chat-domain';
import type { McpServerConfig, McpStatus, McpTool, McpCommonOptions } from '@amber/chat-domain';
import { getMcpManager, initMcpManager } from '../di/AppContainer.ets';
import { getMcpSettingsStore } from '../platform_impl/McpSettingsStore.ets';

const ROW: string = '#EFECE4';
const GREEN: string = '#34A853';
const RED: string = '#EA4335';

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
  @State servers: McpServerConfig[] = [];
  @State statuses: Record<string, McpStatus> = {};
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State refreshing: boolean = false;
  // 编辑态:draft 非 null 即开 sheet;creating 区分新建/编辑
  @State draft: McpServerConfig | null = null;
  @State creating: boolean = false;
  @State draftTab: number = 0;
  @State expandedTools: string[] = [];
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
    if (draft.commonOptions.name.trim().length === 0) return;
    if (this.creating) {
      getMcpSettingsStore().updateMcpServers(
        (old: McpServerConfig[]): McpServerConfig[] => [...old, draft]);
    } else {
      getMcpSettingsStore().updateMcpServers(
        (old: McpServerConfig[]): McpServerConfig[] =>
          old.map((c: McpServerConfig): McpServerConfig =>
            c.id === draft.id ? draft : c));
    }
    this.draft = null;
  }

  private openCreate(): void {
    // :140 — 新建默认 StreamableHTTPServer()
    this.draft = makeMcpStreamableHttpServer({});
    this.creating = true;
    this.draftTab = 0;
    this.expandedTools = [];
  }

  private openEdit(config: McpServerConfig): void {
    this.draft = cloneMcpServerConfig(config);
    this.creating = false;
    this.draftTab = 0;
    this.expandedTools = [];
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
  pill(text: string) {
    Text(text)
      .fontSize(10).fontColor(INK3)
      .padding({ left: 8, right: 8, top: 2, bottom: 2 })
      .backgroundColor(ROW).borderRadius(999)
      .border({ width: 1, color: LINE })
  }

  @Builder
  serverItem(config: McpServerConfig) {
    Row() {
      // '{}' 图标块(:285-291)
      Text('{}')
        .fontSize(13).fontColor(INK3).textAlign(TextAlign.Center)
        .width(34).height(34)
        .backgroundColor(ROW).borderRadius(8)
      Column() {
        Row() {
          Text(config.commonOptions.name)
            .fontSize(15).fontColor(INK).maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
          // enable 圆点(:309-318)
          Circle({ width: 8, height: 8 })
            .fill(config.commonOptions.enable ? GREEN : RED)
            .margin({ left: 7 })
        }
        .width('100%')
        Row() {
          this.pill(transportLabel(config))
          this.pill(statusLabel(this.statusOf(config)))
        }
        .margin({ top: 6 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })
      // 编辑 gear(:331-343)
      Column() {
        Icon({ name: 'gear', iconSize: 18, color: INK3 })
      }
      .width(44).height(44)
      .justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
      .onClick((): void => { this.openEdit(config); })
    }
    .width('100%').padding({ left: 14, right: 14, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).borderRadius(10)
    .border({ width: 1, color: LINE })
  }

  @Builder
  formLabel(title: string, desc: string) {
    Column() {
      Text(title).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Text(desc).fontSize(11).fontColor(INK3).margin({ top: 3 })
    }
    .alignItems(HorizontalAlign.Start).width('100%')
    .padding({ top: 12, bottom: 6 })
  }

  @Builder
  basicTab(draft: McpServerConfig) {
    Scroll() {
      Column() {
        // enable 开关(:479-512)
        Row() {
          Text('启用').fontSize(14).fontColor(INK).layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: draft.commonOptions.enable })
            .selectedColor(ACCENT)
            .onChange((v: boolean): void => { this.patchCommon({ enable: v }); })
        }
        .width('100%')
        Text('是否启用此MCP服务器').fontSize(11).fontColor(INK3)
        Divider().margin({ top: 12, bottom: 4 })

        // 名称(:516-544)
        this.formLabel('名称', 'MCP服务器的显示名称')
        TextInput({ text: draft.commonOptions.name, placeholder: '例如：My MCP Server' })
          .fontSize(14).backgroundColor('#FFFFFF')
          .onChange((v: string): void => { this.patchCommon({ name: v }); })
        Divider().margin({ top: 12, bottom: 4 })

        // 传输类型(:548-604)— 0=Streamable HTTP,1=SSE
        this.formLabel('传输类型', '选择MCP服务器的传输协议类型')
        Row() {
          Text('Streamable HTTP')
            .fontSize(13)
            .fontColor(draft.kind !== 'sse' ? '#FFFFFF' : INK)
            .textAlign(TextAlign.Center).layoutWeight(1)
            .padding({ top: 8, bottom: 8 })
            .backgroundColor(draft.kind !== 'sse' ? ACCENT : SURFACE)
            .border({ width: 1, color: draft.kind !== 'sse' ? ACCENT : LINE })
            .borderRadius({ topLeft: 10, bottomLeft: 10 })
            .onClick((): void => { this.switchTransport(0); })
          Text('SSE')
            .fontSize(13)
            .fontColor(draft.kind === 'sse' ? '#FFFFFF' : INK)
            .textAlign(TextAlign.Center).layoutWeight(1)
            .padding({ top: 8, bottom: 8 })
            .backgroundColor(draft.kind === 'sse' ? ACCENT : SURFACE)
            .border({ width: 1, color: draft.kind === 'sse' ? ACCENT : LINE })
            .borderRadius({ topRight: 10, bottomRight: 10 })
            .onClick((): void => { this.switchTransport(1); })
        }
        .width('100%')
        Divider().margin({ top: 12, bottom: 4 })

        // URL(:608-646 — 按类型 desc/placeholder)
        this.formLabel('服务器地址',
          draft.kind === 'sse' ? 'SSE传输服务器的URL地址' : '流式HTTP服务器的URL地址')
        TextInput({
          text: draft.url,
          placeholder: draft.kind === 'sse'
            ? 'https://example.com/sse' : 'https://example.com/mcp',
        })
          .fontSize(14).backgroundColor('#FFFFFF')
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
        Divider().margin({ top: 12, bottom: 4 })

        // 请求头(:650-769)
        this.formLabel('自定义请求头', '为MCP服务器请求添加自定义HTTP头')
        ForEach(draft.commonOptions.headers, (header: [string, string], index: number) => {
          Row() {
            Column() {
              TextInput({ text: header[0], placeholder: '例如：Authorization' })
                .fontSize(13).backgroundColor('#FFFFFF')
                .onChange((v: string): void => {
                  const d: McpServerConfig | null = this.draft;
                  if (d === null) return;
                  const headers: Array<[string, string]> = [...d.commonOptions.headers];
                  // :678 — name trim 写入
                  headers[index] = [v.trim(), headers[index][1]];
                  this.patchCommon({ headers: headers });
                })
              TextInput({ text: header[1], placeholder: '例如：Bearer token123' })
                .fontSize(13).backgroundColor('#FFFFFF').margin({ top: 8 })
                .onChange((v: string): void => {
                  const d: McpServerConfig | null = this.draft;
                  if (d === null) return;
                  const headers: Array<[string, string]> = [...d.commonOptions.headers];
                  // :702 — value trim 写入
                  headers[index] = [headers[index][0], v.trim()];
                  this.patchCommon({ headers: headers });
                })
            }
            .layoutWeight(1)
            Text('✕')
              .fontSize(15).fontColor(INK3).margin({ left: 10 })
              .onClick((): void => {
                const d: McpServerConfig | null = this.draft;
                if (d === null) return;
                const headers: Array<[string, string]> = d.commonOptions.headers
                  .filter((_h: [string, string], i: number): boolean => i !== index);
                this.patchCommon({ headers: headers });
              })
          }
          .width('100%').margin({ bottom: 8 })
        }, (header: [string, string], index: number): string => `${index}:${header[0]}`)
        Button('添加请求头')
          .fontSize(13).width('100%')
          .backgroundColor(ROW).fontColor(INK)
          .onClick((): void => {
            const d: McpServerConfig | null = this.draft;
            if (d === null) return;
            // :746 — '' to ''
            const headers: Array<[string, string]> = [...d.commonOptions.headers, ['', '']];
            this.patchCommon({ headers: headers });
          })
      }
      .width('100%').padding({ bottom: 16 })
      .alignItems(HorizontalAlign.Start)
    }
        .align(Alignment.Start)
    .width('100%').layoutWeight(1)
  }

  @Builder
  toolsTab(draft: McpServerConfig) {
    Column() {
      // :784-787 — client null → 不可用文案
      if (getMcpManager().getClient(draft) === null) {
        Text('添加MCP Server时无法查看工具列表，请添加完后再来查看')
          .fontSize(13).fontColor(INK3).margin({ top: 24 })
      }
      List() {
        ForEach(draft.commonOptions.tools, (tool: McpTool) => {
          ListItem() {
            Column() {
              Row() {
                Text(tool.name)
                  .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  .layoutWeight(1)
                Text('需要审批').fontSize(10).fontColor(INK3)
                Toggle({ type: ToggleType.Switch, isOn: tool.needsApproval })
                  .selectedColor(ACCENT).scale({ x: 0.7, y: 0.7 })
                  .onChange((v: boolean): void => {
                    this.patchTool(tool.name, 'needsApproval', v);
                  })
                Text('启用').fontSize(10).fontColor(INK3).margin({ left: 4 })
                Toggle({ type: ToggleType.Switch, isOn: tool.enable })
                  .selectedColor(ACCENT).scale({ x: 0.7, y: 0.7 })
                  .onChange((v: boolean): void => {
                    this.patchTool(tool.name, 'enable', v);
                  })
                Text(this.expandedTools.indexOf(tool.name) >= 0 ? '▲' : '▼')
                  .fontSize(12).fontColor(INK3).margin({ left: 6 })
                  .onClick((): void => { this.toggleToolExpanded(tool.name); })
              }
              .width('100%')
              if (this.expandedTools.indexOf(tool.name) >= 0) {
                if (tool.description !== null && tool.description.trim().length > 0) {
                  Text(tool.description)
                    .fontSize(12).fontColor(INK3).margin({ top: 6 })
                    .width('100%')
                }
                // 参数 tag(:913-931)— required 高亮
                if (tool.inputSchema !== null &&
                  Object.keys(tool.inputSchema.properties).length > 0) {
                  Flex({ wrap: FlexWrap.Wrap }) {
                    ForEach(Object.keys(tool.inputSchema.properties), (key: string) => {
                      Text(key)
                        .fontSize(11)
                        .fontColor(tool.inputSchema !== null &&
                          tool.inputSchema.required !== null &&
                          tool.inputSchema.required.indexOf(key) >= 0 ? ACCENT : INK3)
                        .padding({ left: 8, right: 8, top: 3, bottom: 3 })
                        .backgroundColor(ROW).borderRadius(8)
                        .margin({ right: 4, top: 4 })
                    }, (key: string): string => key)
                  }
                  .width('100%')
                }
              }
            }
            .width('100%').padding(10)
            .backgroundColor(SURFACE).borderRadius(10)
            .border({ width: 1, color: LINE })
            .alignItems(HorizontalAlign.Start)
          }
          .margin({ bottom: 8 })
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
        .fontSize(13).fontColor('#FFFFFF')
        .padding({ left: 18, right: 18, top: 10, bottom: 10 })
    }
    .backgroundColor(RED).borderRadius(10).margin({ left: 6 })
    .onClick((): void => { this.deleteServer(config); })
  }

  @Builder
  editSheet() {
    Column() {
      // Tab 行(:398-424)
      Row() {
        Text('基础设置')
          .fontSize(14)
          .fontWeight(this.draftTab === 0 ? FontWeight.Bold : FontWeight.Regular)
          .fontColor(this.draftTab === 0 ? ACCENT : INK3)
          .padding({ left: 16, right: 16, top: 10, bottom: 10 })
          .borderWidth({ bottom: 2 })
          .borderColor(this.draftTab === 0 ? ACCENT : '#00000000')
          .onClick((): void => { this.draftTab = 0; })
        Text('工具')
          .fontSize(14)
          .fontWeight(this.draftTab === 1 ? FontWeight.Bold : FontWeight.Regular)
          .fontColor(this.draftTab === 1 ? ACCENT : INK3)
          .padding({ left: 16, right: 16, top: 10, bottom: 10 })
          .borderWidth({ bottom: 2 })
          .borderColor(this.draftTab === 1 ? ACCENT : '#00000000')
          .onClick((): void => { this.draftTab = 1; })
      }
      .width('100%')
      if (this.draft !== null && this.draftTab === 0) {
        this.basicTab(this.draft)
      } else if (this.draft !== null) {
        this.toolsTab(this.draft)
      }
      // 保存(:447-460 — name 非空才可)
      Row() {
        Button('保存')
          .fontSize(14)
          .backgroundColor(ACCENT).fontColor(ACCENT_INK)
          .padding({ left: 14, right: 14, top: 6, bottom: 6 })
          .onClick((): void => { this.confirmDraft(); })
      }
      .width('100%').justifyContent(FlexAlign.End).padding({ top: 8 })
    }
    .width('100%').height('90%').padding(16)
  }

  @Builder
  importSheet() {
    Column() {
      Text('导入MCP服务器')
        .fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
        .width('100%')
      Text('粘贴 MCP 服务器 JSON 配置，支持标准 mcpServers 格式')
        .fontSize(12).fontColor(INK3).width('100%').margin({ top: 6 })
      TextArea({ text: this.importText, placeholder: '{ "mcpServers": { ... } }' })
        .fontSize(13).width('100%').layoutWeight(1)
        .backgroundColor('#FFFFFF').margin({ top: 12 })
        .border({
          width: 1,
          color: this.importError.length > 0 ? RED : LINE,
        })
        .onChange((v: string): void => {
          this.importText = v;
          this.importError = '';
        })
      if (this.importError.length > 0) {
        Text(this.importError)
          .fontSize(12).fontColor(RED).width('100%').maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 6 })
      }
      Row() {
        Button('取消')
          .fontSize(14).backgroundColor(ROW).fontColor(INK)
          .onClick((): void => { this.showImport = false; })
        Button('导入')
          .fontSize(14).backgroundColor(ACCENT).fontColor('#FFFFFF')
          .margin({ left: 8 })
          .onClick((): void => { this.confirmImport(); })
      }
      .width('100%').justifyContent(FlexAlign.End).margin({ top: 12 })
    }
    .width('100%').height('70%').padding(16)
  }

  build() {
    Column() {
      // 顶栏(:126-147)— 返回 + MCP 设置 + 导入/添加(双 trailing → 保留 inline,对齐 PageTitleBar 样式)
      Row() {
        Icon({ name: 'back', iconSize: 20, color: INK })
          .onClick((): void => { router.back(); })
        Text('MCP 设置')
          .fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 8 })
          .layoutWeight(1)
        Text('导入').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .onClick((): void => {
            this.importText = '';
            this.importError = '';
            this.showImport = true;
          })
        Text('＋').fontSize(20).fontColor(INK)
          .onClick((): void => { this.openCreate(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      // 下拉刷新 syncAll(:157-165)
      Refresh({ refreshing: $$this.refreshing }) {
        List() {
          ForEach(this.servers, (config: McpServerConfig) => {
            ListItem() {
              this.serverItem(config)
            }
            .margin({ bottom: 10 })
            .swipeAction({ end: this.deleteAction(config) })
          }, (config: McpServerConfig): string => config.id)
        }
        .width('100%').height('100%')
        .padding({ left: 18, right: 18, top: 10 })
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
          Text('未找到 MCP 服务器').fontSize(15).fontColor(INK)
          Text('添加一个即可开始使用').fontSize(12).fontColor(INK3).margin({ top: 8 })
        }
        .position({ x: 0, y: '40%' }).width('100%')
        .alignItems(HorizontalAlign.Center)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
    .bindSheet(this.draft !== null, this.editSheet(), {
      height: SheetSize.LARGE,
      // 手势下滑关闭时复位状态(onDisappear 手势/程序化两条路径都触发)
      onDisappear: (): void => { this.draft = null; },
    })
    .bindSheet(this.showImport, this.importSheet(), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.showImport = false; },
    })
  }
}
