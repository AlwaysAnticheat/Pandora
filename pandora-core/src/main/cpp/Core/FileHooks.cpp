#include "FileHooks.hpp"
#include <array>
#include <cstdio>
#include <cstring>
#include <sys/stat.h>
#include <unistd.h>

namespace Pandora::FileHooks
{
    namespace
    {
        using EntryHook = Action (*)(SyscallContext &);
        using ExitHook = void (*)(SyscallContext &);

        struct Route
        {
            Arm64Syscall Number;
            EntryHook OnEntry;
            ExitHook OnExit;
        };

        constexpr Route Routes[] = { { Arm64Syscall::OpenAt, HookOpenAt, nullptr }, { Arm64Syscall::OpenAt2, HookOpenAt, nullptr }, { Arm64Syscall::ReadlinkAt, HookReadlinkAt, FinishReadlinkAt }, { Arm64Syscall::Fstat, HookFstat, FinishFstat }, { Arm64Syscall::NewFstatAt, HookNewFstatAt, FinishNewFstatAt }, { Arm64Syscall::Statx, HookStatx, FinishStatx } };

        GlobalState *ReadyState()
        {
            auto *State = GetGlobalState();
            return State && State->Initialized.load() ? State : nullptr;
        }

        std::int64_t GetFileSize(const char *Path)
        {
            struct stat Status;
            return stat(Path, &Status) == 0 ? Status.st_size : -1;
        }

        bool RewritePathArgument(SyscallContext &Context, std::uint64_t Address, std::size_t OriginalLength, const char *Replacement)
        {
            const auto Length = std::strlen(Replacement) + 1;
            if (Length <= OriginalLength + 1)
                return Context.Memory.Write(Address, Replacement, Length) == (ssize_t)Length;

            const auto Scratch = (Context.Registers.StackPointer & ~std::uint64_t{ 15 }) - PathCapacity;
            if (Context.Memory.Write(Scratch, Replacement, Length) != (ssize_t)Length)
                return false;
            Context.SetArgument(1, Scratch);
            return Context.CommitRegisters();
        }

        void RewriteInputPath(SyscallContext &Context)
        {
            auto *State = ReadyState();
            const auto Address = Context.Argument(1);
            if (!State || !Address)
                return;

            char Path[PathCapacity];
            const auto Length = Context.Memory.ReadCString(Address, Path);
            if (!Length)
                return;
            if (auto *Rule = State->FindRule(Path, Length, false))
                RewritePathArgument(Context, Address, Length, Rule->ReplacementPath);
        }
    }

    Action HookOpenAt(SyscallContext &Context)
    {
        RewriteInputPath(Context);
        return Action::Continue;
    }

    Action HookReadlinkAt(SyscallContext &)
    {
        return Action::TraceExit;
    }

    Action HookFstat(SyscallContext &)
    {
        return Action::TraceExit;
    }

    Action HookNewFstatAt(SyscallContext &Context)
    {
        RewriteInputPath(Context);
        return Action::TraceExit;
    }

    Action HookStatx(SyscallContext &Context)
    {
        RewriteInputPath(Context);
        return Action::TraceExit;
    }

    void FinishReadlinkAt(SyscallContext &Context)
    {
        auto *State = ReadyState();
        const auto Length = Context.Result();
        const auto Buffer = Context.OriginalArgument(2);
        const auto Capacity = Context.OriginalArgument(3);
        if (!State || !Buffer || Length <= 0 || Length >= (std::int64_t)PathCapacity || (std::uint64_t)Length > Capacity)
            return;

        char Path[PathCapacity];
        if (Context.Memory.Read(Buffer, Path, (std::size_t)Length) != Length)
            return;
        Path[Length] = '\0';
        const auto *Rule = State->FindRule(Path, (std::size_t)Length, true);
        if (!Rule || Rule->OriginalLength > Capacity)
            return;
        if (Context.Memory.Write(Buffer, Rule->OriginalPath, Rule->OriginalLength) != (ssize_t)Rule->OriginalLength)
            return;
        Context.SetResult((std::int64_t)Rule->OriginalLength);
        Context.CommitRegisters();
    }

