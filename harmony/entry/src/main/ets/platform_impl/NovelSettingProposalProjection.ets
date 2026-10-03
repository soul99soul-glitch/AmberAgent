import type { NovelMessage, NovelSettingProposal } from '@amber/deepread-domain';

const isProposalSource = (proposal: NovelSettingProposal, message: NovelMessage): boolean =>
  proposal.sourceMessageId === message.id || proposal.sourceMessageId === message.uiMessage.id;

// Keep resolved proposals beside the original message; resolution does not reorder history.
export const settingProposalsForMessage = (
  proposals: NovelSettingProposal[], message: NovelMessage,
): NovelSettingProposal[] => proposals.filter((proposal: NovelSettingProposal): boolean =>
  isProposalSource(proposal, message));

// The caller passes the complete persisted history, including messages hidden by an active run.
export const orphanSettingProposals = (
  proposals: NovelSettingProposal[], messages: NovelMessage[],
): NovelSettingProposal[] => proposals.filter((proposal: NovelSettingProposal): boolean =>
  !messages.some((message: NovelMessage): boolean => isProposalSource(proposal, message)));
