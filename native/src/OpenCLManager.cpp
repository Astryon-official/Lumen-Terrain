#include "OpenCLManager.h"
#include "DeviceRuntime.h"

namespace LTE
{

namespace
{

/*
 * Snapshot of device 0 taken at Initialize(); returned by reference
 * to preserve the 2.0 API shape.
 */
OpenCLDeviceInfo g_legacyInfo;

} // namespace


bool OpenCLManager::Initialize()
{
    /*
     * Forward to the multi-device runtime. "Initialized" means at
     * least one usable device exists - identical semantics to 2.0.
     */
    DeviceRuntime::Initialize();

    if (DeviceRuntime::DeviceCount() > 0)
    {
        g_legacyInfo = DeviceRuntime::GetDeviceInfo(0);
        return true;
    }

    return false;
}


void OpenCLManager::Shutdown()
{
    DeviceRuntime::Shutdown();
    g_legacyInfo = OpenCLDeviceInfo{};
}


bool OpenCLManager::IsInitialized()
{
    return DeviceRuntime::IsInitialized();
}


const OpenCLDeviceInfo& OpenCLManager::GetDeviceInfo()
{
    return g_legacyInfo;
}


cl_context OpenCLManager::GetContext()
{
    return nullptr;   // owned per-device by DeviceRuntime
}


cl_command_queue OpenCLManager::GetQueue()
{
    return nullptr;   // owned per-device by DeviceRuntime
}


cl_device_id OpenCLManager::GetDevice()
{
    return nullptr;   // owned per-device by DeviceRuntime
}

} // namespace LTE
