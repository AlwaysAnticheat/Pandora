# Research notes

## Current architecture

- `:pandora-core` builds the arm64 C++23 payload and Java component factory as
  an unsigned APK.
- The payload has no runtime library dependencies. Its current DEX is 7,224
  bytes and contains only `AppFactory`, its data holder, and the generated `R`
  class.
- `:pandora` builds the signed Android app. A generated asset embeds the core
  APK at `assets/pandora/payload.apk`.
- The builder reads an input APK through Android's document picker, updates its
  binary manifest, appends the payload DEX files and arm64 library, embeds the
  original APK, and exports an unsigned ZIP-aligned APK.
- An input app's existing `AppComponentFactory` class name is saved in the
  output. Pandora's factory delegates class loading and component creation to
  that original factory.

## Verification

- AGP 9.4 and Gradle 9.6 build the signed `:pandora:assembleRelease` output.
- The connected Android 16 arm64 device installed and launched the signed
  builder. The on-device archive test passed with an AndroidX component
  factory in the input APK.
- The test output passed `zipalign -c 4`. `aapt dump badging` parsed its binary
  manifest. `apksigner verify` confirmed it remained unsigned.
- The on-device archive test passed after removal of the signature proxy and
  verifies that no signature metadata asset is added.
- A temporary copy signed with the local JKS installed and launched. The
  payload extracted the embedded original APK. The signed builder was
  restored afterward.
- AOSP source checks for Android 10 through 17 are in
  `AOSP-COMPATIBILITY.md`. Symbol presence does not verify device behavior.

## Remaining compatibility work

- Run device or emulator tests on Android 10 through 15 and 17. The connected
  device covers Android 16 only.
- Validate apps distributed as split APK sets.
- Exercise native tracing against representative target apps. The AndroidX
  factory launch test does not cover every custom factory implementation.
