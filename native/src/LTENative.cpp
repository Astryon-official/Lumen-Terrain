#include "LTENative.h"

#include "LTE.h"
#include "DeviceRuntime.h"
#include "CpuCompute.h"
#include "Logger.h"

using namespace LTE;


/*
 * JNI contract (LTE 2.1 heterogeneous scheduling)
 *
 * Java:  double[] gpuProcessTerrain(int x, int z, int deviceIndex, int[] heightmap)
 * Native input : jint[256] column heights, index = z * 16 + x
 *                deviceIndex selects which enumerated GPU runs the work
 * Native output: jdouble[256] heightModification or nullptr on failure.
 *
 * Every failure path returns a safe value; nothing here may crash the JVM.
 */


extern "C" JNIEXPORT void JNICALL
Java_com_astryon_lte_gpu_LTENative_initializeNative(
    JNIEnv* env,
    jclass clazz
)
{
    LTE::Initialize();
}


extern "C" JNIEXPORT void JNICALL
Java_com_astryon_lte_gpu_LTENative_shutdownNative(
    JNIEnv* env,
    jclass clazz
)
{
    LTE::Shutdown();
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_astryon_lte_gpu_LTENative_isGPURuntimeAvailable(
    JNIEnv* env,
    jclass clazz
)
{
    return DeviceRuntime::IsInitialized() ? JNI_TRUE : JNI_FALSE;
}


extern "C" JNIEXPORT jint JNICALL
Java_com_astryon_lte_gpu_LTENative_getDeviceCount(
    JNIEnv* env,
    jclass clazz
)
{
    if (!LTE::IsInitialized())
    {
        return 0;
    }

    return static_cast<jint>(DeviceRuntime::DeviceCount());
}


/*
 * Returns String[n][6]:
 *   [i][0] name
 *   [i][1] vendor
 *   [i][2] platformName
 *   [i][3] driverVersion
 *   [i][4] compute units (string)
 *   [i][5] global memory MB (string)
 *
 * Returns an empty array when OpenCL is unavailable - never null.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_astryon_lte_gpu_LTENative_getDeviceInfo(
    JNIEnv* env,
    jclass clazz
)
{
    jclass stringClass = env->FindClass("java/lang/String");

    if (stringClass == nullptr)
    {
        if (env->ExceptionCheck()) { env->ExceptionClear(); }
        return nullptr;
    }


    /*
     * Outer element type must be String[] ("[Ljava/lang/String;"),
     * not String - this is String[count][6].
     */
    jclass stringArrayClass =
        env->FindClass("[Ljava/lang/String;");

    if (stringArrayClass == nullptr)
    {
        if (env->ExceptionCheck()) { env->ExceptionClear(); }
        return nullptr;
    }

    const int count =
        LTE::IsInitialized() ? DeviceRuntime::DeviceCount() : 0;

    jobjectArray outer =
        env->NewObjectArray(
            static_cast<jsize>(count), stringArrayClass, nullptr);

    if (outer == nullptr)
    {
        return nullptr;
    }


    for (int i = 0; i < count; ++i)
    {
        jobjectArray row =
            env->NewObjectArray(6, stringClass, nullptr);

        if (row == nullptr)
        {
            return outer;   // best effort; caller tolerates short arrays
        }

        const OpenCLDeviceInfo& info = DeviceRuntime::GetDeviceInfo(i);

        auto set = [&](int slot, const std::string& value)
        {
            jstring s = env->NewStringUTF(value.c_str());
            if (s != nullptr)
            {
                env->SetObjectArrayElement(row, slot, s);
                env->DeleteLocalRef(s);
            }
        };

        set(0, info.name);
        set(1, info.vendor);
        set(2, info.platformName);
        set(3, info.driverVersion);
        set(4, std::to_string(info.computeUnits));
        set(5,
            std::to_string(
                static_cast<long long>(info.globalMemory / (1024ULL * 1024ULL))));

        env->SetObjectArrayElement(outer, i, row);
        env->DeleteLocalRef(row);
    }

    return outer;
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_astryon_lte_gpu_LTENative_isDeviceHealthy(
    JNIEnv* env,
    jclass clazz,
    jint index
)
{
    if (!LTE::IsInitialized())
    {
        return JNI_FALSE;
    }

    return DeviceRuntime::IsHealthy(static_cast<int>(index))
        ? JNI_TRUE : JNI_FALSE;
}


extern "C" JNIEXPORT void JNICALL
Java_com_astryon_lte_gpu_LTENative_setDeviceEnabled(
    JNIEnv* env,
    jclass clazz,
    jint index,
    jboolean enabled
)
{
    DeviceRuntime::SetEnabled(static_cast<int>(index), enabled != JNI_FALSE);
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_astryon_lte_gpu_LTENative_getGPUDeviceName(
    JNIEnv* env,
    jclass clazz
)
{
    /*
     * Legacy single-device accessor: name of device 0.
     * Kept for log lines and older tooling.
     */
    if (!DeviceRuntime::IsInitialized())
    {
        return env->NewStringUTF("");
    }

    const OpenCLDeviceInfo& info = DeviceRuntime::GetDeviceInfo(0);

    return env->NewStringUTF(info.name.c_str());
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_astryon_lte_gpu_LTENative_getNativeVersion(
    JNIEnv* env,
    jclass clazz
)
{
    return env->NewStringUTF(LTE::GetVersion());
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_astryon_lte_gpu_LTENative_runGPUBenchmark(
    JNIEnv* env,
    jclass clazz
)
{
    /*
     * Aggregated benchmark kept for the hardware profile:
     * sum of per-device scores of all healthy devices. The scheduler's
     * real weighting uses per-device scores via benchmarkDevice().
     */
    if (!LTE::IsInitialized())
    {
        return 0;
    }

    long total = 0;

    for (int i = 0; i < DeviceRuntime::DeviceCount(); ++i)
    {
        total += DeviceRuntime::RunBenchmark(i);
    }

    return static_cast<jlong>(total);
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_astryon_lte_gpu_LTENative_benchmarkDevice(
    JNIEnv* env,
    jclass clazz,
    jint index
)
{
    if (!LTE::IsInitialized())
    {
        return 0;
    }

    return static_cast<jlong>(DeviceRuntime::RunBenchmark(static_cast<int>(index)));
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_astryon_lte_gpu_LTENative_runCPUBenchmark(
    JNIEnv* env,
    jclass clazz
)
{
    /*
     * The CPU benchmark runs the identical terrain algorithm on the
     * host so scores are directly comparable with GPU benchmarks.
     */
    return static_cast<jlong>(LTE::CpuCompute::RunBenchmark());
}


extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_astryon_lte_gpu_LTENative_gpuProcessTerrain(
    JNIEnv* env,
    jclass clazz,
    jint x,
    jint z,
    jint deviceIndex,
    jintArray javaHeightmap
)
{
    /*
     * Validate every argument before touching native memory.
     */
    if (env == nullptr || javaHeightmap == nullptr)
    {
        return nullptr;
    }


    const jsize length =
        env->GetArrayLength(javaHeightmap);

    if (length != 256)
    {
        return nullptr;
    }


    jint heightmap[256];

    env->GetIntArrayRegion(javaHeightmap, 0, 256, heightmap);

    if (env->ExceptionCheck())
    {
        env->ExceptionClear();
        return nullptr;
    }


    jdouble result[256];

    const bool success =
        DeviceRuntime::ProcessTerrain(
            static_cast<int>(deviceIndex), x, z, heightmap, result);

    if (!success)
    {
        /*
         * Safe failure: caller falls back to another device or CPU.
         */
        return nullptr;
    }


    jdoubleArray javaResult =
        env->NewDoubleArray(256);

    if (javaResult == nullptr)
    {
        return nullptr;
    }


    env->SetDoubleArrayRegion(javaResult, 0, 256, result);

    if (env->ExceptionCheck())
    {
        env->ExceptionClear();
        return nullptr;
    }

    return javaResult;
}
