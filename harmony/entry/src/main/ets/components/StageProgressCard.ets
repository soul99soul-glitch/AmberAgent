// StageProgressCard — 对照设计 §7.4 进度 UI
// 单个 stage 状态卡:PENDING/RUNNING/READY/FAILED + quality BASIC/STANDARD + 重试按钮

import type { DeepReadGenerationStage, DeepReadSectionStatus } from '@amber/deepread-domain';
import { STAGE_LABELS } from '@amber/deepread-domain';

@Component
export struct StageProgressCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  stage: DeepReadGenerationStage = 'OVERVIEW';
  status: DeepReadSectionStatus = 'PENDING';
  quality: string = '';
  onRetry: () => void = () => {};

  private statusText(): string {
    switch (this.status) {
      case 'PENDING': return '待开始';
      case 'RUNNING': return '生成中';
      case 'READY': return '已完成';
      case 'FAILED': return '失败';
    }
  }

  private statusColor(): string {
    switch (this.status) {
      case 'READY': return '#43A047';
      case 'RUNNING': return '#1976D2';
      case 'FAILED': return '#E53935';
      default: return '#999';
    }
  }

  build(): void {
    Row() {
      Text(STAGE_LABELS[this.stage]).fontSize(14).fontWeight(FontWeight.Medium).layoutWeight(1)
      if (this.status === 'READY' && this.quality.length > 0) {
        Text(this.quality === 'STANDARD' ? '标准' : '基础').fontSize(11).fontColor('#666').margin({ right: 8 })
      }
      Text(this.statusText()).fontSize(12).fontColor(this.statusColor())
      if (this.status === 'FAILED') {
        Button('重试').type(ButtonType.Normal).fontSize(12).margin({ left: 8 }).onClick(() => this.onRetry())
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('90%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .margin({ top: 6 })
    .borderRadius(8)
    .backgroundColor('#FAFAFA')
  }
}
