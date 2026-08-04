// SettingProviderDetailPageFull — Provider 详情页(v5 终端账本风格)
// 设计: Provider Management v5 — ScreenDetail
//   - PageHeader: ← + slug(mono, eyebrow "提供商") + 导出(描边)
//   - SegUnderline tabs: 配置 / 模型
//   - 配置 tab: Identity 行 + 协议 CfgSeg + 鉴权(label+CredField) + 端点(inline fields) + 账户(Toggle)
//   - 模型 tab: eyebrow "已启用模型" + 模型行 + 添加模型(ACCENT 实心按钮)
//
// 路由参数: providerId(从 router.getParams 读取)
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
//
// ArkUI 规则: 无 as unknown / 无对象展开 / 无 any/unknown;
//   @Prop 避免保留字; ForEach/@Builder 内不声明 const/let(UI 树优先)
import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, SIGNAL, ERROR,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
} from '../design/tokens.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface ProviderModel {
  id: string;
  name: string;
  contextWindow: number;
  capabilities: string[];
}

interface ProviderParams {
  providerId?: string;
  slug?: string;
}

@Entry
@Component
struct SettingProviderDetailPageFull {
  // —— 详情字段(扁平 @State, 保证响应式) ——
  @State slug: string = 'minimax';
  @State name: string = 'MiniMax';
  @State protocol: string = 'openai';       // openai / google / claude
  @State authMethod: string = 'api_key';     // api_key / token_plan
  @State apiKey: string = 'sk-minimax-9f3a2b7c4d8e1f6a';
  @State baseUrl: string = 'https://api.minimaxi.com/v1';
  @State connected: boolean = true;
  @State providerEnabled: boolean = true;
  @State accountBalance: boolean = false;
  @State models: ProviderModel[] = [
    { id: 'm-1', name: 'MiniMax-M1', contextWindow: 1000000, capabilities: ['tools', 'vision'] },
  ];
  @State tab: number = 0;                    // 0=配置, 1=模型
  @State apiKeyRevealed: boolean = false;

  aboutToAppear(): void {
    const rawParams: object = router.getParams() as object;
    if (rawParams !== null && rawParams !== undefined) {
      const params: ProviderParams = rawParams as ProviderParams;
      // 占位: 无论 providerId 取何值, 均展示 MiniMax 草稿数据
      if (params.slug !== undefined && params.slug.length > 0) {
        this.slug = params.slug;
      }
    }
  }

  // —— 派生/格式化 ——

  private initial(text: string): string {
    if (text.length === 0) return '?';
    return text.charAt(0).toUpperCase();
  }

  private modelCount(): number {
    return this.models.length;
  }

  private formatCtx(ctx: number): string {
    if (ctx >= 1000000) {
      const m: number = ctx / 1000000;
      if (m === Math.floor(m)) {
        return `${m}M ctx`;
      }
      return `${m.toFixed(1)}M ctx`;
    }
    if (ctx >= 1000) {
      return `${Math.round(ctx / 1000)}K ctx`;
    }
    return `${ctx} ctx`;
  }

  // —— 操作 ——
  private setProtocol(v: string): void { this.protocol = v; }
  private setAuthMethod(v: string): void { this.authMethod = v; }
  private setName(v: string): void { this.name = v; }
  private setProviderEnabled(v: boolean): void { this.providerEnabled = v; }
  private setAccountBalance(v: boolean): void { this.accountBalance = v; }

  private toggleKeyReveal(): void {
    this.apiKeyRevealed = !this.apiKeyRevealed;
  }

