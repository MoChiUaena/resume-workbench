// HTTP-only contract tests use synthetic records and dynamically assigned port 0.
import assert from 'node:assert/strict';
import {once} from 'node:events';
import test from 'node:test';
import {startQaModelServer} from './qa-model-server.mjs';

const EXPECTED_KEY = 'upgrade-fixture-only';
const WRONG_KEY = 'wrong-synthetic-key';

async function fixture(t, expectedKeys) {
  const previous = process.env.QA_MODEL_EXPECTED_KEYS;
  let server;
  try {
    if (expectedKeys === undefined) delete process.env.QA_MODEL_EXPECTED_KEYS;
    else process.env.QA_MODEL_EXPECTED_KEYS = JSON.stringify(expectedKeys);
    server = startQaModelServer(0);
  } finally {
    if (previous === undefined) delete process.env.QA_MODEL_EXPECTED_KEYS;
    else process.env.QA_MODEL_EXPECTED_KEYS = previous;
  }
  t.after(() => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())));
  await once(server, 'listening');
  return 'http://127.0.0.1:' + server.address().port;
}

async function complete(base, model, authorization) {
  const headers = {'Content-Type': 'application/json'};
  if (authorization !== undefined) headers.Authorization = authorization;
  const response = await fetch(base + '/v1/chat/completions', {method: 'POST', headers,
    body: JSON.stringify({model, messages: [{role: 'user', content: 'OK'}]})});
  const text = await response.text();
  assert.ok(!text.includes(EXPECTED_KEY) && !text.includes(WRONG_KEY), 'Response must not disclose credentials');
  return {status: response.status, body: JSON.parse(text)};
}

test('mapped model accepts the exact stored synthetic Bearer and returns fixed OK', async t => {
  const base = await fixture(t, {'qa-model': EXPECTED_KEY});
  const result = await complete(base, 'qa-model', 'Bearer ' + EXPECTED_KEY);
  assert.equal(result.status, 200);
  assert.equal(result.body.choices[0].message.content, 'OK');
  const requests = await (await fetch(base + '/requests')).text();
  assert.ok(!requests.includes(EXPECTED_KEY) && !requests.includes('Authorization'));
  assert.deepEqual(JSON.parse(requests), [{model: 'qa-model', messages: [{role: 'user', content: 'OK'}], tools: null}]);
});

test('a changed but decryptable stored key is rejected instead of producing fake OK', async t => {
  const base = await fixture(t, {'qa-model': EXPECTED_KEY});
  const result = await complete(base, 'qa-model', 'Bearer ' + WRONG_KEY);
  assert.equal(result.status, 401);
  assert.deepEqual(result.body, {error: {message: 'fixture authentication rejected'}});
  assert.deepEqual(await (await fetch(base + '/requests')).json(), []);
});

test('mapped model rejects a missing Authorization header', async t => {
  const base = await fixture(t, {'qa-model': EXPECTED_KEY});
  const result = await complete(base, 'qa-model');
  assert.equal(result.status, 401);
  assert.deepEqual(result.body, {error: {message: 'fixture authentication rejected'}});
});

test('mapped model requires the Bearer scheme even when the key matches', async t => {
  const base = await fixture(t, {'qa-model': EXPECTED_KEY});
  const result = await complete(base, 'qa-model', 'Basic ' + EXPECTED_KEY);
  assert.equal(result.status, 401);
});

test('unmapped report model retains existing no-auth fixture behavior', async t => {
  const base = await fixture(t, {'qa-model': EXPECTED_KEY});
  const result = await complete(base, 'qa-job-normal');
  assert.equal(result.status, 200);
  assert.equal(result.body.choices[0].message.content, 'OK');
});

test('absent optional mapping preserves existing qa-model compatibility', async t => {
  const base = await fixture(t);
  assert.equal((await complete(base, 'qa-model', 'Bearer ' + WRONG_KEY)).status, 200);
  assert.equal((await complete(base, 'qa-model')).status, 200);
});
