# Development

Walkline is a walking-navigation project targeting **iOS and Android**. The Android prototype is currently at version 0.8.5; the iOS app is planned and has not yet been implemented. The backend exposes a platform-neutral HTTP API intended for both clients. The Android package remains `com.tobenamed.app` for configuration compatibility.

## Architecture

- `android/core-nav`: pure Kotlin geometry, route progress, maneuvers, street-side experiments, and navigation state. No SDK imports.
- `android/app`: Jetpack Compose UI, foreground fused location, rotation-vector heading, authenticated HTTP client, 3D map, and native Google Street View.
- `ios`: [iOS development plan](../ios/README.md); no app or Xcode project is included yet.
- `backend`: Node HTTP proxy with OpenStreetMap and optional legacy Google adapters. Input validation, authentication, request limits, timeouts, provider provenance, and bounded in-memory caching.

Building location and street routing target are separate. The app matches the address street before selecting an approach; users can override the street point. Automatic selection does not certify an entrance or road level.

The 3D viewpoint follows interpolated raw GPS at approximately 1.7 m eye height. A separate freshness/reliability-gated heading stream rotates the camera without creating GPS fixes, advancing progress, or restarting position animation. Manual gestures pause following until the user explicitly resumes. Route geometry stays separate from the measured position.

## Validation

```sh
node scripts/check-public-repo.mjs
node --test scripts/check-public-repo.test.mjs android/app/src/test/js/scene.test.cjs
npm test --prefix backend
```

With JDK 17 and Android SDK 36, from `android/`:

```sh
./gradlew :core-nav:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Tests cover geometry/progress, freshness, wrong-way/off-route handling, state transitions, provider boundaries, raw-position packets, heading filtering, scene animation/readiness, and immediate gesture control. Backend tests inject responses rather than making billable provider calls.

Manual emulator validation includes a public 151 m pedestrian replay at 1.4 m/s through arrival, stationary heading changes, map gestures/resume, view switching, foreground return, and actual GPU-frame checks. Recordings and configured APKs are local artifacts, not repository content. These results do not establish physical-phone positioning accuracy.

See [renderer notes](3D_NAVIGATION.md) for the GPU check and fallback, and [emulator replay](EMULATOR_WALK.md) for the public test helper.

## Current limits

- Physical-phone graphics, compass calibration, outdoor accuracy, and battery performance need validation.
- Guidance is foreground only; locking or leaving the app pauses tracking.
- Route and building data may lack sidewalks, entrances, heights, or vertical road levels.
- Street View is recorded, discrete imagery with best-effort exterior selection.
- Live wrong-side certainty, safe crossing identification, exact-door navigation, AR, visual positioning, and house-number recognition are not implemented.
- The shared pilot token and public community endpoints are development infrastructure, not a production deployment.

## Public repository hygiene

Run the repository check before committing. If Gitleaks is installed, scan the publishable snapshot and any existing history:

```sh
node scripts/check-public-repo.mjs --gitleaks gitleaks
```

The check reports file paths and finding categories, never secret values. It examines both index content and non-ignored working files. It compares local configured secret values and setup identifiers when those ignored files exist. The Gitleaks option exports only publishable content to a temporary scan directory; it does not scan or publish private local configuration.

CI runs the same guard and Gitleaks with pinned upstream actions/binaries. See [SECURITY.md](../SECURITY.md).
