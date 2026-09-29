// ProviderPrimitives — Provider UI 原语(G6-S2)
// Android 基准: ProviderRedesignPrimitives.kt(733 行)
// 必要组件:
//   ProviderCardV2          — 渐变头部卡片(类型色)+ 名称 + baseUrl + 模型数 + 启用开关 + 编辑/删除
//   ProviderTypeBadge       — 类型徽章(OpenAI=绿 / Claude=橙 / Google=蓝 / DeepSeek=紫 / Custom=灰)
//   ProviderStatusIndicator — 连接状态点(connected 绿 / disconnected 灰 / testing 琥珀脉冲 / error 红)
//   ProviderModelCountBadge — "N 模型" 胶囊
//   ProviderUrlPreview      — 截断 baseUrl + 复制按钮
//
// 设计 token:见 design/tokens.ets(SURFACE/LINE/INK/SIGNAL/ERROR 系)
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;@Prop 避开 size/enabled/Select/onClick;
//   ForEach/@Builder 内不出现 const/let(UI 树优先);颜色/标签查找用 private 方法(对齐 Tag.ets)
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT,
  SIGNAL, ERROR, BODY, META, LABEL,
} from '../../design/tokens.ets';
import { AmberSwitch, SwitchSize } from './Switch.ets';

// Provider 类型色常量(对齐 Android ProviderTypeBadge 配色)
const COLOR_OPENAI: string = '#3FA768';
const COLOR_OPENAI_DARK: string = '#2D8450';
const COLOR_CLAUDE: string = '#D98324';
const COLOR_CLAUDE_DARK: string = '#B36A18';
const COLOR_GOOGLE: string = '#3B7DDD';
const COLOR_GOOGLE_DARK: string = '#2E66B0';
const COLOR_DEEPSEEK: string = '#7C5CBF';
const COLOR_DEEPSEEK_DARK: string = '#6347A0';
const COLOR_CUSTOM: string = '#8F8B80';
const COLOR_CUSTOM_DARK: string = '#736F66';

// 连接状态常量(供 ProviderStatusIndicator / ProviderCardV2 共用)
const STATUS_CONNECTED: string = 'connected';
const STATUS_DISCONNECTED: string = 'disconnected';
const STATUS_TESTING: string = 'testing';
const STATUS_ERROR: string = 'error';

// ProviderTypeBadge — 类型徽章(类型色背景 + 白字)
//   OpenAI=绿 / Claude=橙 / Google=蓝 / DeepSeek=紫 / Custom=灰
@Component
export struct ProviderTypeBadge {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop providerType: string = 'custom';

  private badgeColor(): string {
    const t: string = this.providerType.toLowerCase();
    if (t === 'openai') return COLOR_OPENAI;
    if (t === 'claude') return COLOR_CLAUDE;
    if (t === 'google') return COLOR_GOOGLE;
    if (t === 'deepseek') return COLOR_DEEPSEEK;
    return COLOR_CUSTOM;
  }

  private badgeLabel(): string {
    const t: string = this.providerType.toLowerCase();
    if (t === 'openai') return 'OpenAI';
    if (t === 'claude') return 'Claude';
    if (t === 'google') return 'Google';
    if (t === 'deepseek') return 'DeepSeek';
    if (t === 'zhipu') return '智谱';
    if (t === 'azure') return 'Azure';
    if (t === 'ollama') return 'Ollama';
    return '自定义';
  }

  build(): void {
    Text(this.badgeLabel())
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(LABEL)
      .fontWeight(FontWeight.Medium)
      .fontColor('#FFFFFF')
      .backgroundColor(this.badgeColor())
      .borderRadius(50)
      .padding({ left: 8, right: 8, top: 2, bottom: 2 })
      .maxLines(1)
  }
}

// ProviderStatusIndicator — 连接状态点
//   connected=绿(SIGNAL) / disconnected=灰(INK4) / testing=琥珀脉冲 / error=红(ERROR)
//   脉冲:aboutToAppear 启动 iterations:-1 动画,halo 仅 testing 状态渲染(对齐 RunningIndicator)
@Component
export struct ProviderStatusIndicator {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop status: string = STATUS_DISCONNECTED;
  @Prop dotSize: number = 8;
  @State pulseAlpha: number = 0.5;

  aboutToAppear(): void {
    // 900ms 循环:alpha 0.5→0.1(RepeatMode.Restart,对齐 ChatListIndicators)
    animateTo({
      duration: 900,
      iterations: -1,
      curve: Curve.Linear,
      playMode: PlayMode.Normal,
    }, (): void => {
      this.pulseAlpha = 0.1;
    });
  }

  private dotColor(): string {
    if (this.status === STATUS_CONNECTED) return SIGNAL;
    if (this.status === STATUS_TESTING) return '#E0A030';
    if (this.status === STATUS_ERROR) return ERROR;
    return INK4;
  }

  build(): void {
    Stack({ alignContent: Alignment.Center }) {
      // 脉冲 halo(仅 testing 状态渲染;动画自 appear 起持续运行)
      if (this.status === STATUS_TESTING) {
        Text('')
          .width(this.dotSize * 2.2)
          .height(this.dotSize * 2.2)
          .borderRadius(this.dotSize * 1.1)
          .backgroundColor(this.dotColor())
          .opacity(this.pulseAlpha)
      }
      // 实心点
      Text('')
        .width(this.dotSize)
        .height(this.dotSize)
        .borderRadius(this.dotSize / 2)
        .backgroundColor(this.dotColor())
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(this.dotSize * 2.4)
    .height(this.dotSize * 2.4)
  }
}

// ProviderModelCountBadge — "N 模型" 胶囊
//   count > 0:ACCENT 字 + ACCENT_LIGHT 底;count = 0:INK4 字 + SURFACE2 底(弱化)
@Component
export struct ProviderModelCountBadge {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop modelCount: number = 0;

