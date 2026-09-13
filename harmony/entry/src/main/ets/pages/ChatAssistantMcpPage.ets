// ChatAssistantMcpPage — 助手 MCP 服务器选择面(D-121)
// Android 锚点:
//   AssistantMcpPage.kt(全文 60 行)— 顶栏 title assistant_page_tab_mcp
//     (values-zh 'MCP')+ BackButton;主体 = McpPicker(assistant, servers,
//     onUpdateAssistant = vm.update)
//   McpPicker.kt McpPicker(:157-251)— 仅列 enable 服务器(:169);行:状态图标
//     (Idle=bracket/Connecting+Reconnecting=spinner/Connected=MCP 图标/
//     Error=alert,:179-192)+ 名称 + 状态文本(:202-208 — 'Idle'/'Connecting'/
//     'Connected'/'Reconnecting (a/m)'/'Error: msg' 逐字,注意与 SettingMcpPage
//     statusLabel 不同格式)+ Connected 时 Tag 'N/M tools'(:213-221)+
//     Switch(:223-246 — checked → ids+id;否则 ids-id;removeIf 无效服务器
//     谓词为常量(仅当列表无此 server 时清空全表)— 行只为列表内 server 渲染,
//     该分支不可达,忠实保留注释)
//   McpPickerButton(:53-155)— Android 内无调用点(死代码),不移植
// 偏差登记:图标 → 文本符号('{}'/'⟳'/'⚠'/'MCP');spinner → Progress 组件;
//   Card → SURFACE 圆角卡(设置面惯例);AssistantDetailVM.mcpServerConfigs →
//   McpSettingsStore 订阅(同一数据源)

import { ACCENT,BG,INK,INK3,LINE,SURFACE,GUTTER,RADIUS,BODY2,META } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { router } from '@kit.ArkUI';
import {
  loadAssistants, patchAssistant,
} from '@amber/chat-domain';
import type { Assistant, McpServerConfig, McpStatus, McpTool } from '@amber/chat-domain';
import { getChatKvStore, getMcpManager, initMcpManager } from '../di/AppContainer.ets';
import { getMcpSettingsStore } from '../platform_impl/McpSettingsStore.ets';
import { mutateAssistant } from '../platform_impl/AssistantMutation.ets';

const RED: string = '#EA4335';

interface RouterParams {
  assistantId?: string;
}

// :202-208 状态文本逐字(与 SettingMcpPage statusLabel 不同 — 带计数/消息)
const pickerStatusText = (status: McpStatus): string => {
  if (status.kind === 'idle') return 'Idle';
  if (status.kind === 'connecting') return 'Connecting';
  if (status.kind === 'connected') return 'Connected';
  if (status.kind === 'reconnecting') {
    return `Reconnecting (${status.attempt}/${status.maxAttempts})`;
  }
  return `Error: ${status.message}`;
};

