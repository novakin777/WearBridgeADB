# WearBridge — native interface system

## Product read

WearBridge is a paired phone-and-watch device console for people who already understand shell commands. The frequent job is to connect to a known watch, choose the execution/transport mode explicitly, send a command, and read a live terminal. The screen should feel like a precise instrument: calm, confident, operational, and recognizably Android.

## Information architecture

The phone app has three persistent destinations, reachable from the bottom edge:

1. **Terminal** — compact manual NORMAL/STREAM selector, execution input, Run/STOP (Ctrl+C), expandable terminal keys, and a large live output surface. Disconnect shell ends the PTY session; STOP sends Ctrl+C only. This is the primary destination and the only place that starts commands or changes shell transport mode.
2. **Files** — watch storage actions. Existing Android document pickers, remote path browser, sorting, selection, batch transfer, and delete confirmation remain the actual flows.
3. **Settings** — explicit SHELL/ROOT execution profile and Termux API lifecycle; explains that ROOT is granted on the watch and that API listens only on phone loopback.

A compact connection strip stays visible across destinations and reports the paired watch/Data Layer state. No command type may select NORMAL or STREAM automatically.

## Visual direction: Field Console

A dark, OLED-friendly ink surface with cool neutral text, a restrained sea-glass mint action color, and separate semantic colors for connected, waiting, and failed states. The visual signature is a sharp terminal cursor/connection marker and one large, decisive Run action. Use the Android system sans for interface text and monospace only for commands and terminal output. Avoid neon hacker styling, generic gradients, glass, decorative charts, and cards around every row.

### Semantic palette

| Role | Token | Value |
|---|---|---|
| Background | `canvas` | `#0B1215` |
| Primary content surface | `surface` | `#121D20` |
| Raised/selected surface | `surfaceRaised` | `#1B2A2D` |
| Console surface | `console` | `#081012` |
| Primary text | `onSurface` | `#E9F1EF` |
| Supporting text | `onSurfaceVariant` | `#A9BCB8` |
| Primary action | `primary` | `#A8E6C5` |
| Text on action | `onPrimary` | `#092018` |
| Secondary accent | `secondary` | `#80C9D7` |
| Success / connected | `success` | `#8ED5A7` |
| Warning / waiting | `warning` | `#F2C47E` |
| Error / destructive | `error` | `#FF9C96` |
| Divider / outline | `outline` | `#304246` |

Colors are declared in Android resource tokens. Status is always expressed with text/icon plus color. Use strong color only for current mode, connection state, and primary action.

## Component rules

- Page title: Android sans, 26–30sp medium; section/title 18–20sp; body 14–16sp; helper/metadata 12–13sp. Text scales with system settings.
- Terminal content and command: monospace, at least 13sp for output and 15sp for input; selectable output, visible empty prompt state, adaptive viewport height, and a bounded output tail to protect memory during long sessions.
- Actions: native Android Button/EditText semantics, 48dp minimum targets, clear labels, visible pressed/focused/disabled states; primary Run uses filled mint, STOP is secondary/error-toned, and Disconnect shell is explicitly separate from process interruption.
- Surfaces: 16–20dp only for the main console and grouped task surfaces; use spacing/dividers for ordinary settings and list rows. Shape contrast distinguishes primary action, selected mode, and read-only output.
- Icons never replace accessible labels for core operations. Existing OS file pickers remain native.
- Feedback is local to the action: connection status near the app header, transfer result in the terminal/activity message area, delete confirmation before destructive action, and recovery action beside a failed connection/transfer when possible.

## Adaptation and system UI

- Compact windows: single column, scrollable terminal controls, bottom destinations, fixed connection status.
- Wider windows/landscape: cap text/input measure, let console occupy spare space, keep command entry and Run adjacent when width allows. Preserve a single reading/focus order.
- IME open: allow the content to scroll to the focused command field and its Run action; keep terminal output independently scrollable and preserve command text.
- Edge-to-edge uses transparent system bars, correct light/dark icon contrast, and top/bottom/system-cutout insets. Scrollable content must remain reachable above the gesture/navigation area.
- Physical keyboard remains supported: Enter submits, Ctrl+C/D/Z and terminal navigation keys preserve existing STREAM semantics; Tab order is logical.

## Motion and accessibility

Use only brief native press/selection feedback and a short status transition; no decorative loops or large layout animation. System animation scale controls remain authoritative. Maintain 48dp touch areas, strong text contrast, logical TalkBack order, descriptive state text, focus indication, selectable terminal output, and large-font reflow. Empty, waiting, connected, disconnected, permission-denied, busy, failed, and success states have explicit text and a next step.

## Implementation constraints

The phone and Wear modules currently use Kotlin Android Views, target SDK 28 and compile SDK 28; the phone module has no Compose or Material Components dependency. Retain that stack and all existing command, Wear Data Layer, PTY, picker, transfer, ROOT/SHELL, and Termux API code paths. Use resource tokens and platform Views for this pass. Do not claim the new Material Components/Compose library APIs are in use. M3 Expressive is applied as selective hierarchy, semantic tokens, and shape/state contrast, not as a dependency migration.
