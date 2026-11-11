#pragma once
#include "Structures.h"

namespace Pandora
{
    int InstallSeccompFilter();
    bool RouteSeccompEntry(SyscallContext &Context);
    void RouteSyscallExit(SyscallContext &Context);
    int GetProcessThreads(pid_t ProcessId, pid_t *Threads, int Capacity, pid_t Exclude);
    AttachResult AttachAllThreads(pid_t ProcessId, pid_t Exclude);
    void TracerMainLoop(pid_t Parent);
    void *TraceStarterThread(void *Argument);
    int StartTraceAsync();
}
