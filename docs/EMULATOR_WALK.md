# Emulator walking replay

Use a Google APIs/Play services emulator with location enabled, a configured Android app, and a running local backend. See [setup](LIVE_SETUP.md).

## Public example

The helper uses **339 N Oak Park Ave, Oak Park, IL**, a public museum address. It resolves a real pedestrian route through the configured backend and injects coordinates into emulator GPS. Provider geometry is kept in memory. This is simulated movement using real services, not a physical accuracy test.

From the repository root:

```sh
node --env-file=backend/.env backend/scripts/replay-emulator-walk.mjs --prepare
```

Select that destination in the app, get location, and start the walk. Then:

```sh
node --env-file=backend/.env backend/scripts/replay-emulator-walk.mjs --play
```

The example path is approximately 151 m; exact geometry can change with provider data. Default speed is 1.4 m/s. Optional flags: `--serial emulator-5554 --speed-mps 1.0`. Ctrl+C stops the helper; end the walk in the app when finished. Only emulator serials are accepted.

## Emulator controls

Android's [extended controls](https://developer.android.com/studio/run/emulator-extended-controls) also support location route playback and GPX/KML import. Do not run two GPS playback sources simultaneously.

GPS playback changes location, not phone facing. Use Virtual sensors to test stationary rotation. With reliable heading, the camera follows the phone even if it points away from the route. Without reliable heading, route direction is the fallback.

## What to check

- Measured GPS moves the viewpoint while route progress and instruction distances update.
- Stationary phone rotation changes orientation without changing physical progress.
- Pan/pinch/rotate/tilt pauses following; it stays paused during phone rotation.
- **Follow walk** restores measured-position following.
- Switching 3D/Street View retains the walk; **View building** intentionally faces the destination.
- Arrival identifies the street spot, stops upcoming guidance, and permits building inspection/End walk.
- Weak/stale GPS and leaving the foreground pause reliable guidance.

Street View searches require movement and respect a request interval. Recorded panorama positions update discretely; visible changes depend on coverage and network latency. Missing imagery falls back to the 3D map.
