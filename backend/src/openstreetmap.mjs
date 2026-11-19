import { setTimeout as delay } from 'node:timers/promises';
import { ApiError } from './google.mjs';
import { distanceM, sameStreet, validPoint } from './street-approach.mjs';

const fail = (code, message, status = 502) => { throw new ApiError(status, code, message); };
const defaultGeocoder = 'https://photon.komoot.io';
const defaultRouter = 'https://routing.openstreetmap.de/routed-foot';
const userAgent = 'Walkline/0.8.5 (Android walking navigation; local development pilot)';

function endpoint(value) {
  const url = new URL(value);
  if (url.username || url.password || url.search || url.hash ||
      (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))))
    throw new Error('OpenStreetMap services require HTTPS URLs without credentials, queries, or fragments.');
  return url.toString().replace(/\/$/, '');
}

export function photonCandidates(data, query) {
  const requestedNumber = query.match(/^\s*(\d+[\p{L}-]*)\b/u)?.[1]?.toLowerCase();
  return (Array.isArray(data?.features) ? data.features : []).slice(0, 5).flatMap(feature => {
    const p = feature.properties ?? {};
    const coords = feature.geometry?.coordinates;
    const position = { lat: coords?.[1], lng: coords?.[0] };
    if (feature.geometry?.type !== 'Point' || !validPoint(position) || !['N', 'W', 'R'].includes(p.osm_type) ||
        !Number.isSafeInteger(p.osm_id) || p.osm_id <= 0) return [];
    const streetNumber = String(p.housenumber ?? '').slice(0, 40);
    const street = typeof p.street === 'string' ? p.street.slice(0, 200) : '';
    const streetLine = [streetNumber, street].filter(Boolean).join(' ');
    const address = [...new Set([streetLine || p.name, p.city || p.town || p.village, p.state, p.postcode, p.country].filter(Boolean))].join(', ');
    if (!address) return [];
    return [{ placeId: `osm_${p.osm_type}_${p.osm_id}`, formattedAddress: address.slice(0, 1000), position,
      precision: streetNumber && street ? 'ROOFTOP' : 'APPROXIMATE',
      partialMatch: Boolean(requestedNumber && requestedNumber !== streetNumber.toLowerCase()),
      streetNames: street ? [street] : [], streetNumber }];
  });
}

export function osmApproach(data, destination) {
  if (data?.code !== 'Ok') return null;
  const candidates = (data.waypoints ?? []).flatMap(waypoint => {
    const position = { lat: waypoint.location?.[1], lng: waypoint.location?.[0] };
    if (!validPoint(position) || !sameStreet(waypoint.name, destination.streetNames) || distanceM(position, destination.position) > 150) return [];
    return [{ position, streetName: waypoint.name, distance: distanceM(position, destination.position) }];
  }).sort((a, b) => a.distance - b.distance);
  const selected = candidates[0];
  return selected ? { position: selected.position, streetName: selected.streetName } : null;
}

export function osmInstruction(step) {
  const type = step.maneuver?.type ?? '';
  const modifier = step.maneuver?.modifier ?? '';
  const street = step.name?.trim() || 'the walking route';
  let maneuver = 'STRAIGHT', instruction = `Continue on ${street}`;
  if (step.mode === 'ferry') return { maneuver: 'FERRY', instruction: 'Board the ferry following local pedestrian access.' };
  if (type === 'depart') return { maneuver: 'DEPART', instruction: `Head on ${street}` };
  if (type === 'arrive') return { maneuver: 'STRAIGHT', instruction: 'Arrive at the street spot' };
  if (modifier === 'uturn') return { maneuver: 'UTURN_LEFT', instruction: `Make a U-turn onto ${street}` };
  if (['roundabout', 'rotary', 'roundabout turn'].includes(type))
    return { maneuver: modifier.includes('left') ? 'ROUNDABOUT_LEFT' : 'ROUNDABOUT_RIGHT',
      instruction: `At the roundabout, continue onto ${street}` };
  if (modifier.includes('left') || modifier.includes('right')) {
    const side = modifier.includes('left') ? 'left' : 'right';
    const slight = modifier.includes('slight') || type === 'fork';
    maneuver = type === 'fork' ? `FORK_${side.toUpperCase()}` : `TURN_${slight ? 'SLIGHT_' : ''}${side.toUpperCase()}`;
    instruction = `${slight ? 'Bear' : 'Turn'} ${side} onto ${street}`;
  }
  return { maneuver, instruction };
}

/** OSM content only. Shared pilot services are serialized, throttled, bounded, and cached in memory. */
export class OpenStreetMapProvider {
  source = 'openstreetmap';
  constructor({ fetcher = fetch, geocoderUrl = defaultGeocoder, routerUrl = defaultRouter,
    gapMs = 1100, now = () => Date.now(), wait = delay } = {}) {
    this.fetcher = fetcher;
    this.geocoderUrl = endpoint(geocoderUrl);
    this.routerUrl = endpoint(routerUrl);
    this.gapMs = gapMs; this.now = now; this.wait = wait;
    this.cache = new Map(); this.queues = new Map(); this.lastCalls = new Map(); this.pending = new Map();
  }

