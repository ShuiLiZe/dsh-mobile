# DSH Mobile design system

## Direction

This is an internal design reference, not a promise that DSH Mobile replaces the host UI. Plugin-owned controls follow DSH's current theme; native connection screens follow Android light/dark mode and place readable cards over the bundled character artwork. Keep controls calm and functional. Avoid decorative gradients, glow, glass effects, particles, animated ambient backgrounds, or emoji icons in product controls; the existing brand illustration is separate from these controls.

## Color

The native colors below mirror the [light](../../apps/mobile/android/app/src/main/res/values/colors.xml) and [dark](../../apps/mobile/android/app/src/main/res/values-night/colors.xml) resources; use DSH semantic tokens instead for WebView controls.

| Native role | Light | Dark |
| --- | --- | --- |
| Background | `#F7F8FA` | `#0F172A` |
| Card surface | `#FFFFFF` | `#1E293B` |
| Primary text | `#111827` | `#F8FAFC` |
| Secondary text | `#667085` | `#CBD5E1` |
| Accent | `#2563EB` | `#60A5FA` |
| Text on primary button | `#FFFFFF` | `#0F172A` |
| Border | `#E4E7EC` | `#475569` |
| Error | `#B91C1C` | `#FCA5A5` |

Maintain at least 4.5:1 text contrast over cards and artwork scrims. Native screens follow Android's theme; the WebView follows DSH's theme. Plugin surfaces must not force a global theme.

## Typography

In WebView controls, inherit DSH's system font stack. Use at least 16 CSS pixels for mobile inputs to avoid focus zoom while preserving larger user font preferences. The existing repository hero asset includes its own brand title; do not overlay a duplicate title.

Keep per-Origin mobile font preferences separate from App-wide WebView zoom. Show advanced typography only when the theme consumes its public tokens; a terminal canvas needs its own supported font control. Native text uses scaled text units (`sp`), while native geometry uses `dp`; neither is a raw device pixel.

## Native surfaces

The APK draws several screens itself (device list and its empty state, connection centre, LAN/remote setup, pairing, the connection wait, failure sheets and the scanner). They share `DshNativeUi` for spacing, type, motion and shapes, and every colour comes from `DshNativeTheme` with the `res/values(-night)/colors.xml` fallback.

- One hierarchy per screen: a 30sp display title with a 15sp caption directly beneath it, 17sp bold section labels for field groups, 15sp body copy and 13sp captions. Nothing competes at the same weight.
- Spacing is an 8dp grid (8/12/16/20/24/32); pages get a 32dp vertical gutter and a 24dp horizontal gutter with a 560dp content cap.
- Device rows carry a mode tag, the device name, the origin and a status line with a coloured dot, then a monospace-free platform icon and a chevron; the whole row is the 48dp+ target and presses with `interactive-bg`.
- Empty states reuse the DSH mark at 40dp with one sentence and the scan / manual-address pair, rather than a bare blank list.
- Menus are a DSH action sheet, not an `AlertDialog` list: a scrim, a 28dp-top-rounded panel, single-choice rows with a checkmark and a description, and one destructive action at most.
- The connection wait is a centred 64dp DSH mark, a 2dp hairline progress line, the status text and a cancel button, revealed with a 200ms fade.
- The scanner keeps a dark immutable surface for camera contrast, but its brackets, hint, pill and close target follow the same radii and type scale.

## Shape and depth

- Native card radius: 20dp; native sheet radius: 28dp (top corners only).
- Native card radius: 20dp; native sheet radius: 28dp (top corners only).
- Native button radius: 14dp for secondary actions and 16dp for primary actions.
- Native touch target: at least 48dp. Web targets: at least 44 CSS pixels, with 48px for primary mobile actions where space permits.
- Use a subtle theme-aware border and restrained shadows.
- Never shift layout on hover or press.

## Motion

Use short 150–240ms state/navigation transitions. Respect `prefers-reduced-motion`; avoid decorative entrance or ambient animation. A loading indicator may run while an operation is pending, with a visible cancellation action for connection waits. Reveal a painted webpage without keeping the full-screen artwork above its progress; keep the smaller cancellation action until it becomes usable.

## Mobile layout

- Honor safe-area insets on every edge.
- Keep conversation content full-width on narrow screens.
- Prevent page-level horizontal overflow. Wide tables and code blocks may scroll inside their own containers.
- Keep primary actions reachable above the soft keyboard.
- Test 375px and 390px portrait, landscape, font scaling, and keyboard-open states.

## Brand artwork

- Default app artwork: `assets/brand/app-icon-master.png` shows the whale-themed character holding a phone. Keep this as the default identity and README icon; do not add text or watermarks. The Android launcher uses the monochrome DSH official mark (FishLogo from `@deepseek-ai/dsh-client-ui-primitives`) as an adaptive icon through one `.LauncherDsh` alias; native pages follow the connected DSH theme. This does not make this community app an official DeepSeek client.
- Repository hero: use `assets/brand/repository-hero.png`; the existing image includes the DSH Mobile title at left and a character with a desktop scene at right. Keep README copy outside the image rather than duplicating its built-in title.

## Delivery checklist

- [ ] Native light/dark colors or DSH semantic tokens, with readable artwork scrims.
- [ ] No emoji icons, gradients, glow, particles, or glass effects.
- [ ] Visible keyboard focus.
- [ ] Native targets at least 48dp; Web targets at least 44 CSS pixels.
- [ ] Safe-area and soft-keyboard handling.
- [ ] Reduced motion supported.
- [ ] No content hidden behind fixed controls.
- [ ] No page-level horizontal overflow at 375px; wide content scrolls only inside its container.
