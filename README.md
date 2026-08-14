# HyMacro

![Minecraft](https://img.shields.io/badge/minecraft-26.1.2-brightgreen.svg)
![Fabric](https://img.shields.io/badge/fabric-0.19.3+-blue.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)
[![Mod](https://github.com/THIONG/hymacro/actions/workflows/mod.yml/badge.svg)](https://github.com/THIONG/hymacro/actions/workflows/mod.yml)

A Fabric mod for building farming macros in Minecraft, made for the Hypixel
Skyblock Garden.

You walk the plot once, marking where each stretch ends and saying what happens
on the way there. The macro is drawn in the world as you build it, and it runs
from inside the game, so the computer stays yours while it works.

## Install

1. Fabric Loader 0.19.3 or newer for Minecraft 26.1.2.
2. [Fabric API](https://modrinth.com/mod/fabric-api) `0.155.2+26.1.2` in `mods`.
3. The `.jar` from the [latest release](https://github.com/THIONG/hymacro/releases),
   in the same folder.

## Build one

```
                          /hymacro new wart
stand where it begins     /hymacro point
walk to the end of a row  /hymacro point
                          /hymacro hold d
                          /hymacro spam attack 4
```

Repeat for each stretch, then press **F9**. **F12** stops it.

A leg ends when you arrive, not after a measured time, so there is nothing to
calibrate and nothing that drifts out of step. `/hymacro` on its own prints
every command, in colour, in game.

### What a leg can do

| Command | Effect |
|---------|--------|
| `/hymacro hold <key>` | Hold it for the whole leg |
| `/hymacro spam <key> [ticks]` | Click it over and over |
| `/hymacro once <key>` | Click it once as the leg starts |
| `/hymacro walk` | Steer to the point on its own |
| `/hymacro look <yaw> <pitch>` | Aim it by numbers |
| `/hymacro send <text>` | Put in chat on arriving there |

Keys are `w a s d space shift ctrl attack use`, where `attack` is left click and
`use` is right click.

Any of them can name a leg rather than the one just made, so a mistake on leg
two does not need undoing back from leg six:

```
/hymacro leg 2            what it does
/hymacro leg 2 walk       change it
/hymacro leg 2 clear      make it only walk
```

A leg is numbered after where it ends: leg 2 runs from point 1 to point 2.

Points can be moved after the fact, so one being a block out is not a rebuild:

```
/hymacro move 2           put point 2 where you stand
/hymacro anchor           carry the whole macro so point 1 lands on you
```

`anchor` is how a macro moves to another plot of the same shape.

## Seeing it

The macro is drawn where it happens: a box on every point with its number above
it, a line along the ground with arrows showing which way it travels, and what
each leg does floating over the middle of that leg.

| Colour | Meaning |
|--------|---------|
| Green | A key is held |
| Orange | Something is clicked repeatedly |
| Grey | Nothing set, it only walks |

The leg from the last point back to the first is drawn faintly. It is real, the
macro loops, but it is the way back rather than more of the same work.
`/hymacro show false` turns the drawing off.

## Several macros

```
/hymacro list             every macro, marking the current one
/hymacro new <name>
/hymacro load <name>
/hymacro rename <name>
/hymacro delete <name>
```

Everything is written as it changes, in `config/hymacro-routes.json`. There is
no save step.

`/hymacro share` copies the current one to your clipboard as a single line to
send to someone, `/hymacro share <name>` copies one you are not on, and
`/hymacro import <name>` reads one back. Names complete with Tab.

An imported macro is checked field by field, and a code from a newer HyMacro
says so rather than failing as corrupt. Import also lists every line the macro
would type into your chat, since that is somebody else's commands running under
your name.

## Development

The mod is in [`mod/`](mod) and builds with a JDK 25 and nothing else:

```bash
cd mod && ./gradlew build
```

The jar lands in `mod/build/libs`. [`mod.yml`](.github/workflows/mod.yml) builds
every push, and [`mod-release.yml`](.github/workflows/mod-release.yml) publishes
a release from a `mod-v*` tag, reusing the jar CI already built.

[docs/fabric-mod.md](docs/fabric-mod.md) records the verified toolchain for this
Minecraft version, which publishes no mappings, along with the reasoning behind
how the macro engine works.

## Notes

Automating gameplay may go against the Hypixel rules. Use at your own risk and
keep an eye on it while it runs.

This began as a Windows executable driving the game through the global input
queue, which meant Minecraft had to stay in the foreground and the computer
could not be used for anything else. The mod replaced it: running inside the
game, there is no operating system input to route anywhere. Those releases are
still on the releases page, and their code is in the history.

## Licence

MIT. See [LICENSE](LICENSE).
