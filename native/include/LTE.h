#pragma once

/*
 * LTE native core lifecycle.
 *
 * Ownership rule (fixed in 2.0.0):
 *   LTE::Initialize() owns exactly one OpenCL initialization.
 *   Hardware detection utilities never initialize OpenCL themselves.
 */

namespace LTE
{

bool Initialize();

void Shutdown();

bool IsInitialized();

const char* GetVersion();

long RunGPUBenchmark();

}