  async request(url, ttlMs) {
    const key = url.toString(), time = this.now();
    const cached = this.cache.get(key);
    if (cached && time - cached.time < ttlMs) return cached.data;
    const host = new URL(key).host;
    if ((this.pending.get(host) ?? 0) >= 4) fail('PROVIDER_BUSY', 'The walking service is busy. Try again shortly.', 429);
    this.pending.set(host, (this.pending.get(host) ?? 0) + 1);
    const task = (this.queues.get(host) ?? Promise.resolve()).catch(() => {}).then(async () => {
      // A prior queued request may already have filled the cache.
      const previous = this.cache.get(key);
      if (previous && this.now() - previous.time < ttlMs) return previous.data;
      const remaining = this.gapMs - (this.now() - (this.lastCalls.get(host) ?? -Infinity));
      if (remaining > 0) await this.wait(remaining);
      this.lastCalls.set(host, this.now());
      let response;
      try { response = await this.fetcher(key, { redirect: 'error', signal: AbortSignal.timeout(12_000),
        headers: { 'User-Agent': userAgent, Accept: 'application/json' } }); }
      catch { fail('UPSTREAM_UNAVAILABLE', 'The OpenStreetMap service could not be reached. Try again.', 503); }
      if (!response.ok) fail(response.status === 429 ? 'PROVIDER_BUSY' : 'UPSTREAM_UNAVAILABLE',
        'The OpenStreetMap service is temporarily unavailable. Try again shortly.', response.status === 429 ? 429 : 503);
      if (Number(response.headers.get('content-length') ?? 0) > 2_000_000) fail('INVALID_UPSTREAM_RESPONSE', 'The map response was too large.');
      let raw = '';
      try {
        const reader = response.body.getReader(); const chunks = []; let size = 0;
        while (true) {
          const { value, done } = await reader.read(); if (done) break;
          size += value.length;
          if (size > 2_000_000) { await reader.cancel(); throw new Error('size'); }
          chunks.push(value);
        }
        raw = Buffer.concat(chunks).toString('utf8');
      } catch { fail('INVALID_UPSTREAM_RESPONSE', 'The map response could not be read.'); }
      let data;
      try { data = JSON.parse(raw); } catch { fail('INVALID_UPSTREAM_RESPONSE', 'The map service returned an unreadable response.'); }
      if (this.cache.size >= 256) this.cache.delete(this.cache.keys().next().value);
      this.cache.set(key, { data, time: this.now() });
      return data;
    });
    this.queues.set(host, task.catch(() => {}));
    try { return await task; } finally { this.pending.set(host, this.pending.get(host) - 1); }
  }

  async geocode(address) {
    const url = new URL(`${this.geocoderUrl}/api/`);
    url.searchParams.set('q', address); url.searchParams.set('limit', '5'); url.searchParams.set('lang', 'en');
    return { source: this.source, candidates: photonCandidates(await this.request(url, 86_400_000), address) };
  }

  async streetApproach(destination) {
    if (!/^osm_[NWR]_\d+$/.test(destination.placeId ?? ''))
      fail('PROVIDER_CHANGED', 'The navigation provider changed. Search and confirm the address again.', 400);
    if (!destination.streetNames.length) return { source: this.source, approach: null };
    const p = destination.position;
    const url = new URL(`${this.routerUrl}/nearest/v1/foot/${p.lng},${p.lat}`);
    url.searchParams.set('number', '32');
    return { source: this.source, approach: osmApproach(await this.request(url, 21_600_000), destination) };
  }

  async route(origin, destination) {
    const url = new URL(`${this.routerUrl}/route/v1/foot/${origin.lng},${origin.lat};${destination.lng},${destination.lat}`);
    for (const [key, value] of Object.entries({ steps: 'true', overview: 'full', geometries: 'polyline', alternatives: 'false', radiuses: '60;20' }))
      url.searchParams.set(key, value);
    const data = await this.request(url, 60_000);
    if (data.code !== 'Ok' || !data.routes?.length) fail('NO_WALKING_ROUTE', 'No mapped walking route was found. Choose a nearby street spot.', 404);
    const route = data.routes[0];
    if (typeof route.geometry !== 'string' || !route.geometry || !Number.isFinite(route.distance) || route.distance < 0 ||
        !Number.isFinite(route.duration) || route.duration < 0) fail('INVALID_UPSTREAM_RESPONSE', 'The walking route had no usable path.');
    const end = data.waypoints?.at(-1)?.location;
    if (!validPoint({ lng: end?.[0], lat: end?.[1] })) fail('INVALID_UPSTREAM_RESPONSE', 'The route endpoint was invalid.');
    if (distanceM({ lng: end[0], lat: end[1] }, destination) > 25)
      fail('STREET_SNAP_TOO_FAR', 'The route could not reach the selected street spot. Choose a nearby accessible point.', 404);
    if ((route.legs ?? []).flatMap(leg => leg.steps ?? []).some(step => step.mode && !['walking', 'ferry'].includes(step.mode)))
      fail('NO_WALKING_ROUTE', 'The service did not return a pedestrian route.', 404);
    return { source: this.source, encodedPolyline: route.geometry, distanceMeters: route.distance, durationSeconds: route.duration,
      warnings: ['OpenStreetMap walking routes depend on mapped paths and access tags. Check local access, traffic, and crossings.'],
      steps: (route.legs ?? []).flatMap(leg => leg.steps ?? []).map(step => ({
        distanceMeters: Number.isFinite(step.distance) && step.distance >= 0 ? step.distance : 0, ...osmInstruction(step) })) };
  }
}
