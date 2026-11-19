# Walkline navigation proxy

Node.js 22+, native HTTP/fetch, no third-party runtime dependencies. Copy `.env.example` to your own ignored `.env`, generate a private pilot token, then run `npm start`. See [setup](../docs/LIVE_SETUP.md).

The HTTP API is platform-neutral and intended for Walkline's iOS and Android clients. The current integration is with the Android prototype; the iOS client is planned.

## Providers

`NAV_DATA_PROVIDER=openstreetmap` is the default: Photon address search and FOSSGIS/OSRM foot routing, with configurable hosted/self-hosted endpoints. Requests are throttled per host and cached in bounded memory. Community services support a limited development pilot, not general production traffic.

The explicit legacy `google` adapter needs your own server key for Geocoding/Routes. Google-derived routes use the Android Google-map path. The custom 3D scene uses OSM data. A provider change requires a fresh address confirmation.

## API

Navigation endpoints require `Authorization: Bearer <NAV_API_TOKEN>` and `Content-Type: application/json`. Responses use `Cache-Control: no-store`.

| Endpoint | Input | Output |
|---|---|---|
| `GET /health` | No authentication | Process health/provider; not an upstream availability check |
| `POST /v1/geocode` | `address` | Provider source and address candidates |
| `POST /v1/street-approach` | Selected `placeId`, `position`, `streetNames`, optional `streetNumber` | Matching address-street approach or `null` |
| `POST /v1/routes` | `origin`, `destination` coordinates | Pedestrian geometry, instructions, distance/duration, warnings, source |

Coordinates use `{ "lat": 30, "lng": -95 }`. Candidates include an identifier, formatted address, position, match precision, partial-match flag, street aliases, and building number. Apartment/delivery details are not geocoding inputs. Clients can include `dataSource` on street/route requests to reject a changed provider.

Building markers and street routing targets stay separate. Street selection checks names/directionals rather than choosing an alley by proximity. Unknown matches require manual selection in the client. Route modes and endpoint proximity are validated. These checks do not establish an exact door, safe crossing, or vertical road level.

Errors have `{ "error": { "code": "...", "message": "..." } }`. Missing matches/routes remain explicit; synthetic geometry is never silently substituted. Inputs, credentials, and raw upstream errors are not logged or returned as diagnostic dumps.

## Tests and fixtures

```sh
npm test
npm run start:fixture
```

Tests inject provider responses. The fixture service on port 8081 is explicitly synthetic and does not call providers. Release Android clients reject fixture results.

Real emulator playback is separate: see [the replay guide](../docs/EMULATOR_WALK.md). The optional `scripts/verify-google.mjs` also makes real provider requests; do not include it in automated tests.

## Deployment

The Dockerfile copies only runtime source and package metadata, and runs as the Node user. Terminate HTTPS at the hosting platform. Replace the shared pilot token with an appropriate authentication design before general distribution, apply server-side quotas, and use providers sized for the expected traffic. See [SECURITY.md](../SECURITY.md).
