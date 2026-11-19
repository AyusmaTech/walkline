// Street names are compared, not guessed from distance to the building centroid.
const aliases = { north: 'n', south: 's', east: 'e', west: 'w', northeast: 'ne', northwest: 'nw',
  southeast: 'se', southwest: 'sw', street: 'st', avenue: 'ave', boulevard: 'blvd', drive: 'dr',
  road: 'rd', lane: 'ln', court: 'ct', place: 'pl', parkway: 'pkwy', terrace: 'ter', highway: 'hwy' };
export const normalizedStreet = name => name.normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase()
  .replace(/[^\p{L}\p{N}]+/gu, ' ').trim().split(/\s+/).map(word => aliases[word] ?? word).join(' ');
export const sameStreet = (name, names) => Boolean(name?.trim()) && names.some(n => normalizedStreet(n) === normalizedStreet(name));
export const validPoint = point => point && Number.isFinite(point.lat) && Number.isFinite(point.lng) &&
  Math.abs(point.lat) <= 90 && Math.abs(point.lng) <= 180;
export function distanceM(a, b) {
  const radians = Math.PI / 180;
  const dlat = (b.lat - a.lat) * radians, dlng = (b.lng - a.lng) * radians;
  const h = Math.sin(dlat / 2) ** 2 + Math.cos(a.lat * radians) * Math.cos(b.lat * radians) * Math.sin(dlng / 2) ** 2;
  return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, h)));
}
export function streetCandidates(data, destination) {
  const primary = (data.destinations ?? []).find(item => {
    if (item.primary?.place === `places/${destination.placeId}`) return true;
    // A street-address Place ID can resolve to the business/museum occupying the same building.
    // Accept that alias only with the exact street number + name and a nearby building position.
    const point = { lat: item.primary?.location?.latitude, lng: item.primary?.location?.longitude };
    return Boolean(destination.streetNumber) && validPoint(point) && distanceM(point, destination.position) <= 30 &&
      (item.primary?.postalAddress?.addressLines ?? []).some(line => sameStreet(line,
        destination.streetNames.map(name => `${destination.streetNumber} ${name}`)));
  });
  return (primary?.navigationPoints ?? []).flatMap(point => {
    const position = { lat: point.location?.latitude, lng: point.location?.longitude };
    if (!validPoint(position) || !point.travelModes?.includes('WALK') || distanceM(position, destination.position) > 150) return [];
    return [{ position, name: point.displayName?.text ?? '', roadAccessible: point.travelModes.includes('DRIVE') }];
  }).sort((a, b) => Number(b.roadAccessible) - Number(a.roadAccessible));
}
