// SettingMcpPageFull — MCP 服务器管理页(G5-S4a)
// 本页为设置面自治态:服务器列表 / 增删改 / 测试连接 / 工具展开
// 持久化:占位本地草稿(后续接入 McpSettingsStore 后替换 load/save)
// 依赖:PillSelect / FlatTextField / ConfirmDialog
import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { ConfirmDialog } from '../components/ui/ConfirmDialog.ets';

type TransportKind = 'stdio' | 'sse';

interface McpToolInfo {
  name: string;
  description: string;
}

interface McpServerItem {
  id: string;
  name: string;
  transport: TransportKind;
  command: string; // stdio: 命令; sse: url
  args: string; // 逗号分隔参数
  connected: boolean;
  testing: boolean;
  expanded: boolean;
  tools: McpToolInfo[];
}

function makeServer(
  id: string, name: string, transport: TransportKind,
  command: string, args: string, connected: boolean, tools: McpToolInfo[],
): McpServerItem {
  return {
    id: id, name: name, transport: transport, command: command, args: args,
    connected: connected, testing: false, expanded: false, tools: tools,
  };
}

function makeTool(name: string, description: string): McpToolInfo {
  return { name: name, description: description };
}

@Entry
@Component
struct SettingMcpPageFull {
  @State loaded: boolean = false;
  @State servers: McpServerItem[] = [];
  // 编辑态:'' = 未编辑具体项;editingId 非空 = 编辑该服务器
  @State editingId: string = '';
  // 新增态:showAddForm 为 true 时底部展开新增表单
  @State showAddForm: boolean = false;
  // 内联表单态(新增与编辑共用)
  @State formName: string = '';
  @State formTransportIndex: number = 0; // 0 stdio / 1 sse
  @State formCommand: string = '';
  @State formArgs: string = '';
  // 删除确认
  @State confirmShow: boolean = false;
  @State pendingDeleteId: string = '';
  private counter: number = 0;
  private transportOptions: string[] = ['stdio', 'sse'];

  aboutToAppear(): void {
    this.servers = [
      makeServer('s-1', 'filesystem', 'stdio', 'npx', '-y,@modelcontextprotocol/server-filesystem,/tmp',
        true, [makeTool('read_file', '读取文件内容'), makeTool('write_file', '写入文件内容')]),
      makeServer('s-2', 'fetch', 'sse', 'http://localhost:8080/sse', '',
        false, [makeTool('fetch', '抓取 URL 内容')]),
    ];
    this.counter = 2;
    this.loaded = true;
  }

  private nextId(): string {
    this.counter += 1;
    return `s-${this.counter}`;
  }

  private transportOf(index: number): TransportKind {
    return index === 1 ? 'sse' : 'stdio';
  }

  private resetForm(): void {
    this.editingId = '';
    this.showAddForm = false;
    this.formName = '';
    this.formTransportIndex = 0;
    this.formCommand = '';
    this.formArgs = '';
  }

  private beginAdd(): void {
    this.resetForm();
    this.showAddForm = true;
  }

  private beginEdit(id: string): void {
    const s: McpServerItem | undefined = this.servers.find((x: McpServerItem): boolean => x.id === id);
    if (s === undefined) return;
    this.resetForm();
    this.editingId = id;
    this.formName = s.name;
    this.formTransportIndex = s.transport === 'sse' ? 1 : 0;
    this.formCommand = s.command;
    this.formArgs = s.args;
  }

  private cancelForm(): void {
    this.resetForm();
  }

  private applyForm(): void {
    const name: string = this.formName.trim();
    if (name.length === 0) {
      promptAction.showToast({ message: '请填写服务器名称', duration: 2000 });
      return;
    }
    const cmd: string = this.formCommand.trim();
    if (cmd.length === 0) {
      promptAction.showToast({ message: '请填写命令 / URL', duration: 2000 });
      return;
    }
    const transport: TransportKind = this.transportOf(this.formTransportIndex);
    if (this.showAddForm) {
      const id: string = this.nextId();
      const fresh: McpServerItem = makeServer(id, name, transport, cmd, this.formArgs, false, []);
      this.servers = [...this.servers, fresh];
      promptAction.showToast({ message: '已添加', duration: 1500 });
    } else if (this.editingId.length > 0) {
      this.servers = this.servers.map((s: McpServerItem): McpServerItem => {
        if (s.id !== this.editingId) return s;
        return makeServer(s.id, name, transport, cmd, this.formArgs, s.connected, s.tools);
      });
      promptAction.showToast({ message: '已保存', duration: 1500 });
    }
    this.resetForm();
  }

