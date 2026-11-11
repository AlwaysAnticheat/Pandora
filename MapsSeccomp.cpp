#include "MapsSeccomp.hpp"

#include "Enc.h"
#include "LibcSafe.hpp"
#include "Utility/Logger.hpp"

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>

#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/ucontext.h>
#include <unistd.h>

extern "C" char __MundoSvc;

namespace libc_custom
{
    __attribute__((noinline, used, section(".text.mundo_svc"))) long Svc(long Nr, long A0, long A1, long A2, long A3, long A4, long A5)
    {
        register long X0 __asm__("x0") = A0;
        register long X1 __asm__("x1") = A1;
        register long X2 __asm__("x2") = A2;
        register long X3 __asm__("x3") = A3;
        register long X4 __asm__("x4") = A4;
        register long X5 __asm__("x5") = A5;
        register long X8 __asm__("x8") = Nr;
        __asm__ volatile(".global __MundoSvc\n"
                         "__MundoSvc:\n"
                         "svc #0\n"
                         : "+r"(X0)
                         : "r"(X1), "r"(X2), "r"(X3), "r"(X4), "r"(X5), "r"(X8)
                         : "memory", "cc");
        return X0;
    }
} // namespace libc_custom

namespace SDK::MapsSeccomp
{
    namespace
    {
        constexpr const char *LoaderPkg = "com.pubg.obsidian";
        constexpr size_t LoaderPkgLen = 17;
        constexpr size_t MaxCmdlineLen = 128;
        constexpr size_t TaskCommLen = 16;
        constexpr size_t StreamChunk = 8192;
        constexpr size_t StreamOut = 16384;
        constexpr size_t SigStackSize = 64 * 1024;
        constexpr useconds_t RefreshIntervalUs = 10 * 1000 * 1000;

        bool Installed = false;
        char HostPkg[MaxCmdlineLen] = { 0 };
        size_t HostPkgLen = 0;
        char CommContent[TaskCommLen + 1] = { 0 };
        size_t CommContentLen = 0;
        alignas(16) unsigned char SigStack[SigStackSize];

        char FilesDirPath[128] = { 0 };
        size_t FilesDirPathLen = 0;

        int SpoofFdMaps = -1;
        int SpoofFdCmdline = -1;
        int SpoofFdStatus = -1;
        int SpoofFdComm = -1;

        enum ProcFile
        {
            PATH_OTHER,
            PATH_MAPS,
            PATH_COMM,
            PATH_CMDLINE,
            PATH_STATUS
        };

        __attribute__((always_inline)) inline bool IsDigit(char C)
        {
            return C >= '0' && C <= '9';
        }

        __attribute__((always_inline)) inline bool IsPkgNameChar(char C)
        {
            return (C >= 'a' && C <= 'z') || (C >= 'A' && C <= 'Z') || IsDigit(C) || C == '.' || C == '_';
        }

        __attribute__((always_inline)) inline bool StartsWith(const char *Str, const char *Prefix)
        {
            while (*Prefix)
                if (*Str++ != *Prefix++)
                    return false;
            return true;
        }

        __attribute__((always_inline)) inline bool Equals(const char *A, const char *B)
        {
            while (*A && *A == *B)
            {
                ++A;
                ++B;
            }
            return *A == 0 && *B == 0;
        }

        struct SafeWriter
        {
            char *Base;
            size_t Capacity;
            size_t Position;

            __attribute__((always_inline)) SafeWriter(char *B, size_t C) : Base(B), Capacity(C), Position(0) {}

            __attribute__((always_inline)) bool Append(const char *Src, size_t N)
            {
                if (Position + N >= Capacity)
                    return false;
                libc_custom::memcpy(Base + Position, Src, N);
                Position += N;
                return true;
            }

            __attribute__((always_inline)) bool AppendChar(char C)
            {
                if (Position + 1 >= Capacity)
                    return false;
                Base[Position++] = C;
                return true;
            }

