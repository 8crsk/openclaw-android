# Architecture

4AIs turns an Android phone into a self-contained AI agent. Three things make
that work: a Node.js gateway that runs **on the phone**, an AccessibilityService
that gives the model **hands and eyes**, and a bring-your-own-key provider layer
that talks **directly** to the model API you choose.

```
Compose UI ─► WsRpcClient ──ws://127.0.0.1:3000──► OpenClaw Gateway (Node.js on-device)
                 │                                        │
                 └─► ApprovalChannel                      └──► Your provider (NVIDIA / OpenAI /
                     (risk-graded dialogs)                     Anthropic / Gemini) with YOUR key
                                                          │
              agent runs `act tap 5` (exec tool)          │
                        │                                 │
                        ▼                                 │
        filesDir/bin/act ──POST──► http://127.0.0.1:3001 (ShizukuBridge, bearer-authed)
                                        │
                                        ├─ /agent/act, /ui/* ──► UiRoutes ──► AccessibilityService
                                        │                        (tree, taps, swipes, screenshots)
                                        └─ /exec ──► Shizuku shell (optional, allowlisted)
```

## The on-device gateway

- The Node.js binary ships inside the APK as `libnode.so` in `nativeLibraryDir`
  (Android happily marks `lib*.so` files executable). `NodeProcess` runs it
  directly with `ProcessBuilder` — no proot, no glibc tricks, no Termux app.
  The binary is a Termux-style build compiled with
  `TERMUX_APP__PACKAGE_NAME=com.crsk.openclaw` so its hardcoded paths land in
  our `filesDir`. `LD_LIBRARY_PATH` points at `nativeLibraryDir` for the
  companion libs (libcrypto, libicu, …).
- First launch runs `node-setup.js` (assets): extracts the bundled `npm.tgz`,
  `npm install openclaw@2026.5.12 --ignore-scripts`, generates a 32-byte hex
  gateway token, and writes the `~/.openclaw/config.json` skeleton plus a
  0600 `auth-token` sidecar the Kotlin side reads. A versioned done-marker
  (`.4ais_setup_done_v10`) gates re-provisioning.
- `GatewayService` (foreground service + wake lock) keeps the gateway alive
  through Android's process killing; `GatewayHealthMonitor` probes `/health`
  and restarts on failure. The health check requires a JSON content-type —
  the gateway's SPA fallback returns 200 text/html for any URL, so a naive
  200-check false-positives.

## WebSocket RPC (not REST!)

`/v1/chat/completions` does **not** exist on openclaw 2026.5.12. The app speaks
JSON-RPC frames over one long-lived WebSocket on 127.0.0.1:3000:
`{type:"req", id, method, params}` ↔ `{type:"res", id, ok, payload|error}` plus
unsolicited `{type:"event", event, payload}`.

- **Handshake:** server emits `connect.challenge`; client responds with
  `method:"connect"`, `role:"operator"`, `client.mode:"backend"` (loopback
  shortcut — no device crypto), and the token from the `auth-token` sidecar.
- **Chat is the `agent` method** with `{message, sessionKey, model, provider,
  idempotencyKey, extraSystemPrompt}`. Streaming arrives as `event:"agent"`
  frames (fields nested under `data`), demuxed by `ChatSession` into a sealed
  `ChunkEvent`: `TextDelta`, `ToolStart`, `ToolResult`, `Lifecycle`, `Usage`,
  `AgentError`, `Done`.
- **Token economy:** every user turn gets a fresh `sessionKey` so openclaw
  never replays prior tool results (screenshots, tree dumps) into context.
  Conversational memory is re-added explicitly as a text-only prelude (last 6
  messages, 240 chars each). A runaway guard kills any single turn that
  crosses 250k input tokens.

## Bring-your-own-key providers

- `data/providers/ProviderCatalog.kt` defines NVIDIA (free default), OpenAI,
  Anthropic, and Gemini — each as a **custom** OpenAI-compatible provider
  (explicit `baseUrl` + `models` array + `agentRuntime: pi`). We deliberately
  avoid openclaw's built-in provider plugins: their static model catalogs have
  drifted from the live APIs and reject valid models.
- Keys are entered in Settings, validated with a GET `/models` probe
  (`ProviderKeyValidator`), and stored in Android EncryptedSharedPreferences.
  On **every gateway start**, `NodeProcess.refreshMcpConfig` rebuilds
  `models.providers` in openclaw's config from the keystore — config.json is a
  derived artifact; the keystore is the source of truth. `node-setup.js` never
  sees a key.
