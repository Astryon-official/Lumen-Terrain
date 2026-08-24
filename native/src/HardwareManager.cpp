#include "HardwareManager.h"
#include "DeviceRuntime.h"
#include "Logger.h"

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX            // keep std::min/std::max usable
#endif
#include <windows.h>     // registry CPU brand string (ARM64)
#endif

#include <algorithm>
#include <fstream>       // Linux /proc parsing
#include <map>           // Linux topology grouping
#include <string>
#include <thread>
#include <vector>

/*
 * Hardware detection (LTE 2.0.0)
 *
 * Platform notes:
 *   - Windows: CPU identity via cpuid (brand string); topology via
 *     std::thread::hardware_concurrency(). No /proc or /sys usage.
 *   - Linux: /proc/cpuinfo first, sysfs fallback, then the portable
 *     hardware_concurrency fallback.
 *
 * GPU data comes from OpenCLManager after LTE::Initialize(); this class
 * never initializes OpenCL itself.
 */

namespace LTE
{

namespace
{

#if defined(_WIN32)

/*
 * Shared normalization: trim outer whitespace, collapse inner runs.
 */
std::string NormalizeCPUName(std::string value)
{
    const auto notSpace =
        [](unsigned char c)
        {
            return c != ' ';
        };

    value.erase(value.begin(),
        std::find_if(value.begin(), value.end(), notSpace));

    value.erase(
        std::find_if(value.rbegin(), value.rend(), notSpace).base(),
        value.end());

    std::string collapsed;

    bool previousSpace = false;

    for (char c : value)
    {
        if (c == ' ')
        {
            if (previousSpace)
            {
                continue;
            }

            previousSpace = true;
        }
        else
        {
            previousSpace = false;
        }

        collapsed.push_back(c);
    }

    return collapsed.empty() ? "Unknown CPU" : collapsed;
}

/*
 * x86/x64: brand string via cpuid.
 * ARM64 has no cpuid instruction - handled below.
 */
#if defined(_M_X64) || defined(_M_IX86) || \
    defined(__x86_64__) || defined(__i386__)

void CpuId(
    int function,
    int subLeaf,
    int regs[4]
)
{
    __cpuid(reinterpret_cast<int*>(regs), function);
}


std::string DetectCPUNameWindows()
{
    int regs[4];

    /*
     * Brand string: 3 leaves of 16 bytes = 48 characters.
     */
    char brand[49] = {};

    for (int i = 0; i < 3; ++i)
    {
        CpuId(0x80000002 + i, 0, regs);

        for (int r = 0; r < 4; ++r)
        {
            const int base = i * 16 + r * 4;

            brand[base + 0] = static_cast<char>((regs[r] >> 0) & 0xFF);
            brand[base + 1] = static_cast<char>((regs[r] >> 8) & 0xFF);
            brand[base + 2] = static_cast<char>((regs[r] >> 16) & 0xFF);
            brand[base + 3] = static_cast<char>((regs[r] >> 24) & 0xFF);
        }
    }

    brand[48] = '\0';

    return NormalizeCPUName(std::string(brand));
}

#else   // Windows ARM64: read the brand string from the registry.

std::string DetectCPUNameWindows()
{
    HKEY key;

    if (RegOpenKeyExA(
            HKEY_LOCAL_MACHINE,
            "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
            0,
            KEY_READ,
            &key) != ERROR_SUCCESS)
    {
        return "Unknown CPU";
    }

    char brand[256] = {};

    DWORD size = static_cast<DWORD>(sizeof(brand) - 1);

    DWORD type = REG_SZ;

    const LSTATUS result = RegQueryValueExA(
        key,
        "ProcessorNameString",
        nullptr,
        &type,
        reinterpret_cast<LPBYTE>(brand),
        &size);

    RegCloseKey(key);

    if (result != ERROR_SUCCESS)
    {
        return "Unknown CPU";
    }

    return NormalizeCPUName(std::string(brand));
}

#endif  // x86/x64 vs ARM64

#else   // !defined(_WIN32)

bool ReadTextFile(const std::string& path, std::string& value)
{
    std::ifstream file(path);

    if (!file.is_open())
    {
        return false;
    }

    std::getline(file, value);

    return !value.empty();
}

std::string DetectCPUNameLinux()
{
    std::string value;

    /*
     * DMI product name is a reasonable machine identifier when
     * cpuinfo is unavailable.
     */
    if (ReadTextFile(
            "/sys/devices/virtual/dmi/id/product_name",
            value))
    {
        if (!value.empty() &&
            value != "To Be Filled By O.E.M.")
        {
            return value;
        }
    }


    std::ifstream file("/proc/cpuinfo");

    if (file.is_open())
    {
        std::string line;

        while (std::getline(file, line))
        {
            const auto separator = line.find(':');

            if (separator == std::string::npos)
            {
                continue;
            }

            if (line.substr(0, separator) != "model name")
            {
                continue;
            }

            value = line.substr(separator + 1);

            while (!value.empty() && value.front() == ' ')
            {
                value.erase(value.begin());
            }

            if (!value.empty())
            {
                return value;
            }
        }
    }


    return "Unknown CPU";
}

#endif


std::string DetectCPUName()
{
#if defined(_WIN32)
    return DetectCPUNameWindows();
#else
    return DetectCPUNameLinux();
#endif
}


void BuildFallbackTopology(
    unsigned int threadCount,
    std::vector<CPUInfo>& cpus
)
{
    CPUInfo cpu;

    cpu.name = DetectCPUName();
    cpu.packageId = 0;
    cpu.logicalThreads = threadCount;
    cpu.physicalCores = threadCount;   // unknown split: assume 1:1

    for (unsigned int i = 0; i < threadCount; ++i)
    {
        CPUCoreInfo core;
        core.coreId = i;

        CPUThreadInfo thread;
        thread.logicalThread = i;

        core.threads.push_back(thread);
        cpu.cores.push_back(core);
    }

    cpus.push_back(cpu);
}

} // namespace


bool HardwareManager::Initialize()
{
    Log("Detecting hardware");

    cpus.clear();
    gpus.clear();


#if !defined(_WIN32)

    /*
     * Linux: prefer real /proc/cpuinfo topology.
     */
    {
        std::ifstream file("/proc/cpuinfo");

        if (file.is_open())
        {
            struct Record
            {
                unsigned int processor = 0;
                unsigned int physicalPackage = 0;
                unsigned int coreId = 0;
                std::string modelName;
            };

            std::vector<Record> records;

            Record current;
            bool hasProcessor = false;

            std::string line;

            auto commit =
                [&]()
                {
                    if (hasProcessor)
                    {
                        records.push_back(current);
                    }

                    current = Record{};
                    hasProcessor = false;
                };


            while (std::getline(file, line))
            {
                if (line.empty())
                {
                    commit();
                    continue;
                }

                const auto separator = line.find(':');

                if (separator == std::string::npos)
                {
                    continue;
                }

                const std::string key =
                    line.substr(0, separator);

                std::string value =
                    line.substr(separator + 1);

                while (!value.empty() && value.front() == ' ')
                {
                    value.erase(value.begin());
                }


                try
                {
                    if (key == "processor")
                    {
                        current.processor =
                            static_cast<unsigned int>(std::stoul(value));
                        hasProcessor = true;
                    }
                    else if (key == "physical id")
                    {
                        current.physicalPackage =
                            static_cast<unsigned int>(std::stoul(value));
                    }
                    else if (key == "core id")
                    {
                        current.coreId =
                            static_cast<unsigned int>(std::stoul(value));
                    }
                    else if (key == "model name")
                    {
                        current.modelName = value;
                    }
                }
                catch (...)
                {
                    // malformed numeric field: ignore line safely
                }
            }

            commit();


            std::map<unsigned int,
                std::map<unsigned int, std::vector<unsigned int>>> packages;

            for (const auto& record : records)
            {
                packages[record.physicalPackage][record.coreId]
                    .push_back(record.processor);

                if (cpus.empty() ||
                    cpus.back().packageId != record.physicalPackage ||
                    cpus.back().name.empty())
                {
                    if (cpus.empty() ||
                        cpus.back().packageId != record.physicalPackage)
                    {
                        CPUInfo info;
                        info.packageId = record.physicalPackage;
                        info.name = record.modelName;
                        cpus.push_back(info);
                    }
                    else
                    {
                        cpus.back().name = record.modelName;
                    }
                }
            }


            for (auto& [packageId, cores] : packages)
            {
                CPUInfo* target = nullptr;

                for (auto& cpu : cpus)
                {
                    if (cpu.packageId == packageId)
                    {
                        target = &cpu;
                        break;
                    }
                }

                if (!target)
                {
                    CPUInfo info;
                    info.packageId = packageId;
                    cpus.push_back(info);
                    target = &cpus.back();
                }


                for (auto& [coreId, threads] : cores)
                {
                    CPUCoreInfo core;
                    core.coreId = coreId;

                    for (unsigned int t : threads)
                    {
                        CPUThreadInfo thread;
                        thread.logicalThread = t;
                        core.threads.push_back(thread);
                    }

                    target->cores.push_back(core);
                }


                target->physicalCores =
                    static_cast<unsigned int>(target->cores.size());

                target->logicalThreads = 0;

                for (const auto& core : target->cores)
                {
                    target->logicalThreads +=
                        static_cast<unsigned int>(core.threads.size());
                }
            }
        }
    }

#endif   // !_WIN32


    if (cpus.empty())
    {
        /*
         * Portable fallback (always used on Windows):
         * logical thread count from the standard library and the
         * cpuid brand string where available.
         */
        const unsigned int threads =
            std::max(
                1u,
                std::thread::hardware_concurrency());

        BuildFallbackTopology(threads, cpus);

        Log("CPU topology: fallback mode");
    }


#if defined(_WIN32)

    /*
     * On Windows, upgrade the name in the fallback topology using cpuid.
     */
    if (!cpus.empty() &&
        (cpus.front().name.empty() || cpus.front().name == "Unknown CPU"))
    {
        cpus.front().name = DetectCPUNameWindows();
    }

#endif


    /*
     * GPUs: report every device DeviceRuntime enumerated.
     * This class never initializes OpenCL itself - LTE::Initialize()
     * owns that lifecycle.
     */
    if (DeviceRuntime::IsInitialized())
    {
        const int count = DeviceRuntime::DeviceCount();

        for (int i = 0; i < count; ++i)
        {
            const OpenCLDeviceInfo& device = DeviceRuntime::GetDeviceInfo(i);

            if (device.name.empty())
            {
                continue;
            }

            GPUInfo gpu;
            gpu.name = device.name;
            gpu.openclSupported = true;
            gpu.computeUnits = device.computeUnits;
            gpu.globalMemory = device.globalMemory;
            gpu.integrated = device.integrated;

            gpus.push_back(gpu);

            const std::string message =
                "GPU detected (" + std::to_string(i) + "): "
                + device.name + ", "
                + std::to_string(device.computeUnits) + " CUs";

            Log(message.c_str());
        }
    }

    if (gpus.empty())
    {
        Log("No OpenCL GPU information available");
    }


    return !cpus.empty();
}


const std::vector<CPUInfo>&
HardwareManager::GetCPUs() const
{
    return cpus;
}


const std::vector<GPUInfo>&
HardwareManager::GetGPUs() const
{
    return gpus;
}


unsigned int
HardwareManager::GetTotalPhysicalCores() const
{
    unsigned int total = 0;

    for (const auto& cpu : cpus)
    {
        total += cpu.physicalCores;
    }

    return total;
}


unsigned int
HardwareManager::GetTotalLogicalThreads() const
{
    unsigned int total = 0;

    for (const auto& cpu : cpus)
    {
        total += cpu.logicalThreads;
    }

    return total;
}

} // namespace LTE
