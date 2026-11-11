#pragma once

// Public API for libpandora-core.a
//
// A process-local file-path redirector for arm64 Android. After install(),
// any access this process makes to `ownApkPath` (open/stat/readlink) is
// transparently served from `redirectApkPath`, including reported file size.
// Redirection is implemented with a detached seccomp + ptrace tracer; there
// are no JNI entry points and no auto-initialization.

namespace pandora
{
    // Install a redirect from `ownApkPath` to `redirectApkPath` and start the
    // tracer asynchronously. Both paths must be non-null, non-empty, absolute
    // paths. Returns true if the tracer was started (it activates shortly
    // after on a background thread; poll active() to confirm). Returns false
    // if arguments are invalid or a redirect is already starting/active.
    bool install(const char *ownApkPath, const char *redirectApkPath);

    // True once the tracer has attached and the seccomp filter is live.
    bool active();

    // PID of the tracer child process, or 0 if it has not started.
    int tracer_pid();
}

// C-linkage aliases for consumers that link the archive from C.
extern "C"
{
    int pandora_install(const char *own_apk_path, const char *redirect_apk_path);
    int pandora_active(void);
    int pandora_tracer_pid(void);
}
