// BackupReminderCard — 备份提醒卡(ui/BackupReminderCard.kt:85 行)
// Android 基准: 读取 backupReminderConfig(enabled/lastBackupTime/intervalDays)判断是否到期
//   到期 → Card(icon + title + subtitle + dismiss);未到期 → 不渲染
// ArkUI: config 作为 @Prop 传入;dismiss 为本地 @State(非持久,对齐 Android)
import { INK2, INK3, SURFACE, LINE, BODY, LABEL } from '../../design/tokens.ets';
import { Icon } from './Icon.ets';

export interface BackupConfig {
  enabled: boolean;
  lastBackupTime: number; // epoch ms,0 = 从未
  intervalDays: number;
}

export const isBackupDue = (config: BackupConfig): boolean => {
  if (!config.enabled) return false;
  if (config.lastBackupTime === 0) return true;
  const elapsed: number = Date.now() - config.lastBackupTime;
  return elapsed > config.intervalDays * 24 * 60 * 60 * 1000;
};

export const backupSubtitle = (config: BackupConfig): string => {
  if (config.lastBackupTime === 0) return '从未备份';
  const days: number = Math.floor((Date.now() - config.lastBackupTime) / (24 * 60 * 60 * 1000));
  if (days === 0) return '今天已备份';
  return `${days} 天前备份`;
};

@Component
export struct BackupReminderCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop config: BackupConfig = { enabled: false, lastBackupTime: 0, intervalDays: 7 };
  @State dismissed: boolean = false;
  onClick: () => void = (): void => {};

  build(): void {
    if (this.themeEpoch >= 0 && isBackupDue(this.config) && !this.dismissed) {
      Row() {
        Icon({ name: 'save', iconSize: BODY, color: INK2 }).margin({ right: 10 })
        Column() {
          Text('备份你的数据').fontSize(BODY).fontColor(INK2)
          Text(backupSubtitle(this.config))
            .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text('✕').fontSize(LABEL).fontColor(INK3)
          .onClick((): void => { this.dismissed = true; })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 14, bottom: 14 })
      .backgroundColor(SURFACE)
      .border({ width: 1, color: LINE })
      .borderRadius(12)
      .onClick((): void => { this.onClick(); })
    }
  }
}
