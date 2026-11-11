#pragma once
#include "Structures.h"

namespace Pandora
{
    int InitializeRedirect(const char *OriginalPath, const char *ReplacementPath);
    bool IsRedirectActive();
    pid_t GetTracerPid();
    bool RewritePathArgument(SyscallContext &Context, std::uint64_t Address,
                             std::size_t OriginalLength, const char *Replacement);
    void HandleOpenAt(SyscallContext &Context);
    void HandleReadlinkAtExit(SyscallContext &Context);
    void HandleStatExit(SyscallContext &Context);
    void HandleFstatExit(SyscallContext &Context);
    void HandleStatxExit(SyscallContext &Context);
    std::int64_t GetFileSize(const char *Path);
}