  build(): void {
    Text(`${this.modelCount} 模型`)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(LABEL)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.modelCount > 0 ? ACCENT : INK4)
      .backgroundColor(this.modelCount > 0 ? ACCENT_LIGHT : SURFACE2)
      .borderRadius(50)
      .padding({ left: 8, right: 8, top: 3, bottom: 3 })
      .maxLines(1)
  }
}

// ProviderUrlPreview — 截断 baseUrl + 复制按钮
//   仅显示 host(去 scheme/path,降低视觉噪音);复制完整 baseUrl 到剪贴板
@Component
export struct ProviderUrlPreview {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop baseUrl: string = '';

  // baseUrl 仅显示 host(去掉 scheme 与 path)
  private hostPreview(): string {
    const raw: string = this.baseUrl.trim();
    if (raw.length === 0) return '未配置 Base URL';
    let rest: string = raw;
    if (rest.startsWith('https://')) {
      rest = rest.substring(8);
    } else if (rest.startsWith('http://')) {
      rest = rest.substring(7);
    }
    const slash: number = rest.indexOf('/');
    if (slash >= 0) rest = rest.substring(0, slash);
    return rest.length > 0 ? rest : raw;
  }

  private copyUrl(): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.baseUrl));
    promptAction.showToast({ message: '已复制 Base URL', duration: 2000 });
  }

  build(): void {
    Row() {
      Text(this.hostPreview())
        .fontSize(META)
        .fontColor(INK3)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)

      Text('复制')
        .fontSize(LABEL)
        .fontColor(INK3)
        .padding({ left: 8, right: 4, top: 4, bottom: 4 })
        .onClick((): void => { this.copyUrl(); })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(VerticalAlign.Center)
  }
}

// ProviderCardV2 — 渐变头部卡片
//   头部:类型色渐变(135°) + 名称 + 类型徽章 + 状态指示
//   主体:baseUrl 预览(可复制)+ 模型计数 + 启用开关 + 编辑/删除按钮
//   回调命名避开 onClick 保留字:onEdit / onDelete / onToggle
@Component
export struct ProviderCardV2 {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop providerName: string = '';
  @Prop providerType: string = 'custom';
  @Prop baseUrl: string = '';
  @Prop modelCount: number = 0;
  @Prop providerEnabled: boolean = false;
  @Prop status: string = STATUS_DISCONNECTED;
  onEdit: () => void = (): void => {};
  onDelete: () => void = (): void => {};
  onToggle: (next: boolean) => void = (_n: boolean): void => {};

  // 类型 → 主色
  private typeColor(): string {
    const t: string = this.providerType.toLowerCase();
    if (t === 'openai') return COLOR_OPENAI;
    if (t === 'claude') return COLOR_CLAUDE;
    if (t === 'google') return COLOR_GOOGLE;
    if (t === 'deepseek') return COLOR_DEEPSEEK;
    return COLOR_CUSTOM;
  }

  // 类型 → 深色(渐变末端)
  private typeColorDark(): string {
    const t: string = this.providerType.toLowerCase();
    if (t === 'openai') return COLOR_OPENAI_DARK;
    if (t === 'claude') return COLOR_CLAUDE_DARK;
    if (t === 'google') return COLOR_GOOGLE_DARK;
    if (t === 'deepseek') return COLOR_DEEPSEEK_DARK;
    return COLOR_CUSTOM_DARK;
  }

  build(): void {
    Column() {
      // 渐变头部:名称 + 类型徽章 + 状态点
      Row() {
        Text(this.providerName.length > 0 ? this.providerName : '未命名 Provider')
          .fontSize(BODY)
          .fontWeight(FontWeight.Medium)
          .fontColor('#FFFFFF')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)

        ProviderTypeBadge({ providerType: this.providerType })

        ProviderStatusIndicator({ status: this.status, dotSize: 7 })
          .margin({ left: 8 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
      .linearGradient({
        angle: 135,
        colors: [[this.typeColor(), 0], [this.typeColorDark(), 1]],
      })
      .alignItems(VerticalAlign.Center)

      // 主体:baseUrl 预览 + 底部操作行
      Column() {
        ProviderUrlPreview({ baseUrl: this.baseUrl })

        Row() {
          ProviderModelCountBadge({ modelCount: this.modelCount })

          Blank().layoutWeight(1)

          // 启用开关(独立点击,不触发卡片点击)
          AmberSwitch({
            checked: this.providerEnabled,
            switchSize: SwitchSize.Small,
            onCheckedChange: (next: boolean): void => { this.onToggle(next); },
          })
            .margin({ right: 4 })

          // 编辑按钮
          Text('编辑')
            .fontSize(META)
            .fontColor(INK2)
            .padding({ left: 10, right: 6, top: 6, bottom: 6 })
            .onClick((): void => { this.onEdit(); })

          // 删除按钮
          Text('删除')
            .fontSize(META)
            .fontColor(ERROR)
            .padding({ left: 6, right: 4, top: 6, bottom: 6 })
            .onClick((): void => { this.onDelete(); })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .margin({ top: 10 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 10, bottom: 12 })
      .alignItems(HorizontalAlign.Start)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .backgroundColor(SURFACE)
    .borderRadius(14)
    .border({ width: 1, color: LINE })
    .clip(true)
  }
}
