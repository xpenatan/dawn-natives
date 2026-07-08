# dawn-natives

`dawn-natives` builds Dawn artifacts for jWebGPU:

- native static `webgpu_dawn` packages for desktop, Android, and iOS
- an Emscripten `emdawnwebgpu` package for Web

Pins live in `dawn-natives.toml`. Dawn is pinned by Chromium branch number:

```toml
[dawn]
repository = "https://dawn.googlesource.com/dawn"
chromiumVersion = "7458"
```

Common tasks:

```bash
./gradlew printNativeDepsConfig
./gradlew packageAll
./gradlew packageWeb
./gradlew writeReleaseManifest
./gradlew updateDawn -PdawnNatives.chromiumVersion=7458
```

Run the workflow manually. Leave `publishRelease` unchecked to validate only.
