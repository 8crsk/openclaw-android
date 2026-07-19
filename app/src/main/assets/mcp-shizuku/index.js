#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { parseUiautomatorXml, formatTree, findNode } from "./uitree.js";

const BRIDGE = process.env.SHIZUKU_BRIDGE_URL || "http://127.0.0.1:3001/exec";

async function exec(argv, stdin, timeoutMs = 10000) {
  const r = await fetch(BRIDGE, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ argv, stdin, timeoutMs }),
  });
  if (!r.ok) {
    let detail = `bridge ${r.status}`;
    try { const body = await r.json(); if (body.error) detail = body.error; } catch (_) {}
    throw new Error(detail);
  }
  return await r.json();
}

// Sleep helper for the WhatsApp macro's screen-transition waits.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Most recent condensed UI tree, refreshed by ui_tree / dumpTree. tap_element
// resolves an `index` argument against this cache.
let lastDump = [];

// Dump the current screen and parse it into the condensed node list.
async function dumpTree() {
  const res = await exec(
    ["sh", "-c", "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && cat /sdcard/ui.xml"],
    null,
    15000,
  );
  if (res.exitCode !== 0) {
    throw new Error(
      `uiautomator dump failed (exit ${res.exitCode}): ${res.stderr || res.stdout || "no output"}`,
    );
  }
  lastDump = parseUiautomatorXml(res.stdout || "");
  return lastDump;
}

// Tap the center of a resolved node.
function tapNode(node) {
  const [cx, cy] = node.center;
  return exec(["input", "tap", String(cx), String(cy)]);
}

// Find a node by selector, re-dumping once after a short wait if the first
// scan misses (absorbs slow screen transitions). `filter` optionally narrows
// the candidate set before matching.
async function findWithRetry(selector, filter) {
  const pick = (nodes) => findNode(filter ? nodes.filter(filter) : nodes, selector);
  let node = pick(await dumpTree());
  if (!node) {
    await sleep(700);
    node = pick(await dumpTree());
  }
  return node;
}

// Type text into the currently focused field (spaces encoded for `input text`).
function inputText(text) {
  return exec(["input", "text", String(text).replace(/ /g, "%s")]);
}

// Scripted WhatsApp send: launch → search → pick contact → type → send.
// Every step resolves elements from a fresh tree by text — no hardcoded
// coordinates. On failure it returns the failed step plus the current screen.
async function sendWhatsapp(contact, message) {
  const log = [];
  const fail = (step) => ({
    isError: true,
    content: [
      {
        type: "text",
        text:
          `send_whatsapp failed at step: ${step}\n` +
          `progress:\n${log.join("\n") || "(none)"}\n\n` +
          `current screen:\n${formatTree(lastDump)}`,
      },
    ],
  });

  // Step 0: WhatsApp installed?
  const pkgs = await exec(["sh", "-c", "pm list packages com.whatsapp"]);
  if (!(pkgs.stdout || "").includes("package:com.whatsapp")) {
    return {
      isError: true,
      content: [{ type: "text", text: "WhatsApp is not installed (com.whatsapp)" }],
    };
  }

  // Step 1: launch WhatsApp.
  await exec(["monkey", "-p", "com.whatsapp", "-c", "android.intent.category.LAUNCHER", "1"]);
  await sleep(2000);
  await dumpTree();
  log.push("launched WhatsApp");

  // Step 2: open search.
  const search = await findWithRetry({ text: "Search" });
  if (!search) return fail("open search");
  await tapNode(search);
  await sleep(800);
  log.push("opened search");

  // Step 3: type the contact name into the focused search field.
  await inputText(contact);
  await sleep(1200);
  log.push(`typed contact "${contact}"`);

  // Step 4: tap the first matching result row. Exclude the search input
  // itself (it now also contains the contact text) — pick a clickable row.
  const result = await findWithRetry(
    { text: contact },
    (n) => n.clickable && n.role !== "input",
  );
  if (!result) return fail("find contact in results");
  await tapNode(result);
  await sleep(1200);
  log.push("opened chat");

  // Step 5: focus the message field and type the message.
  const box = await findWithRetry({ text: "Message" });
  if (!box) return fail("find message input");
  await tapNode(box);
  await sleep(500);
  await inputText(message);
  await sleep(500);
  log.push("typed message");

  // Step 6: tap send.
  const send = await findWithRetry({ text: "Send" }, (n) => n.clickable);
  if (!send) return fail("tap send");
  await tapNode(send);
  log.push("tapped send");

  return {
    content: [{ type: "text", text: `WhatsApp message sent to "${contact}":\n${log.join("\n")}` }],
  };
}

