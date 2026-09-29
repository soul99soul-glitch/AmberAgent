import type { CouncilToolMode } from '@amber/deepread-domain';
import { ACCENT, BODY, LABEL } from '../design/tokens.ets';
import { SSHUIColors } from './SSHUIColors.ets';

interface CouncilToolModeOption { mode: CouncilToolMode; title: string; detail: string; }
const OPTIONS: CouncilToolModeOption[] = [
  { mode: 'off', title: '关闭', detail: '席位只生成文字，不调用工具。' },
  { mode: 'search', title: '联网搜索', detail: '使用已配置且获准的搜索与网页抓取工具。' },
  { mode: 'full', title: 'FULL', detail: '使用默认助手（首个配置）已启用且可用的工具，保留原审批设置。' },
];

@Component
export struct CouncilToolModePicker {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop mode: CouncilToolMode = 'off';
  @Prop disabled: boolean = false;
  onSelect: (mode: CouncilToolMode) => void = (_mode: CouncilToolMode): void => {};
  @StorageProp('accentColor') accentColor: string = ACCENT;
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  private refreshColors(): void { this.colors = new SSHUIColors(); }

  build(): void {
    Column() {
      ForEach(OPTIONS, (option: CouncilToolModeOption): void => {
        Row({ space: 12 }) {
          Column({ space: 3 }) {
            Text(option.title).fontSize(BODY).fontColor(this.colors.ink).width('100%')
            Text(option.detail).fontSize(LABEL).fontColor(this.colors.muted).width('100%')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Text(this.mode === option.mode ? '✓' : '').fontSize(BODY).fontColor(this.accentColor).width(20)
        }.width('100%').constraintSize({ minHeight: 44 })
          .padding({ left: 14, right: 14, top: 10, bottom: 10 })
          .enabled(!this.disabled).onClick((): void => { this.onSelect(option.mode); })
      }, (option: CouncilToolModeOption): string => option.mode)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%')
  }
}
