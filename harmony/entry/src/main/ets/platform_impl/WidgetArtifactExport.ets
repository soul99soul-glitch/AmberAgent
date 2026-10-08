// Widget HTML uses the same sanitized document as its local preview.
import type { ConversationWidgetArtifact } from '@amber/chat-domain';
import { widgetArtifactExport } from '@amber/chat-domain';
import { common } from '@kit.AbilityKit';
import { saveMarkdownFile } from './ChatExportFiles.ets';

export const saveWidgetArtifact = (
  context: common.UIAbilityContext, artifact: ConversationWidgetArtifact, dark: boolean,
): Promise<boolean> => {
  const document = widgetArtifactExport(artifact, dark);
  return saveMarkdownFile(context, document.fileName, document.content);
};
