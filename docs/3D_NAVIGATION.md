# 3D navigation

These notes describe the current Android renderer. Walkline targets both iOS and Android; the iOS rendering and SDK integration are planned.

## Renderer

The default scene bundles MapLibre GL JS 5.24.1 in an Android WebView, using OpenFreeMap vector tiles. Native MapLibre remains the compatibility fallback. The script, worker, stylesheet, upstream license, and source checksum are bundled; no remote executable script is loaded.

Walking uses approximately 1.7 m eye height at interpolated measured GPS, a near-level camera, and independent phone-facing yaw. Preview is elevated. The native fallback retains a more elevated camera with a 60-degree pitch limit.

The scene uses mapped building footprints/heights, styled facade/roof detail, a pedestrian route, destination/street markers, and selective cutaways. Missing heights are approximated. Geometry does not establish actual entrances, sidewalk placement, crossing safety, or road level.

## Geometry and performance

Tile MultiPolygons are split into independently identified building parts. Parser/PBF buffers are excluded from plain GeoJSON and worker payloads. City context is bounded to 2,400 nearby parts within 1.5 km, with detail on 90 nearby buildings. Cutaways use footprint/height intersections and respect courtyards/raised bases.

Position interpolation and phone yaw are independent. Heading does not create movement. Route geometry is not shifted under the viewer. Canvas touch capture pauses following before map gesture processing; a latch prevents queued GPS updates from overriding manual inspection.

Pixel ratio and tile cache are bounded. Retained GPU pixels support startup and idle checks, with a performance cost. Physical-phone frame rate, heat, and battery behavior remain unverified.

## Readiness and recovery

Android's visual-state fence and a bootstrap-frame check precede scene readiness. Actual base-tile content and useful GPU pixels are required. First-person readiness does not rely on the separate viewpoint overlay or the geographic marker below the camera.

A transparent scene triggers one bounded map/context rebuild while preserving navigation state. Repeated failure, unsupported graphics, or renderer-process loss selects native 3D. Backgrounding pauses checks; releasing the Android view destroys its WebView. A working fallback is not counted as a WebGL pass. Recovery covers a reproduced emulator failure, not every possible browser/driver defect.

The WebView bridge is confined to trusted bundled content. API credentials and private delivery notes are not passed to JavaScript. File/content access, web geolocation, mixed content, and third-party cookies are disabled. External main-frame links leave the WebView.

## GPU verification

With a debug build open in its 3D scene:

```sh
node backend/scripts/check-emulator-scene.mjs --watch-seconds 30
```

The helper requires an Android emulator and its debug WebView socket. It checks real non-uniform map pixels, parsed building features, and readiness without logging navigation coordinates. It fails on native fallback and removes its temporary ADB forward. Do not switch views during a watch.

## Data and attribution

Photon address lookup and FOSSGIS/OSRM pedestrian routing supply the default OSM path. OpenFreeMap supplies tiles. Keep OpenStreetMap, OpenMapTiles, OpenFreeMap, and SDK attribution visible. Community endpoints support only a limited pilot; configure hosted/self-hosted endpoints before general distribution.

The optional legacy Google adapter stays on the Google map path. Google Street View is a separate selectable SDK view. A provider change requires a fresh address search and confirmation.

See [third-party notices](../THIRD_PARTY_NOTICES.md), [setup](LIVE_SETUP.md), and [Street View filtering](STREET_VIEW_FILTERING.md).
