# Research notes

## Confirmed

- The Android module builds `libpandora-core.so` for `arm64-v8a` through JNI.
- The native implementation lives in `pandora-core/src`. The app CMake target
  links that implementation as a static library.
- `AppFactory` starts the native redirector and expects an embedded
  `assets/base.apk` in a host APK.
- `CreatorProxy` needs signature data, but no code currently populates
  `AppFactory.Data.SignatureData`. Signature substitution therefore cannot work
  from this source alone.
- The Android manifest registers `AppFactory` and declares no activity.
- The project has no code to read an arbitrary APK, update its manifest or DEX,
  package assets and native libraries, or export an unsigned result.

## Android workflow to implement

1. Select an input APK through Android's document picker.
2. Validate the input as an APK and inspect its package, components, DEX files,
   native ABIs, and signing certificate metadata.
3. Apply the payload to the APK structure, including component factory setup,
   DEX placement, native library placement, and payload assets.
4. Validate the output archive and write it through Android's document create
   flow. The output is unsigned as requested.

The Android UI should have one primary screen with an input APK card, an
inspection summary, a single build action, progress, and a completed output
state. The input action should use `ACTION_OPEN_DOCUMENT` and the output action
should use `ACTION_CREATE_DOCUMENT` so the user controls both locations.
Interactive content must account for system bar insets on modern Android.
[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files),
[edge-to-edge guidance](https://developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge).

The archive rewrite must handle binary `AndroidManifest.xml`, DEX naming and
class conflicts, native ABI placement, and existing signatures. APK mutation
invalidates an existing APK signature, so the final output needs signature
metadata removed and must be identified as unsigned.
[AppComponentFactory attribute](https://developer.android.com/reference/android/R.styleable.html),
[apksigner guidance](https://developer.android.com/tools/apksigner).

## Build checks

- The arm64 native CMake target builds with installed NDK 30 and Clang 21 in
  C++23 mode for Android API 29.
- Gradle 9.6 and AGP 9.4 complete `:app:assembleDebug`. The output is
  `app/build/outputs/apk/debug/app-debug-unsigned.apk`.
- `apksigner verify` rejects that output because it is unsigned, as requested.
- AOSP source for `ActivityThread`, `PackageManager`, `PackageInfo`, `Parcel`,
  and `SigningInfo` was downloaded for Android 10 through 17. The symbol report
  is in `AOSP-COMPATIBILITY.md`. Source presence does not establish that
  reflection or native tracing works on a device.
