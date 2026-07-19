# Bundled APKs

Drop these three APKs here before building. They are sideloaded by the 4AIs setup wizard and
must be present at runtime — the build will succeed without them, but `INSTALL_TERMUX` /
`INSTALL_SHIZUKU` phases will fail at runtime.

| File              | Package                          | Source                                                                                  |
|-------------------|----------------------------------|-----------------------------------------------------------------------------------------|
| `termux.apk`      | `com.termux`                     | GitHub Actions debug build (arm64-v8a) — **must be debuggable** for `run-as` to work    |
| `termux-api.apk`  | `com.termux.api`                 | GitHub Actions debug build from termux/termux-api                                       |
| `shizuku.apk`     | `moe.shizuku.privileged.api`     | https://shizuku.rikka.app/download/ or GitHub releases                                  |

## Why GitHub debug builds (not F-Droid)

4AIs executes commands inside Termux via `run-as com.termux` through Shizuku. The `run-as`
command requires `android:debuggable="true"` in the target app's manifest — this is a
kernel-level check that cannot be bypassed.

- **GitHub Actions builds** have `debuggable=true` (debug signing) — `run-as` works.
- **F-Droid builds** have `debuggable=false` (release signing) — `run-as` is rejected.

The two builds use different signing keys, so you **cannot** install one over the other.
If a user has F-Droid Termux installed, they must uninstall it first.

## Where to get them

1. Go to https://github.com/termux/termux-app/actions
2. Pick the latest successful workflow run
3. Download the `termux-app_arm64-v8a-debug` artifact
4. Extract the APK and rename to `termux.apk`

Same process for `termux-api` from https://github.com/termux/termux-api/actions.

## Pinning

Update `gradle/apk-versions.txt` when bumping these APKs. Keep arm64-v8a splits where available
to keep the AAB under 100 MB.

## Licensing

Termux and termux-api: GPLv3. Shizuku: Apache 2.0. Redistribution as bundled assets is fine
under both licenses provided we ship the LICENSE files alongside (TODO: add to assets/).