            __attribute__((always_inline)) void Terminate()
            {
                Base[Position] = 0;
            }
        };

        struct LineScanner
        {
            char *Base;
            size_t Total;
            size_t Cursor;

            LineScanner(char *B, size_t T) : Base(B), Total(T), Cursor(0) {}

            bool Next(char *&LineOut, size_t &LineLenOut)
            {
                if (Cursor >= Total)
                    return false;
                size_t Start = Cursor;
                while (Cursor < Total && Base[Cursor] != '\n')
                    ++Cursor;
                LineOut = Base + Start;
                LineLenOut = Cursor - Start;
                if (Cursor < Total)
                    ++Cursor;
                return true;
            }
        };

        void NormalizeProcPath(const char *In, char *Out, size_t OutSize)
        {
            size_t Write = 0;
            size_t Read = 0;
            size_t InLen = In ? libc_custom::strlen(In) : 0;
            while (Read < InLen && Write + 1 < OutSize)
            {
                if (In[Read] == '/')
                {
                    Out[Write++] = '/';
                    ++Read;
                    while (Read < InLen)
                    {
                        if (In[Read] == '/')
                        {
                            ++Read;
                            continue;
                        }
                        if (In[Read] == '.' && (Read + 1 >= InLen || In[Read + 1] == '/'))
                        {
                            Read += (Read + 1 < InLen) ? 2 : 1;
                            continue;
                        }
                        break;
                    }
                }
                else
                    Out[Write++] = In[Read++];
            }
            Out[Write] = 0;
        }

        const char *StripPidOrSelf(const char *Cursor)
        {
            if (StartsWith(Cursor, "self") && (Cursor[4] == '/' || Cursor[4] == 0))
                return Cursor + 4;
            const char *Scan = Cursor;
            while (IsDigit(*Scan))
                ++Scan;
            return (Scan == Cursor) ? nullptr : Scan;
        }

        ProcFile ClassifyPath(const char *RawPath)
        {
            if (!RawPath)
                return PATH_OTHER;

            char Normalized[1024];
            NormalizeProcPath(RawPath, Normalized, sizeof(Normalized));

            if (!StartsWith(Normalized, "/proc/"))
                return PATH_OTHER;

            const char *Cursor = StripPidOrSelf(Normalized + 6);
            if (!Cursor)
                return PATH_OTHER;

            for (bool KeepPeeling = true; KeepPeeling;)
            {
                KeepPeeling = false;
                if (StartsWith(Cursor, "/root/proc/"))
                {
                    Cursor = StripPidOrSelf(Cursor + 11);
                    if (!Cursor)
                        return PATH_OTHER;
                    KeepPeeling = true;
                }
                else if (StartsWith(Cursor, "/cwd/proc/"))
                {
                    Cursor = StripPidOrSelf(Cursor + 10);
                    if (!Cursor)
                        return PATH_OTHER;
                    KeepPeeling = true;
                }
                else if (StartsWith(Cursor, "/task/"))
                {
                    const char *AfterTid = Cursor + 6;
                    while (IsDigit(*AfterTid))
                        ++AfterTid;
                    if (AfterTid == Cursor + 6)
                        return PATH_OTHER;
                    Cursor = AfterTid;
                    KeepPeeling = true;
                }
            }

            if (Equals(Cursor, "/maps"))
                return PATH_MAPS;
            if (Equals(Cursor, "/comm"))
                return PATH_COMM;
            if (Equals(Cursor, "/cmdline"))
                return PATH_CMDLINE;
            if (Equals(Cursor, "/status"))
                return PATH_STATUS;
            return PATH_OTHER;
        }

