// scripts/test-gemini-toolcall.mjs — Step 0 gate per spec
// Run: GEMINI_API_KEY=... node scripts/test-gemini-toolcall.mjs

const KEY = process.env.GEMINI_API_KEY;
if (!KEY) { console.error('GEMINI_API_KEY not set'); process.exit(2); }

const ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions';
const MODEL = 'gemini-2.5-flash';

const fail = (n, msg) => { console.error(`✗ Assertion ${n}: ${msg}`); process.exit(1); };
const ok   = (n, msg) => console.log(`✓ Assertion ${n}: ${msg}`);

async function streamLines(resp) {
  const lines = [];
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (line.startsWith('data:')) lines.push(line.slice(5).trim());
    }
  }
  return lines;
}

// Assertion 1 — streaming chat completion
{
  const resp = await fetch(ENDPOINT, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      stream: true,
      stream_options: { include_usage: true },
      messages: [{ role: 'user', content: 'Say "hello world" in exactly two words.' }],
    }),
  });
  if (!resp.ok) fail(1, `HTTP ${resp.status}: ${await resp.text()}`);
  const lines = await streamLines(resp);
  if (lines.length < 2) fail(1, `expected >=2 SSE chunks, got ${lines.length}`);
  if (lines[lines.length - 1] !== '[DONE]') fail(1, `expected last chunk to be [DONE], got ${lines[lines.length-1]}`);
  const hasContent = lines.slice(0, -1).some(l => {
    try { return JSON.parse(l).choices?.[0]?.delta?.content; } catch { return false; }
  });
  if (!hasContent) fail(1, 'no choices[0].delta.content in any chunk');
  ok(1, 'streaming chat completion');

  // Assertion 5 — streaming usage on penultimate chunk
  const usageLine = lines.slice(0, -1).reverse().find(l => {
    try { return JSON.parse(l).usage; } catch { return false; }
  });
  if (!usageLine) fail(5, 'no usage object in any chunk (stream_options.include_usage ignored?)');
  const usage = JSON.parse(usageLine).usage;
  if (!Number.isInteger(usage.total_tokens) || usage.total_tokens <= 0) fail(5, `bad usage shape: ${JSON.stringify(usage)}`);
  ok(5, `streaming usage present (total_tokens=${usage.total_tokens})`);
}

// Assertion 2 — tool-call emission (non-streaming for cleaner assertion)
const toolDef = {
  type: 'function',
  function: {
    name: 'get_weather',
    description: 'Get current weather for a city',
    parameters: {
      type: 'object',
      properties: { city: { type: 'string' } },
      required: ['city'],
    },
  },
};

let toolCall;
{
  const resp = await fetch(ENDPOINT, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      tools: [toolDef],
      tool_choice: 'auto',
      messages: [{ role: 'user', content: "What's the weather in Paris? Use the get_weather tool." }],
    }),
  });
  if (!resp.ok) fail(2, `HTTP ${resp.status}: ${await resp.text()}`);
  const body = await resp.json();
  toolCall = body.choices?.[0]?.message?.tool_calls?.[0];
  if (!toolCall) fail(2, `no tool_calls in response: ${JSON.stringify(body).slice(0, 400)}`);
  if (toolCall.function?.name !== 'get_weather') fail(2, `wrong function name: ${toolCall.function?.name}`);
  try { JSON.parse(toolCall.function.arguments); } catch { fail(2, `arguments not valid JSON: ${toolCall.function.arguments}`); }
  ok(2, `tool_calls emitted (name=${toolCall.function.name}, args=${toolCall.function.arguments})`);

  // Assertion 4 — usage accounting on non-streaming
  const u = body.usage;
  if (!u || !Number.isInteger(u.total_tokens) || u.total_tokens <= 0) fail(4, `bad non-streaming usage: ${JSON.stringify(u)}`);
  ok(4, `non-streaming usage (total_tokens=${u.total_tokens})`);
}

// Assertion 3 — tool-result roundtrip
{
  const resp = await fetch(ENDPOINT, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      tools: [toolDef],
      messages: [
        { role: 'user', content: "What's the weather in Paris? Use the get_weather tool." },
        { role: 'assistant', tool_calls: [toolCall] },
        { role: 'tool', tool_call_id: toolCall.id, content: '{"temp_c": 18, "summary": "overcast"}' },
      ],
    }),
  });
  if (!resp.ok) fail(3, `HTTP ${resp.status}: ${await resp.text()}`);
  const body = await resp.json();
  const reply = body.choices?.[0]?.message?.content;
  if (!reply || typeof reply !== 'string' || reply.length < 5) fail(3, `expected natural-language reply, got: ${JSON.stringify(reply)}`);
  if (!/18|overcast|paris/i.test(reply)) fail(3, `reply does not reference tool result: "${reply}"`);
  ok(3, `tool-result roundtrip ("${reply.slice(0, 80)}...")`);
}

console.log('\nAll 5 assertions passed. Gate is GREEN — implementation may proceed.');
