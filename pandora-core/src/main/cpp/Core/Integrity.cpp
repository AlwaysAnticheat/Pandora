#include "Integrity.hpp"
#include "Hooks.hpp"
#include <array>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <sys/stat.h>
#include <unistd.h>

namespace Pandora
{
    int InitializeRedirect(const char *OriginalPath, const char *ReplacementPath)
    {
        if (!OriginalPath || !ReplacementPath || OriginalPath[0] != '/' || ReplacementPath[0] != '/')
            return -1;
        static std::mutex InstallMutex;
        std::lock_guard<std::mutex> Lock(InstallMutex);
        if (!EnsureGlobalState())
            return -1;
        auto *Shared = GetGlobalState();
        const auto State = Shared->State.load();
        if (State == RedirectState::Starting || State == RedirectState::Active)
            return -1;
        if (!Shared->AddRule(OriginalPath, ReplacementPath))
            return -1;
        Shared->TracerPid.store(0);
        SetState(RedirectState::Starting);
        return StartTraceAsync();
    }
    bool IsRedirectActive() { return GetGlobalState() && GetGlobalState()->State.load() == RedirectState::Active; }
    pid_t GetTracerPid() { return GetGlobalState() ? GetGlobalState()->TracerPid.load() : 0; }
    std::int64_t GetFileSize(const char *Path)
    {
        struct stat Status;
        return stat(Path, &Status) == 0 ? Status.st_size : -1;
    }
    bool RewritePathArgument(SyscallContext &Context, std::uint64_t Address,
                             std::size_t OriginalLength, const char *Replacement)
    {
        const auto Length = std::strlen(Replacement);
        if (Length <= OriginalLength)
        {
            return Context.Memory.Write(Address, Replacement, Length + 1) != 0;
        }
        const auto Scratch = (Context.Registers.StackPointer & ~std::uint64_t{15}) - 4096;
        if (Context.Memory.Write(Scratch, Replacement, Length + 1) == 0)
            return false;
        Context.Registers.General[1] = Scratch;
        return Context.CommitRegisters();
    }
    void HandleOpenAt(SyscallContext &Context)
    {
        auto *Shared = GetGlobalState();
        if (!Shared || !Shared->Initialized.load() || !Context.Registers.General[1])
            return;
        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(Context.Registers.General[1], Path);
        if (!Length)
            return;
        if (auto *Rule = Shared->FindRule(Path, Length, false))
            RewritePathArgument(Context, Context.Registers.General[1], Length, Rule->ReplacementPath);
    }
    void HandleReadlinkAtExit(SyscallContext &Context)
    {
        auto *Shared = GetGlobalState();
        const auto Buffer = Context.Registers.General[2];
        if (!Shared || !Shared->Initialized.load() || !Buffer)
            return;
        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(Buffer, Path);
        if (!Length)
            return;
        if (auto *Rule = Shared->FindRule(Path, Length, true))
        {
            if (Context.Memory.Write(Buffer, Rule->OriginalPath, Rule->OriginalLength + 1) != 0)
            {
                Context.Registers.General[0] = std::strlen(Rule->OriginalPath);
                Context.CommitRegisters();
            }
        }
    }
    void HandleFstatExit(SyscallContext &Context)
    {
        auto *Shared = GetGlobalState();
        const auto Buffer = Context.Registers.General[1];
        if (!Shared || !Shared->Initialized.load() || !Buffer)
            return;
        char Link[PathCapacity], Path[PathCapacity];
        std::snprintf(Link, sizeof(Link), "/proc/self/fd/%d", static_cast<int>(Context.Registers.General[0]));
        auto Length = readlink(Link, Path, PathCapacity - 1);
        if (Length < 1)
            return;
        Path[Length] = '\0';
        const auto *Rule = Shared->FindRule(Path, std::strlen(Path), true);
        if (!Rule)
            return;
        std::array<std::uint64_t, 16> Status;
        if (Context.Memory.Read(Buffer, Status.data(), 128) == 0)
            return;
        const auto Size = GetFileSize(Rule->OriginalPath);
        if (Size < 0)
            return;
        Status[6] = Size;
        Status[8] = (static_cast<std::uint64_t>(Size) + 511) >> 9;
        Context.Memory.Write(Buffer, Status.data(), 128);
    }
    void HandleStatxExit(SyscallContext &Context)
    {
        auto *Shared = GetGlobalState();
        const auto PathAddress = Context.Registers.General[1];
        const auto Buffer = Context.Registers.General[4];
        if (!Shared || !Shared->Initialized.load() || !PathAddress || !Buffer)
            return;
        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(PathAddress, Path);
        const auto *Rule = Length ? Shared->FindRule(Path, Length, true) : nullptr;
        if (!Rule)
            return;
        std::array<std::uint64_t, 32> Status;
        if (Context.Memory.Read(Buffer, Status.data(), 256) == 0)
            return;
        const auto Size = GetFileSize(Rule->OriginalPath);
        if (Size < 0)
            return;
        Status[5] = Size;
        Status[6] = (static_cast<std::uint64_t>(Size) + 511) >> 9;
        Status[0] |= 0x200;
        Context.Memory.Write(Buffer, Status.data(), 256);
    }
    void HandleStatExit(SyscallContext &Context)
    {
        const auto Number = static_cast<Arm64Syscall>(Context.Registers.General[8]);
        if (Number == Arm64Syscall::Fstat)
        {
            HandleFstatExit(Context);
            return;
        }
        if (Number == Arm64Syscall::Statx)
        {
            HandleStatxExit(Context);
            return;
        }
        auto *Shared = GetGlobalState();
        if (Number != Arm64Syscall::NewFstatAt || !Shared || !Shared->Initialized.load())
            return;
        const auto Address = Context.Registers.General[1], Buffer = Context.Registers.General[2];
        if (!Address || !Buffer)
            return;
        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(Address, Path);
        const auto *Rule = Length ? Shared->FindRule(Path, Length, false) : nullptr;
        if (!Rule)
            return;
        std::array<std::uint64_t, 16> Status;
        if (Context.Memory.Read(Buffer, Status.data(), 128) == 0)
            return;
        const auto Size = GetFileSize(Rule->ReplacementPath);
        if (Size < 0)
            return;
        Status[6] = Size;
        Status[8] = (static_cast<std::uint64_t>(Size) + 511) >> 9;
        Context.Memory.Write(Buffer, Status.data(), 128);
    }
}
