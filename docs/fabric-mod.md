# The Fabric mod

The executable drives the game through `SendInput`, which feeds the global
Windows input queue. That queue always goes to the foreground window, so
Minecraft has to stay in front and the computer cannot be used for anything else
while it runs.

The mod removes that. It runs inside the game process, so there is no operating
system input to route anywhere: it sets the state of the keys the game already
reads every tick, and sends commands through the player's own connection. Window
focus stops being part of the problem.

The source is in [`mod/`](../mod), built by [`mod.yml`](../.github/workflows/mod.yml)
and released by [`mod-release.yml`](../.github/workflows/mod-release.yml).

## Building a route

A route is a list of points. A leg is the stretch between one point and the next,
and it carries the work to do along the way.

```
stand on the first block   /hymacro point
stand on the second block  /hymacro point
                           /hymacro hold d
                           /hymacro spam attack 4
                           F9
```

| Command | Effect |
|---------|--------|
| `/hymacro point` | Marks where you stand, facing where you face |
| `/hymacro hold <key>` | Hold a key on the way to the last point |
| `/hymacro spam <key> [ticks]` | Click it repeatedly instead of holding |
| `/hymacro list` | Read the route back |
| `/hymacro undo`, `clear` | Drop the last point, or start over |
| `/hymacro radius <blocks>` | How close counts as arrived |
| `/hymacro warp <command>` | What to send at the end of a lap |
| `/hymacro play`, `stop` | Also on F9 and F12 |

Points are drawn in the world with particles, with a trail between them, so the
route can be seen as it is built.

## Why legs end on arrival

The first version of the mod, and the executable before it, ran on a stopwatch:
hold a key for a measured number of seconds. That needs calibrating, and it
drifts. A tenth of a second of error is a wall out of position eight rows later.

A leg now ends when the player reaches its point. There is nothing to measure and
nothing to drift: falling short simply means holding the keys a moment longer.
Speed, buffs, lag and being shoved by a mob all stop mattering.

A leg that never arrives times out instead, because something stuck against a
block or teleported away must not hold a key down forever.

## Why declaring rather than recording

An earlier version recorded a route by watching the player walk it, working out
from the pattern of presses whether a button was held or spammed.

Declaring is better. A recording captures the hesitations along with the intent,
a single leg cannot be changed without walking the whole route again, and the
hold-or-spam guess could simply be wrong. Position still has to be captured by
standing somewhere, since there is no other way to name a spot, but everything
else is stated outright.

## Why the look direction is part of a leg

On a wall of crops the player faces the wall while moving sideways. A camera off
by a degree ruins a run as surely as a mistimed key, so yaw and pitch are
recorded with the point and restored when the leg begins.

## Toolchain, verified against the official example mod

The Fabric example mod has a branch per Minecraft version, and its `26.1.2`
branch is the authority on how a build is declared.

| Item | Value |
|------|-------|
| Minecraft | 26.1.2 |
| Java | 25 |
| Fabric Loader | 0.19.3 |
| Fabric API | `0.155.2+26.1.2` |
| Loom | `1.17-SNAPSHOT`, resolving to 1.17.19 |
| Mappings | **none declared** |

That last row was the question that blocked the whole thing. Neither Yarn, which
stopped at 1.21.11, nor Mojang's published mappings, absent from the 26.1.2
metadata, cover this version. It turns out neither is needed: Loom handles naming
itself on 26.x, so the dependency block has no `mappings` line at all. Fabric
dependencies also use `implementation` rather than the `modImplementation` older
guides call for.

## What was given up, and why

The keys are not rebindable from the game's controls screen, and there is no
chat feedback outside of commands. A binding needs a category type whose shape
changed in this version, and `displayClientMessage` is not on `LocalPlayer` here.
Both were dropped to get the mod compiling at all: the first build had nine
errors, and cutting the Minecraft API surface down to what the compiler had
already confirmed took it to one.

Markers are particles rather than translucent boxes for the same reason. A box
needs the render pipeline, which changes more between versions than anything
else and is the part this version publishes nothing to check against.

## Reading a failed build

Actions logs need a token to fetch, so a red badge alone says nothing about what
broke. On failure the workflow writes the compiler output to
[`mod/reports/last-build.txt`](../mod/reports/last-build.txt) and commits it,
where it can be read over plain HTTP. That loop is what took the build from nine
errors to zero without a JDK anywhere nearby.
