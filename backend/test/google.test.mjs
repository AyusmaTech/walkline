import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GoogleProvider, WALKING_WARNING } from '../src/google.mjs';
import { streetCandidates } from '../src/street-approach.mjs';

test('geocoding encodes the query and preserves multiple confidence-aware matches', async () => {
  const provider = new GoogleProvider('server-secret', async url => {
    assert.equal(url.origin, 'https://maps.googleapis.com');
    assert.equal(url.searchParams.get('address'), '12 Rue & Main, Montréal');
    assert.equal(url.searchParams.get('key'), 'server-secret');
    return Response.json({ status: 'OK', results: [
      { place_id: 'a', formatted_address: '12 Main', partial_match: true, geometry: { location: { lat: 30, lng: -95 }, location_type: 'ROOFTOP' },
        address_components: [{ types: ['route'], long_name: 'South Main Street', short_name: 'S Main St' }] },
      { place_id: 'b', formatted_address: '12 Main B', geometry: { location: { lat: 31, lng: -94 }, location_type: 'RANGE_INTERPOLATED' } },
    ] });
  });
  const result = await provider.geocode('12 Rue & Main, Montréal');
  assert.equal(result.source, 'google');
  assert.equal(result.candidates[0].partialMatch, true);
  assert.equal(result.candidates[1].precision, 'INTERPOLATED');
  assert.deepEqual(result.candidates[0].streetNames, ['South Main Street', 'S Main St']);
  assert.ok(!JSON.stringify(result).includes('server-secret'));
});

test('street selection prefers the address street over a closer alley, wrong direction, and vehicle-only access', async () => {
  const destination = { placeId: 'house', position: { lat: 30, lng: -95 }, streetNames: ['S Main St'] };
  const point = (name, longitude, travelModes = ['WALK', 'DRIVE']) => ({ displayName: { text: name },
    location: { latitude: 30, longitude }, travelModes });
  const provider = new GoogleProvider('secret', async (url, request) => {
    assert.equal(url, 'https://geocode.googleapis.com/v4/geocode/destinations');
    assert.deepEqual(JSON.parse(request.body), { place: 'places/house', travelModes: ['WALK'] });
    assert.ok(!request.headers['X-Goog-FieldMask'].includes('*'));
    return Response.json({ destinations: [{ primary: { place: 'places/house' }, navigationPoints: [
      point('Rear Alley', -94.99999), point('North Main Street', -94.99998),
      point('South Main Street', -94.99997, ['DRIVE']), point('South Main Street', -94.9996),
    ] }] });
  });
  const result = await provider.streetApproach(destination);
  assert.equal(result.approach.streetName, 'South Main Street');
  assert.deepEqual(result.approach.position, { lat: 30, lng: -94.9996 });
});

test('missing, mismatched, and distant street points stay unconfirmed instead of falling back to the roof or alley', async () => {
  const destination = { placeId: 'house', position: { lat: 30, lng: -95 }, streetNames: ['Main Street'] };
  for (const data of [
    { destinations: [] },
    { destinations: [{ primary: { place: 'places/other-house' }, navigationPoints: [{ displayName: { text: 'Main Street' }, location: { latitude: 30, longitude: -95 }, travelModes: ['WALK'] }] }] },
    { destinations: [{ primary: { place: 'places/house' }, navigationPoints: [
      { displayName: { text: 'Alley' }, location: { latitude: 30, longitude: -95 }, travelModes: ['WALK'] },
      { displayName: { text: 'Main Street' }, location: { latitude: 30.02, longitude: -95 }, travelModes: ['WALK'] },
    ] }] },
  ]) {
    const result = await new GoogleProvider('secret', async () => Response.json(data)).streetApproach(destination);
    assert.equal(result.approach, null);
  }
});

