import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createApiServer } from '../src/server.mjs';

async function service(t, options = {}) {
  const calls = [];
  const provider = { source: 'fixture', geocode: async address => { calls.push(address); return { source: 'fixture', candidates: [] }; },
    streetApproach: async destination => { calls.push(destination.placeId); return { source: 'fixture', approach: null }; },
    route: async (origin, destination) => ({ source: 'fixture', origin, destination }) };
  const server = createApiServer({ provider, token: 'pilot-token', ...options });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const url = `http://127.0.0.1:${server.address().port}`;
  const post = (path, body, token = 'pilot-token') => fetch(url + path, { method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, body: JSON.stringify(body) });
  return { url, post, calls };
}

test('authorization and validation run before billable provider calls', async t => {
  const { post, calls } = await service(t);
  assert.equal((await post('/v1/geocode', { address: '123 Main' }, 'wrong')).status, 401);
  assert.equal((await post('/v1/geocode', { address: '' })).status, 400);
  assert.equal((await post('/v1/routes', { origin: { lat: 91, lng: 0 }, destination: { lat: 0, lng: 0 } })).status, 400);
  assert.deepEqual(calls, []);
  const response = await post('/v1/geocode', { address: ' 123 Main ' });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(calls, ['123 Main']);
});

test('street lookup validates the selected destination before calling Google', async t => {
  const { post, calls } = await service(t);
  const destination = { placeId: 'house', position: { lat: 30, lng: -95 }, streetNames: ['Main Street'] };
  assert.equal((await post('/v1/street-approach', destination, 'wrong')).status, 401);
  for (const invalid of [{ ...destination, placeId: 'places/invalid' }, { ...destination, streetNames: ['x'.repeat(201)] },
    { ...destination, position: { lat: 100, lng: 0 } }, { ...destination, streetNames: null }]) {
    assert.equal((await post('/v1/street-approach', invalid)).status, 400);
  }
  assert.deepEqual(calls, []);
  const response = await post('/v1/street-approach', destination);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.deepEqual(calls, ['house']);
});

test('a provider switch requires a fresh address confirmation before street lookup', async t => {
  const { post, calls } = await service(t);
  const response = await post('/v1/street-approach', { dataSource: 'google', placeId: 'house',
    position: { lat: 30, lng: -95 }, streetNames: ['Main Street'] });
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.code, 'PROVIDER_CHANGED');
  assert.deepEqual(calls, []);
});

test('invalid JSON, oversized bodies, and unknown paths fail explicitly', async t => {
  const { url, post } = await service(t);
  assert.equal((await fetch(url + '/v1/geocode', { method: 'POST', headers: { Authorization: 'Bearer pilot-token', 'Content-Type': 'application/json' }, body: '{' })).status, 400);
  assert.equal((await post('/v1/geocode', { address: 'x'.repeat(20_000) })).status, 413);
  assert.equal((await post('/v1/unknown', {})).status, 404);
});

test('rate limits expire and do not forward blocked requests', async t => {
  let time = 0;
  const { post, calls } = await service(t, { perMinute: 1, now: () => time });
  assert.equal((await post('/v1/geocode', { address: 'first' })).status, 200);
  assert.equal((await post('/v1/geocode', { address: 'second' })).status, 429);
  assert.equal(calls.length, 1);
  time = 60_000;
  assert.equal((await post('/v1/geocode', { address: 'third' })).status, 200);
});
