// fake_xml_pull — 测试共享:token 脚本驱动的 XmlPullPort(D-112/D-113/D-114)
import type { XmlPullEvent, XmlPullFactory, XmlPullPort } from '../main/ets/chat/xml_pull.ts';

export interface Token {
  event: XmlPullEvent;
  name?: string;
  depth?: number;
  text?: string | null;
  attrs?: Record<string, string>;
}

export class FakePull implements XmlPullPort {
  private idx: number = 0; // 初始 START_DOCUMENT(Kotlin parser 同)
  private tokens: Token[];

  constructor(tokens: Token[]) {
    this.tokens = [{ event: 'start_document', depth: 0 }, ...tokens, { event: 'end_document', depth: 0 }];
  }

  private get cur(): Token {
    return this.tokens[this.idx];
  }

  get eventType(): XmlPullEvent {
    return this.cur.event;
  }

  get name(): string {
    return this.cur.name ?? '';
  }

  get depth(): number {
    return this.cur.depth ?? 0;
  }

  get text(): string | null {
    return this.cur.text ?? null;
  }

  getAttributeValue(_namespace: string | null, name: string): string | null {
    const a = this.cur.attrs;
    if (a === undefined) return null;
    return Object.prototype.hasOwnProperty.call(a, name) ? a[name] : null;
  }

  next(): XmlPullEvent {
    if (this.idx < this.tokens.length - 1) this.idx++;
    return this.eventType;
  }
}

export const factoryOf = (tokens: Token[]): XmlPullFactory => (): XmlPullPort => new FakePull(tokens);

export const st = (name: string, depth: number, attrs?: Record<string, string>): Token =>
  ({ event: 'start_tag', name, depth, attrs });
export const et = (name: string, depth: number): Token => ({ event: 'end_tag', name, depth });
export const tx = (text: string, depth: number): Token => ({ event: 'text', text, depth });