test('unlabelled access points use bounded reverse lookup to check the address street', async () => {
  let calls = 0;
  const provider = new GoogleProvider('secret', async url => {
    calls++;
    if (calls === 1) return Response.json({ destinations: [{ primary: { place: 'places/house' }, navigationPoints: [
      { location: { latitude: 30, longitude: -94.9998 }, travelModes: ['WALK'] },
    ] }] });
    assert.equal(url.searchParams.get('result_type'), 'route');
    return Response.json({ status: 'OK', results: [{ address_components: [
      { types: ['route'], long_name: 'Main Street', short_name: 'Main St' },
    ] }] });
  });
  assert.equal((await provider.streetApproach({ placeId: 'house', position: { lat: 30, lng: -95 }, streetNames: ['Main St'] })).approach.streetName, 'Main Street');
  assert.equal(calls, 2);
});

test('a building place alias needs the same full street address nearby and never a neighbouring house number', () => {
  const query = { placeId: 'street-address', position: { lat: 30, lng: -95 }, streetNumber: '339', streetNames: ['N Main St'] };
  const data = number => ({ destinations: [{ primary: { place: 'places/museum', location: { latitude: 30, longitude: -95 },
    postalAddress: { addressLines: [`${number} North Main Street`] } }, navigationPoints: [
      { displayName: { text: 'N Main St' }, location: { latitude: 30, longitude: -94.9998 }, travelModes: ['WALK', 'DRIVE'] },
    ] }] });
  assert.equal(streetCandidates(data('339'), query).length, 1);
  assert.equal(streetCandidates(data('341'), query).length, 0);
  const distant = data('339'); distant.destinations[0].primary.location.latitude = 30.001;
  assert.equal(streetCandidates(distant, query).length, 0);
});

test('no-result and provider errors have explicit, non-secret responses', async () => {
  assert.deepEqual(await new GoogleProvider('key', async () => Response.json({ status: 'ZERO_RESULTS' })).geocode('x'), { candidates: [], source: 'google' });
  await assert.rejects(new GoogleProvider('key', async () => Response.json({ status: 'REQUEST_DENIED', error_message: 'secret upstream details' })).geocode('x'),
    error => error.code === 'PROVIDER_CONFIGURATION' && !error.message.includes('secret'));
  await assert.rejects(new GoogleProvider('key', async () => { throw new Error('secret URL'); }).geocode('x'),
    error => error.status === 503 && !error.message.includes('secret'));
});

test('routes use WALK, bounded field masks, warnings, and instructions', async () => {
  const provider = new GoogleProvider('secret', async (url, request) => {
    assert.equal(url, 'https://routes.googleapis.com/directions/v2:computeRoutes');
    assert.equal(request.headers['X-Goog-Api-Key'], 'secret');
    assert.ok(!request.headers['X-Goog-FieldMask'].includes('*'));
    const body = JSON.parse(request.body);
    assert.equal(body.travelMode, 'WALK');
    assert.equal(body.origin.location.latLng.latitude, 30);
    assert.equal(body.routingPreference, undefined);
    return Response.json({ routes: [{ distanceMeters: 120, duration: '90.5s', polyline: { encodedPolyline: 'abc' },
      warnings: ['Path may be closed'], legs: [{ steps: [{ distanceMeters: 120, navigationInstruction: { instructions: 'Turn left', maneuver: 'TURN_LEFT' } }] }] }] });
  });
  const result = await provider.route({ lat: 30, lng: -95 }, { lat: 30.01, lng: -95 });
  assert.equal(result.durationSeconds, 90.5);
  assert.equal(result.steps[0].maneuver, 'TURN_LEFT');
  assert.deepEqual(result.warnings, [WALKING_WARNING, 'Path may be closed']);
});

test('missing walking routes and Google quota failures are not invented routes', async () => {
  await assert.rejects(new GoogleProvider('x', async () => Response.json({ routes: [] })).route({}, {}), error => error.code === 'NO_WALKING_ROUTE');
  await assert.rejects(new GoogleProvider('x', async () => new Response('', { status: 429 })).geocode('x'), error => error.code === 'QUOTA_EXCEEDED');
});
