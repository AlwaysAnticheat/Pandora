# libpandora-core (static library)

A self-contained CMake project that builds the reconstructed arm64 Android
path-redirector as a static library, `libpandora-core.a`.

It has **no JNI entry points** and **no auto-initialization**. The whole public
surface is one function:

```cpp
#include <pandora/redirect.h>

pandora::install(ownApkPath, redirectApkPath);
```

After `install`, any access this process makes to `ownApkPath`
(`open`/`stat`/`readlink`, including reported file size) is transparently served
from `redirectApkPath`. Redirection runs on a detached seccomp + ptrace tracer.

## Public API — `include/pandora/redirect.h`

| Function | Meaning |
| --- | --- |
| `bool pandora::install(const char* ownApkPath, const char* redirectApkPath)` | Install the redirect and start the tracer. Returns `true` if it started. |
| `bool pandora::active()` | `true` once the tracer is attached and the filter is live. |
| `int  pandora::tracer_pid()` | Tracer child PID, or `0`. |

C-linkage aliases (`pandora_install`, `pandora_active`, `pandora_tracer_pid`)
are provided for linking the archive from C. `pandora_install` returns the raw
core status (`0` on success).

The tracer activates on a background thread shortly after `install` returns, so
poll `active()` to confirm it is live.

## Build

arm64-v8a Android only. Point CMake at the NDK toolchain file:

```powershell
cmake -B build `
  -DCMAKE_TOOLCHAIN_FILE="C:\27.0.12077973\build\cmake\android.toolchain.cmake" `
  -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-24
cmake --build build
```

The archive is written to `build/libpandora-core.a`. Adjust the toolchain path
to your installed NDK (this repo's app module was configured with the NDK at
`C:\27.0.12077973`).

## Layout

```
pandora-core/
  CMakeLists.txt            static target -> libpandora-core.a
  include/pandora/redirect.h public API
  src/Redirect.cpp          public API implementation (replaces the JNI bridge)
  src/State.cpp             shared redirect-rule table
  src/ProcessMemory.cpp     process_vm_readv/writev + register commit
  src/Integrity.cpp         openat/stat/readlink rewriters
  src/Hooks.cpp             seccomp filter + ptrace tracer loop
  src/*.h                   internal headers
```

The sources are the reconstruction's JNI-free core; the original `Main.cpp`
(`JNI_OnLoad`) and `JniBridge.cpp` (`Java_...` exports) are intentionally
omitted.
