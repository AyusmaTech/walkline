import { GoogleProvider } from '../src/google.mjs';
import { setDefaultResultOrder } from 'node:dns';

// Match the local backend: its IP-restricted Routes key succeeds via IPv4;
// this network's default IPv6 path was rejected by the Routes service.
setDefaultResultOrder('ipv4first');

// Two live billable requests against a public example address. Run explicitly only;
// never part of npm test. No key, complete provider response, or route is logged/stored.
try {
  const provider = new GoogleProvider(process.env.GOOGLE_MAPS_SERVER_KEY);
  const result = await provider.geocode('233 S Wacker Dr, Chicago, IL');
  const destination = result.candidates[0];
  if (!destination) throw new Error('The public example address returned no match.');
  const route = await provider.route({ lat: destination.position.lat + 0.0015, lng: destination.position.lng }, destination.position);
  console.log(JSON.stringify({ source: provider.source, geocoding: 'passed', walkingRoute: 'passed',
    addressMatches: result.candidates.length, geocodePrecision: destination.precision,
    walkingInstructions: route.steps.length, routeWarnings: route.warnings.length }));
} catch (error) {
  console.error(JSON.stringify({ status: 'failed', code: error.code ?? 'VERIFICATION_ERROR', message: error.message }));
  process.exitCode = 1;
}