        bool ResolveRelativeOpenAt(int Dirfd, const char *Path, char *Out, size_t OutSize)
        {
            if (Dirfd < 0 || Dirfd == AT_FDCWD || !Path || !*Path)
                return false;

            char Link[48];
            libc_custom::memcpy(Link, "/proc/self/fd/", 14);
            size_t Pos = 14;
            Pos += libc_custom::itoa_u32((uint32_t)Dirfd, Link + Pos);
            Link[Pos] = 0;

            long N = libc_custom::Svc(__NR_readlinkat, AT_FDCWD, (long)Link, (long)Out, (long)(OutSize - 2));
            if (N <= 0)
                return false;

            size_t PathLen = libc_custom::strlen(Path);
            if ((size_t)N + 1 + PathLen + 1 > OutSize)
                return false;
            Out[N] = '/';
            libc_custom::memcpy(Out + N + 1, Path, PathLen + 1);
            return true;
        }

        ProcFile ClassifyOpen(int Dirfd, const char *Path, char *Scratch, size_t ScratchSize)
        {
            if (!Path)
                return PATH_OTHER;
            if (Path[0] == '/')
                return ClassifyPath(Path);
            if (!ResolveRelativeOpenAt(Dirfd, Path, Scratch, ScratchSize))
                return PATH_OTHER;
            return ClassifyPath(Scratch);
        }

        __attribute__((always_inline)) inline size_t MatchSandboxLayout(const char *In, size_t InLen, size_t PosAfterLoader)
        {
            if (PosAfterLoader + 5 > InLen)
                return 0;
            if (In[PosAfterLoader] != '/')
                return 0;
            if (libc_custom::memcmp(In + PosAfterLoader + 1, "app_", 4) != 0)
                return 0;

            size_t AppEnd = PosAfterLoader + 5;
            while (AppEnd < InLen && In[AppEnd] != '/')
                ++AppEnd;
            if (AppEnd >= InLen)
                return 0;

            size_t HostStart = AppEnd + 1;
            if (HostStart + HostPkgLen + 1 >= InLen)
                return 0;
            if (libc_custom::memcmp(In + HostStart, HostPkg, HostPkgLen) != 0)
                return 0;
            if (In[HostStart + HostPkgLen] != '/')
                return 0;
            if (In[HostStart + HostPkgLen + 1] != '0')
                return 0;

            return HostStart + HostPkgLen + 2;
        }

        size_t SubstituteChunk(const char *In, size_t InLen, char *Out, size_t OutSize)
        {
            if (OutSize == 0)
                return 0;

            size_t ReadPos = 0;
            size_t WritePos = 0;

            while (ReadPos < InLen && WritePos + 1 < OutSize)
            {
                bool StartBoundary = (ReadPos == 0) || !IsPkgNameChar(In[ReadPos - 1]);
                bool EndBoundary = (ReadPos + LoaderPkgLen >= InLen) || !IsPkgNameChar(In[ReadPos + LoaderPkgLen]);
                bool LoaderMatch = StartBoundary && EndBoundary && ReadPos + LoaderPkgLen <= InLen && libc_custom::memcmp(In + ReadPos, LoaderPkg, LoaderPkgLen) == 0;

                if (!LoaderMatch)
                {
                    Out[WritePos++] = In[ReadPos++];
                    continue;
                }

                if (WritePos + HostPkgLen + 1 >= OutSize)
                    break;
                libc_custom::memcpy(Out + WritePos, HostPkg, HostPkgLen);
                WritePos += HostPkgLen;

                size_t SandboxEnd = MatchSandboxLayout(In, InLen, ReadPos + LoaderPkgLen);
                ReadPos = SandboxEnd ? SandboxEnd : (ReadPos + LoaderPkgLen);
            }

            Out[WritePos] = 0;
            return WritePos;
        }

        void MaskSeccompStatus(char *Buffer, size_t Length)
        {
            constexpr const char SeccompTag[] = "Seccomp";
            constexpr size_t TagLen = sizeof(SeccompTag) - 1;

            LineScanner Scanner(Buffer, Length);
            char *Line;
            size_t LineLen;
            while (Scanner.Next(Line, LineLen))
            {
                if (LineLen < TagLen)
                    continue;
                if (libc_custom::memcmp(Line, SeccompTag, TagLen) != 0)
                    continue;
                for (size_t Digit = TagLen; Digit < LineLen; ++Digit)
                    if (IsDigit(Line[Digit]))
                        Line[Digit] = '0';
            }
        }