  private askDelete(id: string): void {
    this.pendingDeleteId = id;
    this.confirmShow = true;
  }

  private confirmDelete(): void {
    const id: string = this.pendingDeleteId;
    this.confirmShow = false;
    this.pendingDeleteId = '';
    if (id.length === 0) return;
    this.servers = this.servers.filter((s: McpServerItem): boolean => s.id !== id);
    if (this.editingId === id) this.resetForm();
  }

  private cancelDelete(): void {
    this.confirmShow = false;
    this.pendingDeleteId = '';
  }

  private testConnection(id: string): void {
    // 标记 testing
    this.servers = this.servers.map((s: McpServerItem): McpServerItem => {
      if (s.id !== id) return s;
      const copy: McpServerItem = makeServer(s.id, s.name, s.transport, s.command, s.args, s.connected, s.tools);
      copy.testing = true;
      copy.expanded = s.expanded;
      return copy;
    });
    setTimeout((): void => {
      this.servers = this.servers.map((s: McpServerItem): McpServerItem => {
        if (s.id !== id) return s;
        const ok: boolean = s.command.length > 0;
        const copy: McpServerItem = makeServer(s.id, s.name, s.transport, s.command, s.args, ok,
          ok ? [makeTool('ping', '连接测试占位工具')] : []);
        copy.testing = false;
        copy.expanded = ok;
        return copy;
      });
      promptAction.showToast({ message: '测试完成', duration: 1500 });
    }, 800);
  }

