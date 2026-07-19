import { test } from "node:test";
import assert from "node:assert/strict";
import { parseBounds, pickRole, parseUiautomatorXml, formatTree, findNode } from "./uitree.js";

test("parseBounds computes the center of a bounds rect", () => {
  assert.deepEqual(parseBounds("[0,0][100,200]"), [50, 100]);
  assert.deepEqual(parseBounds("[40,100][1040,200]"), [540, 150]);
  assert.deepEqual(parseBounds("[-48,0][1080,96]"), [516, 48]);
});

test("parseBounds returns null for malformed input", () => {
  assert.equal(parseBounds(""), null);
  assert.equal(parseBounds("not-bounds"), null);
  assert.equal(parseBounds(undefined), null);
});

test("pickRole maps Android widget classes to short roles", () => {
  assert.equal(pickRole("android.widget.EditText"), "input");
  assert.equal(pickRole("android.widget.Button"), "button");
  assert.equal(pickRole("android.widget.TextView"), "text");
  assert.equal(pickRole("android.widget.ImageView"), "image");
  assert.equal(pickRole("android.widget.Switch"), "toggle");
  assert.equal(pickRole("android.widget.CheckBox"), "toggle");
  assert.equal(pickRole("android.widget.FrameLayout"), "node");
  assert.equal(pickRole(""), "node");
});

const SAMPLE = `<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node index="0" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" clickable="false" scrollable="false" focusable="false" text="" content-desc="" resource-id="">
    <node index="0" class="android.widget.EditText" bounds="[40,100][1040,200]" clickable="true" scrollable="false" focusable="true" text="" content-desc="Search" resource-id="com.whatsapp:id/search_input"/>
    <node index="1" class="android.widget.Button" bounds="[900,2200][1040,2340]" clickable="true" scrollable="false" focusable="true" text="Send" content-desc="Send" resource-id="com.whatsapp:id/send"/>
    <node index="2" class="android.widget.TextView" bounds="[40,300][1040,400]" clickable="true" scrollable="false" focusable="false" text="John Doe" content-desc="" resource-id="com.whatsapp:id/contact_row"/>
  </node>
</hierarchy>`;

test("parseUiautomatorXml drops uninteresting layout containers", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  // The FrameLayout has no text and is not clickable/scrollable/focusable.
  assert.equal(nodes.length, 3);
  assert.equal(nodes.every((n) => n.role !== "node"), true);
});

test("parseUiautomatorXml indexes nodes and computes centers", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  assert.deepEqual(nodes.map((n) => n.index), [0, 1, 2]);
  assert.deepEqual(nodes[0].center, [540, 150]);
  assert.equal(nodes[0].role, "input");
  assert.equal(nodes[0].desc, "Search");
  assert.equal(nodes[0].id, "search_input");
  assert.equal(nodes[0].clickable, true);
});

test("parseUiautomatorXml strips the package prefix from resource-id", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  assert.equal(nodes[1].id, "send");
  assert.equal(nodes[1].text, "Send");
});

test("parseUiautomatorXml decodes XML entities in text", () => {
  const xml = `<node class="android.widget.TextView" bounds="[0,0][10,10]" clickable="true" scrollable="false" focusable="false" text="Tom &amp; Jerry &quot;hi&quot;" content-desc="" resource-id=""/>`;
  const nodes = parseUiautomatorXml(xml);
  assert.equal(nodes[0].text, 'Tom & Jerry "hi"');
});

test("parseUiautomatorXml returns [] for empty input", () => {
  assert.deepEqual(parseUiautomatorXml(""), []);
  assert.deepEqual(parseUiautomatorXml(undefined), []);
});

test("parseUiautomatorXml keeps a node that is only scrollable", () => {
  const xml = `<node class="androidx.recyclerview.widget.RecyclerView" bounds="[0,200][1080,2000]" clickable="false" scrollable="true" focusable="false" text="" content-desc="" resource-id="com.whatsapp:id/list"/>`;
  const nodes = parseUiautomatorXml(xml);
  assert.equal(nodes.length, 1);
  assert.equal(nodes[0].scrollable, true);
  assert.equal(nodes[0].id, "list");
});

test("formatTree renders compact indexed lines", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  const out = formatTree(nodes);
  const lines = out.split("\n");
  assert.equal(lines.length, 3);
  assert.match(lines[0], /^\[0\] input/);
  assert.match(lines[0], /"Search"/);
  assert.match(lines[0], /id=search_input/);
  assert.match(lines[1], /^\[1\] button +"Send"/);
});

test("formatTree handles an empty list", () => {
  assert.equal(formatTree([]), "(no interactive elements found)");
});

test("findNode matches by case-insensitive text substring", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  assert.equal(findNode(nodes, { text: "john" }).id, "contact_row");
  assert.equal(findNode(nodes, { text: "SEND" }).id, "send");
});

test("findNode matches content-desc when text is empty", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  assert.equal(findNode(nodes, { text: "search" }).id, "search_input");
});

test("findNode matches by exact resource-id", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  assert.equal(findNode(nodes, { id: "send" }).text, "Send");
  assert.equal(findNode(nodes, { id: "missing" }), null);
});

test("findNode returns null when nothing matches or no selector given", () => {
  const nodes = parseUiautomatorXml(SAMPLE);
  assert.equal(findNode(nodes, { text: "nonexistent" }), null);
  assert.equal(findNode(nodes, {}), null);
});
