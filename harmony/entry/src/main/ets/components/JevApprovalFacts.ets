import type { JevApprovalTriage, JevFact, UIMessagePartTool } from '@amber/chat-domain';
import { readJevApprovalTriage } from '@amber/chat-domain';
import { LABEL } from '../design/tokens.ets';
import { SSHUIColors } from './SSHUIColors.ets';

// The caller supplies the current saved subject hash, never the metadata's own hash.
@Component
export struct JevApprovalFacts {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop part: UIMessagePartTool;
  @Prop subjectHash: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';

  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private facts(): JevApprovalTriage | null {
    return this.part.approvalState.type === 'pending' && this.subjectHash.length > 0
      ? readJevApprovalTriage(this.part, this.subjectHash) : null;
  }
  private label(fact: JevFact): string { return fact === 'yes' ? '是' : (fact === 'no' ? '否' : '未知'); }

  @Builder
  private FactLabels(facts: JevApprovalTriage): void {
    Flex({ wrap: FlexWrap.Wrap }) {
      Text(`只读：${this.label(facts.readonly)}`).fontSize(LABEL).fontColor(this.colors.muted)
        .margin({ right: 12, bottom: 4 })
      Text(`可逆：${this.label(facts.reversible)}`).fontSize(LABEL).fontColor(this.colors.muted)
        .margin({ right: 12, bottom: 4 })
      Text(`与任务相关：${this.label(facts.goalAligned)}`).fontSize(LABEL).fontColor(this.colors.muted)
        .margin({ bottom: 4 })
    }.width('100%').padding({ top: 6, bottom: 2 })
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.facts() !== null) this.FactLabels(this.facts()!)
  }
}
