# dawn-natives

`dawn-natives` builds static Dawn `webgpu_dawn` packages for projects that want
to link Dawn into their own native library instead of shipping a second Dawn
runtime library.

The first consumer is jWebGPU. jWebGPU should be able to link the packaged Dawn
archive into `jWebGPU64.dll`, `libjWebGPU64.so`, or the matching platform output
so application deployments have one WebGPU native library instead of a jWebGPU
library plus `webgpu_dawn.dll`.

## Configuration

All dependency and toolchain pins live in `dawn-natives.toml`.

Current keys:

- `[package].version`
- `[dawn].repository`, `[dawn].revision`
- `[android].ndkVersion`, `[android].minSdk`, `[android].cmakeVersion`

`dawn-natives-lock.json` mirrors those pins for quick review and release notes.

## Common Tasks

```bash
./gradlew printNativeDepsConfig
./gradlew resolveDawnSource
./gradlew buildLinuxX64
./gradlew buildWindowsX64
./gradlew buildMacosX64
./gradlew buildMacosArm64
./gradlew buildAndroidAll
./gradlew packageAll
./gradlew verifyPackages
./gradlew writeReleaseManifest
```

Build and package tasks only run when the current host/toolchain can support the
target. Android packaging requires `ANDROID_NDK_HOME`, or `ANDROID_HOME` /
`ANDROID_SDK_ROOT` with the configured NDK installed.

## Continuous Integration

The GitHub Actions workflow builds packages on `master` pushes and manual
dispatches. Each platform job uploads its package ZIP, and the final
`dawn-natives-release` artifact contains all package ZIPs plus
`dawn-natives-manifest.json`.

Successful `master` builds validate the package build and upload workflow
artifacts. To publish a GitHub Release, run the workflow manually with
`workflow_dispatch`; the release uses the current package version tag, such as
`v0.1.0`, and updates that version release in place. Release ZIP filenames do
not include version numbers or build dates.

## Updating Pins

```bash
./gradlew updateDawn -PdawnNatives.dawnRevision=<commit>
./gradlew updateToolchainPin -PdawnNatives.androidNdkVersion=27.0.12077973
```

`updateDawn` verifies the requested revision can be fetched before updating the
pin.

## Package Shape

Each ZIP contains one importable CMake target:

- `dawn_natives::webgpu_dawn`

Every package has a direct package root:

```text
lib/
include/
cmake/dawn-natives-targets.cmake
```

The `include/` directory contains Dawn source headers plus generated WebGPU C
headers, including `webgpu/webgpu.h`.

`writeReleaseManifest` writes `build/packages/dawn-natives-manifest.json` with
package checksums and toolchain metadata.
