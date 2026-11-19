import { createApiServer } from '../src/server.mjs';
import { WALKING_WARNING } from '../src/google.mjs';

// Explicit local-only test service. Every response is marked fixture; never a live fallback.
function encode(points) {
  let previous = [0, 0];
  let result = '';
  for (const point of points) {
    point.forEach((coordinate, i) => {
      const rounded = Math.round(coordinate * 1e5);
      const delta = rounded - previous[i];
      previous[i] = rounded;
      let value = delta < 0 ? ~(delta << 1) : delta << 1;
      while (value >= 32) { result += String.fromCharCode((32 | (value & 31)) + 63); value >>>= 5; }
      result += String.fromCharCode(value + 63);
    });
  }
  return result;
}
const provider = {
  source: 'fixture',
  geocode: async () => ({ source: 'fixture', candidates: [{ placeId: 'fixture-building',
    formattedAddress: 'TEST FIXTURE — sample building', position: { lat: 29.7614, lng: -95.3698 }, precision: 'ROOFTOP', partialMatch: false,
    streetNames: ['TEST FIXTURE street'] }] }),
  streetApproach: async () => ({ source: 'fixture', approach: { position: { lat: 29.7614, lng: -95.3696 }, streetName: 'TEST FIXTURE street' } }),
  route: async (origin, destination) => ({ source: 'fixture', encodedPolyline: encode([[origin.lat, origin.lng], [destination.lat, destination.lng]]),
    distanceMeters: 120, durationSeconds: 100, warnings: [WALKING_WARNING],
    steps: [{ distanceMeters: 120, instruction: 'TEST FIXTURE — walk toward the sample building', maneuver: 'STRAIGHT' }] }),
};
createApiServer({ provider, token: 'local-fixture-token' }).listen(Number(process.env.PORT ?? 8081), '127.0.0.1', () => console.log('Local fixture service on 127.0.0.1:8081; data is synthetic.'));
