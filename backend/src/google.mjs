import { sameStreet, streetCandidates } from './street-approach.mjs';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const WALKING_WARNING = 'Walking routes are in beta and may be missing sidewalks or pedestrian paths. Check local conditions and use safe crossings.';

const fail = (code, message, status = 502) => { throw new ApiError(status, code, message); };

/** No Google response, address, route, panorama, or key is persisted. */
export class GoogleProvider {
  source = 'google';

  constructor(key, fetcher = fetch) {
    if (!key?.trim()) throw new Error('GOOGLE_MAPS_SERVER_KEY is required.');
    this.key = key.trim();
    this.fetcher = fetcher;
  }

  async request(url, options = {}) {
    let response;
    try {
      response = await this.fetcher(url, { ...options, signal: options.signal ?? AbortSignal.timeout(12_000) });
    } catch {
      fail('UPSTREAM_UNAVAILABLE', 'Google could not be reached. Try again.', 503);
    }
    if (!response.ok) {
      if (response.status === 429) fail('QUOTA_EXCEEDED', 'The map service is temporarily at its request limit.', 429);
      if ([401, 403].includes(response.status)) fail('PROVIDER_CONFIGURATION', 'The Google service credentials or API permissions need attention.', 503);
      fail('UPSTREAM_UNAVAILABLE', 'The map service is temporarily unavailable.', 503);
    }
    try { return await response.json(); }
    catch { fail('INVALID_UPSTREAM_RESPONSE', 'The map service returned an unreadable response.'); }
  }

  async geocode(address) {
    const url = new URL('https://maps.googleapis.com/maps/api/geocode/json');
    url.searchParams.set('address', address);
    url.searchParams.set('key', this.key);
    const data = await this.request(url);
    if (data.status === 'ZERO_RESULTS') return { candidates: [], source: this.source };
    if (['OVER_QUERY_LIMIT', 'OVER_DAILY_LIMIT'].includes(data.status)) fail('QUOTA_EXCEEDED', 'Google billing or usage limits need attention.', 429);
    if (data.status === 'REQUEST_DENIED') fail('PROVIDER_CONFIGURATION', 'Enable Geocoding API and check the server key restrictions.', 503);
    if (data.status !== 'OK') fail('GEOCODE_FAILED', 'The address could not be resolved. Check it and try again.');
    const precision = { ROOFTOP: 'ROOFTOP', RANGE_INTERPOLATED: 'INTERPOLATED' };
    const candidates = (data.results ?? []).slice(0, 5).map(result => ({
      placeId: result.place_id,
      formattedAddress: result.formatted_address,
      position: { lat: result.geometry.location.lat, lng: result.geometry.location.lng },
      precision: precision[result.geometry.location_type] ?? 'APPROXIMATE',
      partialMatch: result.partial_match === true,
      streetNames: [...new Set((result.address_components ?? []).filter(c => c.types?.includes('route'))
        .flatMap(c => [c.long_name, c.short_name]).filter(Boolean))],
      streetNumber: (result.address_components ?? []).find(c => c.types?.includes('street_number'))?.long_name ?? '',
    }));
    return { candidates, source: this.source };
  }

  async streetApproach(destination) {
    if (!destination.streetNames.length) return { source: this.source, approach: null };
    const signal = AbortSignal.timeout(12_000);
    const data = await this.request('https://geocode.googleapis.com/v4/geocode/destinations', {
      method: 'POST',
      signal,
      headers: { 'Content-Type': 'application/json', 'X-Goog-Api-Key': this.key,
        'X-Goog-FieldMask': 'destinations.primary.place,destinations.primary.postalAddress,destinations.primary.location,destinations.navigationPoints' },
      body: JSON.stringify({ place: `places/${destination.placeId}`, travelModes: ['WALK'] }),
    });
    const candidates = streetCandidates(data, destination);
    const match = candidates.find(point => sameStreet(point.name, destination.streetNames));
    if (match) return { source: this.source, approach: { position: match.position, streetName: match.name } };
    // Some access points have no road label. Verify at most two against the nearest named road.
    for (const point of candidates.filter(p => !p.name).slice(0, 2)) {
      const url = new URL('https://maps.googleapis.com/maps/api/geocode/json');
      url.searchParams.set('key', this.key);
      url.searchParams.set('latlng', `${point.position.lat},${point.position.lng}`);
      url.searchParams.set('result_type', 'route');
      const reverse = await this.request(url, { signal });
      if (reverse.status !== 'OK') continue;
      const names = (reverse.results?.[0]?.address_components ?? []).filter(c => c.types?.includes('route'))
        .flatMap(c => [c.long_name, c.short_name]).filter(Boolean);
      const name = names.find(n => sameStreet(n, destination.streetNames));
      if (name) return { source: this.source, approach: { position: point.position, streetName: name } };
    }
    return { source: this.source, approach: null };
  }

  async route(origin, destination) {
    const waypoint = point => ({ location: { latLng: { latitude: point.lat, longitude: point.lng } } });
    const fields = [
      'routes.distanceMeters', 'routes.duration', 'routes.polyline.encodedPolyline',
      'routes.warnings', 'routes.legs.steps.distanceMeters',
      'routes.legs.steps.navigationInstruction',
    ];
    const data = await this.request('https://routes.googleapis.com/directions/v2:computeRoutes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Goog-Api-Key': this.key, 'X-Goog-FieldMask': fields.join(',') },
      body: JSON.stringify({ origin: waypoint(origin), destination: waypoint(destination), travelMode: 'WALK',
        computeAlternativeRoutes: false, polylineQuality: 'HIGH_QUALITY', languageCode: 'en-US', units: 'METRIC' }),
    });
    const route = data.routes?.[0];
    if (!route) fail('NO_WALKING_ROUTE', 'No walking route was found. Try a different starting point.', 404);
    if (!route.polyline?.encodedPolyline) fail('INVALID_UPSTREAM_RESPONSE', 'The walking route had no usable path.');
    return {
      source: this.source,
      encodedPolyline: route.polyline.encodedPolyline,
      distanceMeters: route.distanceMeters ?? 0,
      durationSeconds: Number.parseFloat(route.duration ?? '0s'),
      warnings: [...new Set([WALKING_WARNING, ...(route.warnings ?? [])])],
      steps: (route.legs ?? []).flatMap(leg => leg.steps ?? []).map(step => ({
        distanceMeters: step.distanceMeters ?? 0,
        instruction: step.navigationInstruction?.instructions ?? 'Continue along the walking route',
        maneuver: step.navigationInstruction?.maneuver ?? 'STRAIGHT',
      })),
    };
  }
}