const TOOLS = [
  {
    name: "shell",
    description: "Run a shell command on the phone via Shizuku (uid 2000, ADB-level privilege).",
    inputSchema: {
      type: "object",
      required: ["command"],
      properties: { command: { type: "string" }, timeoutMs: { type: "number" } },
    },
  },
  {
    name: "open_app",
    description: "Launch an installed app by its package name.",
    inputSchema: {
      type: "object",
      required: ["pkg"],
      properties: { pkg: { type: "string", description: "Android package name, e.g. com.android.settings" } },
    },
  },
  {
    name: "tap",
    description: "Tap at screen coordinates (x, y in pixels).",
    inputSchema: {
      type: "object",
      required: ["x", "y"],
      properties: { x: { type: "number" }, y: { type: "number" } },
    },
  },
  {
    name: "type_text",
    description:
      "Type text into the currently focused input field. ASCII only — emoji and other non-ASCII characters are not reliably handled by Android's `input text`.",
    inputSchema: {
      type: "object",
      required: ["text"],
      properties: { text: { type: "string" } },
    },
  },
  {
    name: "key",
    description: "Send a key event (e.g. KEYCODE_HOME, KEYCODE_BACK, KEYCODE_ENTER).",
    inputSchema: {
      type: "object",
      required: ["keycode"],
      properties: { keycode: { type: "string" } },
    },
  },
  {
    name: "screenshot_ui",
    description: "Dump the current UI hierarchy as XML (uiautomator dump).",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "install_apk",
    description: "Install an APK from a file path on the device.",
    inputSchema: {
      type: "object",
      required: ["path"],
      properties: { path: { type: "string" } },
    },
  },
  {
    name: "list_packages",
    description: "List installed app packages, optionally filtered by substring.",
    inputSchema: {
      type: "object",
      properties: { filter: { type: "string" } },
    },
  },
  {
    name: "open_url",
    description: "Open a URL in the device's default browser.",
    inputSchema: {
      type: "object",
      required: ["url"],
      properties: { url: { type: "string", description: "Full URL including https://" } },
    },
  },
  {
    name: "swipe",
    description: "Swipe from (x1,y1) to (x2,y2) over a duration in ms.",
    inputSchema: {
      type: "object",
      required: ["x1", "y1", "x2", "y2"],
      properties: {
        x1: { type: "number" }, y1: { type: "number" },
        x2: { type: "number" }, y2: { type: "number" },
        durationMs: { type: "number", description: "Duration in ms (default 300)" },
      },
    },
  },
  {
    name: "ui_tree",
    description:
      "Dump the current screen as a condensed, indexed list of interactive elements. Prefer this over screenshot_ui. Each line is: [index] role \"label\" id=... — pass the index to tap_element.",
    inputSchema: { type: "object", properties: {} },
  },
  {
    name: "tap_element",
    description:
      "Tap a UI element. Provide exactly one of: index (from the most recent ui_tree call), text (case-insensitive substring of the element's visible text or description — re-scans the screen), or id (short resource-id without package prefix — re-scans the screen).",
    inputSchema: {
      type: "object",
      properties: {
        index: { type: "number", description: "Index from the most recent ui_tree output" },
        text: { type: "string", description: "Case-insensitive substring of visible text/description" },
        id: { type: "string", description: "Short resource-id, e.g. send_input" },
      },
    },
  },
  {
    name: "send_whatsapp",
    description:
      "Send a WhatsApp message to a saved contact by name. Opens WhatsApp, searches the contact, types the message, and taps send. Use this for any 'message X on WhatsApp' request.",
    inputSchema: {
      type: "object",
      required: ["contact", "message"],
      properties: {
        contact: { type: "string", description: "Contact name as saved on the phone" },
        message: { type: "string", description: "Message text to send" },
      },
    },
  },
];

function fmt(res) {
  const text = `exit=${res.exitCode}\n--- stdout ---\n${res.stdout || ""}\n--- stderr ---\n${res.stderr || ""}`;
  return { content: [{ type: "text", text }] };
}

const server = new Server(
  { name: "shizuku-phone", version: "0.1.0" },
  { capabilities: { tools: {} } }
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { name, arguments: a = {} } = req.params;
  switch (name) {
    case "shell":
      return fmt(await exec(["sh", "-c", a.command], null, a.timeoutMs));
    case "open_app":
      return fmt(await exec(["monkey", "-p", a.pkg, "-c", "android.intent.category.LAUNCHER", "1"]));
    case "tap":
      return fmt(await exec(["input", "tap", String(a.x), String(a.y)]));
    case "type_text":
      return fmt(await inputText(a.text));
    case "key":
      return fmt(await exec(["input", "keyevent", a.keycode]));
    case "screenshot_ui":
      return fmt(await exec(["sh", "-c", "uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml"], null, 15000));
    case "install_apk":
      return fmt(await exec(["pm", "install", "-r", a.path], null, 60000));
    case "list_packages":
      return fmt(await exec(a.filter ? ["sh", "-c", `pm list packages | grep ${a.filter}`] : ["pm", "list", "packages"]));
    case "open_url":
      return fmt(await exec(["am", "start", "-a", "android.intent.action.VIEW", "-d", a.url]));
    case "swipe":
      return fmt(await exec(["input", "swipe", String(a.x1), String(a.y1), String(a.x2), String(a.y2), String(a.durationMs || 300)]));
    case "ui_tree": {
      const nodes = await dumpTree();
      return { content: [{ type: "text", text: formatTree(nodes) }] };
    }
    case "tap_element": {
      const hasIndex = typeof a.index === "number";
      const selectorCount = [hasIndex, !!a.text, !!a.id].filter(Boolean).length;
      if (selectorCount === 0) throw new Error("provide one of: index, text, id");
      if (selectorCount > 1) {
        throw new Error("provide exactly one of: index, text, id — got multiple");
      }
      let node = null;
      if (hasIndex) {
        if (!lastDump.length) throw new Error("no UI tree cached — call ui_tree first");
        node = lastDump[a.index];
        if (!node) throw new Error(`no element at index ${a.index} — call ui_tree again`);
      } else {
        node = findNode(await dumpTree(), { text: a.text, id: a.id });
        if (!node) {
          throw new Error(
            `no element matching ${a.id ? `id=${a.id}` : `text="${a.text}"`}`,
          );
        }
      }
      await tapNode(node);
      const label = node.text || node.desc || "";
      return {
        content: [{ type: "text", text: `tapped [${node.index}] ${node.role} ${JSON.stringify(label)}` }],
      };
    }
    case "send_whatsapp":
      return await sendWhatsapp(a.contact, a.message);
    default:
      throw new Error(`unknown tool ${name}`);
  }
});

await server.connect(new StdioServerTransport());
