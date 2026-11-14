# Quest Editor Plus

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1-blue)

**Quest Editor Plus** is a client-side companion mod for editing **FTB Quests** quest books.
Built for large, heavily-structured books (thousands of quests, branching trees, chapter images) —
it makes day-to-day editing dramatically faster while staying 100% compatible with vanilla
FTB Quests networking (works in multiplayer exactly like normal FTB editing).

## Features

### Dependencies
- **Alt + LMB drag** from quest to quest — create or remove a dependency with a live
  dotted-line preview. **Green** = adding, **red** = removing.
- **Multi-selection aware** — if the drag starts or ends on a Ctrl-selected group,
  the dependency is applied to every selected quest at once.
- **Loop protection** — cyclic dependencies are rolled back automatically, same as vanilla FTB.
- **Esc / RMB** — cancels an in-progress dependency drag.

### Moving quests
- **Middle-click move** — quests and chapter images follow the cursor for real:
  the dependency lines and arrows travel along with the quest instead of staying behind.
- **Magnetic guides** — while moving, quests snap to the X/Y centers of neighboring
  quests and show a cyan alignment line.
- **Shift (hold) while moving** — fully free placement: no grid snap, no magnets.
- **Arrow keys** — nudge the selection by half a grid cell; **Shift + arrows** for a fine 0.1 step.

### Resizing & shapes
- **Ctrl + mouse wheel** — resize the quest or chapter image under the cursor
  (aspect ratio kept for images). Resizes the whole selection too. The view stays
  pinned under the cursor while zooming.
- **Alt + mouse wheel** — cycle the quest shape (circle → square → hexagon → ...)
  with an instant preview.

### Misc
- **Alt + LMB click** (no drag) — opens the quest edit menu, same as vanilla FTB.
- Real quest icons are shown while dragging multiple quests — no plain white ghosts.
- Frequent edits are debounced (~200 ms), so the view never stutters.

## Installation

1. Install [NeoForge](https://neoforged.net/) for Minecraft **1.21.1** (21.1+).
2. Drop the mod jar into your `mods` folder.
3. Requires **FTB Quests**, **FTB Library**, **FTB Teams** and **Architectury** —
   the usual dependencies of any FTB Quests pack.

## Download

Grab the latest jar from the
[Releases](https://github.com/Ap4kG/questedit-plus/releases) page.

## Building from source

```bash
# requires JDK 21
./gradlew build
```

The jar appears in `build/libs/`.

## License

[MIT](LICENSE) © Ap4k — free to use, modify and redistribute, keep the copyright notice.
