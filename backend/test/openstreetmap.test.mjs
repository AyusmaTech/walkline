import { test } from 'node:test';
import assert from 'node:assert/strict';
import { OpenStreetMapProvider, osmApproach, photonCandidates } from '../src/openstreetmap.mjs';

const building = { position: { lat: 41.89, lng: -87.79 }, streetNames: ['North Main Street'] };
const feature = { geometry: { type: 'Point', coordinates: [-87.79, 41.89] },
  properties: { osm_id: 123, osm_type: 'W', housenumber: '12', street: 'North Main Street', city: 'Oak Park', country: 'United States' } };
const routeData = () => ({ code: 'Ok', waypoints: [{ location: [-87.79, 41.89] }, { location: [-87.79, 41.891] }],
  routes: [{ geometry: '_p~iF~ps|U_ulLnnqC_mqNvxq`@', distance: 111, duration: 80,
    legs: [{ steps: [{ distance: 50, name: 'North Main Street', mode: 'walking', maneuver: { type: 'depart' } },
      { distance: 61, name: 'West Oak Avenue', mode: 'walking', maneuver: { type: 'turn', modifier: 'left' } }] }] }] });

test('OSM address identity and confidence stay separate from Google IDs and approximate matches', () => {
  const [exact] = photonCandidates({ features: [feature] }, '12 N Main St');
  assert.equal(exact.placeId, 'osm_W_123');
  assert.equal(exact.precision, 'ROOFTOP'); assert.equal(exact.partialMatch, false);
  assert.deepEqual(exact.streetNames, ['North Main Street']);
  assert.equal(photonCandidates({ features: [feature] }, '14 N Main St')[0].partialMatch, true);
  const broad = { ...feature, properties: { osm_id: 1, osm_type: 'N', name: 'Oak Park', city: 'Oak Park' } };
  assert.equal(photonCandidates({ features: [broad] }, 'Oak Park')[0].precision, 'APPROXIMATE');
  assert.deepEqual(photonCandidates({ features: [{ ...feature, geometry: { type: 'Point', coordinates: [0, 100] } }] }, '12 Main'), []);
});

test('OSM approach selects the address street rather than a nearer alley or opposite directional street', () => {
  const data = { code: 'Ok', waypoints: [
    { location: [-87.790001, 41.89], name: 'Alley' },
    { location: [-87.79001, 41.89], name: 'South Main Street' },
    { location: [-87.79, 41.8901], name: 'N Main St' },
  ] };
  assert.deepEqual(osmApproach(data, building), { position: { lat: 41.8901, lng: -87.79 }, streetName: 'N Main St' });
  assert.equal(osmApproach({ code: 'Ok', waypoints: [data.waypoints[0], data.waypoints[1]] }, building), null);
  assert.equal(osmApproach({ code: 'Ok', waypoints: [{ name: 'North Main Street', location: [-87.79, 42] }] }, building), null);
});

test('public service calls identify the app, serialize per host, and cache repeated searches', async () => {
  let clock = 0; const starts = [], urls = [];
  const provider = new OpenStreetMapProvider({ now: () => clock, wait: async ms => { clock += ms; },
    fetcher: async (url, options) => {
      assert.match(options.headers['User-Agent'], /Walkline/); assert.equal(options.redirect, 'error');
      starts.push(clock); urls.push(url); return Response.json({ features: [feature] });
    } });
  const first = await provider.geocode('12 Main');
  await Promise.all([provider.geocode('14 Main'), provider.geocode('16 Main')]);
  await provider.geocode('12 Main');
  assert.equal(first.source, 'openstreetmap'); assert.equal(starts.length, 3);
  assert.deepEqual(starts, [0, 1100, 2200]);
  assert.equal(new URL(urls[0]).searchParams.get('q'), '12 Main');
});

test('OSM routing uses the pedestrian service, bounded snapping, and maps turn instructions', async () => {
  let called;
  const provider = new OpenStreetMapProvider({ gapMs: 0, fetcher: async url => { called = new URL(url); return Response.json(routeData()); } });
  const result = await provider.route(building.position, { lat: 41.891, lng: -87.79 });
  assert.equal(result.source, 'openstreetmap'); assert.match(called.pathname, /routed-foot\/route\/v1\/foot/);
  assert.equal(called.searchParams.get('radiuses'), '60;20');
  assert.equal(result.steps[1].maneuver, 'TURN_LEFT'); assert.equal(result.steps[1].instruction, 'Turn left onto West Oak Avenue');
  assert.equal(result.steps[1].distanceMeters, 61);
});

test('missing paths, driving responses, and a route ending far from the selected street are rejected', async () => {
  async function rejected(data, code) {
    const provider = new OpenStreetMapProvider({ gapMs: 0, fetcher: async () => Response.json(data) });
    await assert.rejects(provider.route(building.position, { lat: 41.891, lng: -87.79 }), e => e.code === code);
  }
  await rejected({ code: 'NoRoute' }, 'NO_WALKING_ROUTE');
  const far = routeData(); far.waypoints[1].location = [0, 0]; await rejected(far, 'STREET_SNAP_TOO_FAR');
  const car = routeData(); car.routes[0].legs[0].steps[0].mode = 'driving'; await rejected(car, 'NO_WALKING_ROUTE');
  const invalid = routeData(); invalid.waypoints[1].location = [0, 100]; await rejected(invalid, 'INVALID_UPSTREAM_RESPONSE');
});

test('upstream failures are bounded, explicit, and do not expose service responses', async () => {
  const provider = new OpenStreetMapProvider({ gapMs: 0, fetcher: async () => new Response('private upstream body', { status: 429 }) });
  await assert.rejects(provider.geocode('12 Main'), e => e.code === 'PROVIDER_BUSY' && !e.message.includes('private'));
  const large = new OpenStreetMapProvider({ gapMs: 0, fetcher: async () => new Response('x'.repeat(2_000_001)) });
  await assert.rejects(large.geocode('12 Main'), e => e.code === 'INVALID_UPSTREAM_RESPONSE');
  assert.throws(() => new OpenStreetMapProvider({ routerUrl: 'https://user:secret@example.test' }));
});

test('Google place IDs cannot be reused as an OSM destination during a provider migration', async () => {
  let calls = 0;
  const provider = new OpenStreetMapProvider({ fetcher: async () => { calls++; return Response.json({}); } });
  await assert.rejects(provider.streetApproach({ ...building, placeId: 'google_place_id' }), e => e.code === 'PROVIDER_CHANGED');
  assert.equal(calls, 0);
});
