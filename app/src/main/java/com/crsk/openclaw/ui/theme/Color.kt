package com.crsk.openclaw.ui.theme

import androidx.compose.ui.graphics.Color

// iMessage dark — flat surfaces, one accent, restrained.
// System color values from Apple HIG (iOS dark mode).

// Backgrounds — true black for the chat field, system gray ramp for surfaces.
val BgBase = Color(0xFF000000)
val BgElevated = Color(0xFF1C1C1E)    // system gray 6 — grouped sections, sheets, cards
val Surface = Color(0xFF2C2C2E)       // system gray 5 — received bubbles, input field
val SurfaceHigh = Color(0xFF3A3A3C)   // system gray 4 — pressed / raised

// System gray ramp (iOS dark)
val SystemGray1 = Color(0xFF8E8E93)
val SystemGray2 = Color(0xFF636366)
val SystemGray3 = Color(0xFF48484A)
val SystemGray4 = Color(0xFF3A3A3C)
val SystemGray5 = Color(0xFF2C2C2E)
val SystemGray6 = Color(0xFF1C1C1E)

// Accent — iOS system blue (dark variant).
val AccentBlue = Color(0xFF0A84FF)
val AccentBluePressed = Color(0xFF0060DF)
val AccentBlueSoft = Color(0xFF64B5FF)

// Bubbles — flat fills, no gradient. iMessage uses one solid blue for sent.
val BubbleSent = AccentBlue
val BubbleReceived = Surface

// Hairline separator — iOS uses ~16% white on dark.
val Separator = Color(0x29FFFFFF)
val BorderHairline = Separator
val BorderActive = Color(0x4D0A84FF)

// Text
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = SystemGray1     // subtitles, timestamps, footnotes
val TextMuted = SystemGray2         // placeholders, disabled

// Semantic state
val Success = Color(0xFF30D158)
val Warning = Color(0xFFFF9F0A)     // iOS system orange (dark)
val Danger = Color(0xFFFF453A)

// Back-compat aliases. Kept so legacy screens compile while migrating;
// each redirects to the equivalent flat token so behaviour stays on-brand.
@Deprecated("Use BgBase", ReplaceWith("BgBase")) val BgDeep = BgBase
@Deprecated("Use BgElevated", ReplaceWith("BgElevated")) val BgRaised = BgElevated
@Deprecated("Use BgElevated", ReplaceWith("BgElevated")) val GlassChrome = BgElevated
@Deprecated("Use Surface", ReplaceWith("Surface")) val GlassSurface = Surface
@Deprecated("Use BgElevated", ReplaceWith("BgElevated")) val SurfaceGlass = BgElevated
@Deprecated("Use BgElevated", ReplaceWith("BgElevated")) val SurfaceGlassStrong = BgElevated
@Deprecated("Use AccentBlue", ReplaceWith("AccentBlue")) val Cyan = AccentBlue
@Deprecated("Use AccentBlueSoft", ReplaceWith("AccentBlueSoft")) val CyanSoft = AccentBlueSoft
@Deprecated("Glow tokens dropped; use AccentBlue", ReplaceWith("AccentBlue")) val AccentGlow = AccentBlue
@Deprecated("Glow tokens dropped; use AccentBlue", ReplaceWith("AccentBlue")) val CyanGlow = AccentBlue
@Deprecated("Glow tokens dropped; use AccentBlue", ReplaceWith("AccentBlue")) val WarmGlow = AccentBlue
@Deprecated("Glow tokens dropped; use AccentBlue", ReplaceWith("AccentBlue")) val WarmGlowSoft = AccentBlue
@Deprecated("Sent bubbles are flat; use BubbleSent", ReplaceWith("BubbleSent")) val BubbleSentTop = BubbleSent
@Deprecated("Sent bubbles are flat; use BubbleSent", ReplaceWith("BubbleSent")) val BubbleSentBottom = BubbleSent
