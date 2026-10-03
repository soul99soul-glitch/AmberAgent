import type { NovelProject, NovelMessage } from './models.ts';
import { makeNovelMessage, novelId } from './models.ts';
import { makeNovelCandidateProvenance } from './candidate_provenance.ts';
import { invalidInput, notFound } from './error.ts';

// Cloning is an explicit author decision to reuse this text against the current manuscript.
// The original adoption remains immutable; the new source uses the ordinary candidate stale checks.
export const canCloneCollectedMessage = (
    project: NovelProject, message: NovelMessage, branchId: string,
): boolean => project.messages.some(item => item.id === message.id) &&
    message.role === 'assistant' && message.mode === 'write' &&
    message.collectedChapterId !== null && message.content.trim().length > 0 &&
    (message.runKind === undefined || message.runKind === null || message.runKind === 'prose_continuation' ||
        message.runKind === 'prose_whole_chapter' || message.runKind === 'regenerate') &&
    (message.candidate === undefined ||
        (message.candidate.kind !== 'polish' && message.candidate.branchId === branchId));

export const cloneCollectedMessage = (
    project: NovelProject, messageId: string, branchId: string, now: number,
): { project: NovelProject; message: NovelMessage } => {
    const source = project.messages.find(message => message.id === messageId);
    if (source === undefined) throw notFound('message', messageId);
    if (!canCloneCollectedMessage(project, source, branchId)) {
        throw invalidInput('只有当前分支已收录的正文回复可以再次收录');
    }
    const id = novelId();
    const canonical = JSON.parse(JSON.stringify(source.uiMessage)) as NovelMessage['uiMessage'];
    canonical.id = id;
    canonical.createdAt = new Date(now).toISOString();
    const candidate = makeNovelCandidateProvenance(project, branchId, 'write', null);
    candidate.complete = source.candidate?.complete ?? !source.interrupted;
    const message = makeNovelMessage({ id, role: source.role, mode: source.mode, uiMessage: canonical,
        createdAt: now, granularity: source.granularity, interrupted: source.interrupted,
        runKind: source.runKind, candidate, clonedFromMessageId: source.id,
        rootMessageId: source.rootMessageId ?? source.id });
    return { project: { ...project, messages: [...project.messages, message], ordinaryRun: undefined, updatedAt: now }, message };
};
