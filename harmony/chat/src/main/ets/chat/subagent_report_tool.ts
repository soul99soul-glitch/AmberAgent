// subagent_report_tool — internal structured result capture (D-132a Task 3)
// Android baseline: feature/subagent/.../SubAgentReportTool.kt (complete file).

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { SubAgentResult } from './subagent_models.ts';
import { makeSubAgentResult } from './subagent_models.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

export const SUBAGENT_REPORT_TOOL_NAME: string = 'subagent_report';

const SUMMARY_MAX_CHARS: number = 4000;
const CONFIDENCE_MAX_CHARS: number = 64;
const ERROR_MAX_CHARS: number = 1000;
const LIST_MAX_ITEMS: number = 12;
const LIST_ITEM_MAX_CHARS: number = 1000;

const bounded = (value: string, maxChars: number): string =>
  value.length <= maxChars ? value : value.slice(0, maxChars).trimEnd();

const primitiveContentOrNull = (value: JsonValue | undefined): string | null => {
  if (value === undefined || value === null || typeof value === 'object') return null;
  return String(value);
};

const jsonObjectOrEmpty = (value: JsonValue): JsonObject => {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) return value;
  return {};
};

const objectString = (object: JsonObject, name: string): string =>
  (primitiveContentOrNull(object[name]) ?? '').trim();

const objectStringList = (object: JsonObject, name: string): string[] => {
  const value: JsonValue | undefined = object[name];
  if (value === undefined) return [];
  const raw: string[] = [];
  if (Array.isArray(value)) {
    for (const item of value) {
      const content: string | null = primitiveContentOrNull(item);
      if (content !== null) raw.push(content);
    }
  } else {
    const content: string | null = primitiveContentOrNull(value);
    if (content !== null) raw.push(content);
  }
  const result: string[] = [];
  for (const item of raw) {
    const normalized: string = bounded(item.trim(), LIST_ITEM_MAX_CHARS);
    if (normalized.trim().length > 0) result.push(normalized);
    if (result.length === LIST_MAX_ITEMS) break;
  }
  return result;
};

const stringProperty = (description: string): JsonObject => ({
  type: 'string',
  description,
});

const arrayProperty = (description: string): JsonObject => ({
  type: 'array',
  description,
  items: { type: 'string' },
});

const reportProperties = (): JsonObject => {
  const properties: JsonObject = {};
  properties['summary'] = stringProperty('One concise answer for the supervisor. Required.');
  properties['findings'] = arrayProperty('Key findings or completed work items.');
  properties['evidence'] = arrayProperty(
    'Evidence, source ids, files, commands, or observations supporting the findings.');
  properties['risks'] = arrayProperty('Known risks, blockers, or uncertainty. Empty if none.');
  properties['recommended_next_steps'] = arrayProperty(
    'Concrete next steps for the supervisor. Empty if none.');
  properties['confidence'] = stringProperty(
    'Optional confidence label such as low, medium, or high.');
  properties['error'] = stringProperty(
    'Optional error or blocker text if the task could not be completed.');
  return properties;
};

const toSubAgentResult = (input: JsonValue): SubAgentResult => {
  const object: JsonObject = jsonObjectOrEmpty(input);
  const error: string = bounded(objectString(object, 'error'), ERROR_MAX_CHARS);
  return makeSubAgentResult({
    status: error.length > 0 ? 'failed' : 'completed',
    summary: bounded(objectString(object, 'summary'), SUMMARY_MAX_CHARS),
    findings: objectStringList(object, 'findings'),
    evidence: objectStringList(object, 'evidence'),
    risks: objectStringList(object, 'risks'),
    confidence: bounded(objectString(object, 'confidence'), CONFIDENCE_MAX_CHARS),
    recommendedNextSteps: objectStringList(object, 'recommended_next_steps'),
    error,
  });
};

const textOutput = (payload: JsonObject): UIMessagePart[] => [{
  type: 'text',
  text: JSON.stringify(payload),
  metadata: null,
}];

export class SubAgentReportCapture {
  private latestResult: SubAgentResult | null = null;

  get latest(): SubAgentResult | null {
    return this.latestResult;
  }

  get hasReport(): boolean {
    return this.latestResult !== null;
  }

  tool(): AgentTool {
    return makeAgentTool({
      name: SUBAGENT_REPORT_TOOL_NAME,
      description:
        'Record the structured result for the supervisor. Keep writing normal Markdown for the\n' +
        'human live panel; call this once near the end with the concise facts the main agent\n' +
        'should consume. Do not paste JSON or machine-only wrappers into visible text.',
      parameters: () => makeInputSchemaObj(reportProperties(), ['summary']),
      needsApproval: false,
      allowsAutoApproval: true,
      execute: (input: JsonValue): Promise<UIMessagePart[]> => {
        const result: SubAgentResult = toSubAgentResult(input);
        if (result.summary.trim().length === 0 && result.findings.length === 0) {
          const failure: JsonObject = {};
          failure['status'] = 'failed';
          failure['message'] = 'subagent_report requires a non-empty summary or findings.';
          return Promise.resolve(textOutput(failure));
        }
        this.latestResult = result;
        const acknowledgment: JsonObject = {};
        acknowledgment['status'] = 'ok';
        acknowledgment['message'] =
          'Structured subagent report recorded. Finish with normal human-readable text.';
        acknowledgment['summary_chars'] = result.summary.length;
        acknowledgment['findings_count'] = result.findings.length;
        acknowledgment['evidence_count'] = result.evidence.length;
        acknowledgment['risks_count'] = result.risks.length;
        acknowledgment['recommended_next_steps_count'] = result.recommendedNextSteps.length;
        return Promise.resolve(textOutput(acknowledgment));
      },
    });
  }

  resultOrFallback(displayText: string): SubAgentResult {
    if (this.latestResult !== null) return this.latestResult;
    const normalized: string = bounded(displayText.trim(), SUMMARY_MAX_CHARS);
    return makeSubAgentResult({
      status: 'completed',
      summary: normalized.length > 0
        ? normalized
        : 'Subagent completed without structured report or text output.',
      risks: [
        'Subagent finished without calling subagent_report; summary was derived from visible text.',
      ],
    });
  }
}
