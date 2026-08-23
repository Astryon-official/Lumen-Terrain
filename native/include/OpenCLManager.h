#pragma once

#include "DeviceRuntime.h"

namespace LTE
{

/*
 * Legacy single-device manager (kept for the native test binary).
 *
 * Production scheduling uses DeviceRuntime, which enumerates and owns
 * every device independently. This thin adapter forwards to device 0
 * of DeviceRuntime so existing callers keep working unchanged.
 *
 * OpenCLDeviceInfo is defined once, in DeviceRuntime.h.
 */
class OpenCLManager
{
public:

    static bool Initialize();
    static void Shutdown();

    static bool IsInitialized();

    static const OpenCLDeviceInfo& GetDeviceInfo();

    static cl_context GetContext();
    static cl_command_queue GetQueue();
    static cl_device_id GetDevice();
};

} // namespace LTE
