import type {
  ArtifactFileExport,
  ConversationSvgValidationProof,
  ConversationSceneArtifact,
  ConversationSvgArtifact,
  ConversationUiSnapshotArtifact,
  SceneArtifactExport,
} from '@amber/chat-domain';
import { sceneArtifactExport, svgArtifactExport, uiSnapshotArtifactExport } from '@amber/chat-domain';
import { common } from '@kit.AbilityKit';
import { saveMarkdownFile } from './ChatExportFiles.ets';

const saveDocument = async (
  context: common.UIAbilityContext,
  fileName: string,
  content: string,
): Promise<boolean> => saveMarkdownFile(context, fileName, content);

export const saveSvgArtifact = async (
  context: common.UIAbilityContext,
  artifact: ConversationSvgArtifact,
  proof: ConversationSvgValidationProof,
): Promise<boolean> => {
  const exported: ArtifactFileExport = svgArtifactExport(artifact, proof);
  return saveDocument(context, exported.fileName, exported.content);
};

export const saveSceneJsonArtifact = async (
  context: common.UIAbilityContext,
  artifact: ConversationSceneArtifact,
): Promise<boolean> => {
  const exported: SceneArtifactExport = sceneArtifactExport(artifact);
  return saveDocument(context, exported.jsonFileName, exported.json);
};

export const saveSceneCoverArtifact = async (
  context: common.UIAbilityContext,
  artifact: ConversationSceneArtifact,
): Promise<boolean> => {
  const exported: SceneArtifactExport = sceneArtifactExport(artifact);
  return saveDocument(context, exported.coverFileName, exported.cover);
};

export const saveUiSnapshotArtifact = async (
  context: common.UIAbilityContext,
  artifact: ConversationUiSnapshotArtifact,
): Promise<boolean> => {
  const exported: ArtifactFileExport = uiSnapshotArtifactExport(artifact);
  return saveDocument(context, exported.fileName, exported.content);
};

