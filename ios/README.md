# Walkline for iOS

iOS is a target platform for Walkline, alongside Android. The iOS app has not yet been implemented; this directory documents the planned client and does not contain an Xcode project or a runnable app.

The intended experience includes address intake and confirmation, street-height 3D walking navigation, selectable recorded Street View, phone-facing orientation, manual inspection/resume, and arrival at the street outside the selected building.

The client will use the existing platform-neutral [backend API](../backend/README.md). Location, heading, permissions, renderer behavior, and imagery selection will require iOS-specific implementation and validation. The Kotlin navigation engine currently lives under `android/core-nav/`; it is not already integrated into an iOS client.

See the [project roadmap](../docs/PLAN.md) for the overall development plan.
