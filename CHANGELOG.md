# Changelog

## [1.1.0] - 2026-11-13

### Added

- Searchable installed-app selection with app icons and user/all-app filters.
- A terminal-style build log with actual operation messages and per-entry copied byte counts.
- A copy-log action and a Latest control to resume following new output.

### Fixed

- Preserve a valid input APK when a replacement import fails.
- Keep log scrolling under user control while new lines arrive.
- Apply safe-area insets without losing page padding.
- Use a custom dark alert for split-APK selections and a chevron back control.

### Verified

- Four on-device checks passed on Android 16 arm64, covering installed-app selection, import and build completion, copied byte counts, and log scrolling during live updates.
- Split installations are labeled and require a standalone APK.

## [1.0.0] - 2026-11-12

### Added

- Android app for selecting an APK, applying the Pandora core, and exporting an unsigned patched APK.
- Arm64 C++23 core that redirects reads of the installed APK path to an embedded copy of the original APK.
- Original `AppComponentFactory` delegation for apps that already declare one.
- Dark Android interface with an animated glass tab bar, About page, and Pandora launcher icon.
- Two-module Gradle project with `pandora` and `pandora-core`.

### Fixed

- Preserved syscall entry arguments for exit handlers and corrected `fstat` file-descriptor handling.
- Bounded `readlinkat` rewrites by the returned length and caller buffer size.
- Aligned stored APK resources and verified the output with `zipalign`.
- Removed Kotlin and hidden-API library classes from the injected payload DEX.
- Moved release signing to a local JKS and excluded signing material from Git.

### Compatibility

- Builds for Android 10 through 17 on arm64-v8a.
- Tested the APK transformation and redirected APK reads on an Android 16 arm64 device.
- Accepts one APK file. Split APK sets need separate handling.
- Exports patched APKs unsigned. Installation requires signing the exported file.
