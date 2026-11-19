# Street View selection

Walkline's current Android prototype uses the native Android Street View SDK with `StreetViewSource.OUTDOOR`. The iOS app is planned, and its imagery integration will need separate validation. Exterior filtering is best effort; provider classification can still yield unsuitable imagery.

The building marker and street approach are separate. Building previews search near the address-street point and face the building. Normal walking searches use the measured GPS position, with bounded radius and movement/request-rate gates. Panorama positions outside the requested radius are rejected. Missing coverage has an explicit map fallback.

Choosing the matching address street avoids preferring a nearby alley solely by proximity. This is a placement heuristic, not an independent indoor detector, entrance detector, or road-level check. Manual street selection remains available.

The Android SDK used here exposes `DEFAULT` and `OUTDOOR` sources. The JavaScript Street View service has a separate source-filter API; it is not integrated into this native view. Metadata does not provide an independent indoor classification result for this implementation. Do not infer exterior certainty from copyright or distance to a road.

References: [Android StreetViewSource](https://developers.google.com/android/reference/com/google/android/gms/maps/model/StreetViewSource), [JavaScript Street View requests](https://developers.google.com/maps/documentation/javascript/reference/street-view-service), and [metadata](https://developers.google.com/maps/documentation/streetview/metadata).
