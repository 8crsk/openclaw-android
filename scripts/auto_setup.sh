#!/data/data/com.termux/files/usr/bin/bash
# 4AIs auto-setup: provisions Termux for OpenClaw + NVIDIA NIM.
# Driven from the 4AIs APK via the Termux RUN_COMMAND intent.
# Inputs (env): NVIDIA_API_KEY (required), OPENCLAW_MODEL, OPENCLAW_PORT, MCP_PUSH_DIR
set -euo pipefail

: "${NVIDIA_API_KEY:?NVIDIA_API_KEY required}"
: "${OPENCLAW_MODEL:=meta/llama-3.3-70b-instruct}"
: "${OPENCLAW_PORT:=3000}"
: "${MCP_PUSH_DIR:=$HOME/storage/shared/4AIs/mcp-shizuku}"

log() { printf '[4ais] %s\n' "$*"; }

log "phase=storage  Granting storage access (no-op if already granted)…"
yes | termux-setup-storage 2>/dev/null || true

log "phase=packages Installing Node.js, git, curl, openssh, termux-api…"
export DEBIAN_FRONTEND=noninteractive
pkg update -y -o Dpkg::Options::="--force-confnew" >/dev/null
pkg install -y nodejs git curl openssh termux-api ca-certificates >/dev/null

log "phase=node     Pinning IPv4-first DNS for Node…"
grep -q 'dns-result-order=ipv4first' "$HOME/.bashrc" 2>/dev/null \
  || echo 'export NODE_OPTIONS="${NODE_OPTIONS:-} --dns-result-order=ipv4first"' >> "$HOME/.bashrc"

log "phase=openclaw Installing openclaw via npm…"
npm config set registry https://registry.npmjs.org/
npm install -g openclaw@latest --ignore-scripts --no-audit --no-fund

log "phase=config   Writing OpenClaw config (NVIDIA provider, port ${OPENCLAW_PORT})…"
mkdir -p "$HOME/.openclaw"
cat > "$HOME/.openclaw/config.json" <<JSON
{
  "providers": {
    "nvidia": {
      "type": "openai",
      "baseURL": "https://integrate.api.nvidia.com/v1",
      "apiKey": "${NVIDIA_API_KEY}"
    }
  },
  "defaultProvider": "nvidia",
  "defaultModel": "${OPENCLAW_MODEL}",
  "server": { "host": "127.0.0.1", "port": ${OPENCLAW_PORT} }
}
JSON

log "phase=mcp      Installing MCP shim from ${MCP_PUSH_DIR}…"
MCP_DIR="$HOME/.openclaw/mcp/shizuku-phone"
mkdir -p "$MCP_DIR"
if [ -d "$MCP_PUSH_DIR" ]; then
  cp -f "$MCP_PUSH_DIR"/* "$MCP_DIR/"
fi
( cd "$MCP_DIR" && npm install --omit=dev --no-audit --no-fund --prefer-offline >/dev/null 2>&1 || true )

node -e "
  const fs = require('fs');
  const path = require('path');
  const f = path.join(process.env.HOME, '.openclaw', 'config.json');
  const c = JSON.parse(fs.readFileSync(f, 'utf8'));
  c.mcp = c.mcp || { servers: {} };
  c.mcp.servers['shizuku-phone'] = {
    command: 'node',
    args: [path.join(process.env.HOME, '.openclaw', 'mcp', 'shizuku-phone', 'index.js')],
    env: { SHIZUKU_BRIDGE_URL: 'http://127.0.0.1:3001/exec' }
  };
  fs.writeFileSync(f, JSON.stringify(c, null, 2));
"

touch "$HOME/.openclaw/.4ais_setup_done"
log "phase=done     Setup complete. Marker at \$HOME/.openclaw/.4ais_setup_done"
echo "OPENCLAW_READY"
