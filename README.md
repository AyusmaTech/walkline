# Walkline

Walkline is a walking-navigation project for **iOS and Android**, designed to help delivery drivers find the right building during the final stretch of a delivery. The experience centres on a street-height 3D map, with recorded Street View available for navigation and building recognition.

## Platforms and development status

Both iOS and Android are target platforms. This repository currently contains the Android prototype and the backend; the iOS app is planned and has not yet been implemented.

| Component | Status |
|---|---|
| Android app | Experimental prototype, version 0.8.5 |
| iOS app | Planned; see [iOS development](ios/README.md) |
| Backend | Implemented HTTP API intended to support both mobile clients |

Emulator checks cover the current Android navigation flow. Physical-phone compass calibration, outdoor positioning, and graphics performance still need field testing.

## Features

- Type, explicitly paste, or share an address; review and confirm the result.
- Keep apartment and delivery instructions separate from address lookup.
- Select a street arrival spot outside the building, with a manual correction option.
- Follow a pedestrian route in a 3D city using measured GPS and independent phone-facing updates.
- Inspect the map with gestures and explicitly resume following.
- Switch to recorded Street View or inspect the destination building.
- Handle route joining, wrong-way movement, rerouting, weak/stale GPS, and street-target arrival.

Guidance ends at the selected street spot. It does not verify an entrance, sidewalk, safe crossing, or vertical road level. Street View is recorded imagery and can have missing or incorrectly classified coverage. Tracking pauses when the app leaves the foreground.

## Run the current prototype

The following instructions cover the backend and available Android implementation. iOS setup instructions will accompany its implementation.

Requirements: **Node.js 22+**, **JDK 17**, Android SDK Platform **36**, and an Android device/emulator with Google Play services. Minimum Android version: API 26.

On a fresh clone, copy the configuration templates:

```sh
cp backend/.env.example backend/.env
cp android/local.properties.example android/local.properties
```

Set a locally generated `NAV_API_TOKEN` in both files, and set `sdk.dir` to your Android SDK location. The default emulator API URL is `http://10.0.2.2:8080`. Do not overwrite an existing private configuration file.

Start the backend:

```sh
cd backend
npm start
```

In a second terminal, build the Android app with JDK 17 selected:

```sh
cd android
./gradlew :app:assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Android Studio can also run the `app` configuration.

The default OpenStreetMap address/routing path and OpenFreeMap tiles do not require a Google API key. Optional Street View needs your own restricted Android Maps SDK key. See [setup](docs/LIVE_SETUP.md) for device connections, credentials, and provider configuration.

## Checks

From the repository root:

```sh
node scripts/check-public-repo.mjs
node --test scripts/check-public-repo.test.mjs android/app/src/test/js/scene.test.cjs
npm test --prefix backend
```

For Android checks:

```sh
cd android
./gradlew :core-nav:test :app:testDebugUnitTest :app:lintDebug
```

Tests use synthetic or injected data. Real provider calls and GPS playback are separate: see [emulator replay](docs/EMULATOR_WALK.md). CI runs repository checks, a Gitleaks scan, backend tests, and scene tests without private credentials.

## Project layout

| Path | Purpose |
|---|---|
| `android/app/` | Compose UI, location/heading, 3D map, Street View, API client |
| `android/core-nav/` | SDK-free geometry, route progress, navigation logic and tests |
| `ios/` | iOS implementation plan and platform status |
| `backend/` | Platform-neutral Node API proxy and provider adapters |
| `scripts/` | Public-repository checks |
| `docs/` | Setup, architecture, renderer notes, replay, and roadmap |

[Development](docs/DEVELOPMENT.md) · [3D renderer](docs/3D_NAVIGATION.md) · [Roadmap](docs/PLAN.md) · [Backend API](backend/README.md)

## Data and credentials

No credentials, signing keys, configured APKs, personal setup notes, or local recordings belong in this repository. Configure your own ignored local files. See [SECURITY.md](SECURITY.md) before publishing builds or deploying the backend.

Address and route requests go through the proxy to the configured providers. The default public Photon/FOSSGIS services are intended for a limited local pilot; use hosted or self-hosted endpoints before general distribution. OpenFreeMap receives tile requests, and Google receives Street View requests. Upstream services have their own policies. The proxy uses bounded in-memory caches and does not write address/location queries to disk or application logs. Delivery details remain in Android state rather than address lookup requests.

## Licensing

A distribution license for original project code has not been selected. Bundled third-party software retains its own license; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
