import type { FileStore } from '../platform/files.ts';
import { createGhostwriteRepository } from './ghostwrite_repository.ts';
import { createPolishRepository } from './polish_repository.ts';
import type { NovelProjectRepository } from './repository_contract.ts';
import { createWorkspaceArchiveRepository } from './workspace_archive_repository.ts';
import { createWorkspaceHistoryRepository } from './workspace_history_repository.ts';
import { createWorkspaceProjectRepository } from './workspace_project_repository.ts';
import { createWorkspaceProposalRepository } from './workspace_proposal_repository.ts';
import { createWorkspaceStorage } from './workspace_transaction.ts';

export type { NovelProjectRepository, NovelWorkspaceCas, NovelWorkspaceMutationKind, NovelWorkspaceSnapshot, NovelWorkspaceStatus } from './repository_contract.ts';
export { migrateNovelProject } from './workspace_codec.ts';

export const createFileNovelRepository = (fileStore: FileStore): NovelProjectRepository => {
  const storage = createWorkspaceStorage(fileStore);
  return {
    tryWithPolishDriverLock: storage.tryWithPolishDriverLock,
    withPolishDriverLock: storage.withPolishDriverLock,
    ...createWorkspaceProjectRepository(storage),
    ...createWorkspaceHistoryRepository(storage),
    ...createWorkspaceProposalRepository(storage),
    ...createGhostwriteRepository(storage),
    ...createPolishRepository(storage),
    ...createWorkspaceArchiveRepository(storage),
  };
};
