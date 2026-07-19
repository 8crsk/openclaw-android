# Product

## Register

product

## Users

Mainstream, non-technical phone owners who want a capable AI agent on their phone
for free, with no PC required. Their context is casual and mobile: they expect the
app to "just work" like any messaging app they already use. They will not tolerate
setup friction or jargon, and they judge quality by feel, not feature lists.

## Product Purpose

4AIs runs the OpenClaw AI gateway locally on-device using free NVIDIA models,
giving full agent mode (tool use, MCP, phone automation via accessibility/Shizuku)
completely free. Success is a non-technical user installing the app, getting through
setup without confusion, and chatting with an agent that can actually do things on
their phone — and having the whole thing feel as easy and familiar as texting a friend.

## Brand Personality

Calm, familiar, capable. The voice is plain-spoken and reassuring, never technical
for its own sake. The emotional goal is effortless trust: serious, powerful software
that feels as approachable as Messages. It never shows off; the power shows through
what it can do, not through loud visuals.

## Anti-references

- Termux / terminal / hacker tooling (explicitly rejected — must not read as a dev console)
- Gimmicky or toy apps (must feel like premium, serious software)
- Generic white "ChatGPT clone" chat with plain bubbles
- Cluttered enterprise / SaaS-admin dashboards

## Design Principles

- **Familiar over novel.** Borrow Apple Messages' interaction grammar so there is
  nothing to learn. Texting an AI should feel exactly like texting a person.
- **Show the work, calmly.** Surface what the gateway/agent is actually doing in
  plain language ("Starting the gateway…", "Connecting accessibility…"), never a bare spinner.
- **Every pixel earns its meaning.** If we hold the data (connections, usage, context
  window, plugins), present it usefully instead of hiding it.
- **Quiet confidence.** Dark, restrained, glass. Capability carries the impression, not noise.
- **Rock-solid is the product.** No jank, no lost state (rotation never wipes a chat),
  no glitches. Polish is a feature, not a finish.

## Accessibility & Inclusion

Target WCAG AA: body text ≥ 4.5:1 contrast, large/bold text ≥ 3:1. Honor
`prefers-reduced-motion` equivalents (bubble physics and the orb degrade to crossfades).
Tap targets ≥ 48dp. Respect system dynamic type. Color is never the only signal for
state (connection / usage / errors pair color with icon or label).
