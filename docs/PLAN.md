# Roadmap

Walkline targets **iOS and Android**, helping delivery drivers navigate on foot to the street outside a building. Both platforms are intended to offer a street-height 3D map with selectable recorded Street View. The Android prototype and backend are implemented; the iOS app is planned.

## Current Android prototype and backend

- Address intake, review, explicit confirmation, and separate delivery details.
- Pedestrian routes, separate building/street targets, and manual street correction.
- Foreground GPS and independent phone-facing heading.
- 3D navigation, Street View, manual inspection/resume, and building inspection.
- Route joining, wrong-way/off-route handling, rerouting, and street-target arrival.
- Automated navigation/provider tests, public emulator replay, GPU checks, and native graphics fallback.

## Next priorities

1. Physical-phone walks: measure positioning, heading, turn response, arrival, graphics, and battery use.
2. Hosted HTTPS backend with appropriate authentication and provider capacity for untethered testing.
3. Active-session behavior when switching apps or locking the device; currently guidance is foreground only.
4. Improve the final approach using observed field results and available pedestrian data.
5. Rendering reliability and performance across devices.
6. Implement the iOS client with the same core walking journey and backend API; verify its platform-specific location, heading, map, and Street View behavior.

## Future candidates

Back-to-vehicle routing, better entrance data, visual positioning/AR, on-device building-number recognition, and multi-stop journeys. These are not current capabilities or accuracy promises. iOS is a target platform in the main development plan.

Safe crossing detection, exact-door arrival, street-side certainty, and stacked road levels need suitable data and separate validation. They cannot be established by map styling alone.