  private copyApiKey(): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.apiKey));
    promptAction.showToast({ message: '已复制 API Key', duration: 2000 });
  }

  private exportConfig(): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.baseUrl));
    promptAction.showToast({ message: '已导出端点地址', duration: 2000 });
  }

  private addModel(): void {
    promptAction.showToast({ message: '添加模型', duration: 1500 });
  }

  // —— @Builder 原语 ——

  // Eyebrow: // ACCENT + 标签(mono UPPERCASE INK3) + · 计数(INK4) + 向右延伸的发丝线
  @Builder eyebrowLine(label: string, count: string): void {
    Row() {
      Text('//')
        .fontSize(10)
        .fontColor(ACCENT)
        .fontWeight(FontWeight.Bold)
        .fontFamily('monospace')
      Text(' ' + label.toUpperCase())
        .fontSize(10)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK3)
        .fontFamily('monospace')
      if (count.length > 0) {
        Text(' · ' + count)
          .fontSize(10)
          .fontColor(INK4)
          .fontFamily('monospace')
      }
      Column()
        .height(1)
        .backgroundColor(LINE)
        .layoutWeight(1)
        .margin({ left: 8 })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ top: 16, bottom: 9 })
  }

  // Monogram: 圆角方块 + 首字母(mono 9/700)
  @Builder monogramBox(initial: string, onState: boolean, boxSize: number): void {
    Stack() {
      Text(initial)
        .fontSize(9)
        .fontWeight(FontWeight.Bold)
        .fontColor(onState ? INK : INK4)
        .fontFamily('monospace')
    }
    .width(boxSize)
    .height(boxSize)
    .borderRadius(8)
    .backgroundColor(SURFACE2)
    .border({ width: 1, color: LINE })
  }

  // 字段小标签(mono UPPERCASE INK4)
  @Builder fieldLabel(text: string): void {
    Text(text.toUpperCase())
      .fontSize(9.5)
      .fontWeight(FontWeight.Medium)
      .fontColor(INK4)
      .fontFamily('monospace')
      .margin({ top: 12, bottom: 8 })
  }

  // 协议分段(openai/google/claude, 3 等分)
  @Builder protocolSeg(): void {
    Row() {
      Column() {
        Text('openai')
          .fontSize(11)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.protocol === 'openai' ? ACCENT : INK3)
          .fontFamily('monospace')
      }
      .layoutWeight(1)
      .padding({ top: 9, bottom: 9, left: 6, right: 6 })
      .backgroundColor(this.protocol === 'openai' ? ACCENT_LIGHT : BG)
      .border({ width: 1, color: this.protocol === 'openai' ? ACCENT : LINE })
      .borderRadius(7)
      .onClick((): void => { this.setProtocol('openai'); })

      Column() {
        Text('google')
          .fontSize(11)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.protocol === 'google' ? ACCENT : INK3)
          .fontFamily('monospace')
      }
      .layoutWeight(1)
      .margin({ left: 6 })
      .padding({ top: 9, bottom: 9, left: 6, right: 6 })
      .backgroundColor(this.protocol === 'google' ? ACCENT_LIGHT : BG)
      .border({ width: 1, color: this.protocol === 'google' ? ACCENT : LINE })
      .borderRadius(7)
      .onClick((): void => { this.setProtocol('google'); })

      Column() {
        Text('claude')
          .fontSize(11)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.protocol === 'claude' ? ACCENT : INK3)
          .fontFamily('monospace')
      }
      .layoutWeight(1)
      .margin({ left: 6 })
      .padding({ top: 9, bottom: 9, left: 6, right: 6 })
      .backgroundColor(this.protocol === 'claude' ? ACCENT_LIGHT : BG)
      .border({ width: 1, color: this.protocol === 'claude' ? ACCENT : LINE })
      .borderRadius(7)
      .onClick((): void => { this.setProtocol('claude'); })
    }
    .width('100%')
  }

  // 鉴权分段(api_key/token_plan, 2 等分)
  @Builder authSeg(): void {
    Row() {
      Column() {
        Text('api_key')
          .fontSize(11)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.authMethod === 'api_key' ? ACCENT : INK3)
          .fontFamily('monospace')
      }
      .layoutWeight(1)
      .padding({ top: 9, bottom: 9, left: 6, right: 6 })
      .backgroundColor(this.authMethod === 'api_key' ? ACCENT_LIGHT : BG)
      .border({ width: 1, color: this.authMethod === 'api_key' ? ACCENT : LINE })
      .borderRadius(7)
      .onClick((): void => { this.setAuthMethod('api_key'); })

      Column() {
        Text('token_plan')
          .fontSize(11)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.authMethod === 'token_plan' ? ACCENT : INK3)
          .fontFamily('monospace')
      }
      .layoutWeight(1)
      .margin({ left: 6 })
      .padding({ top: 9, bottom: 9, left: 6, right: 6 })
      .backgroundColor(this.authMethod === 'token_plan' ? ACCENT_LIGHT : BG)
      .border({ width: 1, color: this.authMethod === 'token_plan' ? ACCENT : LINE })
      .borderRadius(7)
      .onClick((): void => { this.setAuthMethod('token_plan'); })
    }
    .width('100%')
  }

  // 凭据字段: › 前缀 + •••• 掩码 + 👁 切换 + 📋 复制
  @Builder credField(): void {
    Row() {
      Text('›')
        .fontSize(11)
        .fontColor(INK3)
        .fontFamily('monospace')
      Text(this.apiKeyRevealed ? this.apiKey : '••••••••••••••••')
        .fontSize(11.5)
        .fontColor(INK)
        .fontFamily('monospace')
        .layoutWeight(1)
        .margin({ left: 8 })
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text(this.apiKeyRevealed ? '🙈' : '👁')
        .fontSize(13)
        .fontColor(INK3)
        .margin({ left: 10 })
        .onClick((): void => { this.toggleKeyReveal(); })
      Text('📋')
        .fontSize(13)
        .fontColor(INK3)
        .margin({ left: 10 })
        .onClick((): void => { this.copyApiKey(); })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ top: 11, bottom: 11 })
    .border({ width: { top: 1 }, color: { top: LINE } })
  }

  // 内联字段标签行(label 左, 值右, 顶部发丝线)——值以 @BuilderParam 注入不支持,
  //   故各字段直接内联(见 configTab 中 display_name / base_url / account_balance)

  // —— 配置 tab 内容 ——
  @Builder configTab(): void {
    Scroll() {
      Column() {
        // Identity 行
        Row() {
          this.monogramBox(this.initial(this.name), this.providerEnabled, 40)
          Column() {
            Text(this.name)
              .fontSize(16)
              .fontWeight(FontWeight.Bold)
              .fontColor(INK)
            Row() {
              Circle({ width: 5, height: 5 })
                .fill(this.connected ? SIGNAL : INK4)
              Text((this.connected ? '已连接' : '未连接') + ' · 令牌 · ' + `${this.modelCount()}` + ' 个模型')
                .fontSize(10.5)
                .fontColor(INK3)
                .fontFamily('monospace')
                .margin({ left: 6 })
            }
            .margin({ top: 4 })
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)
          .margin({ left: 12 })
          AmberSwitch({
            checked: this.providerEnabled,
            switchSize: SwitchSize.Small,
            onCheckedChange: (v: boolean): void => { this.setProviderEnabled(v); },
          })
        }
        .width('100%')
        .padding({ top: 14, left: 2, right: 2, bottom: 0 })
        .border({ width: { bottom: 1 }, color: { bottom: LINE } })

        // 协议
        this.eyebrowLine('协议', '')
        this.protocolSeg()

        // 鉴权
        this.eyebrowLine('鉴权', '')
        this.fieldLabel('auth_method')
        this.authSeg()
        this.fieldLabel('api_key')
        this.credField()

        // 端点
        this.eyebrowLine('端点', '')
        Row() {
          Text('display_name')
            .fontSize(10)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK4)
            .fontFamily('monospace')
          TextInput({ text: this.name })
            .fontSize(13.5)
            .fontColor(INK)
            .backgroundColor(BG)
            .borderRadius(0)
            .layoutWeight(1)
            .margin({ left: 12 })
            .textAlign(TextAlign.End)
            .onChange((v: string): void => { this.setName(v); })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .padding({ top: 11, bottom: 11 })
        .border({ width: { top: 1 }, color: { top: LINE } })

        Row() {
          Text('base_url')
            .fontSize(10)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK4)
            .fontFamily('monospace')
          Text('› ' + this.baseUrl)
            .fontSize(11)
            .fontColor(INK)
            .fontFamily('monospace')
            .layoutWeight(1)
            .margin({ left: 12 })
            .textAlign(TextAlign.End)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .padding({ top: 11, bottom: 11 })
        .border({ width: { top: 1 }, color: { top: LINE } })

        // 账户
        this.eyebrowLine('账户', '')
        Row() {
          Text('account_balance')
            .fontSize(10)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK4)
            .fontFamily('monospace')
          Blank()
          AmberSwitch({
            checked: this.accountBalance,
            switchSize: SwitchSize.Small,
            onCheckedChange: (v: boolean): void => { this.setAccountBalance(v); },
          })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .padding({ top: 11, bottom: 11 })
        .border({ width: { top: 1 }, color: { top: LINE } })

        Column().height(28)
      }
      .width('100%')
      .padding({ left: 18, right: 18, top: 2, bottom: 28 })
    }
    .layoutWeight(1)
    .scrollBar(BarState.Off)
    .align(Alignment.TopStart)
  }

  // —— 模型 tab 内容 ——
  @Builder modelsTab(): void {
    Scroll() {
      Column() {
        this.eyebrowLine('已启用模型', `${this.modelCount()}`)
        ForEach(this.models, (m: ProviderModel): void => {
          Row() {
            this.monogramBox(this.initial(m.name), true, 28)
            Column() {
              Text(m.name)
                .fontSize(12)
                .fontWeight(FontWeight.Medium)
                .fontColor(INK)
                .fontFamily('monospace')
              Row() {
                ForEach(m.capabilities, (cap: string): void => {
                  Text(cap)
                    .fontSize(9)
                    .fontColor(INK3)
                    .fontFamily('monospace')
                    .backgroundColor(SURFACE2)
                    .borderRadius(3)
                    .padding({ left: 4, right: 4, top: 1, bottom: 1 })
                    .margin({ right: 4 })
                }, (cap: string): string => cap)
              }
              .margin({ top: 4 })
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)
            .margin({ left: 10 })

            Text(this.formatCtx(m.contextWindow))
              .fontSize(10)
              .fontColor(INK4)
              .fontFamily('monospace')
            Text('›')
              .fontSize(14)
              .fontColor(INK4)
              .margin({ left: 10 })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .padding({ top: 11, right: 12, bottom: 11, left: 10 })
          .border({ width: { top: 1 }, color: { top: LINE } })
        }, (m: ProviderModel): string => m.id)

        // 添加模型按钮(ACCENT 实心, 全宽)
        Row() {
          Text('+ 添加模型')
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .fontColor(ACCENT_INK)
        }
        .width('100%')
        .height(46)
        .justifyContent(FlexAlign.Center)
        .backgroundColor(ACCENT)
        .borderRadius(12)
        .margin({ top: 18 })
        .onClick((): void => { this.addModel(); })

        Column().height(28)
      }
      .width('100%')
      .padding({ left: 18, right: 18, top: 2, bottom: 28 })
    }
    .layoutWeight(1)
    .scrollBar(BarState.Off)
    .align(Alignment.TopStart)
  }

  build(): void {
    Column() {
      // —— PageHeader ——
      Row() {
        Text('←')
          .fontSize(24)
          .fontColor(INK)
          .onClick((): void => { router.back(); })
        Column() {
          Text('提供商')
            .fontSize(10.5)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK3)
            .fontFamily('monospace')
          Text(this.slug)
            .fontSize(13.5)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK3)
            .fontFamily('monospace')
            .margin({ top: 1 })
        }
        .alignItems(HorizontalAlign.Start)
        .margin({ left: 8 })
        .layoutWeight(1)
        Text('导出')
          .fontSize(10)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK3)
          .fontFamily('monospace')
          .border({ width: 1, color: LINE2 })
          .borderRadius(3)
          .padding({ left: 9, right: 9, top: 4, bottom: 4 })
          .onClick((): void => { this.exportConfig(); })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      // —— SegUnderline tabs ——
      Row() {
        Column() {
          Text('配置')
            .fontSize(11)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.tab === 0 ? INK : INK3)
            .fontFamily('monospace')
            .padding({ top: 10, bottom: 8 })
          Column()
            .height(2)
            .width('100%')
            .backgroundColor(this.tab === 0 ? ACCENT : 'rgba(0,0,0,0)')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .onClick((): void => { this.tab = 0; })

        Column() {
          Text('模型')
            .fontSize(11)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.tab === 1 ? INK : INK3)
            .fontFamily('monospace')
            .padding({ top: 10, bottom: 8 })
          Column()
            .height(2)
            .width('100%')
            .backgroundColor(this.tab === 1 ? ACCENT : 'rgba(0,0,0,0)')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .onClick((): void => { this.tab = 1; })
      }
      .width('100%')
      .padding({ left: 18, right: 18 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      // —— Tab 内容 ——
      if (this.tab === 0) {
        this.configTab()
      } else {
        this.modelsTab()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }
}
