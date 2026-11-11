#include "pandora/Redirect.hpp"
#include "Integrity.hpp"

namespace Pandora
{
    bool Install(const char *OwnApkPath, const char *RedirectApkPath)
    {
        return InitializeRedirect(OwnApkPath, RedirectApkPath) == 0;
    }

    bool IsActive()
    {
        return IsRedirectActive();
    }

    int TracerPid()
    {
        return static_cast<int>(GetTracerPid());
    }
}

namespace pandora
{
    bool install(const char *ownApkPath, const char *redirectApkPath)
    {
        return Pandora::Install(ownApkPath, redirectApkPath);
    }

    bool active()
    {
        return Pandora::IsActive();
    }

    int tracer_pid()
    {
        return Pandora::TracerPid();
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