        void BuildFilesDirPath()
        {
            constexpr const char Prefix[] = "/data/data/";
            constexpr size_t PrefixLen = sizeof(Prefix) - 1;
            constexpr const char Suffix[] = "/files";
            constexpr size_t SuffixLen = sizeof(Suffix) - 1;

            SafeWriter Writer(FilesDirPath, sizeof(FilesDirPath));
            if (!Writer.Append(Prefix, PrefixLen))
            {
                FilesDirPathLen = 0;
                return;
            }
            if (!Writer.Append(LoaderPkg, LoaderPkgLen))
            {
                FilesDirPathLen = 0;
                return;
            }
            if (!Writer.Append(Suffix, SuffixLen))
            {
                FilesDirPathLen = 0;
                return;
            }
            Writer.Terminate();
            FilesDirPathLen = Writer.Position;
        }

        bool EnsureFilesDir()
        {
            if (FilesDirPathLen == 0)
                return false;
            long Rc = libc_custom::Svc(__NR_mkdirat, AT_FDCWD, (long)FilesDirPath, 0700);
            if (Rc == 0)
                return true;
            if (Rc == -EEXIST)
                return true;
            errno = (int)-Rc;
            return false;
        }

        int CreateFileSentinel(const char *NameSuffix)
        {
            char Path[192];
            SafeWriter Writer(Path, sizeof(Path));
            if (!Writer.Append(FilesDirPath, FilesDirPathLen))
                return -1;
            if (!Writer.AppendChar('/'))
                return -1;
            if (!Writer.Append(NameSuffix, libc_custom::strlen(NameSuffix)))
                return -1;
            Writer.Terminate();

            long Fd = libc_custom::Svc(__NR_openat, AT_FDCWD, (long)Path, O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC, 0600);
            if (Fd < 0)
            {
                errno = (int)-Fd;
                return -1;
            }
            return (int)Fd;
        }

        bool WriteAll(int Fd, const char *Buf, size_t Len)
        {
            size_t Written = 0;
            while (Written < Len)
            {
                long N = libc_custom::Svc(__NR_write, (long)Fd, (long)(Buf + Written), (long)(Len - Written));
                if (N <= 0)
                    return false;
                Written += (size_t)N;
            }
            return true;
        }

        bool FillFdStatic(int TargetFd, const char *Content, size_t Len)
        {
            if (libc_custom::Svc(__NR_ftruncate, (long)TargetFd, 0) < 0)
                return false;
            if (libc_custom::Svc(__NR_lseek, (long)TargetFd, 0, SEEK_SET) < 0)
                return false;
            if (Len > 0 && !WriteAll(TargetFd, Content, Len))
                return false;
            if (libc_custom::Svc(__NR_lseek, (long)TargetFd, 0, SEEK_SET) < 0)
                return false;
            return true;
        }

