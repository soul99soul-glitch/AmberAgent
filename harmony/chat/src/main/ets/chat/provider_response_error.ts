import type { MessageChunk } from './message.ts';

// Non-stream protocols may return useful text together with a failed terminal.
// Keep that text available to the existing raw checkpoint while still rejecting
// the generation, so no tool execution or successful completion can follow it.
export class ProviderResponseError extends Error {
  readonly partialChunk: MessageChunk | null;

  constructor(message: string, partialChunk: MessageChunk | null = null) {
    super(message);
    this.name = 'ProviderResponseError';
    this.partialChunk = partialChunk;
  }
}
