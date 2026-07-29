// subagent_report_tool.test.ts — D-132a Task 3
// Android baselines: SubAgentReportTool.kt and SubAgentReportToolTest.kt (complete files).
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart, UIMessagePartText } from '../main/ets/chat/message.ts';
import {
  SUBAGENT_REPORT_TOOL_NAME, SubAgentReportCapture,
} from '../main/ets/chat/subagent_report_tool.ts';

const outputText = (parts: UIMessagePart[]): string => {
  assert.equal(parts.length, 1);
  const part: UIMessagePartText = parts[0] as UIMessagePartText;
  assert.equal(part.type, 'text');
  assert.equal(part.metadata, null);
  return part.text;
};

const outputPayload = (parts: UIMessagePart[]): JsonObject =>
  JSON.parse(outputText(parts)) as JsonObject;

test('report tool exposes the exact Android name, flags, and protocol-significant schema order', () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  const tool = capture.tool();

  assert.equal(SUBAGENT_REPORT_TOOL_NAME, 'subagent_report');
  assert.equal(tool.name, 'subagent_report');
  assert.equal(tool.description,
    'Record the structured result for the supervisor. Keep writing normal Markdown for the\n' +
    'human live panel; call this once near the end with the concise facts the main agent\n' +
    'should consume. Do not paste JSON or machine-only wrappers into visible text.');
  assert.equal(tool.needsApproval, false);
  assert.equal(tool.allowsAutoApproval, true);
  assert.equal(tool.mandatoryApproval, false);

  const schema = tool.parameters();
  assert.ok(schema !== null);
  if (schema === null) return;
  assert.deepEqual(Object.keys(schema.properties), [
    'summary', 'findings', 'evidence', 'risks',
    'recommended_next_steps', 'confidence', 'error',
  ]);
  assert.deepEqual(schema.required, ['summary']);
  assert.deepEqual(schema.properties['summary'], {
    type: 'string', description: 'One concise answer for the supervisor. Required.',
  });
  assert.deepEqual(schema.properties['findings'], {
    type: 'array', description: 'Key findings or completed work items.', items: { type: 'string' },
  });
  assert.deepEqual(schema.properties['evidence'], {
    type: 'array',
    description: 'Evidence, source ids, files, commands, or observations supporting the findings.',
    items: { type: 'string' },
  });
  assert.deepEqual(schema.properties['risks'], {
    type: 'array', description: 'Known risks, blockers, or uncertainty. Empty if none.',
    items: { type: 'string' },
  });
  assert.deepEqual(schema.properties['recommended_next_steps'], {
    type: 'array', description: 'Concrete next steps for the supervisor. Empty if none.',
    items: { type: 'string' },
  });
  assert.deepEqual(schema.properties['confidence'], {
    type: 'string', description: 'Optional confidence label such as low, medium, or high.',
  });
  assert.deepEqual(schema.properties['error'], {
    type: 'string', description: 'Optional error or blocker text if the task could not be completed.',
  });
});

test('reportToolCapturesSupervisorPayloadWithoutUsingVisibleText', async () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  const output: UIMessagePart[] = await capture.tool().execute({
    summary: 'Reviewed the target files and found one blocking issue.',
    findings: ['Tool search must keep the report tool visible inside subagent runs.'],
    evidence: ['subagent_report'],
    risks: ['Subagent could finish without a structured report.'],
    recommended_next_steps: ['Treat missing report as fallback, not as a crash.'],
    confidence: 'high',
  });

  const ack: JsonObject = outputPayload(output);
  assert.equal(ack['status'], 'ok');
  assert.equal(ack['message'],
    'Structured subagent report recorded. Finish with normal human-readable text.');
  assert.equal(ack['summary_chars'], 55);
  assert.equal(ack['findings_count'], 1);
  assert.equal(ack['evidence_count'], 1);
  assert.equal(ack['risks_count'], 1);
  assert.equal(ack['recommended_next_steps_count'], 1);

  const result = capture.latest;
  assert.ok(result !== null);
  if (result === null) return;
  assert.equal(capture.hasReport, true);
  assert.equal(result.status, 'completed');
  assert.equal(result.summary, 'Reviewed the target files and found one blocking issue.');
  assert.deepEqual(result.findings,
    ['Tool search must keep the report tool visible inside subagent runs.']);
  assert.deepEqual(result.evidence, ['subagent_report']);
  assert.deepEqual(result.risks, ['Subagent could finish without a structured report.']);
  assert.deepEqual(result.recommendedNextSteps,
    ['Treat missing report as fallback, not as a crash.']);
  assert.equal(result.confidence, 'high');
  assert.equal(result.error, '');
});

test('reportToolCapturesOptionalErrorField', async () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();

  await capture.tool().execute({
    summary: 'Could not finish', error: 'blocked by missing file',
  });

  assert.equal(capture.latest?.error, 'blocked by missing file');
  assert.equal(capture.latest?.status, 'failed');
});

