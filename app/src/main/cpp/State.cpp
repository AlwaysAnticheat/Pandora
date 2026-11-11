#include "Structures.h"
#include <cstring>
#include <new>
#include <sys/mman.h>

namespace Pandora
{
    namespace
    {
        GlobalState *SharedState = nullptr;
    }

    GlobalState *GetGlobalState() { return SharedState; }


    std::uint64_t HashPath(const char *Path, std::size_t Length)
    {
        std::uint64_t Hash = FnvOffset;
        for (std::size_t Index = 0; Index < Length; ++Index)
            Hash = (Hash ^ static_cast<unsigned char>(Path[Index])) * FnvPrime;
        return Hash;
    }

    bool EnsureGlobalState()
    {
        if (SharedState)
            return true;
        void *Region = mmap(nullptr, sizeof(GlobalState), PROT_READ | PROT_WRITE,
                            MAP_SHARED | MAP_ANONYMOUS, -1, 0);
        if (Region == MAP_FAILED)
            return false;
        std::memset(Region, 0, sizeof(GlobalState));
        auto *State = new (Region) GlobalState;
        for (auto &Rule : State->Rules)
            Rule.Enabled = false;
        State->RuleCount.store(0);
        State->Initialized.store(false);
        State->State.store(RedirectState::Idle);
        State->TracerPid.store(0);
        SharedState = State;
        if (!State->Initialized.load())
        {
            std::memset(State->OriginalIndex, 0xff, sizeof(State->OriginalIndex));
            std::memset(State->ReplacementIndex, 0xff, sizeof(State->ReplacementIndex));
            State->Initialized.store(true);
        }
        return true;
    }

    void SetState(RedirectState State)
    {
        if (SharedState)
            SharedState->State.store(State);
    }

    bool GlobalState::AddRule(const char *Original, const char *Replacement)
    {
        const auto Index = RuleCount.load();
        if (Index >= RuleCapacity)
            return false;
        auto &Rule = Rules[Index];
        std::strncpy(Rule.OriginalPath, Original, PathCapacity - 1);
        std::strncpy(Rule.ReplacementPath, Replacement, PathCapacity - 1);
        Rule.OriginalLength = std::strlen(Rule.OriginalPath);
        Rule.ReplacementLength = std::strlen(Rule.ReplacementPath);
        Rule.OriginalHash = HashPath(Rule.OriginalPath, Rule.OriginalLength);
        Rule.ReplacementHash = HashPath(Rule.ReplacementPath, Rule.ReplacementLength);
        Rule.Enabled = true;
        auto Insert = [Index](std::int32_t *Table, std::uint64_t Hash)
        {
            auto Slot = static_cast<std::uint8_t>(Hash);
            while (Table[Slot] != -1)
                ++Slot;
            Table[Slot] = static_cast<std::int32_t>(Index);
        };
        Insert(OriginalIndex, Rule.OriginalHash);
        Insert(ReplacementIndex, Rule.ReplacementHash);
        RuleCount.fetch_add(1);
        return true;
    }
    const RedirectRule *GlobalState::FindRule(const char *Path, std::size_t Length, bool Reverse) const
    {
        const auto Hash = HashPath(Path, Length);
        const auto First = static_cast<std::uint8_t>(Hash);
        auto Slot = First;
        const auto *Table = Reverse ? ReplacementIndex : OriginalIndex;
        do
        {
            if (Table[Slot] == -1)
                return nullptr;
            const auto &Rule = Rules[Table[Slot]];
            const auto *Candidate = Reverse ? Rule.ReplacementPath : Rule.OriginalPath;
            if ((Reverse ? Rule.ReplacementHash : Rule.OriginalHash) == Hash &&
                (Reverse ? Rule.ReplacementLength : Rule.OriginalLength) == Length &&
                std::memcmp(Candidate, Path, Length) == 0)
                return &Rule;
            ++Slot;
        } while (Slot != First);
        return nullptr;
    }
}
