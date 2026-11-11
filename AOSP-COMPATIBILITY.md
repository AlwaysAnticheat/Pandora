# AOSP source compatibility

| AOSP source symbol | [Android 10](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/core/java/) | [Android 11](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/) | [Android 12](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/) | [Android 13](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/) | [Android 14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/) | [Android 15](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/) | [Android 16](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/) | [Android 17](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android17-release/core/java/) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ActivityThread.currentActivityThread | yes | yes | yes | yes | yes | yes | yes | yes |
| ActivityThread.mBoundApplication | yes | yes | yes | yes | yes | yes | yes | yes |
| PackageManager.sPackageInfoCache | no | yes | yes | yes | yes | yes | yes | yes |
| PackageInfo.CREATOR | yes | yes | yes | yes | yes | yes | yes | yes |
| Parcel.mCreators | yes | yes | yes | yes | yes | yes | yes | yes |
| Parcel.sPairedCreators | no | no | no | no | yes | yes | yes | yes |
| SigningInfo.getApkContentsSigners | yes | yes | yes | yes | yes | yes | yes | yes |

This report checks source symbol presence. It does not establish runtime access
to hidden APIs or device-specific behavior.

Sources were downloaded from the `android10-release` through
`android17-release` branches of AOSP frameworks/base on android.googlesource.com.
