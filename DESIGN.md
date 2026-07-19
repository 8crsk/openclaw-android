# Design

Visual system for 4AIs. The north star: **texting an AI should feel exactly like
texting a person in Apple Messages** — dark, calm, familiar, premium. Built in
Jetpack Compose (Material 3), so this captures the iMessage *visual language and
motion*, not literal iOS components.

## Theme

- **Dark only.** True-black (`#000000`) base for OLED depth and the authentic
  Messages feel. No light mode.
- **Glass, used sparingly.** Translucent, blurred chrome for the top bar, bottom
  tab bar, and input bar — never decorative glass on content.
- Color strategy: **Restrained.** One saturated accent (iMessage blue) on a
  near-monochrome dark field. Semantic colors (green/orange/red) only for state.

## Color

OKLCH-reasoned, shipped as Compose `Color` hex (the platform is Compose, not CSS).

| Role | Token | Hex | Notes |
|---|---|---|---|
| Base background | `BgBase` | `#000000` | Chat + app background (OLED black) |
| Elevated surface | `BgElevated` | `#1C1C1E` | Cards, sheets, grouped sections |
| Surface | `Surface` | `#2C2C2E` | Received bubbles, input field, inset rows |
| Surface high | `SurfaceHigh` | `#3A3A3C` | Pressed states, raised insets |
| **Accent (brand)** | `AccentBlue` | `#0A84FF` | The one brand color. iOS system blue, dark. |
| Accent pressed | `AccentBluePressed` | `#0060DF` | Pressed / active |
| Sent bubble | `BubbleSentTop→Bottom` | `#1F8DFF → #0A7CF4` | Subtle vertical gradient, iMessage-style |
| Received bubble | `BubbleReceived` | `#2C2C2E` | Solid |
| Text primary | `TextPrimary` | `#FFFFFF` | Body, headings |
| Text secondary | `TextSecondary` | `#98989F` | Subtitles, timestamps, metadata |
| Text muted | `TextMuted` | `#636366` | Placeholders, disabled |
| Separator | `BorderHairline` | `#FFFFFF @ 12%` | Hairline dividers, glass edges |
| Success | `Success` | `#30D158` | Connected, done |
| Warning | `Warning` | `#FF9F0A` | Attention |
| Danger | `Danger` | `#FF453A` | Errors, destructive (clear, stop) |

**Contrast:** body text is white or `#98989F` on `#000`/`#1C1C1E` — both clear AA.
Never gray-on-tint body. Sent-bubble text is always white on blue (AA large+).

## Typography

- **Inter** (bundled: Regular / Medium / SemiBold / Bold) as the SF Pro stand-in.
  One family, hierarchy through weight + scale.
- Display headings use tight tracking (−0.02 to −0.04em); body is neutral.
- Chat message text: 16sp / 22 line-height (`bodyLarge`). Comfortable, Messages-sized.
- Timestamps, "Delivered", tool labels: 11–12sp `labelSmall` in `TextSecondary`.
- No all-caps body. Uppercase only for ≤2-word state pills.

## Components

- **Message bubble.** 20dp radius, 6dp tail corner (bottom-inner). Sent = blue
  gradient, white text, right-aligned. Received = `#2C2C2E`, white text, left.
  Spring pop-in keyed once per message; bubble grows with streamed text.
- **Typing indicator.** Three bobbing dots in a received bubble (already built).
- **Tool-call chip.** Inline under an assistant bubble; icon + name + state, tinted
  by status (running = blue, done = green, failed = red).
- **Bottom tab bar (Telegram-inspired).** Floating glass pill, icon + label per tab,
  active tab tinted `AccentBlue` with a soft blue glass highlight that springs
  between tabs. Tabs: Chats · Connections · Usage · Settings (grows as screens land).
- **Top bar (Messages-style).** Avatar/status dot + title + subtitle on the left;
  small rounded pill action buttons top-right (Telegram-style).
- **Connections card.** A row of logo bubbles for wired-up capabilities
  (Accessibility, Shizuku, MCP plugins, provider key) — present = full color,
  absent = dimmed. Color is never the only signal; each bubble has its glyph.
- **Usage meter.** Claude-Code-style limit/usage readout; context-window gauge per
  chat with a **Clear context** action.
- **GlassCard.** Translucent surface + hairline border + faint top sheen. Content
  container for settings and data.
- **Buttons.** Primary = solid `AccentBlue`, 18dp radius, white label. Ghost =
  hairline outline, white label.

## Layout

- Edge-to-edge, status-bar + nav-bar transparent (black). 16dp screen gutters.
- Bubbles `widthIn(max = 320dp)`, aligned to sender side.
- Floating tab bar over content with bottom safe-area padding.

## Motion

- iMessage-grade: spring-based, ease-out, no bounce/elastic beyond the bubble's
  gentle overshoot. `dampingRatio` ~0.55–0.85.
- Send: bubble pops from the tail (transform origin at bottom-inner corner).
- Streaming: `animateContentSize` spring so the bubble grows as text arrives.
- Tab switch: blue highlight springs between tabs (stiffness ~300).
- The **orb**: animated gradient sphere (setup hero + optional "thinking"
  indicator), recolored to glow on black; slow drift, reduced-motion → static.
- Every animation needs a reduced-motion fallback (crossfade / instant).

## State integrity (non-negotiable)

- **Rotation / config change must never wipe the chat.** State lives in
  ViewModel/`rememberSaveable`, not transient composition state.
- No jank: animations keyed so streaming deltas don't re-trigger entrances;
  no focus theft on the input; scroll stays pinned while generating.
