# Local setup

Walkline targets iOS and Android. This guide covers the platform-neutral backend and current Android prototype. The iOS implementation and its setup instructions are planned; see [iOS development](../ios/README.md).

## Backend

Use Node.js 22 or newer. On a fresh clone, copy `backend/.env.example` to `backend/.env`. Generate a private pilot token locally, for example with `openssl rand -hex 32`, and set it as `NAV_API_TOKEN`. Use the same token in Android's local configuration.

The default provider is `NAV_DATA_PROVIDER=openstreetmap`. `PHOTON_BASE_URL` and `OSRM_FOOT_BASE_URL` default to community services for a small local pilot. Requests are serialized and cached in memory. Configure hosted/self-hosted endpoints before general distribution. Production startup rejects the community endpoints unless `NAV_COMMUNITY_PILOT=true` explicitly opts into a limited pilot.

```sh
cd backend
npm start
```

`GET http://127.0.0.1:8080/health` checks process health and reports the provider. It does not verify upstream service availability. Keep the backend running during navigation.

## Android

Use JDK 17, Android SDK Platform 36, and a Google APIs/Play services emulator or compatible phone. Minimum Android version is API 26.

Copy `android/local.properties.example` to `android/local.properties`, then edit the values:

```properties
sdk.dir=/path/to/Android/sdk
NAV_API_BASE_URL=http://10.0.2.2:8080
NAV_API_TOKEN=YOUR_TOKEN_HERE
MAPS_API_KEY=
```

The files above are ignored by Git. Do not overwrite existing private configuration. With JDK 17 selected:

```sh
cd android
./gradlew :app:assembleDebug
```

Alternatively, open `android/` in Android Studio and run the `app` configuration. The default 3D map and OSM provider work without a Google key.

## Optional Google Street View

Supply your own Google Cloud project with billing and Maps SDK for Android enabled. Restrict the Android key to Maps SDK for Android, package `com.tobenamed.app`, and your signing certificate SHA-1. Obtain the local debug certificate fingerprint with `./gradlew :app:signingReport`, then set `MAPS_API_KEY` in the ignored local properties file and rebuild.

See the [official Android Maps setup guide](https://developers.google.com/maps/documentation/android-sdk/cloud-setup). This repository contains no shared project, billing account, certificate fingerprint, or usable key.

The optional legacy `NAV_DATA_PROVIDER=google` adapter additionally requires your own server key for Geocoding/Routes in `backend/.env`. Restrict it to the required APIs and server egress IPs. Google route data remains on the Google map path; it is not plotted on the default OSM scene. Changing provider requires a fresh address search/confirmation.

`node --env-file=backend/.env backend/scripts/verify-google.mjs` explicitly makes real Google requests and may consume billable usage. It is separate from automated tests.

## Device connection

For the emulator, `http://10.0.2.2:8080` reaches the host computer.

For a USB-connected phone:

```sh
adb reverse tcp:8080 tcp:8080
```

Build the phone configuration from `android/`:

```sh
./gradlew :app:assembleDebug -PNAV_API_BASE_URL=http://127.0.0.1:8080
```

Keep USB and the host backend connected. An untethered phone requires a hosted HTTPS backend. Release clients require HTTPS; debug cleartext access is limited to local host addresses. Do not attach a locally configured APK to a public release: it embeds the pilot token and optional Android key.

## First walk

1. Enter a public test destination, review the result, and confirm its address.
2. Check the selected address street. Use **Change street spot** if needed.
3. Optionally inspect Street View; exterior filtering is best effort.
4. Grant precise foreground location, get a fresh fix, and start the walk.
5. Check stationary phone rotation, measured movement, manual map gestures, and **Follow walk**.
6. Verify the building number at the street target and end guidance.

Use [emulator playback](EMULATOR_WALK.md) for repeatable development tests. Emulator inputs do not measure outdoor GPS accuracy. Foreground tracking pauses when the app is backgrounded or locked. Physical-phone calibration, graphics, battery use, road levels, and sidewalk accuracy need separate testing.
