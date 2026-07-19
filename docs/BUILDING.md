# Building & testing

## Requirements

- Android Studio (Ladybug or newer) with SDK Platform 35
- JDK 17 (bundled with Android Studio)
- An **arm64-v8a** device, Android 8.0+ (API 26). The bundled Node.js binary
  is arm64-only — x86_64 emulators will not work.

## Build

```bash
git clone https://github.com/8crsk/openclaw-android.git
cd openclaw-android
./scripts/fetch-node-libs.sh   # downloads the prebuilt Node.js .so files (~33MB, one-time)
cp local.properties.example local.properties   # set sdk.dir to your SDK path
./gradlew assembleDebug
```

Or open the project in Android Studio and press Run. **No API keys, no
`.env`, no service accounts** — the build is completely self-contained. Users
supply their own model API key inside the app (Settings → AI provider).

> The on-device Node.js native libraries (`app/src/main/jniLibs/`) are not
> tracked in git — they're hosted as a GitHub Release asset and fetched by
> `scripts/fetch-node-libs.sh` (checksum-verified). The build fails with a
> pointer to that script if they're missing.

## Run on a device

1. Install the debug APK (`app/build/outputs/apk/debug/`).
2. First launch walks through: privacy consent → enable the "4AIs UI
   Automation" accessibility service → automatic node bootstrap. The bootstrap
   needs network (it runs `npm install openclaw` on the phone) and takes ~3
   minutes / ~200MB.
3. Enter a provider key in Settings. NVIDIA keys are free at
   [build.nvidia.com](https://build.nvidia.com).

### Useful logs

```bash
adb logcat -s NodeSetup NodeProcess ChatViewModel WsRpcClient ShizukuBridge
```

The gateway's stderr also rolls into `filesDir/logs/gateway.err` on-device.

## Tests

```bash
./gradlew testDebugUnitTest   # JVM unit tests
./gradlew lintDebug           # Android lint
```

CI runs both plus `assembleDebug` on every PR (`.github/workflows/ci.yml`).

## Gotchas

- **Setup runs once per version.** `node-setup.js` is gated by a
  `.4ais_setup_done_v<N>` marker in `filesDir/home/.openclaw/`. If you change
  node-setup.js, bump `SETUP_VERSION` there **and** the marker name in
  `NodeSetup.kt` (they must match), or your changes won't reach existing
  installs.
- **Provider config is derived.** `NodeProcess.refreshMcpConfig` rewrites
  `models.providers` in openclaw's config.json from the device keystore on
  every gateway start — editing config.json by hand won't stick.
- **Benchmark variant** (`./gradlew assembleBenchmark`) is production-like but
  debug-signed, used by the `:benchmark` macrobenchmark module.
