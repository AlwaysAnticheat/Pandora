#pragma once
#include "Constants.h"
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <sys/types.h>

namespace Pandora
{
    struct RedirectRule
    {
        char OriginalPath[PathCapacity];
        char ReplacementPath[PathCapacity];
        std::uint64_t OriginalLength;
        std::uint64_t ReplacementLength;
        std::uint64_t OriginalHash;
        std::uint64_t ReplacementHash;
        bool Enabled;
        std::uint8_t AlignmentPadding[7];
    };

    struct GlobalState
    {
        RedirectRule Rules[RuleCapacity];
        std::int32_t OriginalIndex[HashCapacity];
        std::int32_t ReplacementIndex[HashCapacity];
        std::atomic<std::uint64_t> RuleCount;
        std::atomic<bool> Initialized;
        std::atomic<RedirectState> State;
        std::uint8_t AlignmentPadding[2];
        std::atomic<pid_t> TracerPid;
        bool AddRule(const char *Original, const char *Replacement);
        const RedirectRule *FindRule(const char *Path, std::size_t Length, bool Reverse) const;
    };

    struct Arm64Registers
    {
        std::uint64_t General[31];
        std::uint64_t StackPointer;
        std::uint64_t ProgramCounter;
        std::uint64_t ProcessorState;
    };

    struct ProcessMemory
    {
        pid_t ProcessId;
        ssize_t Read(std::uint64_t Address, void *Buffer, std::size_t Length) const;
        ssize_t Write(std::uint64_t Address, const void *Buffer, std::size_t Length) const;
        std::size_t ReadCString(std::uint64_t Address, char (&Buffer)[PathCapacity]) const;
    };
    struct alignas(16) SyscallContext
    {
        pid_t ThreadId;
        std::uint8_t ReservedHeader[12];
        Arm64Registers Registers;
        ProcessMemory Memory;
        bool CommitRegisters();
    };

    struct AttachResult
    {
        pid_t Threads[ThreadCapacity]{};
        int Count = 0;
        bool Success = false;
        bool Contains(pid_t ThreadId) const;
        void DetachAll() const;
    };

    struct ThreadTraceState
    {
        bool OptionsSet = false;
        bool AwaitingExit = false;
    };

    GlobalState *GetGlobalState();
    bool EnsureGlobalState();
    void SetState(RedirectState State);
    std::uint64_t HashPath(const char *Path, std::size_t Length);
}