        bool RefillFdFromRealProc(int TargetFd, const char *ProcPath, bool MaskSeccomp)
        {
            long SrcFd = libc_custom::Svc(__NR_openat, AT_FDCWD, (long)ProcPath, O_RDONLY | O_CLOEXEC);
            if (SrcFd < 0)
                return false;

            if (libc_custom::Svc(__NR_ftruncate, (long)TargetFd, 0) < 0)
            {
                libc_custom::Svc(__NR_close, SrcFd);
                return false;
            }
            if (libc_custom::Svc(__NR_lseek, (long)TargetFd, 0, SEEK_SET) < 0)
            {
                libc_custom::Svc(__NR_close, SrcFd);
                return false;
            }

            char InBuf[StreamChunk + LoaderPkgLen + 2];
            char OutBuf[StreamOut];
            size_t Carry = 0;
            bool Failed = false;

            for (;;)
            {
                long N = libc_custom::Svc(__NR_read, SrcFd, (long)(InBuf + Carry), (long)StreamChunk);
                if (N < 0)
                {
                    Failed = true;
                    break;
                }
                bool Eof = (N == 0);
                size_t Have = Carry + (size_t)N;

                size_t Consume = Have;
                if (!Eof)
                {
                    size_t Back = 0;
                    while (Back < Have && Back < LoaderPkgLen + 1 && IsPkgNameChar(InBuf[Have - 1 - Back]))
                        ++Back;
                    if (Back >= Have)
                        Back = Have;
                    Consume = Have - Back;
                }

                if (Consume > 0)
                {
                    size_t Written = SubstituteChunk(InBuf, Consume, OutBuf, sizeof(OutBuf));
                    if (MaskSeccomp)
                        MaskSeccompStatus(OutBuf, Written);
                    if (!WriteAll(TargetFd, OutBuf, Written))
                    {
                        Failed = true;
                        break;
                    }
                }

                Carry = Have - Consume;
                if (Carry > 0)
                    libc_custom::memcpy(InBuf, InBuf + Consume, Carry);

                if (Eof)
                {
                    if (Carry > 0)
                    {
                        size_t Written = SubstituteChunk(InBuf, Carry, OutBuf, sizeof(OutBuf));
                        if (MaskSeccomp)
                            MaskSeccompStatus(OutBuf, Written);
                        if (!WriteAll(TargetFd, OutBuf, Written))
                            Failed = true;
                    }
                    break;
                }
            }

            libc_custom::Svc(__NR_close, SrcFd);
            if (Failed)
                return false;
            libc_custom::Svc(__NR_lseek, (long)TargetFd, 0, SEEK_SET);
            return true;
        }

        bool PrimeAllSentinels()
        {
            if (!FillFdStatic(SpoofFdComm, CommContent, CommContentLen))
                return false;
            if (!RefillFdFromRealProc(SpoofFdCmdline, "/proc/self/cmdline", false))
                return false;
            if (!RefillFdFromRealProc(SpoofFdMaps, "/proc/self/maps", false))
                return false;
            if (!RefillFdFromRealProc(SpoofFdStatus, "/proc/self/status", true))
                return false;
            return true;
        }

        void *UpdaterThreadMain(void *)
        {
            pthread_setname_np(pthread_self(), "AsyncGC");
            for (;;)
            {
                usleep(RefreshIntervalUs);
                RefillFdFromRealProc(SpoofFdMaps, "/proc/self/maps", false);
                RefillFdFromRealProc(SpoofFdStatus, "/proc/self/status", true);
            }
            return nullptr;
        }

        long HandleSpoofedOpen(int SentinelFd)
        {
            if (SentinelFd < 0)
                return (long)(int64_t)-EACCES;
            char Path[24];
            libc_custom::memcpy(Path, "/proc/self/fd/", 14);
            size_t Pos = 14;
            Pos += libc_custom::itoa_u32((uint32_t)SentinelFd, Path + Pos);
            Path[Pos] = 0;
            long Fd = libc_custom::Svc(__NR_openat, AT_FDCWD, (long)Path, O_RDONLY | O_CLOEXEC);
            return (Fd < 0) ? (long)(int64_t)-EACCES : Fd;
        }

        long HandleOpen(long Nr, int Dirfd, const char *Path, long A2, long A3)
        {
            char Scratch[512];
            ProcFile Kind = ClassifyOpen(Dirfd, Path, Scratch, sizeof(Scratch));
            switch (Kind)
            {
            case PATH_MAPS:
                return HandleSpoofedOpen(SpoofFdMaps);
            case PATH_COMM:
                return HandleSpoofedOpen(SpoofFdComm);
            case PATH_CMDLINE:
                return HandleSpoofedOpen(SpoofFdCmdline);
            case PATH_STATUS:
                return HandleSpoofedOpen(SpoofFdStatus);
            default:
                return libc_custom::Svc(Nr, Dirfd, (long)Path, A2, A3);
            }
        }

