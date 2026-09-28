import type { FileStore, CouncilSeatRunKey } from '@amber/deepread-domain';
import type { Conversation, ConversationStore } from '@amber/chat-domain';
import { currentMessages } from '@amber/chat-domain';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';

export interface CouncilSeatStore extends ConversationStore {
  load(): Promise<Conversation | null>;
}

interface CouncilSeatEnvelope {
  version: number;
  key: CouncilSeatRunKey;
  conversation: Conversation;
}

class FileCouncilSeatStore implements CouncilSeatStore {
  private files: FileStore;
  private key: CouncilSeatRunKey;
  private path: string;
  private tail: Promise<void> = Promise.resolve();

  constructor(files: FileStore, key: CouncilSeatRunKey) {
    if (key.runId.length === 0 || key.seatId.length === 0 || !Number.isInteger(key.round) || key.round < 0) {
      throw new Error('Invalid Council seat key');
    }
    this.files = files;
    this.key = { runId: key.runId, round: key.round, seatId: key.seatId };
    this.path = `model-council/seats/${sha256HexUtf8(JSON.stringify(this.key))}.json`;
  }

  async load(): Promise<Conversation | null> {
    await this.tail;
    const text: string | null = await this.files.readText(this.path);
    if (text === null) return null;
    const envelope: CouncilSeatEnvelope = JSON.parse(text) as CouncilSeatEnvelope;
    if (envelope.version !== 1 || JSON.stringify(envelope.key) !== JSON.stringify(this.key)
      || envelope.conversation.id.length === 0 || !Array.isArray(envelope.conversation.messageNodes)) {
      throw new Error('Invalid Council seat checkpoint');
    }
    for (const node of envelope.conversation.messageNodes) {
      if (!Array.isArray(node.messages) || node.messages.length === 0
        || !Number.isInteger(node.selectIndex) || node.selectIndex < 0 || node.selectIndex >= node.messages.length) {
        throw new Error('Invalid Council seat message node');
      }
    }
    // Exercise selected-node validity without replacing IDs, branches or metadata.
    currentMessages(envelope.conversation);
    return envelope.conversation;
  }

  save(conversation: Conversation): Promise<void> {
    const envelope: CouncilSeatEnvelope = { version: 1, key: this.key, conversation: conversation };
    // Freeze the complete snapshot at invocation time, before entering the write queue.
    const text: string = JSON.stringify(envelope);
    const write: Promise<void> = this.tail.then(async (): Promise<void> => {
      await this.files.writeText(this.path, text);
      // FileStore's production text write can be partial: never publish that as committed.
      if (await this.files.readText(this.path) !== text) throw new Error('Council seat incomplete checkpoint write');
    });
    this.tail = write.then((): void => {}, (): void => {});
    return write;
  }
}

export const createCouncilSeatStore = (files: FileStore, key: CouncilSeatRunKey): CouncilSeatStore =>
  new FileCouncilSeatStore(files, key);
