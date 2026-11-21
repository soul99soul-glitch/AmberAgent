// AmberTaskCenterSheet — 底栏「任务」原位面板
// 聚合：云端后台（ChatCloudDialogueSession）+ Mac 会话（MacGatewayStatus.sessions）+
// 本机后台（ConversationTaskRunner）+ 子代理（SubAgentManager）+ 当前待审批/异常卡点；
// 诚实展示真实来源与状态（需你处理 / 后台运行中 / 最近完成），不伪造百分比进度条，支持原位补充说明与停止。

import type { SubAgentManager, SubAgentRun, MacGatewayPendingApproval, MacDecisionTrustContext } from '@amber/chat-domain';
import { getChatKvStore, getConversationTaskRunner, getSubAgentManager } from '../../di/AppContainer.ets';
import {
  ChatCloudDialogueSession, type CloudDialogueTaskRecord,
} from '../../platform_impl/chat/ChatCloudDialogueSession.ets';
import type { LocalTaskRecord } from '../../platform_impl/ConversationTaskRunner.ets';
import {
  MacGatewayClient, loadMacGatewayConnection, describeMacGatewayError,
  type MacGatewayConnection, type MacGatewaySession, type MacGatewayStatus,
} from '../../platform_impl/MacGatewayClient.ets';
import { authenticateForMacDecision, isMacDecisionUnlockedForeground } from '../../platform_impl/MacGatewayTrustAuth.ets';
import {
  ACCENT, ERROR, INK, INK2, INK3, INK4,
  LINE, SUCCESS_FG, SURFACE, SURFACE2, WARNING_FG, accentSoftBg,
} from '../../design/tokens.ets';

export type TaskCenterBucket = 'attention' | 'running' | 'recent';

export interface UnifiedTaskCardItem {
  id: string;
  bucket: TaskCenterBucket;
  channelKind: 'cloud' | 'mac' | 'local' | 'subagent' | 'approval';
  channelBadge: string;
  title: string;
  detail: string;
  conversationId: string;
  canFollowup: boolean;
  canStop: boolean;
  stopRequested: boolean;
  updatedAt: number;
  macPendingApproval?: MacGatewayPendingApproval;
  macApprovalConnection?: MacGatewayConnection;
}

const sameApprovalPairing = (current: MacGatewayConnection | null, source: MacGatewayConnection): boolean =>
  current !== null && source.gatewayId.length > 0 &&
  current.gatewayId === source.gatewayId && current.deviceId === source.deviceId &&
  current.fingerprint === source.fingerprint && current.token === source.token;