  private toggleExpand(id: string): void {
    this.servers = this.servers.map((s: McpServerItem): McpServerItem => {
      if (s.id !== id) return s;
      const copy: McpServerItem = makeServer(s.id, s.name, s.transport, s.command, s.args, s.connected, s.tools);
      copy.testing = s.testing;
      copy.expanded = !s.expanded;
      return copy;
    });
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder statusBadge(connected: boolean): void {
    Row() {
      Text(connected ? '●' : '○').fontSize(META)
        .fontColor(connected ? '#5E9C6E' : INK4)
      Text(connected ? '已连接' : '未连接')
        .fontSize(LABEL).fontColor(connected ? '#5E9C6E' : INK4)
        .margin({ left: 4 })
    }
    .padding({ left: 8, right: 8, top: 3, bottom: 3 })
    .backgroundColor(connected ? '#EEF7F0' : SURFACE2)
    .borderRadius(50)
  }

  // 表单:新增与编辑共用(editingId 非空 = 编辑;showAddForm = 新增)
  @Builder formBlock(): void {
    Column() {
      Text(this.editingId.length > 0 ? '编辑服务器' : '新增服务器')
        .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .margin({ bottom: 10 })

      FlatTextField({
        value: this.formName, label: '名称', placeholder: 'filesystem',
        onChange: (v: string): void => { this.formName = v; },
      })
      Row() {
        Text('传输方式').fontSize(META).fontColor(INK3).layoutWeight(1)
        PillSelect({
          options: this.transportOptions,
          selectedIndex: this.formTransportIndex,
          onSelect: (i: number): void => { this.formTransportIndex = i; },
        })
      }
      .width('100%').margin({ top: 10 })

      FlatTextField({
        value: this.formCommand,
        label: this.formTransportIndex === 1 ? 'URL' : '命令',
        placeholder: this.formTransportIndex === 1 ? 'http://host:port/sse' : 'npx',
        onChange: (v: string): void => { this.formCommand = v; },
      }).margin({ top: 10 })

      if (this.formTransportIndex === 0) {
        FlatTextField({
          value: this.formArgs, label: '参数(逗号分隔)', placeholder: '-y,@mcp/server-filesystem,/tmp',
          onChange: (v: string): void => { this.formArgs = v; },
        }).margin({ top: 10 })
      }

      Row() {
        Text('取消').fontSize(BODY).fontColor(INK3)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .backgroundColor(SURFACE2).borderRadius(50)
          .onClick((): void => { this.cancelForm(); })
        Blank()
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .backgroundColor(ACCENT_LIGHT).borderRadius(50)
          .border({ width: 1, color: ACCENT })
          .onClick((): void => { this.applyForm(); })
      }
      .width('100%').margin({ top: 14 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(12).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: ACCENT }).margin({ left: 16, right: 16, bottom: 12 })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('MCP 服务器').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        if (!this.showAddForm && this.editingId.length === 0) {
          Text('+ 添加').fontSize(BODY).fontColor(ACCENT)
            .onClick((): void => { this.beginAdd(); })
        }
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('服务器列表')

            if (this.servers.length === 0 && !this.showAddForm) {
              // 空状态
              Column() {
                Text('暂无 MCP 服务器').fontSize(BODY).fontColor(INK3)
                Text('点击下方按钮开始配置').fontSize(LABEL).fontColor(INK4).margin({ top: 4 })
                Text('+ 添加服务器').fontSize(BODY).fontColor(ACCENT)
                  .padding({ left: 16, right: 16, top: 10, bottom: 10 })
                  .backgroundColor(ACCENT_LIGHT).borderRadius(50)
                  .border({ width: 1, color: ACCENT })
                  .margin({ top: 14 })
                  .onClick((): void => { this.beginAdd(); })
              }
              .width('100%').alignItems(HorizontalAlign.Center)
              .padding({ top: 32, bottom: 32 })
            }

            ForEach(this.servers, (s: McpServerItem): void => {
              Column() {
                Row() {
                  Column() {
                    Text(s.name.length > 0 ? s.name : '未命名')
                      .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                    Text(s.transport)
                      .fontSize(LABEL).fontColor(INK4).margin({ top: 2 })
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)
                  this.statusBadge(s.connected)
                }
                .width('100%')

                Row() {
                  Text('编辑').fontSize(META).fontColor(INK2)
                    .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                    .backgroundColor(SURFACE2).borderRadius(50)
                    .onClick((): void => { this.beginEdit(s.id); })
                  Text(s.testing ? '测试中…' : '测试')
                    .fontSize(META).fontColor(ACCENT)
                    .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                    .backgroundColor(ACCENT_LIGHT).borderRadius(50)
                    .margin({ left: 8 })
                    .onClick((): void => { this.testConnection(s.id); })
                  Blank()
                  Text(s.expanded ? '收起 ▲' : `工具 (${s.tools.length}) ▼`)
                    .fontSize(META).fontColor(INK3)
                    .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                    .onClick((): void => { this.toggleExpand(s.id); })
                  Text('删除').fontSize(META).fontColor('#C93A2F')
                    .padding({ left: 10 })
                    .onClick((): void => { this.askDelete(s.id); })
                }
                .width('100%').margin({ top: 10 })

                if (s.expanded) {
                  Column() {
                    if (s.tools.length === 0) {
                      Text('无可用工具').fontSize(LABEL).fontColor(INK4)
                    } else {
                      ForEach(s.tools, (t: McpToolInfo): void => {
                        Row() {
                          Text(t.name).fontSize(META).fontColor(ACCENT)
                          Text(t.description).fontSize(META).fontColor(INK3)
                            .margin({ left: 10 }).layoutWeight(1)
                            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                        }
                        .width('100%').padding({ top: 6, bottom: 6 })
                        .border({ width: { top: 0.5 }, color: { top: LINE } })
                      }, (t: McpToolInfo): string => t.name)
                    }
                  }
                  .width('100%').padding({ top: 4, bottom: 4 })
                }
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding(12).backgroundColor(SURFACE).borderRadius(12)
              .border({ width: 1, color: LINE }).margin({ left: 16, right: 16, bottom: 10 })

              // 编辑表单紧跟在所编辑的卡之后
              if (this.editingId === s.id) {
                this.formBlock()
              }
            }, (s: McpServerItem): string => s.id)

            // 新增表单:底部展开
            if (this.showAddForm) {
              this.formBlock()
            } else if (this.servers.length > 0) {
              Row() {
                Text('+ 添加服务器').fontSize(BODY).fontColor(ACCENT)
                  .padding({ left: 16, right: 16, top: 10, bottom: 10 })
                  .backgroundColor(ACCENT_LIGHT).borderRadius(50)
                  .border({ width: 1, color: ACCENT })
                  .onClick((): void => { this.beginAdd(); })
              }
              .width('100%').justifyContent(FlexAlign.Center).margin({ top: 4, bottom: 8 })
            }
            Column() {}.height(24)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }

      // 删除确认对话框
      ConfirmDialog({
        show: this.confirmShow,
        title: '删除服务器',
        message: '确定删除该 MCP 服务器?该操作不可撤销。',
        confirmText: '删除',
        dismissText: '取消',
        onConfirm: (): void => { this.confirmDelete(); },
        onDismiss: (): void => { this.cancelDelete(); },
      })
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
