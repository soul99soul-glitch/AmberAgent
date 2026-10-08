import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createSectionWriterTools } from '../main/ets/agent/section_writer_tools.ts';
import { validateJsonSchema } from '../main/ets/platform/json_schema_validator.ts';
import type { JsonSchema } from '../main/ets/platform/json_schema_validator.ts';
import type { ScoredImageCandidate } from '../main/ets/research/image_scorer.ts';

const candidate: ScoredImageCandidate = {
  url: 'https://public.example/hero.jpg', width: 1280, height: 720,
  altText: 'Research event', sourceUrl: 'https://public.example/article',
  confidence: 'hero', score: 80, riskFlags: [], selectionReason: 'relevant',
};
const createWriter = () => createSectionWriterTools({
  topicId: 'topic', topicTitle: 'Research event', imageCandidates: [candidate],
});

test('model-visible visual contract selects actual candidate images', async () => {
  const writer = createWriter();
  const tool = writer.tools().find(item => item.name === 'deep_read_write_visuals')!;
  const schema = tool.schema as JsonSchema;
  assert.ok(schema.properties?.hero_image_url, 'hero URL accepted by executor must be visible to model');
  assert.ok(schema.properties?.image_assets?.items?.properties?.url, 'image item URL must be declared');
  const input = { hero_image_url: candidate.url, hero_caption: 'Caption', hero_reason: 'Candidate matches',
    image_assets: [{ url: candidate.url, caption: 'Inline caption', source: 'article', quality_hint: 'clear', selection_reason: 'relevant' }] };
  assert.equal(validateJsonSchema(input, schema).valid, true);
  await tool.execute(JSON.stringify(input));
  assert.equal(writer.current().heroImageUrl, candidate.url);
  assert.equal(writer.current().heroCaption, 'Caption');
  assert.ok(writer.current().imageAssets.some(item => item.url === candidate.url));
  await tool.execute(JSON.stringify({ hero_image_url: 'https://not-in-pool.example/x.jpg',
    image_assets: [{ url: 'https://not-in-pool.example/x.jpg' }] }));
  assert.equal(writer.current().heroImageUrl, candidate.url, 'unknown candidates do not overwrite existing hero');
  assert.ok(!writer.current().imageAssets.some(item => item.url.includes('not-in-pool')));
});

test('model-visible diagram contract declares a usable type and node/edge identities', async () => {
  const writer = createWriter();
  const tool = writer.tools().find(item => item.name === 'deep_read_write_diagram')!;
  const schema = tool.schema as JsonSchema;
  assert.ok(schema.properties?.type?.enum?.includes('process_flow'), 'accepted diagram types must be declared');
  assert.ok(schema.properties?.nodes?.items?.properties?.id);
  assert.ok(schema.properties?.edges?.items?.properties?.from);
  const input = { type: 'process_flow', title: 'Process', nodes: [{ id: 'a', label: 'Start' }, { id: 'b', label: 'End' }],
    edges: [{ from: 'a', to: 'b', label: 'next' }], caption: 'Process explanation' };
  assert.equal(validateJsonSchema(input, schema).valid, true);
  const response = await tool.execute(JSON.stringify(input));
  assert.equal(JSON.parse(response[0].text).status, 'ok');
  assert.equal(writer.current().diagram?.type, 'process_flow');
  assert.equal(writer.current().diagram?.edges.length, 1);
  const missingType = { title: input.title, nodes: input.nodes, edges: input.edges };
  assert.equal(validateJsonSchema(missingType, schema).valid, false, 'missing mandatory type is rejected at contract');
  assert.equal(validateJsonSchema({ ...input, type: 'unknown' }, schema).valid, false);
});
