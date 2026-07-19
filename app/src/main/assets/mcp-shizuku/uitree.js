// Pure, dependency-free helpers for turning a uiautomator XML dump into a
// condensed, indexed element list. No I/O here — safe to import from tests.

// Parse `bounds="[x1,y1][x2,y2]"` into a center point [cx, cy], or null.
export function parseBounds(bounds) {
  const m = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(bounds || "");
  if (!m) return null;
  const x1 = +m[1], y1 = +m[2], x2 = +m[3], y2 = +m[4];
  return [Math.round((x1 + x2) / 2), Math.round((y1 + y2) / 2)];
}

// Map an Android widget class name to a short role label.
export function pickRole(className) {
  const c = (className || "").toLowerCase();
  if (c.includes("edittext")) return "input";
  if (c.includes("button")) return "button";
  if (c.includes("checkbox") || c.includes("switch")) return "toggle";
  if (c.includes("imageview")) return "image";
  if (c.includes("textview")) return "text";
  return "node";
}

// Decode the handful of XML entities uiautomator emits in attribute values.
function decodeEntities(s) {
  return (s || "")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");
}

// Extract one attribute value from a single `<node ...>` tag string.
function attr(tag, name) {
  const m = new RegExp(`(?:^| )${name}="([^"]*)"`).exec(tag);
  return m ? decodeEntities(m[1]) : "";
}

// Parse a full uiautomator XML dump into a flat list of "interesting" nodes.
// A node is interesting if it is clickable, scrollable, or focusable, OR has
// non-empty text / content-desc. Pure layout containers are dropped — that is
// where the token bloat lives. Index is the position in the condensed list.
export function parseUiautomatorXml(xml) {
  const tags = (xml || "").match(/<node\b[^>]*>/g) || [];
  const out = [];
  for (const tag of tags) {
    const text = attr(tag, "text");
    const desc = attr(tag, "content-desc");
    const clickable = attr(tag, "clickable") === "true";
    const scrollable = attr(tag, "scrollable") === "true";
    const focusable = attr(tag, "focusable") === "true";
    if (!(clickable || scrollable || focusable || text || desc)) continue;
    const center = parseBounds(attr(tag, "bounds"));
    if (!center) continue;
    out.push({
      index: out.length,
      role: pickRole(attr(tag, "class")),
      text,
      desc,
      id: attr(tag, "resource-id").replace(/^.*\//, ""),
      clickable,
      scrollable,
      center,
    });
  }
  return out;
}

// Render the node list as compact indexed lines for the model to read.
// Format per line: [index] role "label" id=... (scrollable)
export function formatTree(nodes) {
  if (!nodes.length) return "(no interactive elements found)";
  return nodes
    .map((n) => {
      const label = n.text || n.desc || "";
      const parts = [`[${n.index}]`, n.role.padEnd(6), JSON.stringify(label)];
      if (n.id) parts.push(`id=${n.id}`);
      if (n.scrollable) parts.push("(scrollable)");
      return parts.join(" ");
    })
    .join("\n");
}

// Find the first node matching a selector. `id` matches the short resource-id
// exactly; `text` matches a case-insensitive substring of text or content-desc.
export function findNode(nodes, { text, id } = {}) {
  if (id) return nodes.find((n) => n.id === id) || null;
  if (text) {
    const t = text.toLowerCase();
    return (
      nodes.find(
        (n) =>
          (n.text && n.text.toLowerCase().includes(t)) ||
          (n.desc && n.desc.toLowerCase().includes(t)),
      ) || null
    );
  }
  return null;
}
