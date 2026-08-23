#pragma once

#include <string>
#include <vector>

namespace LTE
{

struct CPUThreadInfo
{
    unsigned int logicalThread;
};

struct CPUCoreInfo
{
    unsigned int coreId;
    std::vector<CPUThreadInfo> threads;
};

struct CPUInfo
{
    std::string name;

    unsigned int packageId = 0;

    unsigned int physicalCores = 0;
    unsigned int logicalThreads = 0;

    std::vector<CPUCoreInfo> cores;
};

struct GPUInfo
{
    std::string name;

    bool openclSupported = false;

    bool integrated = false;

    unsigned int computeUnits = 0;

    unsigned long long globalMemory = 0;
};

class HardwareManager
{
public:

    bool Initialize();

    const std::vector<CPUInfo>& GetCPUs() const;

    const std::vector<GPUInfo>& GetGPUs() const;

    unsigned int GetTotalPhysicalCores() const;

    unsigned int GetTotalLogicalThreads() const;

private:

    std::vector<CPUInfo> cpus;
    std::vector<GPUInfo> gpus;

};

} // namespace LTE
