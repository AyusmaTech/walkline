import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import { distanceM } from '../src/street-approach.mjs';

// A public example only. Google geometry stays in memory; no trace or imagery is saved.
const address = '339 N Oak Park Ave, Oak Park, IL';
const execute = promisify(execFile);
let stopped = false;
process.on('SIGINT', () => { stopped = true; });
process.on('SIGTERM', () => { stopped = true; });

function decode(encoded) {
  let index = 0, lat = 0, lng = 0;
  const points = [];
  function delta() {
    let value = 0, shift = 0, byte;
    do {
      if (index >= encoded.length || shift > 30) throw new Error('The route geometry was invalid.');
      byte = encoded.charCodeAt(index++) - 63;
      value |= (byte & 31) << shift; shift += 5;
    } while (byte >= 32);
    return value & 1 ? ~(value >>> 1) : value >>> 1;
  }
  while (index < encoded.length) {
    lat += delta(); lng += delta();
    points.push({ lat: lat / 1e5, lng: lng / 1e5 });
  }
  if (points.length < 2) throw new Error('The route did not contain a walking path.');
  return points;
}

try {
  const args = process.argv.slice(2);
  const option = name => { const index = args.indexOf(name); return index < 0 ? undefined : args[index + 1]; };
  const mode = args.includes('--prepare') ? 'prepare' : args.includes('--play') ? 'play' : null;
  if (!mode) throw new Error('Use --prepare to set the start, or --play to walk. Optional: --serial emulator-5554 --speed-mps 1.4');
  const serial = option('--serial') ?? 'emulator-5554';
  const speed = Number(option('--speed-mps') ?? 1.4);
  if (!/^emulator-\d+$/.test(serial)) throw new Error('Choose an Android emulator serial; this helper does not move a physical phone.');
  if (!Number.isFinite(speed) || speed < 0.5 || speed > 3) throw new Error('Choose a walking speed between 0.5 and 3 metres per second.');
  if (!process.env.NAV_API_TOKEN) throw new Error('Load backend/.env using Node --env-file before running this helper.');
  const properties = await readFile(new URL('../../android/local.properties', import.meta.url), 'utf8').catch(() => '');
  const sdk = process.env.ANDROID_HOME ?? properties.split(/\r?\n/).find(line => line.startsWith('sdk.dir='))?.slice(8) ?? join(homedir(), 'Library/Android/sdk');
  const adb = join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const runAdb = (...commands) => execute(adb, ['-s', serial, ...commands], { timeout: 10_000 });
  await runAdb('get-state');
  const base = `http://127.0.0.1:${process.env.PORT ?? 8080}`;
  async function post(path, body) {
    const response = await fetch(`${base}/v1/${path}`, { method: 'POST', redirect: 'error',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.NAV_API_TOKEN}` },
      body: JSON.stringify(body), signal: AbortSignal.timeout(18_000) });
    if (!response.ok) throw new Error(`The local ${path} request failed (HTTP ${response.status}).`);
    const result = await response.json();
    if (!['google', 'openstreetmap'].includes(result.source)) throw new Error('Use a real navigation backend for this public replay.');
    return result;
  }
  const destination = (await post('geocode', { address })).candidates[0];
  if (!destination) throw new Error('The public example address did not resolve.');
  const { placeId, position, streetNames, streetNumber } = destination;
  const approach = (await post('street-approach', { placeId, position, streetNames, streetNumber })).approach;
  if (!approach) throw new Error('The public example did not return an address-street point.');
  const origin = { lat: approach.position.lat - 0.0012, lng: approach.position.lng };
  const route = await post('routes', { origin, destination: approach.position });
  const points = decode(route.encodedPolyline);
  const segments = points.slice(1).map((point, i) => distanceM(points[i], point));
  const total = segments.reduce((a, b) => a + b, 0);
  function pointAt(distance) {
    for (let i = 0; i < segments.length; i++) {
      if (distance <= segments[i]) {
        const fraction = segments[i] ? distance / segments[i] : 0;
        return { lat: points[i].lat + (points[i + 1].lat - points[i].lat) * fraction,
          lng: points[i].lng + (points[i + 1].lng - points[i].lng) * fraction };
      }
      distance -= segments[i];
    }
    return points.at(-1);
  }
  const send = point => runAdb('emu', 'geo', 'fix', String(point.lng), String(point.lat));
  console.log(`Public demo: ${address}. Street: ${approach.streetName}.`);
  console.log(`Walking path: about ${Math.round(total)} m; ${Math.ceil(total / speed)} seconds at ${speed} m/s.`);
  for (let i = 0; i < 4 && !stopped; i++) { await send(points[0]); await delay(1_000); }
  if (mode === 'prepare') {
    console.log('Start position set. In the app: select this destination, Get my location, then Start walk. Run again with --play.');
  } else {
    console.log('Walking now. Ctrl+C stops GPS playback. Keep the app open and tap Follow walk if you looked around.');
    const start = performance.now();
    let lastReport = -1;
    while (!stopped) {
      const along = Math.min(total, (performance.now() - start) / 1_000 * speed);
      await send(pointAt(along));
      const report = Math.floor(along / 20);
      if (report !== lastReport) { console.log(`${Math.round(along)} m walked; ${Math.round(total - along)} m to go.`); lastReport = report; }
      if (along >= total) break;
      await delay(1_000);
    }
    if (!stopped) {
      for (let i = 0; i < 8 && !stopped; i++) { await send(points.at(-1)); await delay(1_000); }
      console.log('At the street destination. Check arrival and the building-facing view, then End walk in the app.');
    } else console.log('GPS playback stopped. End walk in the app if finished.');
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