        long HandleReadlinkat(int Dirfd, const char *Path, char *Buf, size_t BufSize)
        {
            char Scratch[1024];
            long N = libc_custom::Svc(__NR_readlinkat, Dirfd, (long)Path, (long)Scratch, (long)(sizeof(Scratch) - 1));
            if (N <= 0)
                return N;
            if ((size_t)N >= sizeof(Scratch))
                N = sizeof(Scratch) - 1;
            Scratch[N] = 0;

            char Spoofed[1024];
            size_t SpoofedLen = SubstituteChunk(Scratch, (size_t)N, Spoofed, sizeof(Spoofed));

            if (!Buf || BufSize == 0)
                return (long)SpoofedLen;
            size_t Copy = SpoofedLen < BufSize ? SpoofedLen : BufSize;
            libc_custom::memcpy(Buf, Spoofed, Copy);
            return (long)Copy;
        }

        void SigsysHandler(int Sig, siginfo_t *Info, void *ContextPtr)
        {
            if (Sig != SIGSYS || !Info || !ContextPtr)
                return;
            if (Info->si_code != SYS_SECCOMP)
                return;
            auto *Regs = ((ucontext_t *)ContextPtr)->uc_mcontext.regs;
            switch (Info->si_syscall)
            {
            case __NR_openat:
            case __NR_openat2:
                Regs[0] = (uint64_t)HandleOpen(Info->si_syscall, (int)Regs[0], (const char *)Regs[1], (long)Regs[2], (long)Regs[3]);
                break;
            case __NR_readlinkat:
                Regs[0] = (uint64_t)HandleReadlinkat((int)Regs[0], (const char *)Regs[1], (char *)Regs[2], (size_t)Regs[3]);
                break;
            default:
                break;
            }
        }

