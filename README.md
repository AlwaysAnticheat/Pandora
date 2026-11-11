# Pandora

Pandora is an Android 10 through 17 arm64 research project. The repository currently contains
a native redirector core and an Android payload module. The payload module has
no launcher activity or APK input workflow yet.

## Source tree

| Path | Purpose |
| --- | --- |
| `pandora-core/` | C++23 static library and public redirect API |
| `app/` | Android payload module and JNI bridge |
| `gradle/` | Gradle wrapper |

The Android module links the static core through CMake. Its debug and release
builds are configured to produce unsigned APKs. Build with
`./gradlew :app:assembleDebug` or `./gradlew :app:assembleRelease` after setting
the Android SDK path through `local.properties` or the standard environment.

The APK transformation workflow and Android user interface are tracked in
`RESEARCH.md`. They are not implemented in the current source tree.
