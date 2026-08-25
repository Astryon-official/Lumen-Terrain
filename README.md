# Lumen Terrain

**Cross-platform GPU/CPU terrain processing for Minecraft (Fabric).**

Lumen Terrain (LTE) is a server-side Fabric mod that offloads terrain analysis and heightmap processing from the world-generation hot path to a native compute core, with automatic OpenCL GPU acceleration when available and a pure-Java CPU fallback everywhere else.

## Highlights

* 🎛️ **Multi-GPU scheduling** — every usable OpenCL device across every platform is enumerated, benchmarked individually, and scheduled with benchmark-weighted rotation plus automatic failover. Unhealthy devices self-heal via cooldown probes; a device dying never interrupts generation.
* ⚡ **Hybrid CPU/GPU routing** — in `auto` mode LTE routes work by queue pressure: near-empty queues take the low-latency Java CPU path, backlogs saturate every GPU. Explicit `backend=gpu|cpu` pins the choice.
* 🧮 **Single-source-of-truth algorithm** — one header (`native/include/TerrainAlgorithm.h`) implements the terrain pipeline for both the CPU and GPU paths; native and JVM tests prove bit-exact equivalence across Intel iGPUs and NVIDIA discrete GPUs.
* 🌍 **Cross-platform by design** — Windows x86-64/ARM64, Linux x86-64/ARM64, macOS x86-64/ARM64. The correct binary is bundled inside the mod JAR, extracted with CRC validation at startup, and loaded by absolute path. No manual DLL/SO/dylib installation, ever.
* 🛟 **Never crashes your server** — missing binaries, unsupported platforms, OpenCL failures, and JNI errors all degrade gracefully to Java CPU processing / vanilla generation.

## Supported platforms

| Platform | Native backend | Status |
|---|---|---|
| Windows x86-64 | ✅ bundled | built & tested (Intel UHD + RTX 2050) |
| Windows ARM64 | ✅ bundled | CI build (GitHub Actions) |
| Linux x86-64 | ✅ bundled | CI build (GitHub Actions) |
| Linux ARM64 | ✅ bundled | CI build (GitHub Actions) |
| macOS ARM64 (Apple Silicon) | ✅ bundled | CI build (GitHub Actions) |
| macOS x86-64 (Intel) | ✅ bundled | CI build (GitHub Actions) |

On any host without an OpenCL runtime (very common on dedicated servers), LTE automatically uses its optimized CPU path — the mod remains fully functional.

## How native loading works

```
detect OS + arch  →  select bundled binary  →  extract to lte-native/<platform>/<version>/
→  load with absolute path  →  validate initialization  →  ready
         ↘ any failure → disable native backend → pure-Java CPU pipeline
```

Extracted libraries live in `lte-native/` under the server's working directory (per-user, versioned, never overwritten while in use). A deliberate in-process restart (`shutdown()` → `load()`) re-initializes the resident library without reloading it — supported and covered by tests.

## Building

### Mod JAR

```bash
./gradlew build          # output: build/libs/lumen-terrain-engine-<version>.jar
```

The locally-built Windows DLL is bundled automatically when present. Binaries for other platforms are staged from `native/artifacts/<platform>/` — CI populates this directory on every push touching `native/**` (see `.github/workflows/native-build.yml`), then assembles the full cross-platform JAR.

### Native core (development)

```bash
cmake -S native -B native/build
cmake --build native/build --config Release
native/build/Release/LTE_Test.exe   # CPU/GPU equivalence + resource regressions
```

Requirements: CMake ≥ 3.20, a JDK (JNI headers), and the vendored Khronos OpenCL headers (no SDK needed). On Linux add an ICD loader (`ocl-icd-opencl-dev`) if you want a linkable `libOpenCL`.

### Verifying the full chain without Minecraft

```bat
gradlew build
tools\smoketest\run_smoke.bat   build\libs\lumen-terrain-engine-2.0.0.jar
tools\stresstest\run_stress.bat build\libs\lumen-terrain-engine-2.0.0.jar
```

* **Smoke test** — platform detection, extraction, load, initialization, per-device JNI processing, determinism across devices, invalid-input safety, shutdown.
* **Stress test** — drives the real production pipeline (queue → worker pool → hybrid router → scheduler → JNI): 20,000-chunk burst through a 4-thread worker pool, concurrent scheduler sweep with output verification against the shipped CPU oracle, per-device failover/recovery, queue-capacity backpressure, cache eviction bounds, repeated shutdown/re-init churn, and memory-growth checks. Exits non-zero on any failure.

(Use `:` instead of `;` on Linux/macOS if invoking the classes directly.)

## Configuration

`config/lte.properties` (created with defaults on first run):

```properties
backend=auto            # auto (benchmark decides, hybrid pressure routing) | gpu | cpu
gpuDevice=auto          # auto (rotate by weight) | <device index>
allowSlowDevices=true   # let slower GPUs participate
workerThreads=auto      # auto = min(4, cores/4) | <count> (max 8)
maxQueueSize=512        # hard cap on queued chunks (also sizes cache bounds)
verbose=false           # pipeline logging (default log stays lightweight)
```

### Multi-GPU behavior

* All non-CPU OpenCL devices are enumerated and benchmarked at startup.
* Dispatch rotates across healthy devices weighted by their measured benchmark scores; the fastest device receives the largest share (set `allowSlowDevices=false` to exclude weak devices entirely).
* A device that fails dispatch is benched for 5 s, then probed once; if it still fails it is benched again. Generation continues on remaining devices or CPU without any user action.
* When every device is down, chunks flow through the identical CPU reference path — outputs stay bit-exact either way.
* `gpuDevice=1` pins all GPU work to a specific device.

## Architecture

```
com.astryon.lte
├── platform/    OS/arch detection, JAR extraction, loader (cross-platform core)
├── gpu/         LTENative JNI bridge + DeviceScheduler (multi-device rotation)
├── benchmark/   identical-workload CPU vs GPU scoring, profile caching
├── chunk/       lock-free queue, bounded caches, worker pool
├── terrain/     heightmap extraction, analysis, CPU reference pipeline
├── generation/  backend routing (hybrid pressure routing, GPU → CPU fallback)
├── monitor/     lock-free statistics, telemetry, optional performance log
├── events/      Fabric lifecycle wiring (clean server shutdown)
└── mixin/       hook into vanilla world-gen (buildSurface TAIL)
```

Native side:

```
native/
├── include/TerrainAlgorithm.h   single source of truth (CPU impl + OpenCL kernel)
├── src/CpuCompute.cpp           host reference path + CPU benchmark
├── src/DeviceRuntime.cpp        per-device state machines, health, cooldowns
├── src/OpenCLManager.cpp        multi-platform enumeration
└── src/LTENative.cpp            JNI boundary (every call failure-safe)
```

## Vision

Minecraft worlds are constantly generated as players explore, creating performance spikes. LTE shifts terrain processing from reactive to proactive — analyzing terrain before players reach it, on whatever hardware the server has.

Built with performance, scalability, and innovation in mind. MIT licensed.

