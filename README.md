# HyMacro

![Python](https://img.shields.io/badge/python-3.11+-blue.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)
![Platform](https://img.shields.io/badge/platform-Windows-lightgrey.svg)
[![CI](https://github.com/THIONG/hymacro/actions/workflows/ci.yml/badge.svg)](https://github.com/THIONG/hymacro/actions/workflows/ci.yml)

Automation tool for the Hypixel Skyblock Garden: cocoa beans, nether wart and
cobblestone.

## Install

### Download the executable

Grab the `.zip` from the [latest release](https://github.com/THIONG/hymacro/releases),
extract it and run `HyMacro.exe`. Python is not required.

> **SmartScreen and antivirus warnings are expected.** The executable is not code
> signed, and a program that installs a global keyboard hook looks a lot like a
> keylogger to heuristic scanners. The full source is in this repository and the
> binary is built in the open by [`release.yml`](.github/workflows/release.yml).
> Every release ships a `.sha256` so the download can be verified.

### Run from source with [uv](https://docs.astral.sh/uv/)

```bash
git clone https://github.com/THIONG/hymacro.git
```

```bash
cd hymacro && uv run hymacro
```

## Usage

Opening HyMacro shows a menu:

```
  Home

    1)   Start the macro
    2)   Calibrate timings
    3)   Settings
    ESC) Exit
```

Options are picked with a single key press. **Escape** always goes back, and
returns to the menu from the macro screen. Each screen clears the previous one.

### Hotkeys while the macro screen is open

| Key | Action |
|-----|--------|
| **F8** | Cocoa Beans |
| **F9** | Nether Wart |
| **F10** | Cobblestone |
| **F12** | Stop the running macro |
| **ESC** | Back to the menu (in the HyMacro window, not in the game) |

Stopping releases every key and mouse button that was held down and prints the
session statistics.

### Failsafes

The macro stops on its own when Minecraft loses focus, when the mouse is moved
beyond a tolerance, or when the session limit is reached. A watchdog checks all
of them ten times a second, including during the long holds of a route.

The stop key is polled directly from Windows as well as through the hotkey hook,
because a hook can silently stop delivering events and losing the ability to stop
a running macro is the worst possible failure.

## Configuration

Settings live in `config.json` next to the executable, and can be edited from
**Settings** inside the app. Values are validated before anything is written, so
the file is never left in a state the program cannot load. Deleting the file
recreates it with the defaults.

**Every duration is expressed in seconds.**

### Routes

| Key | Meaning |
|-----|---------|
| `keys` | The four keys of the pattern: `[out, step, back, step]` |
| `forward_seconds` | Length of the outward leg. This is what moves you |
| `return_seconds` | Length of the return leg. `0` for a one way route |
| `step_seconds` | Time spent moving to the next row |
| `routes_per_warp` | Laps before warping. `4` means 8 rows, not 4 |

There are two shapes of route.

**Serpentine** (nether wart) — you face forward, mine, and travel sideways. The
long leg is lateral and the step between rows goes forward:

```json
"keys": ["d", "w", "a", "w"],
"forward_seconds": 120,
"return_seconds": 120,
"step_seconds": 1.2
```

Right along the row, step forward, left along the next one, step forward, repeat.

**One way** (cocoa beans) — you advance, correct, and repeat, so the return leg
has no long hold:

```json
"keys": ["w", "d", "s", "a"],
"forward_seconds": 1,
"return_seconds": 0
```

### Calibration

The timings depend on plot size, movement speed and buffs, so they cannot be
guessed. **Calibrate timings** is a manual stopwatch: nothing is automated, you
walk the route and press the stop key twice, once at each end. It measures a full
row and the step to the next one, then prints the values to apply.

### Running in the background

By default the macro uses `SendInput`, which feeds the global input queue, so
Minecraft has to stay in the foreground. Setting `general.input_mode` to
`background` posts the messages straight to the game window instead, leaving the
computer free for something else.

**This is worth trying rather than something guaranteed.** Minecraft runs on
GLFW, which does read keyboard and character messages from its own queue, so keys
and chat usually arrive. The mouse is less reliable: the client reads movement
through raw input and ignores some events while unfocused. If mining or movement
do not register, the mode does not work for your setup and a virtual machine is
the dependable alternative.

**Leave Minecraft with Alt+Tab, never with Escape.** Escape opens the game menu,
and the menu swallows every key and click that arrives afterwards, so the macro
appears to stop. Alt+Tab releases the mouse cursor on its own and leaves the
game running. If your client opens the menu whenever the window loses focus,
look for an option to stop it doing that; without one this mode cannot work.

Background mode turns off the focus and mouse failsafes, because both would fire
immediately by design. The stop key keeps working: it is read from Windows
directly, so it responds whatever window is in front.

### Other settings

| Section | Key | Default | Meaning |
|---------|-----|---------|---------|
| `general` | `input_mode` | `foreground` | `foreground` or `background` |
| | `mouse_button` | `left` | Button held down while farming |
| | `chat_key` | `t` | Key that opens the chat |
| | `chat_open_seconds` | `0.12` | Pause before typing a command |
| | `command_input_mode` | `unicode` | `unicode` or `scancode` |
| | `step_jitter_seconds` | `0.008` | Random variation on short steps |
| | `wait_jitter_percent` | `5` | Random variation on long holds |
| | `wait_jitter_max_seconds` | `0.5` | Cap on that variation |
| | `suppress_hotkeys` | `true` | Keep F8 to F12 from reaching the game |
| | `colors` | `auto` | `auto`, `always` or `never` |
| | `banner_animation` | `true` | Animate the banner |
| `safety` | `require_window_focus` | `true` | Stop when Minecraft loses focus |
| | `window_title_contains` | `Minecraft` | Text to look for in the window title |
| | `mouse_failsafe` | `true` | Stop when the mouse moves |
| | `mouse_failsafe_px` | `100` | Mouse tolerance |
| | `max_session_seconds` | `0` | Session limit, `0` for none |

`NO_COLOR` is honoured, and `HYMACRO_CONFIG` overrides the configuration path.

## Command line

| Flag | Description |
|------|-------------|
| `--config PATH` | Use a different `config.json` |
| `--check` | Validate the configuration and exit |
| `--calibrate [MACRO]` | Run the calibration stopwatch |
| `--no-menu` | Go straight to the macro screen |
| `--verbose` | Show debug logs on the console |
| `--version` | Print the version |

## Development

```bash
uv sync --all-groups
```

| Command | Purpose |
|---------|---------|
| `uv run ruff check .` | Lint |
| `uv run ruff format .` | Format |
| `uv run mypy` | Type check, in strict mode |
| `uv run pytest` | Tests |
| `uv run pyinstaller packaging/hymacro.spec --noconfirm` | Build the executable |

### Layout

```
src/hymacro/
  winput.py       SendInput via ctypes: scancodes, mouse, window focus
  background.py   Messages posted straight to the game window
  config.py       Loading, validation and persistence
  console.py      Colour output and the animated banner
  ui.py           Menu primitives shared by every screen
  safety.py       Watchdog and failsafes
  controller.py   Route execution on a worker thread
  screen.py       Banner and shared header
  app.py          The running macro screen
  menu.py         Main menu
  calibration.py  Manual stopwatch
  settings.py     In-app settings editor
```

### Releasing

Bump the version in `pyproject.toml` and `src/hymacro/__init__.py`, then push a
tag. The workflow verifies the tag matches the package version, builds the
executable, checks that it starts, generates the SHA256 and publishes the release.

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## Troubleshooting

**The hotkeys do nothing.** Run HyMacro as administrator. Global keyboard hooks
cannot intercept input aimed at elevated processes.

**It refuses to start, saying the active window is not Minecraft.** That is the
focus failsafe. Press the hotkey with Minecraft in the foreground, or adjust
`safety.window_title_contains` to match your launcher.

**It stops after a few seconds.** Usually the mouse failsafe. Raise
`safety.mouse_failsafe_px` or turn it off.

**It changes rows before reaching the end.** `forward_seconds` is too low.
Measure it with the calibration stopwatch.

Run with `--verbose` to see the details of a failure.

## Notes

Automating gameplay may go against the Hypixel rules. Use at your own risk and
keep an eye on the macro while it runs; the failsafes reduce accidents but do not
replace supervision.

## Licence

MIT. See [LICENSE](LICENSE).
