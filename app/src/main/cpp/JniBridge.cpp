#include "Integrity.h"
#include <jni.h>

extern "C" JNIEXPORT void JNICALL Java_com_pandora_core_AppFactory_initRedirect(
    JNIEnv *Environment, jclass, jstring Original, jstring Replacement)
{
    if (!Original || !Replacement)
        return;
    const char *OriginalBytes = Environment->GetStringUTFChars(Original, nullptr);
    const char *ReplacementBytes = Environment->GetStringUTFChars(Replacement, nullptr);
    if (OriginalBytes && ReplacementBytes)
        Pandora::InitializeRedirect(OriginalBytes, ReplacementBytes);
    if (OriginalBytes)
        Environment->ReleaseStringUTFChars(Original, OriginalBytes);
    if (ReplacementBytes)
        Environment->ReleaseStringUTFChars(Replacement, ReplacementBytes);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_pandora_core_AppFactory_isRedirectActive(JNIEnv *, jclass)
{
    return Pandora::IsRedirectActive();
}
extern "C" JNIEXPORT jint JNICALL Java_com_pandora_core_AppFactory_getTracerPid(JNIEnv *, jclass)
{
    return Pandora::GetTracerPid();
}
extern "C" __attribute__((visibility("default"))) int ioredirect_init(const char *Original, const char *Replacement)
{
    return Pandora::InitializeRedirect(Original, Replacement);
}
extern "C" __attribute__((visibility("default"))) bool ioredirect_is_active() { return Pandora::IsRedirectActive(); }
extern "C" __attribute__((visibility("default"))) int ioredirect_get_tracer_pid() { return Pandora::GetTracerPid(); }