- `ChatViewModel.selectProvider` resolves the Settings selection against the
  catalog with safe fallbacks, then passes `provider` + bare `model` per call.

## UI automation (the agent's hands)

- `PhoneAccessibilityService` reads the UI tree and dispatches native gestures.
  `ShizukuBridge` (NanoHTTPD on 127.0.0.1:3001, **bearer-token auth with
  constant-time compare on every route**) exposes it via `UiRoutes`:
  `/ui/tree`, `/ui/find`, `/ui/tap`, `/ui/long-tap`, `/ui/type`, `/ui/swipe`,
  `/ui/scroll` (scroll-to-find), `/ui/wait`, `/ui/screenshot`, `/ui/observe`,
  `/ui/batch`, `/ui/app/launch`, `/ui/app/stop`, `/ui/clipboard`,
  `/ui/notifications`, `/agent/act`, and more.
- Elements get stable **legend ids** (`MarkAssigner`/`ServedLegend`) with
  occlusion filtering, so the model taps `5` instead of coordinates. After each
  action the response includes a **legend diff** of what changed on screen.
- **The `act` wrapper:** openclaw 2026.5.12 dropped stdio MCP transport, so the
  agent's only built-in tool is generic `exec`. Rather than have the model
  spell out a 60-token curl per step, `NodeProcess.writeActWrapper` installs a
  tiny `act` shell command into `filesDir/bin` (first on PATH, rewritten every
  gateway start so APK updates redeploy it). The model runs `act observe`,
  `act tap 5`, `act type 5 hello`, `act batch [...]`; the wrapper injects the
  bearer token and POSTs to :3001. `ChatSession` teaches the grammar via a
  ~280-token `extraSystemPrompt` on every turn.
- Extras: agent-vision overlay (on-screen highlights while the agent acts),
  `act take_over` for manual handoff, per-app agent notes
  (`AppDocsRepository`), and an on-device audit log of every action.
- **Shizuku is optional.** It adds ADB-level shell (`/exec`, allowlisted
  commands) for the few things accessibility can't do (e.g. force-stop apps).
  The APK is not bundled; users install it from the official source.

## Approvals & safety

- openclaw emits `exec.approval.requested` / `plugin.approval.requested`;
  `ApprovalChannel` classifies the intent into graded risk levels and surfaces
  an in-app dialog. Users can enable auto-approve, but **sensitive intents
  always prompt anyway**. Auto-approve defaults OFF.
- Setup wizard: Welcome → Privacy consent (DPDP/GDPR informed consent before
  any data leaves the device) → Enable accessibility → Node bootstrap → Done.

## Integrations (optional)

- **Composio:** with a user-provided Composio key, the app talks to Composio's
  v3 API directly (`data/composio/`) — toolkit catalog, OAuth connect flow,
  and a generated MCP server URL that `refreshMcpConfig` injects into openclaw
  as a `streamable-http` MCP server. Gives the agent Gmail/Notion/GitHub/300+
  tools.
- **Heartbeat:** openclaw's proactive-agent scheduler, surfaced in Settings.
  Disabled by default (`every: 0m`); tasks live in `~/.openclaw/HEARTBEAT.md`.

## Key directories

```
app/src/main/java/com/crsk/openclaw/
├── accessibility/   # AccessibilityService, UiTreeBuilder, GestureController,
│                    # ActionDispatcher, UiRoutes, MarkAssigner, OcclusionCalculator
├── bootstrap/       # BootstrapManager, GatewayProcess
├── data/
│   ├── composio/    # Direct Composio v3 client + repository
│   ├── heartbeat/   # Heartbeat config store
│   ├── network/     # ProviderKeyValidator, ws/ (WsRpcClient, ChatSession,
│   │                # ApprovalChannel, HeartbeatChannel, GatewayToken)
│   ├── preferences/ # AppPreferences (DataStore), EncryptedKeyStore, ConsentStore
│   └── providers/   # ProviderCatalog — the BYO-key provider definitions
├── node/            # NodeProcess, NodeSetup, ActWrapperScript, LauncherScript
├── overlay/         # Agent overlay bubble + vision highlights
├── service/         # GatewayService (foreground), GatewayHealthMonitor, BootReceiver
├── shizuku/         # ShizukuBridge (:3001 HTTP), ShizukuManager (optional shell)
└── ui/              # Compose screens: chat/, setup/, settings/, status/,
                     # connections/, heartbeat/, navigation/, theme/
```
