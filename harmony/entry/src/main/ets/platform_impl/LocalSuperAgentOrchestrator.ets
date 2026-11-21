import type { SubAgentManager, SubAgentResult, SubAgentRun } from '@amber/chat-domain';
import type { DetachedConversationContext, ConversationRunService } from './ConversationRunService.ets';

interface DispatchBatch {
  conversationId: string;
  runIds: Set<string>;
  sealed: boolean;
}

export class LocalSuperAgentOrchestrator {
  private readonly manager: SubAgentManager;
  private readonly runService: ConversationRunService;
  private readonly batches: Map<string, DispatchBatch> = new Map<string, DispatchBatch>();

  constructor(manager: SubAgentManager, runService: ConversationRunService) {
    this.manager = manager;
    this.runService = runService;
    manager.onRunTerminal((run: SubAgentRun): void => {
      if (run.dispatchBatchId !== undefined) this.deliverReadyBatch(run.dispatchBatchId);
    });
  }

  bind(context: DetachedConversationContext): (runId: string) => void {
    const batchId: string = context.runId;
    const batch: DispatchBatch = {
      conversationId: context.conversationId, runIds: new Set<string>(), sealed: false,
    };
    context.sealSubAgentBatch = (): void => {
      batch.sealed = true;
      this.deliverReadyBatch(batchId);
    };
    return (runId: string): void => {
      batch.runIds.add(runId);
      this.batches.set(batchId, batch);
    };
  }

  private deliverReadyBatch(batchId: string): void {
    const batch: DispatchBatch | undefined = this.batches.get(batchId);
    if (batch === undefined || !batch.sealed) return;
    const reports: string[] = [];
    for (const runId of batch.runIds) {
      const run: SubAgentRun | null = this.manager.snapshot(runId);
      const result: SubAgentResult | null = run?.result ?? null;
      if (run === null || result === null) return;
      if (result.status === 'cancelled') continue;
      reports.push(JSON.stringify({
        title: run.definition.name,
        objective: run.task.objective,
        status: result.status,
        summary: result.summary,
        findings: result.findings,
        evidence: result.evidence,
        risks: result.risks,
        error: result.error,
      }));
    }
    // A sealed batch is consumed once, including when every child was cancelled.
    this.batches.delete(batchId);
    if (reports.length > 0) this.runService.enqueueSubAgentWakeup(batch.conversationId, reports.join('\n'));
  }
}
