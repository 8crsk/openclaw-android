# Security Policy

4AIs runs an AI agent with real device control — accessibility gestures, screen
reading, and optional shell access via Shizuku. We take security reports seriously.

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** (Security tab → "Report a
vulnerability") or email **sanjay@4ais.in**. Please do **not**
open a public issue for security problems. You'll get a first response within
72 hours.

## Security model (what to test against)

- **API keys never ship with the app.** Your provider key (NVIDIA / OpenAI /
  Anthropic / Gemini) is entered in Settings and stored in Android
  EncryptedSharedPreferences (Keystore-backed). If secure storage can't be
  initialised, the app refuses to fall back to plaintext and fails loudly.
- **No middleman server.** Prompts and screen content go directly from the
  device to the provider you chose. There is no proxy, no account, no telemetry.
- **Local-only gateway.** The OpenClaw gateway binds to 127.0.0.1 and requires
  a per-install random token (`~/.openclaw/auth-token`, mode 0600). The
  UI-automation bridge on :3001 requires the same bearer token with a
  constant-time comparison.
- **Human-in-the-loop.** Agent actions above "read" risk are gated by an
  approval dialog; sensitive intents always prompt even when auto-approve is on.
  Every agent action lands in an on-device audit log.

Reports about bypassing any of the above — token leakage, unauthenticated
routes on :3000/:3001, approval bypasses, key exfiltration — are exactly what
we want to hear about.
