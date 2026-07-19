'use strict';
// node-setup.js — first-launch provisioner
// Run as: node node-setup.js
// Env (required): FILES_DIR, OPENCLAW_PORT, NODE_BIN, ASSETS_DIR
//
// API keys never pass through this script. The user's provider keys live in
// Android EncryptedSharedPreferences; NodeProcess.refreshMcpConfig writes the
// models.providers section of config.json from them on every gateway start.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const child_process = require('child_process');

const log  = (phase, msg) => process.stdout.write(`[setup] phase=${phase} ${msg}\n`);
const fail = (msg) => { process.stderr.write(`[setup] FATAL: ${msg}\n`); process.exit(1); };

const FILES_DIR  = process.env.FILES_DIR   || fail('FILES_DIR not set');
const PORT       = process.env.OPENCLAW_PORT   || '3000';
const NODE_BIN   = process.env.NODE_BIN        || process.execPath;
const ASSETS_DIR = process.env.ASSETS_DIR      || path.join(FILES_DIR, 'assets');

const NPM_TGZ         = path.join(ASSETS_DIR, 'npm.tgz');
const NPM_DIR         = path.join(FILES_DIR, 'npm');
const NPM_CLI         = path.join(NPM_DIR, 'package', 'bin', 'npm-cli.js');
const OPENCLAW_DIR    = path.join(FILES_DIR, 'openclaw');
const HOME_DIR        = path.join(FILES_DIR, 'home');
const CONFIG_DIR      = path.join(HOME_DIR, '.openclaw');
const CONFIG_FILE     = path.join(CONFIG_DIR, 'config.json');
const SETUP_VERSION = 10; // v10: BYO-key only — no proxy providers; NodeProcess writes providers from the device keystore
const DONE_MARKER     = path.join(CONFIG_DIR, '.4ais_setup_done_v' + SETUP_VERSION);

if (fs.existsSync(DONE_MARKER)) {
  log('skip', 'already provisioned at setup version ' + SETUP_VERSION);
  process.stdout.write('OPENCLAW_READY\n');
  process.exit(0);
}

// ── Step 1: Extract bundled npm ──────────────────────────────────────────────
log('npm', 'extracting bundled npm…');
fs.mkdirSync(NPM_DIR, { recursive: true });
if (!fs.existsSync(NPM_CLI)) {
  try {
    extractTarGz(NPM_TGZ, NPM_DIR);
  } catch (e) {
    fail('Failed to extract npm.tgz: ' + e.message);
  }
}

// ── Step 2: npm install openclaw ─────────────────────────────────────────────
log('openclaw', 'installing openclaw (needs network, ~2–5 min)…');
fs.mkdirSync(OPENCLAW_DIR, { recursive: true });

const r = runNode([
  NPM_CLI,
  'install',
  '--prefix', OPENCLAW_DIR,
  'openclaw@2026.5.12',
  '--ignore-scripts',
  '--no-audit',
  '--no-fund',
  '--loglevel', 'warn',
]);
if (r !== 0) fail('npm install openclaw failed (exit ' + r + ')');

// ── Step 3: Config ────────────────────────────────────────────────────────────
log('config', 'writing openclaw config…');
fs.mkdirSync(CONFIG_DIR, { recursive: true });

const crypto = require('crypto');
const TOKEN = crypto.randomBytes(32).toString('hex');

const MCP_DIR = path.join(CONFIG_DIR, 'mcp', 'shizuku-phone');

// Providers are intentionally EMPTY here. The Android side (NodeProcess.
// refreshMcpConfig) rewrites models.providers from the user's saved BYO keys
// on every gateway start, so anything written at provision time would be
// overwritten immediately — and this script must never see an API key.
const providers = {};

const config = {
  gateway: {
    mode: 'local',
    bind: 'loopback',
    port: Number(PORT),
    auth: { mode: 'token', token: TOKEN },
  },
  // openclaw 2026.5.12 schema: providers live under models.providers.
  // The agent's per-call `model` param (set by ChatSession on Android) overrides
  // any global default openclaw extensions may install.
  models: {
    providers,
  },
  agents: {
    defaults: {
      // Placeholder — NodeProcess.refreshMcpConfig overwrites primary with the
      // user's provider/model selection on every gateway start.
      model: {
        primary: 'nim/nvidia/nemotron-3-super-120b-a12b',
        fallbacks: [],
      },
      // Heartbeat (proactive agent). Starts disabled so we never spend tokens
      // before the user enables it from the Heartbeat settings screen.
      heartbeat: {
        every: '0m',
        // 24h window so users don't get tripped by silent "quiet-hours" skips.
        activeHours: { start: '00:00', end: '23:59', timezone: 'UTC' },
        skipWhenBusy: true,
        // Fresh session per tick — prevents the agent from parroting its previous reply.
        isolatedSession: true,
        ackMaxChars: 300,
        target: 'none',
        lightContext: false,
      },
    },
  },
  mcp: {
    servers: {
      'shizuku-phone': {
        // NodeProcess.refreshMcpConfig will overwrite this on every gateway start
        // to point command at <MCP_DIR>/launch.sh. This seed exists only so the
        // shape is valid before the first refresh.
        transport: 'stdio',
        command: path.join(MCP_DIR, 'launch.sh'),
        args: [],
        env: { SHIZUKU_BRIDGE_URL: 'http://127.0.0.1:3001/exec' },
      },
    },
  },
};
fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
fs.writeFileSync(path.join(CONFIG_DIR, 'auth-token'), TOKEN, { mode: 0o600 });

