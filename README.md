# Lumen Terrain Engine

**Cross-platform GPU/CPU terrain processing for Minecraft (Fabric).**

Lumen Terrain Engine (LTE) is a server-side Fabric mod that offloads terrain analysis and heightmap processing from the world-generation hot path to a native compute core, with automatic OpenCL GPU acceleration when available and a pure-Java CPU fallback everywhere else.

## Highlights

* 🎛️ **Multi-GPU scheduling** — every usable OpenCL device across every platform is enumerated, benchmarked individually, and scheduled with weight-descending failover. Unhealthy devices self-heal via cooldown probes; a device dying never interrupts generation.
* ⚡ **Automatic backend selection** — LTE benchmarks the CPU reference implementation and the OpenCL kernels on *identical workloads*, picks the winner per machine, and caches the decision (`config/lte/hardware.profile`).
* 🧮 **Single-source-of-truth algorithm** — one header (`native/include/TerrainAlgorithm.h`) implements the terrain pipeline for both CPU and GPU; a native test proves bit-exact equivalence.
* 🌍 **Cross-platform by design** — Windows x86-64, Linux x86-64, Linux ARM64, macOS x86-64/ARM64. The correct binary is bundled inside the mod JAR and extracted automatically at startup. No manual DLL/SO/dylib installation, ever.
* 🛟 **Never crashes your server** — missing binaries, unsupported platforms, OpenCL failures, and JNI errors all degrade gracefully to Java CPU processing / vanilla generation.

## Supported platforms

| Platform | Native backend | Status |
|---|---|---|
| Windows x86-64 | ✅ bundled | built & tested |
| Linux x86-64 | ✅ bundled | CI build |
| Linux ARM64 | ✅ bundled | CI build |
| macOS ARM64 (Apple Silicon) | ✅ bundled | CI build |
| macOS x86-64 (Intel) | ✅ bundled | CI build |

On any host without an OpenCL runtime (very common on dedicated servers), LTE automatically uses its optimized CPU path — the mod remains fully functional.

## How native loading works

```
detect OS + arch  →  select bundled binary  →  extract to lte-native/<platform>/<version>/
→  load with absolute path  →  validate initialization  →  ready
         ↘ any failure → disable native backend → pure-Java CPU pipeline
```

Extracted libraries live in `lte-native/` under the server's working directory (per-user, versioned, never overwritten while in use).

## Building

### Mod JAR

```bash
./gradlew build          # output: build/libs/*.jar
```

The locally-built Windows DLL is bundled automatically when present. Binaries for other platforms are staged from `native/artifacts/<platform>/` — CI populates this directory (see `.github/workflows/native-build.yml`), which assembles the full cross-platform JAR on every push touching `native/**`.

### Native core (development)

```bash
cmake -S native -B native/build
cmake --build native/build --config Release
native/build/Release/LTE_Test.exe   # CPU/GPU equivalence + benchmarks
```

Requirements: CMake ≥ 3.20, a JDK (JNI headers), and either an OpenCL SDK or the vendored Khronos headers (Linux: `ocl-icd-opencl-dev opencl-headers`).

### Verifying the full chain without Minecraft

```bash
javac -d build/smoketest tools/smoketest/LTESmokeTest.java src/main/java/com/astryon/lte/gpu/LTENative.java src/main/java/com/astryon/lte/platform/*.java
java -cp "build/smoketest;build/libs/<mod>.jar" --enable-native-access=ALL-UNNAMED LTESmokeTest
```

(Use `:` instead of `;` on Linux/macOS.)

## Configuration

`config/lte.properties` (created with defaults on first run):

```properties
backend=auto            # auto (benchmark decides) | gpu | cpu
gpuDevice=auto          # auto (rotate by weight) | <device index>
allowSlowDevices=true   # let slower GPUs take overflow work
maxQueueSize=512        # hard cap on queued chunks
chunksPerCycle=4        # chunks processed per worker cycle
verbose=true            # pipeline logging
```

### Multi-GPU behavior

* All non-CPU OpenCL devices are enumerated and benchmarked at startup.
* GPU dispatch goes heaviest-benchmark-first: the fastest device is primary, slower devices only receive overflow or failover work (set `allowSlowDevices=false` to exclude them entirely).
* A device that fails dispatch is benched for 5 s, then probed once; if it still fails it is benched again. Generation continues on remaining devices or CPU without any user action.
* `gpuDevice=1` pins all GPU work to a specific device.

## Architecture

```
com.astryon.lte
├── platform/    OS/arch detection, JAR extraction, loader (cross-platform core)
├── gpu/         LTENative JNI bridge + safe failure contract
├── benchmark/   identical-workload CPU vs GPU scoring, profile caching
├── chunk/       queue, caches, worker thread, prediction
├── terrain/     heightmap extraction, analysis, CPU pipeline
├── generation/  backend routing (GPU → CPU fallback)
└── mixin/       hook into vanilla world-gen (buildSurface TAIL)
```

## Vision

Minecraft worlds are constantly generated as players explore, creating performance spikes. LTE shifts terrain processing from reactive to proactive — analyzing terrain before players reach it, on whatever hardware the server has.

Built with performance, scalability, and innovation in mind. MIT licensed.
