import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { ApiError, GoogleProvider } from './google.mjs';
import { validPoint } from './street-approach.mjs';
import { OpenStreetMapProvider } from './openstreetmap.mjs';

const reject = (status, code, message) => { throw new ApiError(status, code, message); };
const equalToken = (provided, expected) => {
  const a = Buffer.from(provided ?? ''), b = Buffer.from(`Bearer ${expected}`);
  return a.length === b.length && timingSafeEqual(a, b);
};

async function jsonBody(request) {
  if (!request.headers['content-type']?.startsWith('application/json')) reject(415, 'JSON_REQUIRED', 'Send an application/json body.');
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 16_384) reject(413, 'BODY_TOO_LARGE', 'The request is too large.');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { reject(400, 'INVALID_JSON', 'The request body is not valid JSON.'); }
}

export function createApiServer({ provider, token, perMinute = 60, now = () => Date.now() }) {
  if (!token?.trim()) throw new Error('NAV_API_TOKEN is required for the private pilot.');
  const buckets = new Map();
  const server = createServer(async (request, response) => {
    const send = (status, body) => {
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff' });
      response.end(JSON.stringify(body));
    };
    try {
      const path = new URL(request.url, 'http://localhost').pathname;
      if (request.method === 'GET' && path === '/health') return send(200, { status: 'ok', source: provider.source });
      if (!equalToken(request.headers.authorization, token)) reject(401, 'UNAUTHORIZED', 'The app access token is missing or invalid.');
      const time = now();
      for (const [key, bucket] of buckets) if (time - bucket.since >= 60_000) buckets.delete(key);
      const peer = request.socket.remoteAddress ?? 'unknown';
      const bucket = buckets.get(peer) ?? { since: time, count: 0 };
      if (buckets.size >= 10_000 && !buckets.has(peer)) reject(503, 'BUSY', 'The service is busy. Try again shortly.');
      bucket.count++;
      buckets.set(peer, bucket);
      if (bucket.count > perMinute) reject(429, 'RATE_LIMITED', 'Too many requests. Try again in a minute.');
      if (request.method !== 'POST') reject(405, 'METHOD_NOT_ALLOWED', 'Use POST for navigation requests.');
      if (!['/v1/geocode', '/v1/street-approach', '/v1/routes'].includes(path)) reject(404, 'NOT_FOUND', 'Unknown endpoint.');
      const body = await jsonBody(request);
      if (path !== '/v1/geocode' && body?.dataSource !== undefined && body.dataSource !== provider.source)
        reject(400, 'PROVIDER_CHANGED', 'The navigation provider changed. Search and confirm the address again.');
      if (path === '/v1/geocode') {
        if (typeof body?.address !== 'string' || body.address.trim().length < 3 || body.address.length > 2_000) {
          reject(400, 'INVALID_ADDRESS', 'Enter a street address between 3 and 2,000 characters.');
        }
        return send(200, await provider.geocode(body.address.trim()));
      }
      if (path === '/v1/street-approach') {
        if (!validPoint(body?.position) || typeof body?.placeId !== 'string' || !/^[\w-]{1,300}$/.test(body.placeId) ||
            (body.streetNumber !== undefined && (typeof body.streetNumber !== 'string' || body.streetNumber.length > 40)) ||
            !Array.isArray(body.streetNames) || body.streetNames.length > 4 ||
            body.streetNames.some(name => typeof name !== 'string' || !name.trim() || name.length > 200)) {
          reject(400, 'INVALID_DESTINATION', 'A selected address and its street names are required.');
        }
        return send(200, await provider.streetApproach({ placeId: body.placeId, position: body.position,
          streetNames: body.streetNames, streetNumber: body.streetNumber ?? '' }));
      }
      if (!validPoint(body?.origin) || !validPoint(body?.destination)) reject(400, 'INVALID_COORDINATES', 'Valid origin and destination coordinates are required.');
      return send(200, await provider.route(body.origin, body.destination));
    } catch (error) {
      // Never log URLs, addresses, coordinates, tokens, upstream error bodies, or credentials.
      if (error instanceof ApiError) send(error.status, { error: { code: error.code, message: error.message } });
      else send(500, { error: { code: 'INTERNAL_ERROR', message: 'The navigation service could not complete the request.' } });
    }
  });
  server.requestTimeout = 20_000;
  server.headersTimeout = 10_000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const choice = process.env.NAV_DATA_PROVIDER ?? 'openstreetmap';
    if (!['google', 'openstreetmap'].includes(choice)) throw new Error('NAV_DATA_PROVIDER must be google or openstreetmap.');
    const community = !process.env.PHOTON_BASE_URL || !process.env.OSRM_FOOT_BASE_URL ||
      new URL(process.env.PHOTON_BASE_URL).hostname === 'photon.komoot.io' ||
      new URL(process.env.OSRM_FOOT_BASE_URL).hostname === 'routing.openstreetmap.de';
    if (choice === 'openstreetmap' && process.env.NODE_ENV === 'production' && process.env.NAV_COMMUNITY_PILOT !== 'true' && community)
      throw new Error('Configure hosted Photon/foot-routing URLs for production, or explicitly opt into the limited community pilot.');
    const provider = choice === 'google' ? new GoogleProvider(process.env.GOOGLE_MAPS_SERVER_KEY) : new OpenStreetMapProvider({
      geocoderUrl: process.env.PHOTON_BASE_URL, routerUrl: process.env.OSRM_FOOT_BASE_URL });
    const server = createApiServer({ provider, token: process.env.NAV_API_TOKEN });
    const host = process.env.HOST ?? '127.0.0.1';
    const port = Number(process.env.PORT ?? 8080);
    server.listen(port, host, () => console.log(`Navigation proxy listening on ${host}:${port}`));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
