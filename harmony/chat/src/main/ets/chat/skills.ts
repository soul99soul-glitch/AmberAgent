// Stable skill-domain exports. Stateful ownership lives in SkillManager;
// metadata, package IO, mobile instructions and tool protocol have separate owners.
export type { SkillMetadata, SkillScanIssue } from './skill_metadata.ts';
export { makeSkillMetadata, skillParseFrontmatter, skillEnsureDescription,
  skillResolveDescription, skillIsPlaceholderDescription, skillExtractBody } from './skill_metadata.ts';
export type { SkillFilePort, SkillDirEntry, BuiltinSkillAssetPort,
  SkillWorkspacePort, SkillWorkspaceEntry } from './skill_package.ts';
export { MAX_SKILL_FILE_BYTES, resolveSkillDirPath, resolveSkillFilePath,
  isLikelyTextSkillFile, canonicalSkillFileName, collectWorkspaceSkillFiles,
  unzipSkillFiles } from './skill_package.ts';
export type { SkillManagerDeps } from './skill_library.ts';
export { SkillManager } from './skill_library.ts';
export { buildSkillMobileRuntimePrompt } from './skill_mobile_prompt.ts';
export type { CreateSkillToolsDeps } from './skill_tools.ts';
export { createSkillTools } from './skill_tools.ts';

import type { SkillManager } from './skill_library.ts';

// Retain the existing package-collection call shape without exposing private ports.
export const collectSkillFilesFromDirectory = (
  manager: SkillManager, dir: string | null,
): Map<string, string> => manager.collectFilesFromDirectory(dir);