        ENCRYPT_FUNC2 bool BuildFilter(uintptr_t SvcAddr, sock_filter *Out, size_t *OutLen)
        {
            uintptr_t HiAddr = SvcAddr + 8;
            uint32_t LoLo = (uint32_t)(SvcAddr & 0xffffffffu);
            uint32_t LoHi = (uint32_t)(SvcAddr >> 32);
            uint32_t HiLo = (uint32_t)(HiAddr & 0xffffffffu);
            uint32_t HiHi = (uint32_t)(HiAddr >> 32);
            if (LoHi != HiHi)
                return false;

            const uint32_t OffNr = offsetof(struct seccomp_data, nr);
            const uint32_t OffArch = offsetof(struct seccomp_data, arch);
            const uint32_t OffIpLo = offsetof(struct seccomp_data, instruction_pointer);
            const uint32_t OffIpHi = OffIpLo + 4;

            sock_filter Prog[] = {
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, OffArch),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, OffNr),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 3, 0),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat2, 2, 0),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat, 1, 0),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

                // prctl is deliberately NOT trapped. Trapping PR_GET_SECCOMP /
                // PR_GET_NO_NEW_PRIVS via RET_TRAP was the confirmed signal-31 killer:
                // a detection prctl issued on a thread that has SIGSYS masked (or after
                // our handler was displaced) is delivered with the default disposition
                // and terminates the process. Hiding seccomp must be done off the signal
                // path (USER_NOTIF), never with RET_TRAP.
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, OffIpHi),
                BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, LoHi, 0, 4),
                BPF_STMT(BPF_LD | BPF_W | BPF_ABS, OffIpLo),
                BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, LoLo, 0, 2),
                BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, HiLo, 1, 0),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
                BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
            };
            *OutLen = sizeof(Prog) / sizeof(Prog[0]);
            libc_custom::memcpy(Out, Prog, sizeof(Prog));
            return true;
        }

        ENCRYPT_FUNC2 bool ReadOwnCmdline(char *Buf, size_t BufSize)
        {
            long Fd = syscall(SYS_openat, AT_FDCWD, "/proc/self/cmdline", O_RDONLY | O_CLOEXEC, 0);
            if (Fd < 0)
                return false;
            long N = syscall(SYS_read, (int)Fd, Buf, BufSize - 1);
            syscall(SYS_close, (int)Fd);
            if (N <= 0)
                return false;
            for (long Scan = 0; Scan < N; ++Scan)
                if (Buf[Scan] == 0 || Buf[Scan] == ':')
                {
                    Buf[Scan] = 0;
                    break;
                }
            Buf[BufSize - 1] = 0;
            return Buf[0] != 0;
        }
    } // namespace

    ENCRYPT_FUNC2 void Install()
    {
        SDK::Logger::Info("MapsSc", "Install: BEGIN pid=%d tid=%d loaderPkg='%s' loaderLen=%zu", getpid(), gettid(), LoaderPkg, LoaderPkgLen);

        if (Installed)
        {
            SDK::Logger::Warn("MapsSc", "Install: already installed, skipping");
            return;
        }
        Installed = true;

        if (!ReadOwnCmdline(HostPkg, sizeof(HostPkg)))
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'ReadOwnCmdline' errno=%d(%s)", errno, Logger::ErrnoName(errno));
            abort();
        }
        HostPkgLen = libc_custom::strlen(HostPkg);
        SDK::Logger::Info("MapsSc", "Install: step 'ReadOwnCmdline' OK hostPkg='%s' hostLen=%zu", HostPkg, HostPkgLen);

        size_t CommCopy = HostPkgLen < (TaskCommLen - 1) ? HostPkgLen : (TaskCommLen - 1);
        libc_custom::memcpy(CommContent, HostPkg, CommCopy);
        CommContent[CommCopy] = '\n';
        CommContent[CommCopy + 1] = 0;
        CommContentLen = CommCopy + 1;
        SDK::Logger::Info("MapsSc", "Install: step 'build /proc/*/comm buffer' OK len=%zu content='%.*s'", CommContentLen, (int)CommCopy, CommContent);

        BuildFilesDirPath();
        if (FilesDirPathLen == 0)
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'BuildFilesDirPath' (buffer too small)");
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'BuildFilesDirPath' OK path='%s' len=%zu", FilesDirPath, FilesDirPathLen);

        if (!EnsureFilesDir())
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'EnsureFilesDir' path='%s' errno=%d(%s)", FilesDirPath, errno, Logger::ErrnoName(errno));
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'EnsureFilesDir' OK path='%s'", FilesDirPath);

        SpoofFdMaps = CreateFileSentinel("pipeline_cache_v3.bin");
        SpoofFdCmdline = CreateFileSentinel("renderer_config.dat");
        SpoofFdStatus = CreateFileSentinel("shader_ext_state.dat");
        SpoofFdComm = CreateFileSentinel("gles_ext_local.dat");
        if (SpoofFdMaps < 0 || SpoofFdCmdline < 0 || SpoofFdStatus < 0 || SpoofFdComm < 0)
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'CreateFileSentinel'  maps=%d cmdline=%d status=%d comm=%d errno=%d(%s)", SpoofFdMaps, SpoofFdCmdline, SpoofFdStatus, SpoofFdComm, errno, Logger::ErrnoName(errno));
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'CreateFileSentinel x4' OK  maps=%d cmdline=%d status=%d comm=%d dir='%s'", SpoofFdMaps, SpoofFdCmdline, SpoofFdStatus, SpoofFdComm, FilesDirPath);

        if (!PrimeAllSentinels())
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'PrimeAllSentinels'  errno=%d(%s)", errno, Logger::ErrnoName(errno));
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'PrimeAllSentinels' OK");

        pthread_t UpdaterTid;
        if (pthread_create(&UpdaterTid, nullptr, UpdaterThreadMain, nullptr) != 0)
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'pthread_create(updater)'  errno=%d(%s)", errno, Logger::ErrnoName(errno));
            abort();
        }
        pthread_detach(UpdaterTid);
        SDK::Logger::Info("MapsSc", "Install: step 'pthread_create(updater)' OK name='AsyncGC' intervalUs=%u", (unsigned)RefreshIntervalUs);

        uintptr_t SvcAddr = (uintptr_t)&__MundoSvc;
        if (!SvcAddr)
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'resolve __MundoSvc' (linker returned null)");
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'resolve __MundoSvc' OK svcAddr=0x%lx", (unsigned long)SvcAddr);

        stack_t Ss = {};
        Ss.ss_sp = SigStack;
        Ss.ss_size = SigStackSize;
        Ss.ss_flags = 0;
        if (sigaltstack(&Ss, nullptr) != 0)
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'sigaltstack' errno=%d(%s)", errno, Logger::ErrnoName(errno));
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'sigaltstack' OK sp=%p size=%zu", SigStack, (size_t)SigStackSize);

        struct sigaction Sa = {};
        Sa.sa_sigaction = SigsysHandler;
        Sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
        sigemptyset(&Sa.sa_mask);
        if (sigaction(SIGSYS, &Sa, nullptr) != 0)
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'sigaction(SIGSYS)' errno=%d(%s)", errno, Logger::ErrnoName(errno));
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'sigaction(SIGSYS)' OK handler=%p flags=SA_SIGINFO|SA_RESTART|SA_ONSTACK", (void *)SigsysHandler);

        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0)
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'prctl(PR_SET_NO_NEW_PRIVS)' errno=%d(%s)", errno, Logger::ErrnoName(errno));
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'prctl(PR_SET_NO_NEW_PRIVS)' OK");

        sock_filter FilterCode[24];
        size_t FilterLen = 0;
        if (!BuildFilter(SvcAddr, FilterCode, &FilterLen))
        {
            SDK::Logger::Error("MapsSc", "Install: HARD ABORT step 'BuildFilter' (svcAddr=0x%lx straddles 32-bit boundary)", (unsigned long)SvcAddr);
            abort();
        }
        SDK::Logger::Info("MapsSc", "Install: step 'BuildFilter' OK filterLen=%zu bpfBytes=%zu", FilterLen, FilterLen * sizeof(sock_filter));

        sock_fprog Prog = { (unsigned short)FilterLen, FilterCode };

        long Rc = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &Prog);
        if (Rc == 0)
        {
            SDK::Logger::Info("MapsSc", "Install: SUCCESS via SYS_seccomp + TSYNC");
            return;
        }
        SDK::Logger::Warn("MapsSc", "Install: SYS_seccomp+TSYNC failed rc=%ld errno=%d(%s), trying no-TSYNC", Rc, errno, Logger::ErrnoName(errno));

        Rc = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &Prog);
        if (Rc == 0)
        {
            SDK::Logger::Info("MapsSc", "Install: SUCCESS via SYS_seccomp no-TSYNC");
            return;
        }
        SDK::Logger::Warn("MapsSc", "Install: SYS_seccomp no-TSYNC failed rc=%ld errno=%d(%s), trying prctl fallback", Rc, errno, Logger::ErrnoName(errno));

        Rc = prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &Prog);
        if (Rc == 0)
        {
            SDK::Logger::Info("MapsSc", "Install: SUCCESS via prctl(PR_SET_SECCOMP)");
            return;
        }
        SDK::Logger::Error("MapsSc", "Install: HARD ABORT all seccomp arming paths failed, final errno=%d(%s)", errno, Logger::ErrnoName(errno));
        abort();
    }

    size_t SubstitutePackagePath(const char *In, char *Out, size_t OutSize)
    {
        if (!In || !Out || OutSize == 0)
            return 0;
        if (HostPkgLen == 0)
        {
            size_t N = libc_custom::strlen(In);
            if (N >= OutSize)
                N = OutSize - 1;
            libc_custom::memcpy(Out, In, N);
            Out[N] = 0;
            return N;
        }
        return SubstituteChunk(In, libc_custom::strlen(In), Out, OutSize);
    }
} // namespace SDK::MapsSeccomp