@Component
export struct AmberTaskCenterSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @ObjectLink cloudDialogue: ChatCloudDialogueSession;
  @Prop currentConversationId: string = '';
  @Prop pendingApprovalCount: number = 0;
  @Prop consecutiveErrorCount: number = 0;
  @Prop hasUnrecoveredError: boolean = false;

  onBackToChat: () => void = (): void => {};
  onOpenTasksSettings: () => void = (): void => {};
  onOpenGatewaySettings: () => void = (): void => {};
  onJumpToConversation: (conversationId: string) => void = (_conversationId: string): void => {};
  onFocusApproval: () => void = (): void => {};
  onStatusMessage: (msg: string) => void = (_msg: string): void => {};

  @State private localTasks: LocalTaskRecord[] = [];
  @State private subAgentRuns: SubAgentRun[] = [];
  @State private macSessions: MacGatewaySession[] = [];
  private macSessionsConnection: MacGatewayConnection | undefined = undefined;
  @State private followupItemId: string = '';
  @State private followupChannel: string = '';
  @State private followupConvId: string = '';
  @State private followupText: string = '';
  @State private busyAction: boolean = false;

  private unsubscribeRunner: (() => void) | null = null;
  private pollTimer: number = -1;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    const runner = getConversationTaskRunner();
    runner.ensureHydrated().then((): void => {
      if (this.alive) this.localTasks = runner.recentTasks(20);
    }).catch((): void => {});
    this.unsubscribeRunner = runner.subscribe((): void => {
      if (this.alive) this.localTasks = runner.recentTasks(20);
    });
    this.refreshAsyncSources();
    this.pollTimer = setInterval((): void => {
      this.refreshAsyncSources();
    }, 3000);
  }

  aboutToDisappear(): void {
    this.alive = false;
    if (this.unsubscribeRunner !== null) {
      this.unsubscribeRunner();
      this.unsubscribeRunner = null;
    }
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private refreshAsyncSources(): void {
    getSubAgentManager().then((mgr: SubAgentManager): void => {
      if (this.alive) this.subAgentRuns = mgr.recentRuns(24);
    }).catch((): void => {});

    if (this.cloudDialogue.paired) {
      loadMacGatewayConnection(getChatKvStore()).then(async (conn: MacGatewayConnection | null): Promise<void> => {
        if (conn === null || !this.alive) return;
        try {
          const st: MacGatewayStatus = await MacGatewayClient.of(conn).status();
          if (this.alive && Array.isArray(st.sessions)) {
            this.macSessionsConnection = conn;
            this.macSessions = st.sessions;
          }
        } catch (_e) {
          // Gateway offline: leave cached list intact without fabricating
        }
      }).catch((): void => {});
    } else {
      this.macSessionsConnection = undefined;
      this.macSessions = [];
    }
  }

  private collectItems(): UnifiedTaskCardItem[] {
    const items: UnifiedTaskCardItem[] = [];
    const seenIds: Set<string> = new Set<string>();

    if (this.pendingApprovalCount > 0) {
      items.push({
        id: 'approval:current',
        bucket: 'attention',
        channelKind: 'approval',
        channelBadge: '⚠ 待审批',
        title: `当前对话有 ${this.pendingApprovalCount} 项工具操作等待你的确认`,
        detail: '高风险或写入操作已暂停，确认或拒绝后继续执行。',
        conversationId: this.currentConversationId,
        canFollowup: false,
        canStop: false,
        stopRequested: false,
        updatedAt: Date.now(),
      });
    }

    if (this.hasUnrecoveredError && this.consecutiveErrorCount >= 4) {
      items.push({
        id: 'error:streak',
        bucket: 'attention',
        channelKind: 'approval',
        channelBadge: '⚠ 连续受阻',
        title: `本轮连续遇到 ${this.consecutiveErrorCount} 次未恢复异常`,
        detail: '建议回到对话查看失败节点并决定重试或切换策略。',
        conversationId: this.currentConversationId,
        canFollowup: false,
        canStop: false,
        stopRequested: false,
        updatedAt: Date.now() - 1,
      });
    }

    for (const t of this.cloudDialogue.tasks) {
      const uid: string = `cloud:${t.workItemId}`;
      if (seenIds.has(uid)) continue;
      seenIds.add(uid);
      const isActive: boolean = t.status === 'running' || t.status === 'queued' || t.status === 'unknown';
      const needsAttention: boolean = t.status === 'failed' || t.status === 'blocked' || t.status === 'interrupted';
      const bucket: TaskCenterBucket = needsAttention ? 'attention' : isActive ? 'running' : 'recent';
      items.push({
        id: t.workItemId,
        bucket,
        channelKind: 'cloud',
        channelBadge: '☁️ 云端后台',
        title: t.title.length > 0 ? t.title : '云端任务',
        detail: t.progressText.length > 0 ? t.progressText : (t.resultText.length > 0 ? t.resultText : t.status),
        conversationId: this.cloudDialogue.conversationIdForTask(t.workItemId, t.topicId),
        canFollowup: isActive,
        canStop: isActive,
        stopRequested: t.stopRequested,
        updatedAt: t.updatedAt,
      });
    }

    for (const s of this.macSessions) {
      const sid: string = `mac:${s.key}`;
      if (seenIds.has(sid)) continue;
      seenIds.add(sid);
      const stateLower: string = s.state.toLowerCase();
      const hasApproval: boolean = s.pendingApproval !== undefined;
      const needsAttention: boolean = hasApproval || s.abnormal || stateLower === 'waiting' || stateLower === 'stalled' ||
        (s.waitReason !== undefined && s.waitReason.trim().length > 0);
      const isActive: boolean = stateLower === 'running' || stateLower === 'working' || stateLower === 'active';
      const bucket: TaskCenterBucket = needsAttention ? 'attention' : isActive ? 'running' : 'recent';
      const detailText: string = s.pendingApproval !== undefined
        ? `待确认工具：${s.pendingApproval.tool}${s.pendingApproval.detail ? ` · ${s.pendingApproval.detail}` : ''}`
        : (s.waitReason && s.waitReason.length > 0 ? s.waitReason : `${s.agent} · ${s.state}`);
      items.push({
        id: s.key,
        bucket,
        channelKind: 'mac',
        channelBadge: s.host && s.host.length > 0 ? `☁️ ${s.host}` : '☁️ Mac 会话',
        title: s.subject.length > 0 ? s.subject : s.agent,
        detail: detailText,
        conversationId: '',
        canFollowup: false,
        canStop: false,
        stopRequested: false,
        updatedAt: s.updatedAt,
        macPendingApproval: s.pendingApproval,
        macApprovalConnection: this.macSessionsConnection,
      });
    }

    for (const lt of this.localTasks) {
      const lid: string = `local:${lt.taskId}`;
      if (seenIds.has(lid) || seenIds.has(`cloud:${lt.taskId}`)) continue;
      seenIds.add(lid);
      const isActive: boolean = lt.status === 'running' || lt.status === 'queued';
      const needsAttention: boolean = lt.status === 'failed' || lt.status === 'interrupted';
      const bucket: TaskCenterBucket = needsAttention ? 'attention' : isActive ? 'running' : 'recent';
      items.push({
        id: lt.taskId,
        bucket,
        channelKind: 'local',
        channelBadge: '📱 本机',
        title: lt.title.length > 0 ? lt.title : '本机后台任务',
        detail: lt.progressText.length > 0 ? lt.progressText : (lt.resultText.length > 0 ? lt.resultText : lt.status),
        conversationId: lt.conversationId,
        canFollowup: isActive,
        canStop: isActive,
        stopRequested: lt.stopRequested,
        updatedAt: lt.updatedAt,
      });
    }

    for (const run of this.subAgentRuns) {
      const bucket: TaskCenterBucket = run.status === 'approval_required' || run.status === 'failed' || run.status === 'interrupted'
        ? 'attention'
        : run.status === 'running' ? 'running' : 'recent';
      items.push({
        id: run.runId,
        bucket,
        channelKind: 'subagent',
        channelBadge: '📱 子代理',
        title: run.definition.name || run.definition.id,
        detail: run.task.objective || run.displayText || run.status,
        conversationId: run.parentConversationId,
        canFollowup: false,
        canStop: run.status === 'running',
        stopRequested: false,
        updatedAt: run.updatedAtMs,
      });
    }

    return items.sort((a: UnifiedTaskCardItem, b: UnifiedTaskCardItem): number => b.updatedAt - a.updatedAt);
  }

  private bucketItems(all: UnifiedTaskCardItem[], bucket: TaskCenterBucket): UnifiedTaskCardItem[] {
    const filtered: UnifiedTaskCardItem[] = all.filter((i: UnifiedTaskCardItem): boolean => i.bucket === bucket);
    return bucket === 'recent' ? filtered.slice(0, 6) : filtered;
  }

  private stopItem(item: UnifiedTaskCardItem): void {
    if (this.busyAction) return;
    if (item.channelKind === 'cloud') {
      this.cloudDialogue.stopCloudTask(item.id).catch((err: Error): void => {
        this.onStatusMessage(err.message);
      });
    } else if (item.channelKind === 'local') {
      getConversationTaskRunner().stopTask(item.conversationId, item.id).catch((err: Error): void => {
        this.onStatusMessage(err.message);
      });
    } else if (item.channelKind === 'subagent') {
      getSubAgentManager().then((mgr: SubAgentManager): Promise<void> =>
        mgr.cancel(item.id).then((): void => { this.refreshAsyncSources(); }),
      ).catch((err: Error): void => {
        this.onStatusMessage(err.message);
      });
    }
  }

  private decideMacApproval(item: UnifiedTaskCardItem, allow: boolean): void {
    const pending: MacGatewayPendingApproval | undefined = item.macPendingApproval;
    if (pending === undefined || this.busyAction) return;
    if (allow && pending.highRisk) {
      this.onStatusMessage('高风险操作请在 Mac 端确认，手机端仅支持拒绝');
      return;
    }
    const approvalConnection: MacGatewayConnection | undefined = item.macApprovalConnection;
    if (approvalConnection === undefined) return;
    this.busyAction = true;
    (async (): Promise<void> => {
      const conn: MacGatewayConnection | null = await loadMacGatewayConnection(getChatKvStore());
      if (!this.alive || conn === null || !sameApprovalPairing(conn, approvalConnection)) {
        this.onStatusMessage('配对已变更，请刷新待审批列表');
        return;
      }
      if (allow) {
        const verified: boolean = await authenticateForMacDecision(`批准 Mac 执行 ${pending.tool}`);
        if (!verified) {
          this.onStatusMessage('前台生物识别未通过，已取消发送批准');
          return;
        }
      }
      const trustContext: MacDecisionTrustContext = {
        surface: 'app', trustedForeground: allow, biometricVerified: allow, gatewayIdMatches: true,
      };
      const canSendTrusted = async (): Promise<boolean> => {
        const current: MacGatewayConnection | null = await loadMacGatewayConnection(getChatKvStore());
        return sameApprovalPairing(current, approvalConnection) &&
          await isMacDecisionUnlockedForeground() && this.alive;
      };
      await MacGatewayClient.of(conn).decide(item.id, pending.toolCallId, allow, trustContext,
        allow ? canSendTrusted : undefined);
      this.onStatusMessage(allow ? `已批准 ${pending.tool}` : `已拒绝 ${pending.tool}`);
      this.refreshAsyncSources();
    })().catch((err: Error): void => {
      this.onStatusMessage(describeMacGatewayError(err));
    }).finally((): void => {
      if (this.alive) this.busyAction = false;
    });
  }

  private submitInlineFollowup(): void {
    const clean: string = this.followupText.trim();
    if (clean.length === 0 || this.followupItemId.length === 0 || this.busyAction) return;
    this.busyAction = true;
    const itemId: string = this.followupItemId;
    const channel: string = this.followupChannel;
    const convId: string = this.followupConvId || this.currentConversationId;
    const finish = (): void => {
      this.busyAction = false;
      this.followupText = '';
      this.followupItemId = '';
    };

    if (channel === 'cloud') {
      this.cloudDialogue.sendFollowup(itemId, clean)
        .then((): void => {
          finish();
          this.onStatusMessage('已向云端任务发送补充说明');
        })
        .catch((err: Error): void => {
          this.busyAction = false;
          this.onStatusMessage(`发送失败：${err.message}`);
        });
    } else if (channel === 'local') {
      getConversationTaskRunner()
        .submitFollowup(convId, itemId, [{ type: 'text', text: clean, metadata: null }], clean)
        .then((): void => {
          finish();
          this.onStatusMessage('已向本机后台任务发送补充说明');
        })
        .catch((err: Error): void => {
          this.busyAction = false;
          this.onStatusMessage(`发送失败：${err.message}`);
        });
    } else {
      finish();
    }
  }

  @Builder
  private TaskSection(title: string, badgeColor: string, rows: UnifiedTaskCardItem[]): void {
    if (rows.length > 0) {
      Column({ space: 6 }) {
        Row({ space: 6 }) {
          Text(title)
            .fontSize(12)
            .fontWeight(FontWeight.Bold)
            .fontColor(badgeColor)
          Text(`${rows.length}`)
            .fontSize(11)
            .fontColor(INK3)
        }
        .width('100%')

        ForEach(rows, (item: UnifiedTaskCardItem): void => {
          Column({ space: 6 }) {
            Row({ space: 8 }) {
              Text(item.channelBadge)
                .fontSize(11)
                .fontColor(item.bucket === 'attention' ? WARNING_FG : this.accentColorStore)
                .padding({ left: 7, right: 7, top: 2, bottom: 2 })
                .borderRadius(999)
                .backgroundColor(SURFACE)

              Text(item.title)
                .fontSize(13)
                .fontWeight(FontWeight.Medium)
                .fontColor(INK)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
            }
            .width('100%')

            if (item.detail.length > 0) {
              Text(item.detail)
                .fontSize(12)
                .fontColor(INK3)
                .maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
            }

            Row({ space: 8 }) {
              if (item.channelKind === 'approval') {
                Text('前往处理 ›')
                  .fontSize(11.5)
                  .fontColor(this.accentColorStore)
                  .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .borderRadius(999)
                  .backgroundColor(accentSoftBg(this.accentColorStore))
                  .onClick((): void => {
                    this.onFocusApproval();
                    this.onBackToChat();
                  })
              } else if (item.conversationId.length > 0 && item.conversationId !== this.currentConversationId) {
                Text('切到该对话 ›')
                  .fontSize(11.5)
                  .fontColor(this.accentColorStore)
                  .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .borderRadius(999)
                  .backgroundColor(accentSoftBg(this.accentColorStore))
                  .onClick((): void => {
                    this.onJumpToConversation(item.conversationId);
                  })
              }

              if (item.canFollowup) {
                Text(this.followupItemId === item.id ? '收起补充' : '补充说明')
                  .fontSize(11.5)
                  .fontColor(this.accentColorStore)
                  .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .borderRadius(999)
                  .backgroundColor(SURFACE)
                  .onClick((): void => {
                    if (this.followupItemId === item.id) {
                      this.followupItemId = '';
                    } else {
                      this.followupItemId = item.id;
                      this.followupChannel = item.channelKind;
                      this.followupConvId = item.conversationId;
                      this.followupText = '';
                    }
                  })
              }

              if (item.macPendingApproval !== undefined) {
                Row({ space: 6 }) {
                  Text('拒绝')
                    .fontSize(11.5)
                    .fontColor(ERROR)
                    .padding({ left: 10, right: 10, top: 4, bottom: 4 })
                    .borderRadius(999)
                    .backgroundColor(SURFACE)
                    .enabled(!this.busyAction)
                    .onClick((): void => { this.decideMacApproval(item, false); })
                  if (!item.macPendingApproval.highRisk) {
                    Text('验证并批准')
                      .fontSize(11.5)
                      .fontWeight(FontWeight.Medium)
                      .fontColor('#FFFFFF')
                      .padding({ left: 10, right: 10, top: 4, bottom: 4 })
                      .borderRadius(999)
                      .backgroundColor(this.accentColorStore)
                      .enabled(!this.busyAction)
                      .onClick((): void => { this.decideMacApproval(item, true); })
                  } else {
                    Text('高风险 · 仅 Mac 可批')
                      .fontSize(11)
                      .fontColor(WARNING_FG)
                  }
                }
              }

              Blank()

              if (item.canStop) {
                Text(item.stopRequested ? '停止中…' : '停止')
                  .fontSize(11.5)
                  .fontColor(ERROR)
                  .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                  .borderRadius(999)
                  .backgroundColor(SURFACE)
                  .enabled(!item.stopRequested)
                  .onClick((): void => { this.stopItem(item); })
              }
            }
            .width('100%')

            if (this.followupItemId === item.id) {
              Row({ space: 6 }) {
                TextInput({ text: this.followupText, placeholder: '输入补充说明，任务将在下一边界接收…' })
                  .layoutWeight(1)
                  .fontSize(12.5)
                  .fontColor(INK)
                  .backgroundColor(SURFACE)
                  .borderRadius(10)
                  .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                  .onChange((v: string): void => { this.followupText = v; })
                Text(this.busyAction ? '发送中' : '发送')
                  .fontSize(12)
                  .fontWeight(FontWeight.Medium)
                  .fontColor('#FFFFFF')
                  .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                  .borderRadius(10)
                  .backgroundColor(this.accentColorStore)
                  .enabled(!this.busyAction && this.followupText.trim().length > 0)
                  .onClick((): void => { this.submitInlineFollowup(); })
              }
              .width('100%')
              .padding({ top: 4 })
            }
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .borderRadius(16)
          .backgroundColor(SURFACE2)
        }, (item: UnifiedTaskCardItem): string => `${item.channelKind}-${item.id}-${item.bucket}-${item.stopRequested}-${item.updatedAt}`)
      }
      .width('100%')
    }
  }

  build(): void {
    Column({ space: 10 }) {
      Row() {
        Text('任务中心')
          .fontSize(15)
          .fontWeight(FontWeight.Bold)
          .fontColor(INK)
        Text(this.cloudDialogue.paired ? ` · ☁️ ${this.cloudDialogue.gatewayName || 'Mac Gateway'}` : ' · 📱 仅本机')
          .fontSize(11.5)
          .fontColor(INK3)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text('定时与任务 ›')
          .fontSize(12)
          .fontColor(this.accentColorStore)
          .onClick((): void => { this.onOpenTasksSettings(); })
      }
      .width('100%')

      if (this.collectItems().length === 0) {
        Column({ space: 6 }) {
          Text('暂无进行中或待处理的任务')
            .fontSize(13)
            .fontColor(INK2)
            .width('100%')
          Text(this.cloudDialogue.paired
            ? '云端或本机发起较长任务后，可在此统一查看进展、补充说明或停止。'
            : '在本机对话发起耗时任务可并行运行；如需跨设备云端执行，可前往配对 Mac Gateway。')
            .fontSize(12)
            .fontColor(INK3)
            .width('100%')
          if (!this.cloudDialogue.paired) {
            Text('配置 Mac Gateway ›')
              .fontSize(12)
              .fontColor(this.accentColorStore)
              .padding({ top: 2 })
              .onClick((): void => { this.onOpenGatewaySettings(); })
          }
        }
        .width('100%')
        .padding(12)
        .borderRadius(16)
        .backgroundColor(SURFACE2)
      } else {
        Scroll() {
          Column({ space: 12 }) {
            this.TaskSection('需你处理', WARNING_FG, this.bucketItems(this.collectItems(), 'attention'))
            this.TaskSection('后台运行中', this.accentColorStore, this.bucketItems(this.collectItems(), 'running'))
            this.TaskSection('最近完成', SUCCESS_FG, this.bucketItems(this.collectItems(), 'recent'))
          }
          .width('100%')
        }
        .layoutWeight(1)
        .scrollBar(BarState.Off)
      }
    }
    .width('100%')
    .height('100%')
    .padding({ left: 16, right: 16, top: 4, bottom: 10 })
  }
}
