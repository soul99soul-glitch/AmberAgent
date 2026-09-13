// Batch 4 (UI-F29) regression probe — executes the REAL DeepReadTemplate.ets.
// Asserts the editorial_slant template consumes display preferences via safe CSS:
//  - fontScale drives :root{--ds:<n>} (clamped, fixed-point)
//  - fontSerif toggles the body.ds-serif class
//  - default (no opts) renders --ds:1.000 and no serif class
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const ts = require('/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/node_modules/typescript');
const SRC = '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/entry/src/main/ets/platform_impl/DeepReadTemplate.ets';

const js = ts.transpileModule(readFileSync(SRC, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 },
  fileName: SRC,
}).outputText;

const mod = { exports: {} };
new vm.Script(`(function(require,module,exports){${js}\n})`).runInContext(vm.createContext({}))
  (() => { throw new Error('DeepReadTemplate.ets should not require runtime modules beyond types'); }, mod, mod.exports);

const emptyOutput = {
  topicType: 'event', generationComplete: false, generationPhase: 'IDLE', summary: '',
  keyEntities: [], timeline: null, corePoints: null,
  analysis: { coreDispute: null, perspectives: [], implications: null, quotes: [] },
  extendedReading: [], heroImageQuery: null, heroImageUrl: null, heroCaption: null,
  heroImageConfidence: null, imageAssets: [], diagram: null, visualDiagnostics: null,
  references: [], sectionStates: {}, sectionQualities: {},
};

let failures = 0;
const check = (label, cond) => { console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`); if (!cond) failures++; };

const base = mod.exports.renderEditorialSlantHtml('标题', emptyOutput);
check('default keeps --ds:1.000', base.includes(':root{--ds:1.000}'));
check('default html uses calc() font sizing', base.includes('calc(30px * var(--ds))'));
check('default has no serif body class', base.includes('<body>') && !base.includes('class="ds-serif"'));

const big = mod.exports.renderEditorialSlantHtml('标题', emptyOutput, { fontScale: 1.25, fontSerif: true });
check('fontScale 1.25 -> --ds:1.250', big.includes(':root{--ds:1.250}'));
check('fontSerif true -> body.ds-serif', big.includes('<body class="ds-serif">'));

const clamped = mod.exports.renderEditorialSlantHtml('标题', emptyOutput, { fontScale: 99 });
check('fontScale clamped to 1.3', clamped.includes(':root{--ds:1.300}'));
const clampedLow = mod.exports.renderEditorialSlantHtml('标题', emptyOutput, { fontScale: -5 });
check('fontScale clamped to 0.8', clampedLow.includes(':root{--ds:0.800}'));

check('no javascript: URL emitted', !base.includes('javascript:'));
check('js style untouched (css only)', base.includes('calc(') && !base.includes('<script'));

console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILURE(S)`}`);
process.exit(failures === 0 ? 0 : 1);