test('execute enforces Android normalized summary-or-findings validation and exact failure', async () => {
  const emptyCapture: SubAgentReportCapture = new SubAgentReportCapture();
  const rejectedInputs: JsonValue[] = [
    {},
    { summary: '   ', findings: ['', '   ', null, { ignored: true }] },
    { error: 'blocked without a summary or findings' },
    null,
    'primitive top level',
    7,
    true,
    [],
  ];
  for (const input of rejectedInputs) {
    const payload: JsonObject = outputPayload(await emptyCapture.tool().execute(input));
    assert.equal(payload['status'], 'failed');
    assert.equal(payload['message'],
      'subagent_report requires a non-empty summary or findings.');
    assert.equal(emptyCapture.hasReport, false);
    assert.equal(emptyCapture.latest, null);
  }

  const findingsCapture: SubAgentReportCapture = new SubAgentReportCapture();
  const accepted: JsonObject = outputPayload(await findingsCapture.tool().execute({
    findings: '  finding without schema-required summary  ',
  }));
  assert.equal(accepted['status'], 'ok');
  assert.equal(findingsCapture.latest?.summary, '');
  assert.deepEqual(findingsCapture.latest?.findings,
    ['finding without schema-required summary']);
});

test('execute matches Android trim, primitive, malformed-shape, filter, and default semantics', async () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  await capture.tool().execute({
    summary: 7,
    findings: ['  alpha  ', '', '   ', 42, true, null, { ignored: true }, ['ignored']],
    evidence: '  scalar evidence  ',
    risks: { ignored: true },
    recommended_next_steps: false,
    confidence: ['ignored'],
    error: { ignored: true },
  });

  const result = capture.latest;
  assert.ok(result !== null);
  if (result === null) return;
  assert.equal(result.status, 'completed');
  assert.equal(result.summary, '7');
  assert.deepEqual(result.findings, ['alpha', '42', 'true']);
  assert.deepEqual(result.evidence, ['scalar evidence']);
  assert.deepEqual(result.risks, []);
  assert.deepEqual(result.recommendedNextSteps, ['false']);
  assert.equal(result.confidence, '');
  assert.equal(result.error, '');
});

test('execute applies every Android truncation bound and reports normalized counts', async () => {
  const thirteen: JsonValue[] = [];
  for (let index = 0; index < 13; index += 1) {
    thirteen.push(`item-${index}-${'x'.repeat(1100)}`);
  }
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  const ack: JsonObject = outputPayload(await capture.tool().execute({
    summary: `  ${'s'.repeat(4100)}  `,
    findings: thirteen,
    evidence: thirteen,
    risks: thirteen,
    recommended_next_steps: thirteen,
    confidence: `  ${'c'.repeat(70)}  `,
    error: `  ${'e'.repeat(1100)}  `,
  }));

  const result = capture.latest;
  assert.ok(result !== null);
  if (result === null) return;
  assert.equal(result.status, 'failed');
  assert.equal(result.summary.length, 4000);
  assert.equal(result.confidence.length, 64);
  assert.equal(result.error.length, 1000);
  assert.equal(result.findings.length, 12);
  assert.equal(result.evidence.length, 12);
  assert.equal(result.risks.length, 12);
  assert.equal(result.recommendedNextSteps.length, 12);
  for (const list of [
    result.findings, result.evidence, result.risks, result.recommendedNextSteps,
  ]) {
    for (const item of list) assert.equal(item.length, 1000);
  }
  assert.equal(ack['summary_chars'], 4000);
  assert.equal(ack['findings_count'], 12);
  assert.equal(ack['evidence_count'], 12);
  assert.equal(ack['risks_count'], 12);
  assert.equal(ack['recommended_next_steps_count'], 12);
});

test('capture keeps the latest valid report and a rejected call does not replace it', async () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  await capture.tool().execute({ summary: 'First report', findings: ['A'] });
  await capture.tool().execute({ summary: 'Second report', findings: ['B'] });
  assert.equal(capture.latest?.summary, 'Second report');
  assert.deepEqual(capture.latest?.findings, ['B']);

  await capture.tool().execute({ summary: ' ', findings: [] });
  assert.equal(capture.latest?.summary, 'Second report');
  assert.deepEqual(capture.latest?.findings, ['B']);
});

test('fallbackUsesVisibleTextOnlyWhenReportWasNotCaptured', () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();

  const result = capture.resultOrFallback(
    'Human-readable body that should stay visible in the panel.');

  assert.equal(result.status, 'completed');
  assert.equal(result.summary,
    'Human-readable body that should stay visible in the panel.');
  assert.deepEqual(result.findings, []);
  assert.deepEqual(result.evidence, []);
  assert.deepEqual(result.risks, [
    'Subagent finished without calling subagent_report; summary was derived from visible text.',
  ]);
  assert.equal(result.confidence, '');
  assert.deepEqual(result.recommendedNextSteps, []);
  assert.equal(result.error, '');
});

test('structuredReportWinsOverVisibleTextForSupervisor', async () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  await capture.tool().execute({
    summary: 'Short structured result', findings: ['A'], evidence: ['B'],
  });

  const result = capture.resultOrFallback(
    'Very long Markdown body intended for the human sheet.');

  assert.equal(result.summary, 'Short structured result');
  assert.deepEqual(result.findings, ['A']);
  assert.deepEqual(result.evidence, ['B']);
});

test('fallbackSummaryIsCappedForSupervisorContext', () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  const result = capture.resultOrFallback('x'.repeat(5000));

  assert.equal(result.summary.length, 4000);
});

test('blank fallback uses the exact Android default text', () => {
  const capture: SubAgentReportCapture = new SubAgentReportCapture();
  const result = capture.resultOrFallback('   \n  ');

  assert.equal(result.summary,
    'Subagent completed without structured report or text output.');
  assert.deepEqual(result.risks, [
    'Subagent finished without calling subagent_report; summary was derived from visible text.',
  ]);
});
