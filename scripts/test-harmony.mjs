import { readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const require = createRequire(import.meta.url);
const tsx = require.resolve('tsx/cli', { paths: [join(root, 'harmony/chat')] });

function testFiles(directory) {
    return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
        const file = join(directory, entry.name);
        if (entry.isDirectory()) return testFiles(file);
        return /\.test\.(ts|cjs)$/.test(entry.name) ? [file] : [];
    });
}

const files = [
    'harmony/chat/src/test', 'harmony/deepread/src/test',
    'harmony/entry/src/test', 'harmony/tests',
].flatMap(directory => testFiles(join(root, directory))).sort();
files.push(join(root, 'scripts/harmony-standalone/prepare.test.mjs'));
files.push(join(root, 'harmony/scripts/test-lint-arkts.mjs'));

const result = spawnSync(process.execPath, [
    tsx, '--tsconfig', join(root, 'harmony/chat/tsconfig.json'),
    '--test', '--test-concurrency=4', ...process.argv.slice(2), ...files,
], { cwd: root, stdio: 'inherit' });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
