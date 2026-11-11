#include "pandora/redirect.h"
#include "Integrity.h"

// Public API surface for the static library. Thin, JNI-free wrappers over the
// reconstructed redirector core (Pandora::InitializeRedirect and friends).

namespace pandora
{
    bool install(const char *ownApkPath, const char *redirectApkPath)
    {
        // ownApkPath is the intercepted path; redirectApkPath is served in its
        // place. InitializeRedirect returns 0 when the tracer thread started.
        return Pandora::InitializeRedirect(ownApkPath, redirectApkPath) == 0;
    }

    bool active()
    {
        return Pandora::IsRedirectActive();
    }

    int tracer_pid()
    {
        return static_cast<int>(Pandora::GetTracerPid());
    }
}

extern "C"
{
    int pandora_install(const char *own_apk_path, const char *redirect_apk_path)
    {
        return Pandora::InitializeRedirect(own_apk_path, redirect_apk_path);
    }

    int pandora_active(void)
    {
        return Pandora::IsRedirectActive() ? 1 : 0;
    }

    int pandora_tracer_pid(void)
    {
        return static_cast<int>(Pandora::GetTracerPid());
    }
}
