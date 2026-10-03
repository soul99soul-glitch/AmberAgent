// 小说项目专用工具：沿 iOS 的名称和参数，只委托当前项目的领域操作。
// 所有修改由领域层生成 durable proposal，作者在提案界面批准后才写入作品。
import type { NovelCreation, NovelProjectToolInput } from '@amber/deepread-domain';
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool } from './tool.ts';

export type NovelProjectOperationPort = (name: string, input: JsonObject) => Promise<JsonObject>;

export const executeNovelProjectOperation = async (
  creation: NovelCreation, projectId: string, name: string, input: JsonObject,
): Promise<JsonObject> => await creation.executeProjectTool(
  projectId, name, input as unknown as NovelProjectToolInput) as unknown as JsonObject;

const text = (minLength: number = 0): JsonObject => ({ type: 'string', minLength });
const positiveInteger = (): JsonObject => ({ type: 'integer', minimum: 1 });
const strings = (): JsonObject => ({ type: 'array', items: { type: 'string' } });
const arcBeats = (minItems: number): JsonObject => ({
  type: 'array', items: { type: 'string', minLength: 1, maxLength: 160 }, minItems, maxItems: 8,
});
const chapterTarget = (): JsonObject => ({
  chapter_ordinal: positiveInteger(),
  chapter_id: { ...text(1), description: 'Chapter id; takes precedence over chapter_ordinal. Omit both for the latest chapter.' },
});
const planProperties = (): JsonObject => ({
  outline_placement: text(), goal_and_conflict: text(1), must_happen: strings(),
  must_not_happen: strings(), ending_hook: text(), visible_facts: strings(),
});
const planRequired = (): string[] => [
  'outline_placement', 'goal_and_conflict', 'must_happen', 'must_not_happen', 'ending_hook', 'visible_facts',
];

const objectSchema = (properties: JsonObject, required: string[], extra: JsonObject = {}): InputSchemaObj => ({
  type: 'object', properties, required,
  // jsonSchema keeps additionalProperties and compositions in provider requests.
  jsonSchema: { type: 'object', properties, required, additionalProperties: false, ...extra },
});

interface OperationDefinition {
  name: string;
  description: string;
  properties: JsonObject;
  required: string[];
  extra?: JsonObject;
}

