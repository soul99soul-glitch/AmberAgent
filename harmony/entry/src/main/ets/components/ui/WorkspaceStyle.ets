// WorkspaceStyle — 工作区设计系统原语族(ui/WorkspaceStyle.kt:354 行)
// Android 基准: WorkspaceColors + WorkspaceTone + TopBar/IconButton/TextButton/StatusPill/Divider/LeadingIcon
// ArkUI: 复用既有 design tokens(INK/INK2/INK3/INK4/SURFACE/SURFACE2/LINE/ACCENT/SIGNAL/ERROR);
//   WorkspaceTone → 颜色映射函数;组件用 ArkUI Row/Column/Surface 构建
import {
  INK3, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, SIGNAL, ERROR,
  ERROR_BG, SUCCESS_BG, SUCCESS_FG, WARNING_BG, WARNING_FG, LABEL,
} from '../../design/tokens.ets';

export type WorkspaceTone = 'Neutral' | 'Accent' | 'Success' | 'Warning' | 'Danger';

// tone → 颜色对(容器底 + 文字色)
export const toneColors = (tone: WorkspaceTone): { container: string; content: string } => {
  switch (tone) {
    case 'Accent': return { container: ACCENT_LIGHT, content: ACCENT };
    case 'Success': return { container: SUCCESS_BG, content: SUCCESS_FG };
    case 'Warning': return { container: WARNING_BG, content: WARNING_FG };
    case 'Danger': return { container: ERROR_BG, content: ERROR };
    default: return { container: SURFACE2, content: INK3 };
  }
};

@Component
export struct WorkspaceDivider {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  build(): void {
    Column()
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height(1)
      .backgroundColor(LINE)
  }
}

@Component
export struct WorkspaceStatusPill {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @Prop tone: WorkspaceTone = 'Neutral';
  build(): void {
    Text(this.text)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(LABEL)
      .fontColor(toneColors(this.tone).content)
      .padding({ left: 8, right: 8, top: 3, bottom: 3 })
      .backgroundColor(toneColors(this.tone).container)
      .borderRadius(999)
      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
  }
}

@Component
export struct WorkspaceIconButton {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop icon: string = '';
  @Prop tone: WorkspaceTone = 'Neutral';
  @Prop showBorder: boolean = true;
  @Prop btnEnabled: boolean = true;
  onClick: () => void = (): void => {};
  build(): void {
    Text(this.icon)
      
      .enabled(this.themeEpoch >= 0).fontSize(15)
      .fontColor(toneColors(this.tone).content)
      .width(40).height(40)
      .textAlign(TextAlign.Center)
      .backgroundColor(toneColors(this.tone).container)
      .borderRadius(6)
      .border({ width: this.showBorder ? 1 : 0, color: LINE })
      .opacity(this.btnEnabled ? 1 : 0.4)
      .onClick((): void => { if (this.btnEnabled) this.onClick(); })
  }
}

@Component
export struct WorkspaceTextButton {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @Prop tone: WorkspaceTone = 'Neutral';
  onClick: () => void = (): void => {};
  build(): void {
    Text(this.text)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(LABEL)
      .fontColor(toneColors(this.tone).content)
      .padding({ left: 12, right: 12, top: 6, bottom: 6 })
      .backgroundColor(toneColors(this.tone).container)
      .borderRadius(999)
      .onClick((): void => { this.onClick(); })
  }
}

@Component
export struct WorkspaceTopBar {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  onBack: () => void = (): void => {};
  @BuilderParam actions: () => void;

  build(): void {
    Row() {
      Text('←').fontSize(18).fontColor(INK3)
        .onClick((): void => { this.onBack(); })
      Text(this.title)
        .fontSize(22).fontWeight(FontWeight.Medium).fontColor(INK)
        .margin({ left: 12 }).layoutWeight(1)
      this.actions();
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height(56).padding({ left: 16, right: 16 })
  }
}
