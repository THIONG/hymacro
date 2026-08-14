# The Fabric mod

The executable drives the game through `SendInput`, which feeds the global
Windows input queue. That queue always goes to the foreground window, so
Minecraft has to stay in front and the computer cannot be used for anything else
while the macro runs.

The mod solves that. It runs inside the game process, so there is no operating
system input to route anywhere: it sets the state of the key mappings the game
already reads every tick, and sends commands through the player's own network
connection. Window focus stops being part of the problem.

The source lives in [`mod/`](../mod) and is built by
[`mod.yml`](../.github/workflows/mod.yml).

## What was tried before this

**Posting messages to the game window** (`WM_KEYDOWN` and friends aimed at the
Minecraft window rather than the global queue). Implemented and tested: the
messages are accepted and the window is found, but Minecraft does not act on
them while unfocused. Removed in 1.2.0 rather than kept as an option that
promises what it cannot deliver.

**A virtual machine.** Modern Minecraft needs OpenGL 3.2 or newer, and a VM
without GPU passthrough does not provide it, so the game is unlikely to start.

## Toolchain, verified against the official example mod

The Fabric example mod has a branch per Minecraft version, and the `26.1.2`
branch is the authority on how a build is declared today.

| Item | Value |
|------|-------|
| Minecraft | 26.1.2 |
| Java | 25 (`java-runtime-epsilon`) |
| Fabric Loader | 0.19.3 |
| Fabric API | `0.155.2+26.1.2` |
| Loom | `1.17-SNAPSHOT` |
| Mappings | **none declared** |

That last row was the open question. Neither Yarn (which stopped at 1.21.11) nor
Mojang's published mappings (absent from the 26.1.2 version metadata) cover this
version, and it turns out neither is needed: Loom 1.17 handles naming itself on
26.x, so the dependency block has no `mappings` line. Fabric dependencies are
also declared with `implementation` rather than the `modImplementation` older
guides use.

## How the route works

A state machine advanced one client tick at a time. Ticks run at a fixed twenty
per second regardless of frame rate, so a two minute leg is exactly 2400 ticks
and the timing cannot drift with performance.

Each phase holds the movement key for its leg plus attack, then releases
everything before moving on, which is the same discipline the executable follows:
a stop must never leave a key stuck down.

Movement works by setting the key mappings the game already reads, so the packets
leaving the client are the ones a player produces.

## Configuration

`config/hymacro.json` inside the Minecraft instance, created with defaults on
first run. It uses the same field names as the standalone tool
(`forward_seconds`, `return_seconds`, `step_seconds`, `routes_per_warp`, `keys`)
so a calibrated `config.json` can be copied across without translation.

## Failsafes

The focus and mouse checks that the executable relies on stop being meaningful
here, since the whole point is that the window is not in front and the mouse is
in use. Stopping is by keybind, rebindable from the game's own controls screen.

## Reading the build

Actions logs need a token to fetch, so a red badge on its own says nothing about
what broke. The workflow writes its outcome and the relevant lines of compiler
output to [`mod/reports/last-build.txt`](../mod/reports/last-build.txt) and
commits it back, where it can be read over plain HTTP.