@Entry
@Component
struct ChatAssistantMcpPage {
  @State loaded: boolean = false;
  @State assistant: Assistant | null = null;
  @State servers: McpServerConfig[] = [];
  @State statuses: Record<string, McpStatus> = {};
  private assistantId: string = '';
  private unsubServers: () => void = (): void => {};
  private unsubStatuses: () => void = (): void => {};

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined
      ? params.assistantId : '';
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
      });
    this.refresh();
  }

  aboutToDisappear(): void {
    this.unsubServers();
    this.unsubStatuses();
  }

  private async refresh(): Promise<void> {
    const assistants: Assistant[] | null = await loadAssistants(getChatKvStore());
    const found: Assistant | null = assistants === null
      ? null
      : (assistants.find((a: Assistant): boolean => a.id === this.assistantId) ?? null);
    this.assistant = found;
    this.loaded = true;
  }

  private statusOf(config: McpServerConfig): McpStatus {
    const s: McpStatus | undefined = this.statuses[config.id];
    return s ?? { kind: 'idle' };
  }

  // :223-246 — Switch:checked → ids+id;否则 ids-id;随后 vm.update(assistant.copy)
  // F35:整段 RMW 进 assistants 键共享锁;ids 由锁内最新助手算出,不写旧快照
  private async toggleServer(server: McpServerConfig, checked: boolean): Promise<void> {
    const next: Assistant | null = await mutateAssistant(this.assistantId,
      (cur: Assistant): Assistant => {
        let ids: string[];
        if (checked) {
          ids = cur.mcpServers.indexOf(server.id) >= 0
            ? cur.mcpServers : cur.mcpServers.concat([server.id]);
        } else {
          ids = cur.mcpServers.filter((x: string): boolean => x !== server.id);
        }
        // :229/:238 removeIf — 谓词为常量(servers 无此 id 时清空全表);
        //   行仅为列表内 server 渲染 → 不可达,忠实保留
        const absent: boolean = !this.servers.some(
          (s: McpServerConfig): boolean => s.id === server.id);
        if (absent) ids = [];
        return patchAssistant(cur, { mcpServers: ids });
      });
    if (next !== null) this.assistant = next;
  }

  @Builder
  statusIcon(status: McpStatus) {
    if (status.kind === 'connecting' || status.kind === 'reconnecting') {
      // :181-190 — spinner
      LoadingProgress().width(22).height(22).color(ACCENT)
    } else if (status.kind === 'connected') {
      Text('MCP')
        .fontSize(10).fontColor(ACCENT).fontWeight(FontWeight.Bold)
        .width(22).textAlign(TextAlign.Center)
    } else if (status.kind === 'error') {
      Text('⚠').fontSize(16).fontColor(RED).width(22).textAlign(TextAlign.Center)
    } else {
      Text('{}').fontSize(12).fontColor(INK3).width(22).textAlign(TextAlign.Center)
    }
  }

  @Builder
  serverRow(server: McpServerConfig) {
    Row() {
      this.statusIcon(this.statusOf(server))
      Column() {
        Text(server.commonOptions.name)
          .fontSize(15).fontColor(INK)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(pickerStatusText(this.statusOf(server)))
          .fontSize(META).fontColor(INK3).margin({ top: 4 })
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        // :213-221 — Connected 时 'N/M tools' tag
        if (this.statusOf(server).kind === 'connected') {
          Text(`${server.commonOptions.tools.filter((t: McpTool): boolean => t.enable).length}/${server.commonOptions.tools.length} tools`)
            .fontSize(10).fontColor(ACCENT)
            .padding({ left: 8, right: 8, top: 2, bottom: 2 })
            .backgroundColor(SURFACE)
            .border({ width: 1, color: ACCENT })
            .borderRadius(8).margin({ top: 4 })
        }
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 14 })
      Toggle({
        type: ToggleType.Switch,
        isOn: this.assistant !== null && this.assistant.mcpServers.indexOf(server.id) >= 0,
      })
        .selectedColor(ACCENT)
        .onChange((v: boolean): void => {
          this.toggleServer(server, v).catch((): void => {});
        })
    }
    .width('100%').padding({ left: GUTTER, right: GUTTER, top: 12, bottom: 12 })
    .backgroundColor(SURFACE).borderRadius(RADIUS)
    .border({ width: 1, color: LINE })
  }

  build() {
    Column() {
      PageTitleBar({ title: 'MCP 服务器' })

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY2).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else if (this.assistant === null) {
        Column() {
          Text('助手不存在').fontSize(BODY2).fontColor(INK3)
        }.width('100%').padding({ top: 64, bottom: 64 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          // :169 — 仅列 enable 服务器
          ForEach(this.servers.filter(
            (s: McpServerConfig): boolean => s.commonOptions.enable),
            (server: McpServerConfig) => {
              ListItem() {
                this.serverRow(server)
              }
              .margin({ bottom: 6 })
            }, (server: McpServerConfig): string => server.id)
        }
        .width('100%').layoutWeight(1)
        .padding({ left: GUTTER, right: GUTTER, top: 8 })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
