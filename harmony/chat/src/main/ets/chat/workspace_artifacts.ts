import type { AgentToolActivityStore } from './tool_activity.ts';
import type { AgentTool } from './tool.ts';
import type { WorkspaceArchiveToolsDeps } from './workspace_archive_ops.ts';
import { createWorkspaceArchiveTools } from './workspace_archive_ops.ts';
import { createArtifactToolTracker } from './workspace_artifact_tool.ts';
import type { WorkspaceDocumentToolsDeps } from './workspace_document_tools.ts';
import { createWorkspaceDocumentTools } from './workspace_document_tools.ts';
import type { WorkspaceHttpToolsDeps } from './workspace_http_tools.ts';
import { createWorkspaceHttpTools } from './workspace_http_tools.ts';
import type { WorkspaceImageToolsDeps } from './workspace_image_tools.ts';
import { createWorkspaceImageTools } from './workspace_image_tools.ts';

// Workspace artifact capabilities: one tracker, the original eleven-tool order, and shared format implementations.












export interface WorkspaceArtifactToolsDeps extends WorkspaceHttpToolsDeps, WorkspaceArchiveToolsDeps,
  WorkspaceDocumentToolsDeps, WorkspaceImageToolsDeps {
  activityStore: AgentToolActivityStore;
}

export const createWorkspaceArtifactTools = (deps: WorkspaceArtifactToolsDeps): AgentTool[] => {
  const track = createArtifactToolTracker(deps.activityStore);
  return [
    ...createWorkspaceHttpTools(deps, track),
    ...createWorkspaceArchiveTools(deps, track),
    ...createWorkspaceDocumentTools(deps, track),
    ...createWorkspaceImageTools(deps, track),
  ];
};

export { archiveTypeOf, gunzipSplit, MAX_ARCHIVE_BYTES, safeArchiveTarget } from './workspace_archive_ops.ts';
export type { ArchiveType } from './workspace_archive_ops.ts';
export {
  MAX_ARCHIVE_ENTRY_BYTES, mimeFromName, safeBaseName, safeFileName,
} from './workspace_artifact_files.ts';
export {
  MAX_WORKSPACE_TEMP_FILE_BYTES, OFFICE_UNSUPPORTED, parseXlsxText, PDF_BLOCKED_MESSAGE, stripXml,
} from './workspace_document_tools.ts';
export {
  fileNameFromUrl, MAX_DOWNLOAD_BYTES, MAX_HTTP_BODY_BYTES, requireHttpUrl,
} from './workspace_http_tools.ts';
export type { ArtifactHttpPort, ArtifactHttpRequest, ArtifactHttpResponse } from './workspace_http_tools.ts';
export { artifactScaleDims, MAX_IMAGE_BYTES } from './workspace_image_tools.ts';
export type { ArtifactImageInfo, ArtifactImagePort, ArtifactScaleResult } from './workspace_image_tools.ts';
export { buildZipBytes, crc32, dosTimeDate } from './zip_writer.ts';
export type { DeflateRawPort, ZipWriteEntry } from './zip_writer.ts';
