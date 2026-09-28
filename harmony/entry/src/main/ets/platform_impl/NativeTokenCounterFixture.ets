// Debug-only fixed native fixtures. EntryAbility gates both lifecycle calls by BuildProfile.DEBUG.
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import native from 'libamber_native.so';

const DOMAIN: number = 0x0001;
const TAG: string = 'Amber.NativeFixture';
const TOKENIZER_IDS: string[] = ['o200k_base', 'cl100k_base', 'claude', 'gemini'];

interface TokenizerFixture {
  id: string;
  text: string;
  expected: number[];
}

interface FixtureResult {
  runId: string;
  fixtureId: string;
  actual: number[];
  expected: number[];
  pass: boolean;
}

interface FixtureSummary {
  runId: string;
  fixtureId: string;
  completed: number;
  expected: number;
  pass: boolean;
}

const logResult = (runId: string, id: string, actual: number[], expected: number[]): boolean => {
  let pass: boolean = actual.length === expected.length;
  for (let i: number = 0; pass && i < expected.length; i++) {
    pass = actual[i] === expected[i];
  }
  const result: FixtureResult = { runId: runId, fixtureId: id, actual: actual, expected: expected, pass: pass };
  hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify(result));
  return pass;
};

const expectRejected = async (
  runId: string, id: string, ids: string[], texts: string[], expectedMessage: string,
): Promise<boolean> => {
  let matched: boolean = false;
  try {
    await native.countBatch(ids, texts);
  } catch (error) {
    matched = error instanceof Error && error.message === expectedMessage;
  }
  return logResult(runId, id, [matched ? 1 : 0], [1]);
};

const runFixtures = async (runId: string): Promise<void> => {
  const longText: string = '你好, world! 🦀\n'.repeat(4096);
  const fixtures: TokenizerFixture[] = [
    { id: 'empty-text', text: '', expected: [0, 0, 0, 0] },
    { id: 'ascii', text: 'Hello world', expected: [2, 2, 4, 3] },
    { id: 'cjk', text: '你好，世界', expected: [3, 6, 5, 4] },
    { id: 'emoji', text: '🦀🚀🙂', expected: [6, 8, 4, 3] },
    { id: 'newline', text: 'line 1\nline 2', expected: [7, 7, 4, 4] },
    { id: 'nul', text: 'a\u0000b', expected: [3, 3, 1, 1] },
    { id: 'special-literal', text: '<|endoftext|>', expected: [7, 7, 4, 4] },
    { id: 'long-mixed', text: longText, expected: [32768, 36864, 23406, 20480] },
  ];
  let completed: number = 0;
  let pass: boolean = true;
  try {
    for (const fixture of fixtures) {
      const actual: number[] = await native.countBatch(TOKENIZER_IDS,
        [fixture.text, fixture.text, fixture.text, fixture.text]);
      const matched: boolean = logResult(runId, fixture.id, actual, fixture.expected);
      pass = matched && pass;
      completed++;
    }
    const empty: number[] = await native.countBatch([], []);
    pass = logResult(runId, 'empty-batch', empty, []) && pass;
    completed++;

    pass = await expectRejected(runId, 'unequal-length', ['o200k_base'], [],
      'tokenizerIds and texts must have the same length') && pass;
    completed++;
    pass = await expectRejected(runId, 'unknown-id', ['unknown-fixed-fixture'], ['Hello world'],
      'Unknown tokenizer ID') && pass;
    completed++;
    // Fixed JSON is intentionally malformed for the declared TS type to reach real NAPI validation.
    const wrongIdsArray: string[] = JSON.parse('{"not":"array"}') as string[];
    const wrongTextsArray: string[] = JSON.parse('{"not":"array"}') as string[];
    const wrongIdElement: string[] = JSON.parse('[1]') as string[];
    const wrongTextElement: string[] = JSON.parse('[1]') as string[];
    pass = await expectRejected(runId, 'ids-not-array', wrongIdsArray, ['Hello world'],
      'tokenizerIds and texts must be arrays') && pass;
    completed++;
    pass = await expectRejected(runId, 'texts-not-array', ['o200k_base'], wrongTextsArray,
      'tokenizerIds and texts must be arrays') && pass;
    completed++;
    pass = await expectRejected(runId, 'id-not-string', wrongIdElement, ['Hello world'],
      'tokenizerIds and texts must contain only strings') && pass;
    completed++;
    pass = await expectRejected(runId, 'text-not-string', ['o200k_base'], wrongTextElement,
      'tokenizerIds and texts must contain only strings') && pass;
    completed++;

    const concurrent: number[][] = await Promise.all([
      native.countBatch(['o200k_base', 'cl100k_base'], ['Hello world', '你好，世界']),
      native.countBatch(['claude', 'gemini'], ['🦀🚀🙂', longText]),
    ]);
    pass = logResult(runId, 'concurrent-bpe', concurrent[0], [2, 6]) && pass;
    completed++;
    pass = logResult(runId, 'concurrent-byte', concurrent[1], [4, 20480]) && pass;
    completed++;
    const recovered: number[] = await native.countBatch(['o200k_base'], ['Hello world']);
    pass = logResult(runId, 'after-rejections', recovered, [2]) && pass;
    completed++;
  } catch (_error) {
    // No raw exception text: logs contain only fixed fixture IDs and numeric results.
    pass = false;
  }
  const summary: FixtureSummary = {
    runId: runId, fixtureId: 'complete', completed: completed, expected: 18,
    pass: pass && completed === 18,
  };
  hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify(summary));
};

export const runNativeTokenCounterFixture = (want: Want): void => {
  if (want.parameters?.['amberNativeFixture'] !== 'tokenizer-v1') return;
  const runId: string = String(want.parameters?.['amberNativeRunId'] ?? '').trim();
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(runId)) return;
  runFixtures(runId).catch((): void => {
    const summary: FixtureSummary = {
      runId: runId, fixtureId: 'complete', completed: 0, expected: 18, pass: false,
    };
    hilog.error(DOMAIN, TAG, '%{public}s', JSON.stringify(summary));
  });
};