// Seed an empty HEARTBEAT.md. Openclaw skips heartbeat ticks when this file
// is empty/comments-only (reason=empty-heartbeat-file), so zero API cost
// until the user writes a task in the Heartbeat settings screen.
const HEARTBEAT_FILE = path.join(CONFIG_DIR, 'HEARTBEAT.md');
if (!fs.existsSync(HEARTBEAT_FILE)) {
  fs.writeFileSync(
    HEARTBEAT_FILE,
    '# Heartbeat tasks\n' +
    '# Add lines below describing what to check. Leave empty to skip ticks.\n' +
    '# Example:\n' +
    '#   - If there are unread WhatsApp messages from family, summarize them and notify me.\n'
  );
}

// ── Step 4: MCP shim ──────────────────────────────────────────────────────────
log('mcp', 'installing MCP shim…');
const MCP_ASSET_DIR = path.join(ASSETS_DIR, 'mcp-shizuku');
fs.mkdirSync(MCP_DIR, { recursive: true });
for (const f of ['index.js', 'uitree.js', 'package.json']) {
  const src = path.join(MCP_ASSET_DIR, f);
  if (fs.existsSync(src)) {
    fs.writeFileSync(path.join(MCP_DIR, f), fs.readFileSync(src));
  }
}
// Write a starter launch.sh — NodeProcess will rewrite this on every gateway
// start with the current libDir, so the exact paths here don't matter much.
// We point at the JS entry; the wrapper sets LD_LIBRARY_PATH at runtime.
const LAUNCH_SH = path.join(MCP_DIR, 'launch.sh');
fs.writeFileSync(
  LAUNCH_SH,
  '#!/system/bin/sh\n' +
  '# Placeholder — NodeProcess.refreshMcpConfig rewrites this on every gateway start.\n' +
  '# Restores LD_LIBRARY_PATH (stripped by openclaw\'s host-env-security policy).\n' +
  'export LD_LIBRARY_PATH="' + path.dirname(NODE_BIN) + '"\n' +
  'export HOME="' + HOME_DIR + '"\n' +
  'exec "' + NODE_BIN + '" "' + path.join(MCP_DIR, 'index.js') + '"\n',
  { mode: 0o755 }
);
runNode([NPM_CLI, 'install', '--prefix', MCP_DIR, '--omit=dev', '--no-audit', '--no-fund', '--loglevel', 'error']);

// ── Done ──────────────────────────────────────────────────────────────────────
fs.writeFileSync(DONE_MARKER, new Date().toISOString());
log('done', 'setup complete');
process.stdout.write('OPENCLAW_READY\n');

// ── Helpers ───────────────────────────────────────────────────────────────────
function nodeEnv() {
  return {
    ...process.env,
    HOME: HOME_DIR,
    TMPDIR: path.join(FILES_DIR, 'tmp'),
    npm_config_cache: path.join(FILES_DIR, 'npm-cache'),
    npm_config_userconfig: path.join(FILES_DIR, 'npm-config'),
  };
}

function runNode(args) {
  fs.mkdirSync(path.join(FILES_DIR, 'tmp'), { recursive: true });
  const res = child_process.spawnSync(NODE_BIN, args, {
    stdio: 'inherit',
    env: nodeEnv(),
  });
  return res.status ?? 1;
}

function extractTarGz(tgzPath, destDir) {
  const data    = fs.readFileSync(tgzPath);
  const ungzip  = zlib.gunzipSync(data);
  let offset    = 0;
  const BLOCK   = 512;

  while (offset + BLOCK <= ungzip.length) {
    const hdr = ungzip.slice(offset, offset + BLOCK);
    if (hdr.every(b => b === 0)) break;

    const name     = readCStr(hdr, 0, 100);
    const sizeOct  = readCStr(hdr, 124, 12).trim();
    const size     = parseInt(sizeOct, 8) || 0;
    const type     = String.fromCharCode(hdr[156]);
    offset += BLOCK;

    const dest = path.join(destDir, name.replace(/^\//, ''));
    if (type === '5' || name.endsWith('/')) {
      fs.mkdirSync(dest, { recursive: true });
    } else if (type === '0' || type === '' || type === '\0') {
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.writeFileSync(dest, ungzip.slice(offset, offset + size));
    }
    offset += Math.ceil(size / BLOCK) * BLOCK;
  }
}

function readCStr(buf, start, len) {
  const end = buf.indexOf(0, start);
  return buf.slice(start, end === -1 || end > start + len ? start + len : end).toString('utf8');
}
