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
- `[ios].deploymentTarget`

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
./gradlew buildIosAll
./gradlew packageAll
./gradlew verifyPackages
./gradlew writeReleaseManifest
```

Build and package tasks only run when the current host/toolchain can support the
target. Android packaging requires `ANDROID_NDK_HOME`, or `ANDROID_HOME` /
`ANDROID_SDK_ROOT` with the configured NDK installed. iOS packaging requires a
macOS host with Xcode's iOS SDKs installed.

## Continuous Integration

The GitHub Actions workflow builds packages on `master` pushes and manual
dispatches. Each platform or ABI job stages a package directory and uploads it
as the GitHub artifact payload. Android builds are split per ABI so the four
architectures run in parallel instead of serially in one job. The final
`dawn-natives-release` artifact contains all package directories plus
`dawn-natives-manifest.json`.

Successful `master` builds validate the package build and upload workflow
artifacts. To publish a GitHub Release, run the workflow manually with
`workflow_dispatch`; the release uses the current package version tag, such as
`v0.1.0`, and updates that version release in place. CI artifacts are zipped
only by GitHub Actions; the Gradle package tasks do not create nested ZIP files.

## Updating Pins

```bash
./gradlew updateDawn -PdawnNatives.dawnRevision=<commit>
./gradlew updateToolchainPin -PdawnNatives.androidNdkVersion=27.0.12077973
./gradlew updateToolchainPin -PdawnNatives.iosDeploymentTarget=13.0
```

`updateDawn` verifies the requested revision can be fetched before updating the
pin.

## Package Shape

Each package directory contains one importable CMake target:

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
package content checksums and toolchain metadata.
