# Contributing to OpenClaw Android (4AIs)

Thanks for your interest! This project turns an Android phone into a fully
autonomous AI agent — it sees the screen, taps, types, and gets things done,
with the OpenClaw gateway running **on the phone itself**. Contributions of all
sizes are welcome, from typo fixes to new provider integrations.

## Dev setup

1. Install Android Studio (Ladybug or newer) with an Android SDK (API 35).
2. Clone the repo, run `./scripts/fetch-node-libs.sh` (downloads the prebuilt
   on-device Node.js binaries, ~33MB one-time), then copy
   `local.properties.example` → `local.properties`, pointing `sdk.dir` at your
   SDK. **No API keys are needed to build.**
3. Build: `./gradlew assembleDebug` — or just press Run in Android Studio.
4. Target device: Android 8.0+ (API 26), **arm64-v8a only** (the bundled
   Node.js binary is arm64). Emulators must be arm64 images.

First launch on a device runs the bootstrap (extracts npm, installs the
`openclaw` package from npm — needs network, ~3 minutes, ~200MB).

To actually chat, enter a model API key in Settings. NVIDIA keys are free at
[build.nvidia.com](https://build.nvidia.com); OpenAI/Anthropic/Gemini keys work too.

## Running tests

```bash
./gradlew testDebugUnitTest   # JVM unit tests (fast, no device)
./gradlew lintDebug           # Android lint
```

Please make sure both pass before opening a PR.

## Architecture

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first — the short version:

- The OpenClaw gateway (Node.js) runs on-device as `libnode.so` via
  `ProcessBuilder`. The app talks to it over WebSocket JSON-RPC on
  127.0.0.1:3000 (`method:"agent"`, streamed `event:"agent"` frames).
- UI automation is an AccessibilityService exposing HTTP routes on
  127.0.0.1:3001; the model drives it through a tiny on-device `act` shell
  command.
- Providers are bring-your-own-key, defined in
  `data/providers/ProviderCatalog.kt` and written into openclaw's config on
  every gateway start by `NodeProcess.refreshMcpConfig`.

## Pull requests

- Keep PRs small and focused; one change per PR.
- Use [conventional commits](https://www.conventionalcommits.org/) style
  (`feat:`, `fix:`, `docs:`, `chore:`…).
- Add or update unit tests for logic changes (pure functions like
  `selectProvider` and the accessibility tree utilities are well covered —
  follow those patterns).
- UI changes: include a screenshot or screen recording in the PR.

## The quest board

Issues are labeled by difficulty so you can pick your level:

- **🌱 starter** — small and self-contained; a perfect first PR (also tagged
  `good first issue`)
- **⚔️ quest** — a real feature with clear scope and acceptance criteria
- **🐉 boss** — hard architectural challenges; the vision-fallback and
  x86-node class of problems
- **📱 no-code** — device compatibility testing, demo GIFs, docs,
  translations — no Kotlin required

## Leveling up

- **First merged PR** → you're credited in the next release notes.
- **Three merged PRs** (or one slain ⚔️ quest) → you appear on the
  contributors wall in the README, permanently.
- **Sustained contributions** → collaborator invite: triage rights, a say in
  the roadmap, and first review on your PRs.

No contribution is too small — a device compatibility report from a phone we
haven't seen counts the same as code.

## High-impact areas (help wanted)

- **New providers / OpenAI-compatible endpoints** — extend `ProviderCatalog`
  (OpenRouter, Groq, local LLM servers…).
- **Streaming latency** — profiling and improving token-by-token rendering.
- **Fresh-install setup hardening** — the bootstrap flow on diverse devices.
- **New `act` verbs** — smarter UI automation primitives.
- **Docs and translations.**

## Questions

Open a GitHub Discussion, or an issue if you've found a bug. Be excellent to
each other — see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
