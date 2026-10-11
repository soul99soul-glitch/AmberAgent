const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/ChatVisualFixture.ets'), 'utf8');

test('visual fixture covers the certificate Smart UI, same-id updates, and mixed artifacts', () => {
  assert.match(source, /amber-ui v=1 id=ca-controls/);
  assert.match(source, /id":"choice"/);
  assert.match(source, /intermediate-detail/);
  assert.match(source, /kind === 'smart-ui'/);
  assert.match(source, /kind === 'mixed'/);
  assert.match(source, /rawSvgPayload\(\).*rawThreePayload\(\)/s);
});