    void FinishFstat(SyscallContext &Context)
    {
        auto *State = ReadyState();
        const auto Buffer = Context.OriginalArgument(1);
        if (!State || !Buffer || Context.Result() != 0)
            return;

        char Link[64];
        const auto LinkLength = std::snprintf(Link, sizeof(Link), "/proc/%d/fd/%d", Context.ThreadId, (int)Context.OriginalArgument(0));
        if (LinkLength < 0 || LinkLength >= (int)sizeof(Link))
            return;

        char Path[PathCapacity];
        const auto Length = readlink(Link, Path, PathCapacity - 1);
        if (Length <= 0)
            return;
        Path[Length] = '\0';
        const auto *Rule = State->FindRule(Path, (std::size_t)Length, true);
        if (!Rule)
            return;

        std::array<std::uint64_t, 16> Status;
        if (Context.Memory.Read(Buffer, Status.data(), sizeof(Status)) != sizeof(Status))
            return;
        const auto Size = GetFileSize(Rule->OriginalPath);
        if (Size < 0)
            return;
        Status[6] = (std::uint64_t)Size;
        Status[8] = ((std::uint64_t)Size + 511) >> 9;
        Context.Memory.Write(Buffer, Status.data(), sizeof(Status));
    }

    void FinishNewFstatAt(SyscallContext &Context)
    {
        auto *State = ReadyState();
        const auto Address = Context.OriginalArgument(1);
        const auto Buffer = Context.OriginalArgument(2);
        if (!State || !Address || !Buffer || Context.Result() != 0)
            return;

        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(Address, Path);
        const auto *Rule = Length ? State->FindRule(Path, Length, false) : nullptr;
        if (!Rule)
            return;

        std::array<std::uint64_t, 16> Status;
        if (Context.Memory.Read(Buffer, Status.data(), sizeof(Status)) != sizeof(Status))
            return;
        const auto Size = GetFileSize(Rule->ReplacementPath);
        if (Size < 0)
            return;
        Status[6] = (std::uint64_t)Size;
        Status[8] = ((std::uint64_t)Size + 511) >> 9;
        Context.Memory.Write(Buffer, Status.data(), sizeof(Status));
    }

    void FinishStatx(SyscallContext &Context)
    {
        auto *State = ReadyState();
        const auto Address = Context.Argument(1);
        const auto Buffer = Context.OriginalArgument(4);
        if (!State || !Address || !Buffer || Context.Result() != 0)
            return;

        char Path[PathCapacity];
        const auto Length = Context.Memory.ReadCString(Address, Path);
        const auto *Rule = Length ? State->FindRule(Path, Length, true) : nullptr;
        if (!Rule)
            return;

        std::array<std::uint64_t, 32> Status;
        if (Context.Memory.Read(Buffer, Status.data(), sizeof(Status)) != sizeof(Status))
            return;
        const auto Size = GetFileSize(Rule->OriginalPath);
        if (Size < 0)
            return;
        Status[5] = (std::uint64_t)Size;
        Status[6] = ((std::uint64_t)Size + 511) >> 9;
        Status[0] |= 0x200;
        Context.Memory.Write(Buffer, Status.data(), sizeof(Status));
    }

    Action DispatchEntry(SyscallContext &Context)
    {
        for (const auto &Route : Routes)
            if (Route.Number == Context.Number())
                return Route.OnEntry(Context);
        return Action::Continue;
    }

    void DispatchExit(SyscallContext &Context)
    {
        for (const auto &Route : Routes)
            if (Route.Number == Context.Number())
            {
                if (Route.OnExit)
                    Route.OnExit(Context);
                return;
            }
    }
}
