// Compatibility facade. The canonical UIMessage schema lives in DeepRead so
// both domain modules consume one physical definition without a reverse edge.

export type {
  MessageRole, StreamTransportState, ToolApprovalState,
  UIMessagePart, UIMessagePartText, UIMessagePartImage, UIMessagePartVideo,
  UIMessagePartAudio, UIMessagePartDocument, UIMessagePartMiniApp,
  UIMessagePartReasoning, UIMessagePartTool,
  UIMessageUrlContextAnnotation, UIMessageGoogleSearchSuggestionsAnnotation,
  UIMessageAnnotation, UIMessage, UIMessageOpts, UIMessageChoice, MessageChunk,
} from '@amber/deepread-domain';

export {
  makeUIMessage, makeSystemMessage, makeUserMessage, makeAssistantMessage, finishAssistantMessage,
  canResumeToolExecution, isToolExecuted, isToolPending, canToolResumeExecution,
  toolInputAsJson, toText, reasoningPartText, summaryAsText, getTools, isValidToUpload,
  hasBase64Part, isEmptyInputMessage, isEmptyUIMessage,
} from '@amber/deepread-domain';
export {
  CLAUDE_REDACTED_THINKING_METADATA_KEY, CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY,
  hasProtocolReasoningContent, reasoningBlocksCanMerge, mergeReasoningMetadata,
} from '@amber/deepread-domain';
