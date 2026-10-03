// NovelSettingProposalCard — 小说讨论产生的设定提案，确认后才写入项目设定。
// 容器走 NovelApprovalShell(iOS 审批卡规格:r18+accent 描边+图标圆座头);
// 按钮三档:忽略=弱(SURFACE2),采用=主(accent 实底胶囊白字)。
import { ACCENT, ACCENT_INK, INK2, INK3, SURFACE2 } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { NovelApprovalShell } from './NovelApprovalShell.ets';

@Component
export struct NovelSettingProposalCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  @Prop content: string = '';
  @Prop kindLabel: string = '设定提案';
  @Prop status: string = 'pending';
  onResolve: (accepted: boolean) => void = (_accepted: boolean): void => {};

  @Builder
  private Body(): void {
    if (this.content.length > 0) {
      Text(this.content).fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(INK2).lineHeight(20)
        .width('100%')
    }
  }

  @Builder
  private Actions(): void {
    Row() {
      Blank()
      Text('忽略').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
        .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.Center)
        .padding({ left: 14, right: 14 }).backgroundColor(SURFACE2).borderRadius(22)
        .onClick((): void => { this.onResolve(false); })
      Text('采用').fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
        .fontColor(ACCENT_INK)
        .constraintSize({ minHeight: 44 }).textAlign(TextAlign.Center)
        .padding({ left: 18, right: 18 }).backgroundColor(ACCENT).borderRadius(22)
        .margin({ left: 10 })
        .onClick((): void => { this.onResolve(true); })
    }
    .width('100%')
  }

  build(): void {
    Column() {
      NovelApprovalShell({
        iconName: 'ph_lightbulb',
        title: this.title.length > 0 ? this.title : '待确认设定',
        subtitle: this.kindLabel,
        statusLabel: this.status === 'accepted' ? '已采用' : this.status === 'rejected' ? '已忽略' : '待确认',
        statusIcon: this.status === 'accepted' ? 'check' : this.status === 'rejected' ? 'ban' : 'info',
        statusTone: this.status === 'accepted' ? 'success' : 'muted',
        showActions: this.status === 'pending',
        content: (): void => { this.Body(); },
        actions: (): void => { this.Actions(); },
      })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').margin({ bottom: 12 })
  }
}