const definitions = (): OperationDefinition[] => [
  {
    name: 'novel_rename_project', description: 'Propose renaming the novel project; title is the project name, not a chapter title.',
    properties: { title: text(1), reason: text() }, required: ['title'],
  },
  {
    name: 'novel_set_polish_preference', description: 'Propose updating the project polish preference. An empty string clears it.',
    properties: { preference: { ...text(), maxLength: 8000 } }, required: ['preference'],
  },
  {
    name: 'novel_upsert_upcoming_arc', description: 'Propose replacing this branch’s upcoming arc with 1–8 short soft-direction beats.',
    properties: { beats: arcBeats(1) }, required: ['beats'],
  },
  {
    name: 'novel_clear_upcoming_arc', description: 'Propose clearing this branch’s existing upcoming arc.',
    properties: {}, required: [],
  },
  {
    name: 'novel_revise_material', description: 'Propose creating or updating a setting material. Existing material_id must keep its kind; aliases apply to characters.',
    properties: {
      material_id: text(1), kind: { type: 'string', enum: [
        'world', 'character', 'relationship', 'masterOutline', 'writingRequirements', 'custom',
      ] }, title: text(1), content: text(), aliases: strings(), custom_name: text(), tags: strings(),
      injection_mode: { type: 'string', enum: ['always', 'smart', 'off'] },
    }, required: ['kind', 'title', 'content'],
  },
  {
    name: 'novel_propose_chapter_plan', description: 'Propose a draft chapter plan; it must be confirmed by the author before ghostwriting.',
    properties: planProperties(), required: planRequired(),
  },
  {
    name: 'novel_prepare_ghostwrite', description: 'Stage the agreed chapter plan, upcoming arc and suggested batch size for author review. Nothing is saved or started before author approval.',
    properties: {
      ...planProperties(), must_happen: { ...strings(), minItems: 1, maxItems: 32 },
      must_not_happen: { ...strings(), maxItems: 32 }, visible_facts: { ...strings(), maxItems: 32 },
      upcoming_arc: arcBeats(0), suggested_chapter_count: { type: 'integer', minimum: 1, maximum: 10 }, reason: text(),
    }, required: [...planRequired(), 'upcoming_arc', 'suggested_chapter_count'],
  },
  {
    name: 'novel_set_chapter_title', description: 'Propose renaming a working chapter without changing its prose; omit the target for the latest chapter.',
    properties: { ...chapterTarget(), title: text(1) }, required: ['title'],
  },
  {
    name: 'novel_list_chapters', description: 'Read-only: list current branch working chapters with ordinal, title, character count, paragraph count and chapter_id.',
    properties: {}, required: [],
  },
  {
    name: 'novel_read_chapter', description: 'Read-only: read a working chapter as numbered paragraphs. Paragraph bounds are 1-based inclusive; omit the chapter target for the latest chapter.',
    properties: { ...chapterTarget(), start_paragraph: positiveInteger(), end_paragraph: positiveInteger() }, required: [],
  },
  {
    name: 'novel_revise_chapter', description: 'Stage a paragraph-range replacement with old/new text for author approval. Use inclusive paragraph numbers from novel_read_chapter; never claim it is already applied.',
    properties: { ...chapterTarget(), start_paragraph: positiveInteger(), end_paragraph: positiveInteger(),
      new_text: { ...text(1), maxLength: 32000 }, reason: text() },
    required: ['start_paragraph', 'end_paragraph', 'new_text'],
  },
  {
    name: 'novel_revert_recent_chapters', description: 'Stage rewinding the latest N working chapters and their plot snapshots for author approval; this is a suffix rewind, not a middle-chapter deletion.',
    properties: { chapter_count: { type: 'integer', minimum: 1, maximum: 64 }, reason: text() }, required: ['chapter_count'],
  },
  {
    name: 'novel_delete_chapters', description: 'Stage removing selected working chapters, including middle chapters, for author approval. Provide chapter_ordinals and/or chapter_ids; plot state will require sync after approval.',
    properties: {
      chapter_ordinals: { type: 'array', items: positiveInteger(), minItems: 1, maxItems: 64 },
      chapter_ids: { type: 'array', items: text(1), minItems: 1, maxItems: 64 }, reason: text(),
    }, required: [], extra: { anyOf: [{ required: ['chapter_ordinals'] }, { required: ['chapter_ids'] }] },
  },
  {
    name: 'novel_list_setting_proposals', description: 'Read-only: list pending setting suggestions on the active branch. These suggestions are not saved setting materials.',
    properties: {}, required: [],
  },
  {
    name: 'novel_reject_setting_proposals', description: 'Propose rejecting pending setting suggestions without creating materials. Omit proposal_ids or pass an empty array to reject all active suggestions.',
    properties: { proposal_ids: strings() }, required: [],
  },
];

export const createNovelProjectOperationTools = (execute: NovelProjectOperationPort): AgentTool[] =>
  definitions().map((definition: OperationDefinition): AgentTool => makeAgentTool({
    name: definition.name,
    description: definition.description + (definition.name === 'novel_list_chapters'
      || definition.name === 'novel_read_chapter' || definition.name === 'novel_list_setting_proposals'
      ? '' : ' The result contains proposal_id and requires_author_approval; only staging occurs here.'),
    parameters: (): InputSchemaObj => objectSchema(definition.properties, definition.required, definition.extra),
    execute: (input: JsonValue): Promise<UIMessagePart[]> => execute(definition.name, input as JsonObject)
      .then((payload: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(payload), metadata: null }]),
  }));
